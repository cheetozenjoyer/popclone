package dev.popclone;

import android.content.ComponentName;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

// makes system services see our package name instead of the clone's.
// each service gets a fake binder that hands out a proxy, every call has its package names swapped.
public class ServiceHook {

    // service name in ServiceManager -> its aidl interface
    private static final String[][] SERVICES = {
            {"notification", "android.app.INotificationManager"}, // toasts use their own copy of it
            {"connectivity", "android.net.IConnectivityManager"},
            {"wifi", "android.net.wifi.IWifiManager"},
            {"phone", "com.android.internal.telephony.ITelephony"},
            {"iphonesubinfo", "com.android.internal.telephony.IPhoneSubInfo"},
            {"device_identifiers", "android.os.IDeviceIdentifiersPolicyService"},
            {"isub", "com.android.internal.telephony.ISub"},
            {"appops", "com.android.internal.app.IAppOpsService"},
            {"user", "android.os.IUserManager"},
            {"power", "android.os.IPowerManager"},
            {"alarm", "android.app.IAlarmManager"},
            {"location", "android.location.ILocationManager"},
            {"clipboard", "android.content.IClipboard"},
            {"audio", "android.media.IAudioService"},
            {"jobscheduler", "android.app.job.IJobScheduler"},
            {"account", "android.accounts.IAccountManager"},
            {"vibrator", "android.os.IVibratorService"},
            {"netstats", "android.net.INetworkStatsService"},
            {"usagestats", "android.app.usage.IUsageStatsManager"},
            {"appwidget", "com.android.internal.appwidget.IAppWidgetService"},
            {"content", "android.content.IContentService"},
    };

    public static void install() throws Exception {
        // the notification manager keeps its service in a static field, replace that directly
        hookNotification();
        try {
            hookInputMethod();
        } catch (Throwable th) {
            Log.w("popclone", "skipped input method: " + th);
        }

        for (String[] service : SERVICES) {
            try {
                hookByName(service[0], service[1]);
            } catch (Throwable t) {
                Log.w("popclone", "skipped service " + service[0] + ": " + t);
            }
        }
    }

    private static void hookNotification() throws Exception {
        Class<?> holder = Class.forName("android.app.NotificationManager");
        Object real = holder.getDeclaredMethod("getService").invoke(null);
        if (real == null || Proxy.isProxyClass(real.getClass())) return;
        Class<?> iface = Class.forName("android.app.INotificationManager");
        Reflect.field(holder, "sService").set(null, hostPackageProxy(iface, real));
    }

    // the keyboard service rejects a text field whose EditorInfo has a package that is not ours.
    // without this the keyboard opens but cannot type into the clone.
    private static void hookInputMethod() throws Exception {
        Class<?> immClass = Class.forName("android.view.inputmethod.InputMethodManager");
        Object imm = immClass.getDeclaredMethod("getInstance").invoke(null);
        Field serviceField = Reflect.field(immClass, "mService");
        Object real = serviceField.get(imm);
        if (real == null || Proxy.isProxyClass(real.getClass())) return;

        Class<?> iface = Class.forName("com.android.internal.view.IInputMethodManager");
        serviceField.set(imm, Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, (p, method, args) -> {
            if (args != null) {
                for (Object a : args) {
                    if (a instanceof EditorInfo) ((EditorInfo) a).packageName = App.host.getPackageName();
                }
                ActivityHook.useHostPackage(args);
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }));
    }

    private static void hookByName(String name, String ifaceName) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder real = (IBinder) serviceManager.getDeclaredMethod("getService", String.class).invoke(null, name);
        if (real == null || real instanceof FakeBinder) return;

        Class<?> iface = Class.forName(ifaceName);
        Method asInterface = Class.forName(ifaceName + "$Stub").getDeclaredMethod("asInterface", IBinder.class);
        Object realInterface = asInterface.invoke(null, real);

        // asInterface asks the binder for a local interface first, ours answers with the proxy
        IBinder fake = new FakeBinder(real, (IInterface) hostPackageProxy(iface, realInterface), ifaceName);
        Map<String, IBinder> cache = (Map<String, IBinder>) Reflect.field(serviceManager, "sCache").get(null);
        cache.put(name, fake);
    }

    private static Object hostPackageProxy(Class<?> iface, Object real) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, (p, method, args) -> {
            // a system only question (gsf asks it), a clone has no parent profile
            if (method.getName().equals("getProfileParent")) return null;
            // watching a provider of another clone is refused by android (it opens it for the clone's uid),
            // nothing is lost, the other clone's provider lives in another process anyway
            if (method.getName().equals("registerContentObserver") && args[0] instanceof android.net.Uri
                    && Clones.ownerOfAuthority(App.host, ((android.net.Uri) args[0]).getAuthority()) != null) return null;
            Object fake = DeviceHook.fakeAnswer(method.getName());
            if (fake != null) return fake;
            if (args != null) {
                ActivityHook.useHostPackage(args);
                useHostComponent(args);
            }
            try {
                return DeviceHook.fixResult(method.getName(), method.invoke(real, args));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    // some calls (widgets) take a ComponentName and check its package too, keep the class and swap the package
    private static void useHostComponent(Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof ComponentName)) continue;
            ComponentName c = (ComponentName) args[i];
            if (Clones.isCloned(App.host, c.getPackageName())) {
                args[i] = new ComponentName(App.host.getPackageName(), c.getClassName());
            }
        }
    }

    // passes everything to the real binder except queryLocalInterface
    private static class FakeBinder implements IBinder {
        private final IBinder real;
        private final IInterface proxy;
        private final String descriptor;

        FakeBinder(IBinder real, IInterface proxy, String descriptor) {
            this.real = real;
            this.proxy = proxy;
            this.descriptor = descriptor;
        }

        @Override public IInterface queryLocalInterface(String d) { return descriptor.equals(d) ? proxy : null; }
        @Override public String getInterfaceDescriptor() throws RemoteException { return real.getInterfaceDescriptor(); }
        @Override public boolean pingBinder() { return real.pingBinder(); }
        @Override public boolean isBinderAlive() { return real.isBinderAlive(); }
        @Override public void dump(FileDescriptor fd, String[] args) throws RemoteException { real.dump(fd, args); }
        @Override public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException { real.dumpAsync(fd, args); }
        @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException { return real.transact(code, data, reply, flags); }
        @Override public void linkToDeath(DeathRecipient r, int flags) throws RemoteException { real.linkToDeath(r, flags); }
        @Override public boolean unlinkToDeath(DeathRecipient r, int flags) { return real.unlinkToDeath(r, flags); }
    }
}
