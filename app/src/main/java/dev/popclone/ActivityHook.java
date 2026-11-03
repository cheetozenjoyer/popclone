package dev.popclone;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

// hook used to swap a clone's real screen for a stub declared in the manifest.
// the real intent rides along in an extra, LaunchHook swaps it back.
public class ActivityHook {

    public static final String KEY = "popclone.target";
    public static final String EXTRA_SLOT = "popclone.slot";
    private static final String TAG = "popclone";

    public static void install() throws Exception {
        // android 10+ split the activity manager in two, both need the hook
        if (Build.VERSION.SDK_INT >= 29) {
            hook("android.app.ActivityTaskManager", "IActivityTaskManagerSingleton", "android.app.IActivityTaskManager");
        }
        hook("android.app.ActivityManager", "IActivityManagerSingleton", "android.app.IActivityManager");
    }

    private static void hook(String holderClass, String singletonField, String ifaceName) throws Exception {
        Object singleton = Reflect.field(Class.forName(holderClass), singletonField).get(null);
        Class<?> iface = Class.forName(ifaceName);

        Class<?> singletonClass = Class.forName("android.util.Singleton");
        Method get = singletonClass.getDeclaredMethod("get");
        get.setAccessible(true);
        Object real = get.invoke(singleton);
        if (Proxy.isProxyClass(real.getClass())) return; // already hooked

        Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, (p, method, args) -> {
            if (args != null) {
                if (method.getName().startsWith("bind") && isBlockedBind(args)) return 0;
                if (method.getName().equals("startActivity")) rewrite(args);
                useHostPackage(args);
            }
            Object result = call(real, method, args);
            if (method.getName().equals("getContentProvider") && result != null) wrapProvider(result);
            return result;
        });
        Field instance = Reflect.field(singletonClass, "mInstance");
        instance.set(singleton, proxy);
    }

    // google analytics inside a clone fails the uid check in play services and kills the app.
    // saying "cant bind" makes it give up quietly.
    private static final String[] BLOCKED_BIND_ACTIONS = {"com.google.android.gms.measurement.START"};

    private static boolean isBlockedBind(Object[] args) {
        for (Object a : args) {
            if (!(a instanceof Intent)) continue;
            String action = ((Intent) a).getAction();
            for (String blocked : BLOCKED_BIND_ACTIONS) {
                if (blocked.equals(action)) return true;
            }
        }
        return false;
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // content providers check the calling package on every call too.
    // result is a ContentProviderHolder, swap its provider for one that fixes the package name.
    private static void wrapProvider(Object holder) throws Exception {
        Field field = holder.getClass().getField("provider");
        Object provider = field.get(holder);
        if (provider == null || Proxy.isProxyClass(provider.getClass())) return;

        Class<?> iface = Class.forName("android.content.IContentProvider");
        field.set(holder, Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, (p, method, args) -> {
            if (args != null) {
                if (method.getName().equals("call")) {
                    Bundle fake = DeviceHook.fakeSetting(args);
                    if (fake != null) return fake;
                }
                useHostPackage(args);
            }
            return call(provider, method, args);
        }));
    }

    // the system checks that the package name matches our uid.
    // a clone says it is "com.some.app", so say our own name instead.
    public static void useHostPackage(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String && Clones.isCloned(App.host, (String) args[i])) {
                args[i] = App.host.getPackageName();
            } else if (args[i] != null && args[i].getClass().getName().equals("android.content.AttributionSource")) {
                args[i] = hostAttribution(args[i]);
            }
        }
    }

    // android 12+ passes the package inside an AttributionSource object instead of a plain string.
    // returns a copy that says our package, or the same object if it already does.
    private static Object hostAttribution(Object source) {
        try {
            Class<?> cls = source.getClass();
            String pkg = (String) cls.getMethod("getPackageName").invoke(source);
            if (pkg == null || !Clones.isCloned(App.host, pkg)) return source;

            int uid = (int) cls.getMethod("getUid").invoke(source);
            String tag = (String) cls.getMethod("getAttributionTag").invoke(source);
            return cls.getDeclaredConstructor(int.class, String.class, String.class)
                    .newInstance(uid, App.host.getPackageName(), tag);
        } catch (Exception e) {
            Log.w(TAG, "cant fix attribution source: " + e);
            return source;
        }
    }

    private static void rewrite(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Intent) {
                Intent stub = toStub(App.host, (Intent) args[i]);
                if (stub != null) args[i] = stub;
                return;
            }
        }
    }

    // which clone should run this intent. the main app says so with an extra,
    // a clone starting its own activities stays in itself.
    private static int slotFor(Context host, Intent real, String pkg) {
        int slot = real.getIntExtra(EXTRA_SLOT, -1);
        real.removeExtra(EXTRA_SLOT);
        if (slot < 0) slot = Instances.currentSlot();

        Instances.Item item = Instances.find(host, slot);
        if (item == null || !item.pkg.equals(pkg)) return -1;
        return slot;
    }

    // null means leave the intent alone
    private static Intent toStub(Context host, Intent real) {
        ComponentName target = real.getComponent();
        if (target == null || !Clones.isCloned(host, target.getPackageName())) return null;

        int slot = slotFor(host, real, target.getPackageName());
        if (slot < 0) return null;

        Intent stub = new Intent();
        stub.setComponent(new ComponentName(host.getPackageName(), "dev.popclone.Stubs$A" + slot));
        stub.setFlags(real.getFlags());
        stub.putExtra(KEY, real);
        Log.i(TAG, "start " + target.flattenToShortString() + " in stub " + slot);
        return stub;
    }
}
