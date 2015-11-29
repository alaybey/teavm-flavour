/*
 *  Copyright 2015 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.flavour.mp;

import java.util.Arrays;

/**
 *
 * @author Alexey Andreev
 */
public interface ReflectClass<T> {
    boolean isPrimitive();

    boolean isInterface();

    boolean isArray();

    boolean isAnnotation();

    boolean isEnum();

    T[] getEnumConstants();

    int getModifiers();

    ReflectClass<?> getComponentType();

    String getName();

    ReflectClass<? super T> getSuperclass();

    ReflectClass<? super T>[] getInterfaces();

    boolean isInstance(Object obj);

    <U> ReflectClass<U> asSubclass(Class<U> cls);

    default boolean isAssignableFrom(ReflectClass<?> cls) {
        return cls == this || getSuperclass() != null && getSuperclass().isAssignableFrom(cls)
                || Arrays.stream(getInterfaces()).anyMatch(c -> c.isAssignableFrom(cls));
    }

    ReflectMethod[] getDeclaringMethods();

    ReflectMethod[] getMethods();

    ReflectMethod getDeclaringMethod(String name, ReflectClass<?>... parameterTypes);

    ReflectMethod getMethod(String name, ReflectClass<?>... parameterTypes);

    ReflectField[] getDeclaringFields();

    ReflectField[] getFields();

    ReflectField getDeclaringField(String name);

    ReflectField getField(String name);
}