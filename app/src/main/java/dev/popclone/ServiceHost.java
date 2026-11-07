package dev.popclone;

import android.app.Notification;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.pm.ServiceInfo;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

// runs a clone's services inside its stub process. android only knows the stub service (StubService),
// so the real ones are created, bound and stopped here with the same ActivityThread code android uses,
// under tokens of our own. the calls those methods make back to the system are caught in intercept().
// all of it runs on the main thread.
public class ServiceHost {

    private static final String TAG = "popclone";
    public static final String STOP = "popclone.stop";
    public static final Object NONE = new Object();

    // set by the stub service while it is alive
    static StubService stub;

    private static class Rec {
        final ComponentName name;
        final IBinder token = new Binder();
        boolean started;
        int lastStartId;
        // stub intent data -> the real intent it was bound with
        final Map<String, Intent> bound = new HashMap<>();

        Rec(ComponentName name) {
            this.name = name;
        }
    }

    private static final Map<ComponentName, Rec> recs = new HashMap<>();
    private static final Map<IBinder, Rec> byToken = new HashMap<>();
    private static IBinder published;
    private static boolean rebind;

    // startService on the stub: the real intent rides along in an extra
    public static void start(Intent stubIntent, int flags, int startId) {
        Intent real = realIntent(stubIntent);
        if (real == null) return;

        if (stubIntent.getBooleanExtra(STOP, false)) {
            Rec r = recs.get(real.getComponent());
            if (r != null) {
                r.started = false;
                maybeDestroy(r);
            }
            return;
        }

        Rec r = create(real.getComponent());
        if (r == null) return;
        r.started = true;
        r.lastStartId = startId;
        Object data = newData("ServiceArgsData");
        set(data, "token", r.token);
        set(data, "taskRemoved", false);
        set(data, "startId", startId);
        set(data, "flags", flags);
        set(data, "args", real);
        run("handleServiceArgs", data.getClass(), data);
    }

    public static IBinder bind(Intent stubIntent) {
        Intent real = realIntent(stubIntent);
        if (real == null) return null;
        Rec r = create(real.getComponent());
        if (r == null) return null;

        r.bound.put(String.valueOf(stubIntent.getData()), real);
        Object data = newData("BindServiceData");
        set(data, "token", r.token);
        set(data, "intent", real);
        set(data, "rebind", false);
        published = null;
        run("handleBindService", data.getClass(), data);
        IBinder binder = published;
        published = null;
        return binder;
    }

    public static boolean unbind(Intent stubIntent) {
        Intent real = realIntent(stubIntent);
        Rec r = real == null ? null : recs.get(real.getComponent());
        if (r == null) return false;

        Intent bound = r.bound.remove(String.valueOf(stubIntent.getData()));
        Object data = newData("BindServiceData");
        set(data, "token", r.token);
        set(data, "intent", bound != null ? bound : real);
        rebind = false;
        run("handleUnbindService", data.getClass(), data);
        boolean again = rebind;
        rebind = false;
        maybeDestroy(r);
        return again;
    }

    // calls the real services make to the system. returns NONE for everything that is not ours.
    public static Object intercept(String method, Object[] args) {
        switch (method) {
            case "serviceDoneExecuting":
                return isOurs(args[0]) ? null : NONE;
            case "publishService":
                if (!isOurs(args[0])) return NONE;
                published = (IBinder) args[2];
                return null;
            case "unbindFinished":
                if (!isOurs(args[0])) return NONE;
                rebind = (Boolean) args[2];
                return null;
            case "stopServiceToken":
                return stopSelf(args);
            case "setServiceForeground":
                return foreground(args);
            default:
                return NONE;
        }
    }

    private static boolean isOurs(Object token) {
        return token instanceof IBinder && byToken.containsKey(token);
    }

