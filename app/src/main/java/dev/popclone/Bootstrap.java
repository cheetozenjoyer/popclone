package dev.popclone;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// starts a clone's Application by hand, the same steps android does for a normal app:
// create it, install its content providers (firebase and friends start from there), then onCreate.
// runs once per clone per process, on the main thread.
public class Bootstrap {

    private static final Set<String> done = new HashSet<>();

    public static void once(Context host, ApplicationInfo info) throws Exception {
        if (!done.add(info.packageName)) return;

        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null);

        // loadedapk is android's record of an app, it also builds the classloader
        Class<?> compatClass = Class.forName("android.content.res.CompatibilityInfo");
        Object compat = Reflect.field(compatClass, "DEFAULT_COMPATIBILITY_INFO").get(null);
        Object loadedApk = threadClass.getDeclaredMethod("getPackageInfoNoCheck", ApplicationInfo.class, compatClass)
                .invoke(thread, info, compat);

        App.mark("loadedapk ready");

        // with no instrumentation this only creates the app, it does not call onCreate
        Method make = loadedApk.getClass().getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class);
        Application app = (Application) make.invoke(loadedApk, false, null);

        App.mark("application created");
        List<ProviderInfo> providers = Clones.providers(host, info.packageName);
        if (!providers.isEmpty()) {
            Method install = threadClass.getDeclaredMethod("installContentProviders", Context.class, List.class);
            install.setAccessible(true);
            install.invoke(thread, app, providers);
        }

        App.mark("providers installed");
        Instrumentation instrumentation = (Instrumentation) Reflect.field(threadClass, "mInstrumentation").get(thread);
        instrumentation.callApplicationOnCreate(app);
        Log.i("popclone", "started " + info.packageName + " with " + providers.size() + " providers");
    }
}
