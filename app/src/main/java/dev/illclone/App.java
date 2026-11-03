package dev.illclone;

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
        Log.i("illclone", "t+" + (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) + "ms " + what);
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
            DeviceHook.applyBuild();

            // webview allows one process per data folder, so every clone gets its own.
            // has to happen before anything creates a webview.
            try {
                WebView.setDataDirectorySuffix("slot" + slot);
            } catch (Exception e) {
                Log.w("illclone", "webview suffix failed: " + e);
            }
        }

        try {
            ActivityHook.install();
            LaunchHook.install();
            ServiceHook.install();
        } catch (Exception e) {
            Log.e("illclone", "hook failed", e);
        }
        mark("hooks installed");
    }
}
