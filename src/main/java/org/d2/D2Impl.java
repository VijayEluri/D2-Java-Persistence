/*******************************************************************************
 * Copyright 2010 Nathan Kopp
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.d2;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.d2.annotations.D2Entity;
import org.d2.context.D2Context;
import org.d2.pluggable.IndexerFactory;
import org.d2.pluggable.StorageFactory;
import org.d2.serialize.D2Serializer;
import org.d2.serialize.SerializerFactory;
import org.nkts.util.StringVisitor;
import org.nkts.util.Util;

public class D2Impl implements D2
{
    private List<Bucket> buckets = new ArrayList<Bucket>();
    
    private StorageFactory defaultStorageFactory;
    private IndexerFactory defaultIndexerFactory;
    private SerializerFactory defaultSerializerFactory;
    
    private long indexTime;
    private long storageTime;
    
    public D2Impl(StorageFactory defaultStorageFactory, IndexerFactory defaultIndexerFactory, SerializerFactory defaultSerializerFactory)
    {
        this.defaultStorageFactory = defaultStorageFactory;
        this.defaultIndexerFactory = defaultIndexerFactory;
        this.defaultSerializerFactory = defaultSerializerFactory;
    }
    
    // ====================================================
    // logic
    // ====================================================
    /* (non-Javadoc)
     * @see com.pitcru.persistence.d2.D2#registerBucket(com.pitcru.persistence.d2.D2Bucket)
     */
    public void registerBucket(Bucket bucket)
    {
        for(Bucket b : buckets)
        {
            if(b.getClazz()==bucket.getClazz()) return;
        }
        bucket.setD2(this);
        buckets.add(bucket);
    }

    /* (non-Javadoc)
     * @see com.pitcru.persistence.d2.D2#save(com.pitcru.persistence.d2.Object)
     */
    public void save(Object obj, D2Context context)
    {
        Operation operation = new Operation();
        // FIXME - nondeterministic
        Date now = new Date();

        context = useObjectContextIfPossible(obj, context);
        saveInternal(obj, context, operation, now);
        
        Object objectToSave;
        while((objectToSave = operation.pollCascadeSave())!=null)
        {
            saveInternal(objectToSave, context, operation, now);
        }
    }

    public void saveInternal(Object obj, D2Context context, Operation operation, Date now)
    {
        D2Serializer xs = defaultSerializerFactory.createSerializer();
        Bucket bucket = prepareSerializerAndFindBucket(obj.getClass(), xs, new Date(), context, operation);
        
        // check constraint here!
        
        obj = bucket.applyConstraints(obj, context);
        
        assignId(obj, bucket.getClazz(), bucket);
        String xmlStr = xs.serialize(obj);

        String id = IdFinder.getId(obj);
        bucket.getStorage().acquireWriteLock(id);
        try
        {
            long starta = System.currentTimeMillis();
            bucket.getStorage().saveDocument(id, xmlStr, now);
            setMetadata(obj, xmlStr, now, LoadStatus.LOADED, context);
            storageTime += (System.currentTimeMillis()-starta);
            
            if(bucket.getIndexer()!=null)
            {
                long startb = System.currentTimeMillis();
                bucket.getIndexer().indexObject(obj);
                indexTime += (System.currentTimeMillis()-startb);
            }
        }
        finally
        {
            bucket.getStorage().releaseWriteLock(id);
        }
        
        context.registerInstanceToCache(bucket, obj);
    }

    private void setMetadata(Object obj, String xml, Date now, LoadStatus status, D2Context context)
    {
        D2Metadata md = IdFinder.getMd(obj);
        if(md==null)
        {
            md = new D2Metadata();
            IdFinder.setMd(obj, md);
        }
        if(status!=null) md.setStatus(status);
        if(now!=null) md.setSaveTimestamp(now);
        if(xml!=null) md.setLoadedXml(xml);
        if(context!=null) md.setContext(context);
    }

    /* (non-Javadoc)
     * @see com.pitcru.persistence.d2.D2#loadAll(java.lang.Class)
     */
    public <T> List<T> loadAll(final Class<T> clazz, final D2Context context)
    {
        final Operation operation = new Operation();
        final List<T> out = new ArrayList<T>();
        
        final Bucket bucket = prepareSerializerAndFindBucket(clazz, null, null, context, operation);
        
        bucket.getStorage().eachId(new StringVisitor(){
            @Override
            public void visit(String id)
            {
                // it's inefficient to prepare a new XS for each load... but right now we need to, because we must reset the
                // converter for this type... so that it will unmarshall the root-level object properly 
                D2Serializer xs = defaultSerializerFactory.createSerializer();
                prepareSerializerAndFindBucket(clazz, xs, null, context, operation);
                T obj = loadOneObject(clazz, id, xs, bucket, context, true, operation);
                out.add(obj);
            }
        });
        
        return out;
    }
    
    public void reindexAll(final Class<? extends Object> clazz, final D2Context context)
    {
        final Operation operation = new Operation();
        final Bucket bucket = prepareSerializerAndFindBucket(clazz, null, null, context, operation);
        if(bucket.getIndexer()==null) throw new RuntimeException("Cannot index "+clazz.getSimpleName()+" because indexer is null");

        bucket.getStorage().eachId(new StringVisitor(){
            @Override
            public void visit(String id)
            {
                // it's inefficient to prepare a new XS for each load... but right now we need to, because we must reset the
                // converter for this type... so that it will unmarshall the root-level object properly 
                D2Serializer xs = defaultSerializerFactory.createSerializer();
                prepareSerializerAndFindBucket(clazz, xs, null, context, operation);
                Object obj = loadOneObject(clazz, id, xs, bucket, context, true, operation);
                
                bucket.getIndexer().indexObject(obj);
            }
        });
        
    }

    /* (non-Javadoc)
     * @see com.pitcru.persistence.d2.D2#deleteById(java.lang.Class, java.lang.Long)
     */
    public void deleteById(Class<? extends Object> clazz, String id, D2Context context)
    {
        Operation operation = new Operation();
        try
        {
            Bucket bucket = prepareSerializerAndFindBucket(clazz, null, null, context, operation);
    
            bucket.getStorage().deleteDocument(id);
            bucket.getIndexer().deleteDocument(id);
            context.removeFromCache(bucket, id);
        }
        catch (Exception e)
        {
            throw Util.wrap(e);
        }
    }

    /* (non-Javadoc)
     * @see com.pitcru.persistence.d2.D2#loadById(java.lang.Class, java.lang.Long)
     */
    public <T> T loadById(Class<T> clazz, String id, D2Context context)
    {
        Operation operation = new Operation();
        try
        {
            D2Serializer xs = defaultSerializerFactory.createSerializer();
            Bucket bucket = prepareSerializerAndFindBucket(clazz, xs, null, context, operation);
    
            return loadOneObject(clazz, id, xs, bucket, context, true, operation);
        }
        catch (Exception e)
        {
            throw Util.wrap(e);
        }
    }

    public <T> T loadCachedOnlyById(Class<T> clazz, String id, D2Context context, boolean createStandin)
    {
        Operation operation = new Operation();
        try
        {
            D2Serializer xs = defaultSerializerFactory.createSerializer();
            Bucket bucket = prepareSerializerAndFindBucket(clazz, xs, null, context, operation);
    
            return getCachedObjectOrStandin(clazz, id, bucket, context, createStandin);
        }
        catch (Exception e)
        {
            throw Util.wrap(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T loadOneObject(Class<T> clazz, String id, D2Serializer xs, Bucket bucket, D2Context context, boolean forceReload, Operation operation)
    {
        T object = getCachedObjectOrStandin(clazz, id, bucket, context, false);
        
        if(object==null || IdFinder.getMd(object).isStandin() || forceReload)
        {
            object = realizeObject(clazz, id, xs, bucket, context, object);
            if(object==null) return null;

//            TreeWalker.walk(new LoadStandins(this, context), object);
            T objectToLoad;
            while((objectToLoad = (T)operation.pollCascadeLoads())!=null)
            {
                realizeObject(objectToLoad, context, operation);
            }
            
        }
        return object;
    }
    
    public void realizeObject(Object obj, D2Context context)
    {
        realizeObject(obj, context, new Operation());
    }

    private void realizeObject(Object obj, D2Context context, Operation operation)
    {
        Class<?> clazz = obj.getClass();
        String id = IdFinder.getId(obj);
        try
        {
            D2Serializer xs = defaultSerializerFactory.createSerializer();
            Bucket bucket = prepareSerializerAndFindBucket(clazz, xs, null, context, operation);
    
            loadOneObject(clazz, id, xs, bucket, context, true, operation);
        }
        catch (Exception e)
        {
            throw Util.wrap(e);
        }
    }

    private <T> T realizeObject(Class<T> clazz, String id, D2Serializer xs, Bucket bucket, D2Context context, T object)
    {
        String xmlStr = null;
        bucket.getStorage().acquireReadLock(id);
        try
        {
            xmlStr = bucket.getStorage().loadDocument(id);
        }
        finally
        {
            bucket.getStorage().releaseReadLock(id);
        }
        if(xmlStr==null) return null;
        
        if(object==null)
        {
            object = clazz.cast(context.createAndRegisterStandin(bucket, clazz, id));
        }
        else
        {
            // skip reload if document is an exact match to what we had before
            D2Metadata md = IdFinder.getMd(object);
            if(md!=null && xmlStr.equals(md.getLoadedXml())) return object;
        }
        
        xs.deserialize(xmlStr, object);
        
        setMetadata(object, xmlStr, null, LoadStatus.LOADED, context);
        return object;
    }

    private <T> T getCachedObjectOrStandin(Class<T> clazz, String id, Bucket bucket, D2Context context, boolean createStandin)
    {
        if(!bucket.getStorage().isIdValid(id)) return null;
        
        Object cached = context.lookInCache(bucket, id);
        if(cached!=null) return clazz.cast(cached);
        
        if(!createStandin) return null;
      
        T standin = clazz.cast(context.createAndRegisterStandin(bucket, clazz, id));
        
        return standin;
    }

    /**
     * TODO move this to LocalFileStorage
     * 
     * @param obj
     * @param bucketClass
     * @param bucket
     */
    private void assignId(Object obj, @SuppressWarnings("rawtypes") Class bucketClass, Bucket bucket)
    {
        if(bucket==null) throw new RuntimeException("bucket is null");
        if(bucket.getStorage()==null) throw new RuntimeException("bucket's storage system is null");
        if(IdFinder.getMd(obj).isNew() && IdFinder.getId(obj)==null)
        {
            String id = bucket.getStorage().getSeqNextVal(bucketClass.getSimpleName());
            IdFinder.setId(obj, id);
        }
        else
        {
            bucket.getStorage().setSequenceIfMore(obj.getClass().getSimpleName(), IdFinder.getId(obj));
        }
    }

    public Bucket prepareSerializerAndFindBucket(Class<?> clazz, D2Serializer xs, Date now, D2Context context, Operation operation)
    {
        Bucket thisBucket = null;
        for(Bucket bucket : buckets)
        {
            if(bucket.getClazz().isAssignableFrom(clazz))
            {
                if(xs!=null) xs.prepareForRootBucket(clazz, this, now, context, operation, bucket);
                thisBucket = bucket;
            }
            else
            {
                if(xs!=null) xs.prepareForNonRootBucket(this, now, context, operation, bucket);
            }
        }
        return thisBucket;
    }

    public String getAlias(Class<?> clazz)
    {
        D2Entity entityAnnotation = clazz.getAnnotation(D2Entity.class);
        return entityAnnotation==null?clazz.getName():entityAnnotation.alias();
    }
    
    private static D2Context useObjectContextIfPossible(Object obj, D2Context context)
    {
        D2Metadata md = IdFinder.getMd(obj);
        if(md!=null && md.getContext()!=null)
        {
            context = md.getContext();
        }
        return context;
    }
    
    
    
    // ====================================================
    // getters/setters
    // ====================================================
    public List<Bucket> getBuckets()
    {
        return buckets;
    }

    public void setBuckets(List<Bucket> buckets)
    {
        this.buckets = buckets;
    }

    public StorageFactory getDefaultStorageFactory()
    {
        return defaultStorageFactory;
    }

    public IndexerFactory getDefaultIndexerFactory()
    {
        return defaultIndexerFactory;
    }

    @Override
    public void close()
    {
        for(Bucket b : buckets)
        {
            if(b.getIndexer()==null) throw new RuntimeException("indexer is null "+b.getName());
            b.getIndexer().close();
        }
    }

    @Override
    public void resetAllIndexLocks()
    {
        for(Bucket b : buckets)
        {
            b.getIndexer().resetLocks();
        }
    }

    public long getIndexTime()
    {
        return indexTime;
    }

    public void setIndexTime(long indexTime)
    {
        this.indexTime = indexTime;
    }

    public long getStorageTime()
    {
        return storageTime;
    }

    public void setStorageTime(long storageTime)
    {
        this.storageTime = storageTime;
    }

}
