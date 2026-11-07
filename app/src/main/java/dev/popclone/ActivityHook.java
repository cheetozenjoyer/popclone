package dev.popclone;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// hook used to swap a clone's real screen for a stub declared in the manifest.
// the real intent rides along in an extra, LaunchHook swaps it back.
public class ActivityHook {

    public static final String KEY = "popclone.target";
    public static final String EXTRA_SLOT = "popclone.slot";
    // the clone that started a screen, sent along so the screen can be told who called it
    public static final String CALLER = "popclone.caller";
    // activity token -> package of the clone that started it
    static final Map<Object, String> callers = new ConcurrentHashMap<>();
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
                if (method.getName().equals("startService") || method.getName().startsWith("bind")) rewriteService(args);
                if (method.getName().equals("getContentProvider")) rerouteProvider(args);
                if (method.getName().startsWith("broadcastIntent")) fixBroadcastUser(args);
                if (method.getName().startsWith("getIntentSender")) rewriteIntentSender(args);
                Object handled = handleService(method, args);
                if (handled != ServiceHost.NONE) return handled;
                useHostPackage(args);
            }
            Object result = call(real, method, args);
            if (method.getName().equals("getContentProvider") && result != null) wrapProvider(result);
            if (method.getName().equals("checkPermission") && args != null) result = grantCloneAccess(args, result);
            // android says the caller of a screen is us, the screen wants the clone that really called
            if (method.getName().startsWith("getCalling") && args != null && args.length > 0 && callers.containsKey(args[0])) {
                String caller = callers.get(args[0]);
                if (result instanceof String) result = caller;
                else if (result instanceof ComponentName) result = new ComponentName(caller, ((ComponentName) result).getClassName());
                Log.i(TAG, method.getName() + " answered with " + caller);
            }
            return result;
        });
        Field instance = Reflect.field(singletonClass, "mInstance");
        instance.set(singleton, proxy);
    }

    // google analytics and the ads signals client fail the uid check in play services and kill the app.
    // saying "cant bind" makes them give up quietly.
    private static final String[] BLOCKED_BIND_ACTIONS = {
            "com.google.android.gms.measurement.START",
            "com.google.android.gms.gass.START",
    };

    private static boolean isBlockedBind(Object[] args) {
        // with play services cloned the bind goes to the clone, nothing to block
        if (Instances.hasPackage(App.host, "com.google.android.gms")) return false;
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

    // a provider that belongs to another clone is asked for through that clone's stub provider.
    // the clone's own provider is found in this process without asking android.
    private static void rerouteProvider(Object[] args) {
        if (Boolean.TRUE.equals(StubProvider.forwarding.get())) return;
        // getContentProvider(caller, callingPackage, authority, userId, stable)
        if (args.length < 3 || !(args[2] instanceof String)) return;
        String owner = Clones.ownerOfAuthority(App.host, (String) args[2]);
        if (owner == null) return;
        int slot = serviceSlot(App.host, owner);
        if (slot < 0 || slot == Instances.currentSlot()) return;
        args[2] = App.host.getPackageName() + ".stub.p" + slot;
    }

    // a PendingIntent made by a clone points at the real app's screen or service. it gets the same
    // stub treatment as a direct launch, or the system would open the real app when it fires.
    // types: 2 activity, 4 service, 5 foreground service
    private static void rewriteIntentSender(Object[] args) {
        if (!(args[0] instanceof Integer)) return;
        int type = (Integer) args[0];
        if (type != 2 && type != 4 && type != 5) return;
        for (Object a : args) {
            if (!(a instanceof Intent[])) continue;
            Intent[] intents = (Intent[]) a;
            for (int i = 0; i < intents.length; i++) {
                Intent stub = type == 2 ? toStub(App.host, intents[i]) : toServiceStub(App.host, intents[i]);
                if (stub != null) intents[i] = stub;
            }
            return;
        }
    }

    // play services sends broadcasts to all users, that takes a permission only the system has.
    // the user is the last int of the call, -1 means all users.
    private static void fixBroadcastUser(Object[] args) {
        for (int i = args.length - 1; i >= 0; i--) {
            if (!(args[i] instanceof Integer)) continue;
            if ((Integer) args[i] == -1) args[i] = android.os.Process.myUid() / 100000;
            return;
        }
    }

    // the clones are one app to us, so a permission a cloned package defines (gservices) is granted
    private static Object grantCloneAccess(Object[] args, Object result) {
        if (!(result instanceof Integer) || (Integer) result == PackageManager.PERMISSION_GRANTED) return result;
        if (args[0] instanceof String && Clones.declaresPermission(App.host, (String) args[0])) return PackageManager.PERMISSION_GRANTED;
        // our account stand in plays the system's account service, authenticators only answer to that
        if ("android.permission.ACCOUNT_MANAGER".equals(args[0]) && args.length > 2 && args[2] instanceof Integer && (Integer) args[2] == android.os.Process.myUid()) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return result;
    }

    // service calls aimed at a clone go to its stub service, and a few calls the clone's own services
    // make back to the system are answered here. NONE means nothing was done.
    private static Object handleService(Method method, Object[] args) {
        String name = method.getName();
        if (name.equals("stopService")) {
            for (Object a : args) {
                if (!(a instanceof Intent)) continue;
                Intent stub = toServiceStub(App.host, (Intent) a);
                if (stub == null) return ServiceHost.NONE;
                // stopping happens inside the stub process
                App.host.startService(stub.putExtra(ServiceHost.STOP, true));
                return 1;
            }
            return ServiceHost.NONE;
        }
        Object result = ServiceHost.intercept(name, args);
        if (result == null && method.getReturnType() == boolean.class) return false;
        return result;
    }

    private static void rewriteService(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Intent) {
                Intent stub = toServiceStub(App.host, (Intent) args[i]);
                if (stub != null) args[i] = stub;
                return;
            }
        }
    }

    // the slot that runs this package: our own when a clone calls itself, else the first clone of it
    private static int serviceSlot(Context host, String pkg) {
        Instances.Item own = Instances.find(host, Instances.currentSlot());
        if (own != null && own.pkg.equals(pkg)) return own.slot;
        for (Instances.Item item : Instances.all(host)) {
            if (item.pkg.equals(pkg)) return item.slot;
        }
        return -1;
    }

    // null means leave the intent alone
    private static Intent toServiceStub(Context host, Intent real) {
        ComponentName target = real.getComponent();
        if (target == null && real.getPackage() != null) {
            ResolveInfo found = host.getPackageManager().resolveService(real, 0);
            if (found != null) target = new ComponentName(found.serviceInfo.packageName, found.serviceInfo.name);
        }
        if (target == null || !Clones.isCloned(host, target.getPackageName())) return null;

        int slot = serviceSlot(host, target.getPackageName());
        if (slot < 0) return null;

        Intent inner = new Intent(real).setComponent(target);
        Intent stub = new Intent();
        stub.setComponent(new ComponentName(host.getPackageName(), "dev.popclone.Stubs$S" + slot));
        // android hands the same binder to intents that differ only in extras, so the real service goes in the data
        stub.setData(Uri.fromParts("popclone", target.flattenToShortString() + "/" + real.getAction(), null));
        stub.putExtra(KEY, inner);
        Log.i(TAG, "service " + target.flattenToShortString() + " in stub " + slot);
        return stub;
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
        if (item != null && item.pkg.equals(pkg)) return slot;
        // a helper (play services) has no screen of its own, so any clone can reach its screens
        for (Instances.Item other : Instances.all(host)) {
            if (other.pkg.equals(pkg) && other.launcher.isEmpty()) return other.slot;
        }
        return -1;
    }

    // null means leave the intent alone
    private static Intent toStub(Context host, Intent real) {
        ComponentName target = real.getComponent();
        // an intent that names an action and the cloned package (google sign in) gets its screen looked up
        if (target == null && real.getPackage() != null && Clones.isCloned(host, real.getPackage())) {
            ResolveInfo found = host.getPackageManager().resolveActivity(real, 0);
            if (found != null) target = new ComponentName(found.activityInfo.packageName, found.activityInfo.name);
        }
        if (target == null || !Clones.isCloned(host, target.getPackageName())) return null;

        int slot = slotFor(host, real, target.getPackageName());
        if (slot < 0) return null;
        if (real.getComponent() == null) real = new Intent(real).setComponent(target);

        Intent stub = new Intent();
        stub.setComponent(new ComponentName(host.getPackageName(), "dev.popclone.Stubs$A" + slot));
        stub.setFlags(real.getFlags());
        stub.putExtra(KEY, real);
        Instances.Item me = Instances.find(host, Instances.currentSlot());
        if (me != null) stub.putExtra(CALLER, me.pkg);
        Log.i(TAG, "start " + target.flattenToShortString() + " in stub " + slot);
        return stub;
    }
}
