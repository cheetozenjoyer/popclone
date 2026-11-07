package dev.popclone;

import android.os.Process;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// play services (and the apps that call it) check that a package name belongs to the uid that is calling.
// every clone runs under our uid, so the package manager has to say that every cloned package is ours.
public class PackageHook {

    public static void install() throws Exception {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object real = threadClass.getDeclaredMethod("getPackageManager").invoke(null);
        if (real == null || Proxy.isProxyClass(real.getClass())) return;

        Class<?> iface = Class.forName("android.content.pm.IPackageManager");
        Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, (p, method, args) -> {
            // only the real app may switch its own components on and off, a clone just thinks it did
            if (method.getName().equals("setComponentEnabledSetting") && args[0] instanceof android.content.ComponentName
                    && isClone(((android.content.ComponentName) args[0]).getPackageName())) return null;
            Object result;
            try {
                result = method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            switch (method.getName()) {
                case "getPackagesForUid":
                    return withClones(args, (String[]) result);
                case "getPackageUid":
                    return isClone(args[0]) ? Process.myUid() : result;
                default:
                    return result;
            }
        });
        Reflect.field(threadClass, "sPackageManager").set(null, proxy);

        // the host's own package manager was made before this, point it at the proxy too
        try {
            Object pm = App.host.getPackageManager();
            Reflect.field(pm.getClass(), "mPM").set(pm, proxy);
        } catch (Exception e) {
            Log.w("popclone", "host package manager not hooked: " + e);
        }
    }

    private static boolean isClone(Object pkg) {
        if (!(pkg instanceof String)) return false;
        for (Instances.Item item : Instances.all(App.host)) {
            if (item.pkg.equals(pkg)) return true;
        }
        return false;
    }

    // the packages of our own uid, plus every package that is cloned here
    private static String[] withClones(Object[] args, String[] own) {
        if (!(args[0] instanceof Integer) || (Integer) args[0] != Process.myUid()) return own;
        Set<String> all = new LinkedHashSet<>();
        if (own != null) all.addAll(Arrays.asList(own));
        List<String> clones = new ArrayList<>();
        for (Instances.Item item : Instances.all(App.host)) clones.add(item.pkg);
        all.addAll(clones);
        return all.toArray(new String[0]);
    }
}
