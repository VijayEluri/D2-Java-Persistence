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
package org.nkts.util;

import sun.reflect.ReflectionFactory;
import java.lang.reflect.Constructor;

public class SilentObjectCreator {
  public static <T> T create(Class<T> clazz) {
    return create(clazz, Object.class);
  }

  public static <T> T create(Class<T> clazz,
                             Class<? super T> parent) {
    try {
      ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
      Constructor objDef = parent.getDeclaredConstructor();
      Constructor intConstr = rf.newConstructorForSerialization(
          clazz, objDef
      );
      return clazz.cast(intConstr.newInstance());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot create object", e);
    }
  }
}
