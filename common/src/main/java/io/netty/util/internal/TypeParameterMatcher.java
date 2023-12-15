/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.internal;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

public abstract class TypeParameterMatcher {

    private static final TypeParameterMatcher NOOP = new TypeParameterMatcher() {
        @Override
        public boolean match(Object msg) {
            return true;
        }
    };

    public static TypeParameterMatcher get(final Class<?> parameterType) {
        final Map<Class<?>, TypeParameterMatcher> getCache =
                InternalThreadLocalMap.get().typeParameterMatcherGetCache();

        TypeParameterMatcher matcher = getCache.get(parameterType);
        if (matcher == null) {
            if (parameterType == Object.class) {
                matcher = NOOP;
            } else {
                matcher = new ReflectiveMatcher(parameterType);
            }
            getCache.put(parameterType, matcher);
        }

        return matcher;
    }

    /**
     * @param object
     * @param parametrizedSuperclass
     * @param typeParamName
     * @return
     */
    public static TypeParameterMatcher find(
            final Object object, final Class<?> parametrizedSuperclass, final String typeParamName) {

        final Map<Class<?>, Map<String, TypeParameterMatcher>> findCache =
                InternalThreadLocalMap.get().typeParameterMatcherFindCache();
        // 获取继承类的Class对象
        final Class<?> thisClass = object.getClass();

        // key是当前实现类的class
        Map<String, TypeParameterMatcher> map = findCache.get(thisClass);
        if (map == null) {
            // values是一个hashmap,且key是String
            map = new HashMap<String, TypeParameterMatcher>();
            findCache.put(thisClass, map);
        }

        TypeParameterMatcher matcher = map.get(typeParamName);
        if (matcher == null) {
            // matcher的初始化
            matcher = get(find0(object, parametrizedSuperclass, typeParamName));
            map.put(typeParamName, matcher);
        }

        return matcher;
    }

    private static Class<?> find0(
            final Object object, Class<?> parametrizedSuperclass, String typeParamName) {

        // 实现类的class
        final Class<?> thisClass = object.getClass();
        Class<?> currentClass = thisClass;
        for (; ; ) {
            if (currentClass.getSuperclass() == parametrizedSuperclass) {
                // 如果当前实现类是parametrizedSuperclass的直接子类
                // 注意：如果B继承自A，C继承自B,则C.class.getSuperclass()!=A.class
                int typeParamIndex = -1;
                // 返回泛型对象所声明的所有泛型变量的数组，数组元素的排列顺序按照最初声明的顺序。
                // 如果基础泛型对象（其实指的就是泛型类/接口）没有声明任何泛型变量，那么数组长度就为0
                // getTypeParameters返回的是泛型类/接口/泛型方法尖括号内声明的泛型变量所组成的数组
                TypeVariable<?>[] typeParams = currentClass.getSuperclass().getTypeParameters();
                for (int i = 0; i < typeParams.length; i++) {
                    if (typeParamName.equals(typeParams[i].getName())) {
                        // 确定参数传入的泛型，在所有的泛型中的顺序位置
                        typeParamIndex = i;
                        break;
                    }
                }

                if (typeParamIndex < 0) {
                    throw new IllegalStateException(
                            "unknown type parameter '" + typeParamName + "': " + parametrizedSuperclass);
                }

                Type genericSuperType = currentClass.getGenericSuperclass();
                if (!(genericSuperType instanceof ParameterizedType)) {
                    return Object.class;
                }

                // getActualTypeArguments(): 返回泛型参数的类型Type 数组。
                Type[] actualTypeParams = ((ParameterizedType) genericSuperType).getActualTypeArguments();

                Type actualTypeParam = actualTypeParams[typeParamIndex];
                if (actualTypeParam instanceof ParameterizedType) {
                    actualTypeParam = ((ParameterizedType) actualTypeParam).getRawType();
                }
                if (actualTypeParam instanceof Class) {
                    return (Class<?>) actualTypeParam;
                }
                if (actualTypeParam instanceof GenericArrayType) {
                    Type componentType = ((GenericArrayType) actualTypeParam).getGenericComponentType();
                    if (componentType instanceof ParameterizedType) {
                        componentType = ((ParameterizedType) componentType).getRawType();
                    }
                    if (componentType instanceof Class) {
                        return Array.newInstance((Class<?>) componentType, 0).getClass();
                    }
                }
                if (actualTypeParam instanceof TypeVariable) {
                    // Resolved type parameter points to another type parameter.
                    TypeVariable<?> v = (TypeVariable<?>) actualTypeParam;
                    if (!(v.getGenericDeclaration() instanceof Class)) {
                        return Object.class;
                    }

                    currentClass = thisClass;
                    parametrizedSuperclass = (Class<?>) v.getGenericDeclaration();
                    typeParamName = v.getName();
                    if (parametrizedSuperclass.isAssignableFrom(thisClass)) {
                        continue;
                    }
                    return Object.class;
                }

                return fail(thisClass, typeParamName);
            }
            // 递归父类
            currentClass = currentClass.getSuperclass();
            if (currentClass == null) {
                return fail(thisClass, typeParamName);
            }
        }
    }

    private static Class<?> fail(Class<?> type, String typeParamName) {
        throw new IllegalStateException(
                "cannot determine the type of the type parameter '" + typeParamName + "': " + type);
    }

    public abstract boolean match(Object msg);

    private static final class ReflectiveMatcher extends TypeParameterMatcher {
        private final Class<?> type;

        ReflectiveMatcher(Class<?> type) {
            this.type = type;
        }

        @Override
        public boolean match(Object msg) {
            // 用于检查当前类是否兼容参数中的obj对象，它的功能同instanceof运算符相同
            // 即和 msg instanceof type
            return type.isInstance(msg);
        }
    }

    TypeParameterMatcher() {
    }
}