    // stopSelf() of a real service
    private static Object stopSelf(Object[] args) {
        Rec r = byToken.get(args[1]);
        if (r == null) return NONE;
        int startId = (Integer) args[2];
        if (startId < 0 || startId == r.lastStartId) {
            r.started = false;
            // not inside onStartCommand, the service may still be using itself
            new Handler(Looper.getMainLooper()).post(() -> maybeDestroy(r));
        }
        return true;
    }

    // startForeground() of a real service becomes the stub's own
    private static Object foreground(Object[] args) {
        if (!isOurs(args[1])) return NONE;
        Service s = stub;
        if (s == null) return null;
        Notification n = (Notification) args[3];
        if (n == null) {
            s.stopForeground((((Integer) args[4]) & Service.STOP_FOREGROUND_REMOVE) != 0);
            return null;
        }
        int id = (Integer) args[2];
        try {
            if (Build.VERSION.SDK_INT >= 29 && args.length > 5) s.startForeground(id, n, (Integer) args[5]);
            else s.startForeground(id, n);
        } catch (RuntimeException e) {
            // the stub does not declare that foreground type, the plain call is enough
            s.startForeground(id, n);
        }
        return null;
    }

    private static void maybeDestroy(Rec r) {
        if (r.started || !r.bound.isEmpty() || !recs.containsKey(r.name)) return;
        run("handleStopService", IBinder.class, r.token);
        recs.remove(r.name);
        byToken.remove(r.token);
        if (recs.isEmpty() && stub != null) stub.stopSelf();
    }

    private static Intent realIntent(Intent stubIntent) {
        if (stubIntent == null) return null;
        Intent real = stubIntent.getParcelableExtra(ActivityHook.KEY);
        return real == null || real.getComponent() == null ? null : real;
    }

    private static Rec create(ComponentName name) {
        Rec r = recs.get(name);
        if (r != null) return r;
        ServiceInfo info = Clones.serviceInfo(App.host, name);
        if (info == null) {
            Log.e(TAG, "no service info for " + name);
            return null;
        }
        try {
            Bootstrap.once(App.host, info.applicationInfo);
            Class<?> compatClass = Class.forName("android.content.res.CompatibilityInfo");
            r = new Rec(name);
            // known before the call, handleCreateService reports back with this token
            recs.put(name, r);
            byToken.put(r.token, r);
            Object data = newData("CreateServiceData");
            set(data, "token", r.token);
            set(data, "info", info);
            set(data, "compatInfo", Reflect.field(compatClass, "DEFAULT_COMPATIBILITY_INFO").get(null));
            run("handleCreateService", data.getClass(), data);
            // android swallows a failed create, so ask whether the service really exists now
            Map<?, ?> services = (Map<?, ?>) Reflect.field(Class.forName("android.app.ActivityThread"), "mServices").get(thread());
            if (!services.containsKey(r.token)) throw new IllegalStateException("service was not created");
            Log.i(TAG, "service created " + name.flattenToShortString());
            return r;
        } catch (Exception e) {
            Log.e(TAG, "cant create service " + name, e);
            if (r != null) {
                recs.remove(name);
                byToken.remove(r.token);
            }
            return null;
        }
    }

    private static Object thread() throws Exception {
        return Class.forName("android.app.ActivityThread").getDeclaredMethod("currentActivityThread").invoke(null);
    }

    private static Object newData(String simpleName) {
        try {
            Constructor<?> c = Class.forName("android.app.ActivityThread$" + simpleName).getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Reflect.field(target.getClass(), field).set(target, value);
        } catch (Exception e) {
            // a field some android versions do not have (the intent of a created service)
            Log.w(TAG, "no field " + field + ": " + e);
        }
    }

    private static void run(String method, Class<?> type, Object arg) {
        try {
            Method m = Class.forName("android.app.ActivityThread").getDeclaredMethod(method, type);
            m.setAccessible(true);
            m.invoke(thread(), arg);
        } catch (InvocationTargetException e) {
            Log.e(TAG, method + " failed", e.getCause());
        } catch (Exception e) {
            Log.e(TAG, method + " failed", e);
        }
    }
}
