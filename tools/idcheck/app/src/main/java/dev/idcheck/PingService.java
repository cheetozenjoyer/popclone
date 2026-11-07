package dev.idcheck;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

// started and bound by MainActivity, logs every step so a clone can be checked
public class PingService extends Service {

    public static class Local extends Binder {
        public String hello() {
            return "hello from pid " + Process.myPid();
        }
    }

    @Override
    public void onCreate() {
        Log.i("idping", "onCreate pid " + Process.myPid());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("idping", "onStartCommand extra=" + (intent == null ? null : intent.getStringExtra("who")) + " startId=" + startId);
        new Handler().postDelayed(() -> {
            Log.i("idping", "stopSelf");
            stopSelf();
        }, 4000);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i("idping", "onBind");
        return new Local();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i("idping", "onUnbind");
        return false;
    }

    @Override
    public void onDestroy() {
        Log.i("idping", "onDestroy");
    }
}
