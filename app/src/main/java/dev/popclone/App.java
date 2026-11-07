package dev.popclone;

import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class App extends Application {

    // the host context, safe to use from any hook
    public static Context host;

    // logs how many ms since this process started, to see where startup time goes
    public static void mark(String what) {
        Log.i("popclone", "t+" + (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) + "ms " + what);
    }

    // play services refuses a clone's package name, so its ad code fails on its own threads and nobody catches it.
    // errors from that refusal or thrown inside play services code are dropped, the app keeps running.
    private static class Survivor implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler next;

        Survivor(Thread.UncaughtExceptionHandler next) {
            this.next = next;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            if (fromPlayServices(error)) {
                Log.w("popclone", "ignored play services error in " + thread.getName() + ": " + error);
            } else if (next != null) {
                next.uncaughtException(thread, error);
            }
        }
    }

    private static boolean fromPlayServices(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SecurityException && String.valueOf(t.getMessage()).startsWith("Unknown calling package name")) return true;
            StackTraceElement[] trace = t.getStackTrace();
            if (trace.length > 0 && (trace[0].getClassName().startsWith("com.google.android.gms")
                    || String.valueOf(trace[0].getFileName()).contains("com.google.android.gms"))) return true;
        }
        return false;
    }

    // ad sdks (applovin and friends) set their own handler that kills the process, so
    // keep ours on top by putting it back whenever something replaces it
    private static void survivePlayServicesRefusal() {
        wrapHandler();
        Thread keeper = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                wrapHandler();
            }
        }, "popclone-handler");
        keeper.setDaemon(true);
        keeper.start();
    }

    private static void wrapHandler() {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (!(current instanceof Survivor)) Thread.setDefaultUncaughtExceptionHandler(new Survivor(current));
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // allow reflection into hidden android classes, needed for everything else
        HiddenApiBypass.addHiddenApiExemptions("L");
        host = base;

        // inside a clone process, load that clone's device ids
        int slot = Instances.currentSlot();
        if (slot >= 0) {
            DeviceInfo.current = DeviceInfo.load(base, slot);
            // never fall back to the real ids, make new ones if the saved ones are gone
            if (DeviceInfo.current == null) {
                DeviceInfo.current = DeviceInfo.generate();
                try {
                    DeviceInfo.current.save(base, slot);
                } catch (Exception e) {
                    Log.w("popclone", "cant save device ids: " + e);
                }
            }
            DeviceHook.applyBuild();

            // webview allows one process per data folder, so every clone gets its own.
            // has to happen before anything creates a webview.
            try {
                WebView.setDataDirectorySuffix("slot" + slot);
            } catch (Exception e) {
                Log.w("popclone", "webview suffix failed: " + e);
            }
        }

        if (slot >= 0) survivePlayServicesRefusal();

        try {
            ActivityHook.install();
            LaunchHook.install();
            ServiceHook.install();
            if (slot >= 0) PackageHook.install();
        } catch (Exception e) {
            Log.e("popclone", "hook failed", e);
        }
        mark("hooks installed");
    }
}
