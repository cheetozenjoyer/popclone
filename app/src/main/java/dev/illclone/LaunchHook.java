package dev.illclone;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

// runs in the stub process. when android is about to launch the stub activity,
// put the real clone activity in its place.
public class LaunchHook {

    private static final String TAG = "illclone";

    public static void install() throws Exception {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null);
        Handler handler = (Handler) Reflect.field(threadClass, "mH").get(thread);
        int executeTransaction = Reflect.field(Class.forName("android.app.ActivityThread$H"), "EXECUTE_TRANSACTION").getInt(null);

        Field callbackField = Reflect.field(Handler.class, "mCallback");
        Handler.Callback old = (Handler.Callback) callbackField.get(handler);
        callbackField.set(handler, (Handler.Callback) msg -> {
            if (msg.what == executeTransaction) {
                try {
                    swap(msg.obj);
                } catch (Throwable t) {
                    Log.e(TAG, "swap failed", t);
                }
            }
            // false lets the normal handler carry on
            return old != null && old.handleMessage(msg);
        });
    }

    // android 12+ builds the activity record before this callback runs, so the item is too late.
    // that record is kept in ActivityThread under the activity token, change it too.
    private static void fixLaunchRecord(Object transaction, Intent real, ActivityInfo info) throws Exception {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Method find;
        try {
            find = threadClass.getDeclaredMethod("getLaunchingActivity", IBinder.class);
        } catch (NoSuchMethodException e) {
            return; // android 11 and older, the item is enough
        }
        Object thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null);
        Object token = Reflect.field(transaction.getClass(), "mActivityToken").get(transaction);
        Object record = find.invoke(thread, token);
        if (record == null) return;
        Reflect.field(record.getClass(), "intent").set(record, real);
        Reflect.field(record.getClass(), "activityInfo").set(record, info);

        // the record also grabbed the stub app's LoadedApk (its classloader), give it the clone's
        Class<?> compatClass = Class.forName("android.content.res.CompatibilityInfo");
        Object compat = Reflect.field(record.getClass(), "compatInfo").get(record);
        Object loadedApk = threadClass.getDeclaredMethod("getPackageInfoNoCheck", ApplicationInfo.class, compatClass)
                .invoke(thread, info.applicationInfo, compat);
        Reflect.field(record.getClass(), "packageInfo").set(record, loadedApk);
    }

    // msg.obj is a ClientTransaction, the launch is one of its callbacks
    private static void swap(Object transaction) throws Exception {
        List<?> items = (List<?>) Reflect.field(transaction.getClass(), "mActivityCallbacks").get(transaction);
        if (items == null) return;
        for (Object item : items) {
            if (!item.getClass().getName().endsWith("LaunchActivityItem")) continue;

            Field intentField = Reflect.field(item.getClass(), "mIntent");
            Intent stub = (Intent) intentField.get(item);
            Intent real = stub.getParcelableExtra(ActivityHook.KEY);
            if (real == null) continue;

            App.mark("launch seen");
            ActivityInfo info = Clones.activityInfo(App.host, real.getComponent());
            App.mark("package info ready");
            if (info == null) {
                Log.e(TAG, "no activity info for " + real.getComponent());
                continue;
            }
            Bootstrap.once(App.host, info.applicationInfo);
            App.mark("app started");
            intentField.set(item, real);
            Reflect.field(item.getClass(), "mInfo").set(item, info);
            fixLaunchRecord(transaction, real, info);
            Log.i(TAG, "launching " + real.getComponent().flattenToShortString());
        }
    }
}
