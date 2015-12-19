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
package org.teavm.flavour.mp.impl.reflect;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.teavm.flavour.mp.EmitterContext;
import org.teavm.flavour.mp.ReflectClass;
import org.teavm.flavour.mp.impl.EmitterContextImpl;
import org.teavm.flavour.mp.reflect.ReflectField;
import org.teavm.flavour.mp.reflect.ReflectMethod;
import org.teavm.model.AccessLevel;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReader;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReader;
import org.teavm.model.ValueType;

/**
 *
 * @author Alexey Andreev
 */
public class ReflectClassImpl<T> implements ReflectClass<T> {
    public final ValueType type;
    private EmitterContextImpl emitterContext;
    private ReflectContext context;
    private ClassReader classReader;
    private boolean resolved;
    private Class<?> cls;
    private Map<String, ReflectFieldImpl> declaredFields = new HashMap<>();
    private ReflectField[] fieldsCache;
    private Map<MethodDescriptor, ReflectMethodImpl> methods = new HashMap<>();
    private Map<String, ReflectMethodImpl> declaredMethods = new HashMap<>();
    private ReflectMethod[] methodsCache;

    ReflectClassImpl(ValueType type, EmitterContextImpl emitterContext) {
        this.type = type;
        this.emitterContext = emitterContext;
        context = emitterContext.getReflectContext();
    }

    public ReflectContext getReflectContext() {
        return context;
    }

    @Override
    public EmitterContext getContext() {
        return emitterContext;
    }

    @Override
    public boolean isPrimitive() {
        return type instanceof ValueType.Primitive;
    }

    @Override
    public boolean isInterface() {
        resolve();
        return classReader != null && classReader.readModifiers().contains(ElementModifier.INTERFACE);
    }

    @Override
    public boolean isArray() {
        return type instanceof ValueType.Array;
    }

    @Override
    public boolean isAnnotation() {
        resolve();
        return classReader != null && classReader.readModifiers().contains(ElementModifier.ANNOTATION);
    }

    @Override
    public boolean isEnum() {
        resolve();
        return classReader != null && classReader.readModifiers().contains(ElementModifier.ENUM);
    }

