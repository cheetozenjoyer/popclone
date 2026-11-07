package dev.popclone;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileNotFoundException;
import java.util.concurrent.CountDownLatch;

// the provider android knows about in a stub process. other processes are sent here when they ask for
// a provider of the clone that lives in this slot, the call is passed on to the real provider.
// that provider was installed in this process when the clone started, so a plain lookup finds it.
public class StubProvider extends ContentProvider {

    // set while a call is being passed on, so a missing real provider cannot send us back to ourselves
    static final ThreadLocal<Boolean> forwarding = new ThreadLocal<>();

    private static volatile boolean started;

    @Override
    public boolean onCreate() {
        return true;
    }

    // android refuses a uri whose authority is not the provider's, so add the clone's authorities to ours
    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        Instances.Item item = Instances.find(context, Instances.currentSlot());
        if (item != null) {
            ProviderInfo copy = new ProviderInfo(info);
            copy.authority = info.authority + ";" + Clones.authoritiesOf(context, item.pkg);
            info = copy;
        }
        super.attachInfo(context, info);
    }

    // the clone of this slot has to be running, or its providers do not exist yet
    private static void ensureStarted() {
        if (started) return;
        Runnable boot = () -> {
            try {
                Instances.Item item = Instances.find(App.host, Instances.currentSlot());
                ApplicationInfo app = item == null ? null : Clones.appInfo(App.host, item.pkg);
                if (app != null) Bootstrap.once(App.host, app);
            } catch (Exception e) {
                Log.e("popclone", "cant start the clone for its provider", e);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            boot.run();
        } else {
            CountDownLatch done = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                boot.run();
                done.countDown();
            });
            try {
                done.await();
            } catch (InterruptedException e) {
                return;
            }
        }
        started = true;
    }

    private ContentResolver resolver() {
        ensureStarted();
        forwarding.set(true);
        return getContext().getContentResolver();
    }

    private static void done() {
        forwarding.remove();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            return resolver().query(uri, projection, selection, args, order);
        } finally {
            done();
        }
    }

    @Override
    public String getType(Uri uri) {
        try {
            return resolver().getType(uri);
        } finally {
            done();
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        try {
            return resolver().insert(uri, values);
        } finally {
            done();
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        try {
            return resolver().delete(uri, selection, args);
        } finally {
            done();
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        try {
            return resolver().update(uri, values, selection, args);
        } finally {
            done();
        }
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        try {
            return resolver().call(authority, method, arg, extras);
        } finally {
            done();
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            return resolver().openFileDescriptor(uri, mode);
        } finally {
            done();
        }
    }
}
