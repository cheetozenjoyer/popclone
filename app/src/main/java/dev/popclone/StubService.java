package dev.popclone;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

// the one service android knows about in a stub process. every real service of the clone goes
// through it, ServiceHost does the work
public class StubService extends Service {

    @Override
    public void onCreate() {
        ServiceHost.stub = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServiceHost.start(intent, flags, startId);
        // never ask android to restart the stub on its own, it would come back with no intent
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return ServiceHost.bind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return ServiceHost.unbind(intent);
    }

    @Override
    public void onDestroy() {
        ServiceHost.stub = null;
    }
}