    @SuppressWarnings("unchecked")
    @Override
    public T[] getEnumConstants() {
        resolve();
        if (classReader == null) {
            return null;
        }
        if (cls == null) {
            try {
                cls = Class.forName(classReader.getName(), true, context.getClassLoader());
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        return (T[]) cls.getEnumConstants();
    }

    @Override
    public int getModifiers() {
        resolve();
        if (classReader == null) {
            return 0;
        }
        return ReflectContext.getModifiers(classReader);
    }

    @Override
    public ReflectClass<?> getComponentType() {
        if (!(type instanceof ValueType.Array)) {
            return null;
        }
        ValueType componentType = ((ValueType.Array) type).getItemType();
        return context.getClass(componentType);
    }

    @Override
    public String getName() {
        if (type instanceof ValueType.Object) {
            return ((ValueType.Object) type).getClassName();
        } else if (type instanceof ValueType.Void) {
            return "void";
        } else if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                    return "boolean";
                case BYTE:
                    return "byte";
                case SHORT:
                    return "short";
                case CHARACTER:
                    return "char";
                case INTEGER:
                    return "int";
                case LONG:
                    return "long";
                case FLOAT:
                    return "float";
                case DOUBLE:
                    return "double";
                default:
                    return "";
            }
        } else if (type instanceof ValueType.Array) {
            return type.toString().replace('/', '.');
        } else {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public ReflectClass<? super T> getSuperclass() {
        resolve();
        if (classReader == null || classReader.getParent() == null
                || classReader.getName().equals(classReader.getParent())) {
            return null;
        }
        return (ReflectClass<? super T>) context.getClass(new ValueType.Object(classReader.getParent()));
    }

    @SuppressWarnings("unchecked")
    @Override
    public ReflectClass<? super T>[] getInterfaces() {
        resolve();
        if (classReader == null) {
            return (ReflectClass<? super T>[]) Array.newInstance(ReflectClassImpl.class, 0);
        }
        return classReader.getInterfaces().stream()
                .map(iface -> context.getClass(new ValueType.Object(iface)))
                .toArray(sz -> (ReflectClass<? super T>[]) Array.newInstance(ReflectClassImpl.class, sz));
    }

    @Override
    public boolean isInstance(Object obj) {
        throw new IllegalStateException("Don't call this method from compile domain");
    }

    @SuppressWarnings("unchecked")
    @Override
    public <U> ReflectClass<U> asSubclass(Class<U> cls) {
        ReflectClass<U> reflectClass = emitterContext.findClass(cls);
        if (!isAssignableFrom(reflectClass)) {
            throw new IllegalArgumentException(cls.getName() + " is not subclass of " + getName());
        }
        return (ReflectClass<U>) this;
    }

    @Override
    public ReflectMethod[] getDeclaredMethods() {
        resolve();
        if (classReader == null) {
            return new ReflectMethod[0];
        }
        return classReader.getMethods().stream()
                .filter(method -> !method.getName().equals("<clinit>"))
                .map(method -> getDeclaredMethod(method.getDescriptor()))
                .toArray(sz -> new ReflectMethod[sz]);
    }

    @Override
    public ReflectMethod[] getMethods() {
        resolve();
        if (classReader == null) {
            return new ReflectMethod[0];
        }
        if (methodsCache == null) {
            Set<String> visited = new HashSet<>();
            methodsCache = context.getClassSource().getAncestorClasses(classReader.getName())
                    .flatMap(cls -> cls.getMethods().stream())
                    .filter(method -> !method.getName().equals("<clinit>"))
                    .filter(method -> visited.add(method.getDescriptor().toString()))
                    .map(method -> getDeclaredMethod(method.getDescriptor()))
                    .filter(Objects::nonNull)
                    .toArray(sz -> new ReflectMethod[sz]);
        }
        return methodsCache.clone();
    }

    @Override
    public ReflectMethod getDeclaredMethod(String name, ReflectClass<?>... parameterTypes) {
        resolve();
        if (classReader == null) {
            return null;
        }

        ValueType[] internalParameterTypes = Arrays.stream(parameterTypes)
                .map(type -> ((ReflectClassImpl<?>) type).type)
                .toArray(sz -> new ValueType[sz]);
        String key = name + "(" + ValueType.manyToString(internalParameterTypes) + ")";
        return declaredMethods.computeIfAbsent(key, k -> {
            MethodReader candidate = null;
            for (MethodReader method : classReader.getMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                if (!Arrays.equals(method.getParameterTypes(), internalParameterTypes)) {
                    continue;
                }
                if (candidate == null) {
                    candidate = method;
                } else {
                    boolean moreSpecial = context.getClassSource()
                            .isSuperType(candidate.getResultType(), method.getResultType())
                            .orElse(false);
                    if (moreSpecial) {
                        candidate = method;
                    }
                }
            }

            return candidate != null ? getDeclaredMethod(candidate.getDescriptor()) : null;
        });
    }

    private ReflectMethodImpl getDeclaredMethod(MethodDescriptor method) {
        resolve();
        return methods.computeIfAbsent(method, m -> {
            MethodReader methodReader = classReader.getMethod(m);
            return methodReader != null ? new ReflectMethodImpl(this, methodReader) : null;
        });
    }

    @Override
    public ReflectMethod getMethod(String name, ReflectClass<?>... parameterTypes) {
        resolve();
        if (classReader == null) {
            return null;
        }

        Iterable<ClassReader> ancestors = () -> context.getClassSource().getAncestors(classReader.getName())
                .iterator();
        for (ClassReader cls : ancestors) {
            ReflectClassImpl<?> reflectClass = context.getClass(ValueType.object(cls.getName()));
            ReflectMethod method = reflectClass.getDeclaredMethod(name, parameterTypes);
            if (method != null && Modifier.isPublic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    @Override
    public ReflectField[] getDeclaredFields() {
        resolve();
        if (classReader == null) {
            return new ReflectField[0];
        }
        return classReader.getFields().stream()
                .map(fld -> getDeclaredField(fld.getName()))
                .toArray(sz -> new ReflectField[sz]);
    }

    @Override
    public ReflectField[] getFields() {
        if (fieldsCache == null) {
            Set<String> visited = new HashSet<>();
            fieldsCache = context.getClassSource()
                    .getAncestors(classReader.getName())
                    .flatMap(cls -> cls.getFields().stream().filter(fld -> fld.getLevel() == AccessLevel.PUBLIC))
                    .filter(fld -> visited.add(fld.getName()))
                    .map(fld -> context.getClass(ValueType.object(fld.getOwnerName()))
                            .getDeclaredField(fld.getName()))
                    .toArray(sz -> new ReflectField[sz]);
        }
        return fieldsCache.clone();
    }

    @Override
    public ReflectField getDeclaredField(String name) {
        resolve();
        return declaredFields.computeIfAbsent(name, n -> {
            FieldReader fld = classReader.getField(n);
            return fld != null ? new ReflectFieldImpl(this, fld) : null;
        });
    }

    @Override
    public ReflectField getField(String name) {
        resolve();
        if (classReader == null) {
            return null;
        }
        FieldReader fieldReader = classReader.getField(name);
        return fieldReader != null && fieldReader.getLevel() == AccessLevel.PUBLIC
                ? getDeclaredField(name)
                : null;
    }

    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!(type instanceof ValueType.Object)) {
            return;
        }

        String className = ((ValueType.Object) type).getClassName();
        classReader = context.getClassSource().get(className);
    }
}
