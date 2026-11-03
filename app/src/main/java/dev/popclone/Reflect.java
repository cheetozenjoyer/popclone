package dev.popclone;

import java.lang.reflect.Field;

// tiny reflection helper so the hooks stay readable
public class Reflect {

    public static Field field(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
