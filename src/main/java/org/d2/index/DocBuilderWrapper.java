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
package org.d2.index;

public abstract class DocBuilderWrapper implements DocBuilder
{
    protected DocBuilderAbstract builderImpl;

    public DocBuilderAbstract getBuilderImpl()
    {
        return builderImpl;
    }

    public void setBuilderImpl(DocBuilderAbstract builderImpl)
    {
        this.builderImpl = builderImpl;
    }
}
