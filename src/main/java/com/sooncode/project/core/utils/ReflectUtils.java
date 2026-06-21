package com.sooncode.project.core.utils;
import java.beans.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;



public class ReflectUtils {

    private ReflectUtils() {
    }

    public static Object newInstance(Class type, Class[] parameterTypes, Object[] args) {
        return newInstance(getConstructor(type, parameterTypes), args);
    }

    @SuppressWarnings("deprecation")  // on JDK 9
    public static Object newInstance(final Constructor cstruct, final Object[] args) {
        boolean flag = cstruct.isAccessible();
        try {
            if (!flag) {
                cstruct.setAccessible(true);
            }
            Object result = cstruct.newInstance(args);
            return result;
        }
        catch (InstantiationException e) {
            throw new CodeGenerationException(e);
        }
        catch (IllegalAccessException e) {
            throw new CodeGenerationException(e);
        }
        catch (InvocationTargetException e) {
            throw new CodeGenerationException(e.getTargetException());
        }
        finally {
            if (!flag) {
                cstruct.setAccessible(flag);
            }
        }
    }

    public static Constructor getConstructor(Class type, Class[] parameterTypes) {
        try {
            Constructor constructor = type.getDeclaredConstructor(parameterTypes);
            if (System.getSecurityManager() != null) {
                AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                    constructor.setAccessible(true);
                    return null;
                });
            }
            else {
                constructor.setAccessible(true);
            }
            return constructor;
        }
        catch (NoSuchMethodException e) {
            throw new CodeGenerationException(e);
        }
    }



    public static PropertyDescriptor[] getBeanProperties(Class type) {
        return getPropertiesHelper(type, true, true);
    }

    public static PropertyDescriptor[] getBeanGetters(Class type) {
        return getPropertiesHelper(type, true, false);
    }

    public static PropertyDescriptor[] getBeanSetters(Class type) {
        return getPropertiesHelper(type, false, true);
    }

    private static PropertyDescriptor[] getPropertiesHelper(Class type, boolean read, boolean write) {
        try {
            BeanInfo info = Introspector.getBeanInfo(type, Object.class);
            PropertyDescriptor[] all = info.getPropertyDescriptors();
            if (read && write) {
                return all;
            }
            List properties = new ArrayList(all.length);
            for (int i = 0; i < all.length; i++) {
                PropertyDescriptor pd = all[i];
                if ((read && pd.getReadMethod() != null) ||
                        (write && pd.getWriteMethod() != null)) {
                    properties.add(pd);
                }
            }
            return (PropertyDescriptor[]) properties.toArray(new PropertyDescriptor[properties.size()]);
        }
        catch (IntrospectionException e) {
            throw new CodeGenerationException(e);
        }
    }
}
