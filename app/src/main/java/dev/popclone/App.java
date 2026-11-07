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

    // play services refuses a clone's package name and the ads sdk does not catch it on its own threads.
    // only that exact error is dropped, the thread ends and the app keeps running.
    private static void survivePlayServicesRefusal() {
        Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (error instanceof SecurityException && String.valueOf(error.getMessage()).startsWith("Unknown calling package name")) {
                Log.w("popclone", "ignored play services refusal in " + thread.getName());
            } else if (old != null) {
                old.uncaughtException(thread, error);
            }
        });
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
        } catch (Exception e) {
            Log.e("popclone", "hook failed", e);
        }
        mark("hooks installed");
    }
}
