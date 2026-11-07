package dev.popclone;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// a stand in for android's account service, used inside clones. the accounts live in Accounts (our own
// file), so nothing is added to the phone. adding an account and getting tokens ask the authenticator of the
// cloned app that owns the account type (play services for com.google), the same way android does.
public class AccountHook {

    private static final String TAG = "popclone";
    private static final String RESPONSE = "android.accounts.IAccountAuthenticatorResponse";

    private static final Map<String, ComponentName> authenticators = new HashMap<>();
    private static boolean scanned;

    public static Object proxy(Class<?> iface) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, (p, method, args) -> {
            try {
                return handle(method, args == null ? new Object[0] : args);
            } catch (Throwable t) {
                Log.e(TAG, "account call " + method.getName() + " failed", t);
                return defaultFor(method.getReturnType());
            }
        });
    }

    private static Object handle(Method method, Object[] args) {
        Context ctx = App.host;
        String name = method.getName();
        Account account = first(args, Account.class);
        switch (name) {
            case "getAccounts":
            case "getAccountsAsUser":
            case "getAccountsByTypeForPackage":
                return Accounts.list(ctx, (String) args[0]);
            case "getAccountsForPackage":
                return Accounts.list(ctx, null);
            case "addAccountExplicitly":
            case "addAccountExplicitlyWithVisibility":
                return Accounts.add(ctx, account, (String) args[1], (Bundle) args[2]);
            case "removeAccountExplicitly":
                return Accounts.remove(ctx, account);
            case "getPassword":
                return Accounts.password(ctx, account);
            case "setPassword":
                Accounts.setPassword(ctx, account, (String) args[1]);
                return null;
            case "clearPassword":
                Accounts.setPassword(ctx, account, null);
                return null;
            case "getUserData":
                return Accounts.userData(ctx, account, (String) args[1]);
            case "setUserData":
                Accounts.setUserData(ctx, account, (String) args[1], (String) args[2]);
                return null;
            case "peekAuthToken":
                return Accounts.token(ctx, account, (String) args[1]);
            case "setAuthToken":
                Accounts.setToken(ctx, account, (String) args[1], (String) args[2]);
                return null;
            case "invalidateAuthToken":
                Accounts.invalidateToken(ctx, (String) args[0], (String) args[1]);
                return null;
            case "getAuthenticatorTypes":
                return descriptors(ctx);
            case "accountAuthenticated":
            case "hasAccountAccess":
            case "setAccountVisibility":
                return true;
            case "getAccountVisibility":
                return AccountManager.VISIBILITY_VISIBLE;
            case "getAccountsAndVisibilityForPackage":
            case "getPackagesAndVisibilityForAccount":
                return new HashMap<>();
            case "addAccount":
            case "addAccountAsUser":
                addAccount(args);
                return null;
            case "getAuthToken":
                getAuthToken(args);
                return null;
            case "removeAccount":
            case "removeAccountAsUser":
                removeAccount(args);
                return null;
            case "hasFeatures":
            case "hasFeaturesAsUser":
            case "isCredentialsUpdateSuggested":
                reply(args[0], bool(name.equals("isCredentialsUpdateSuggested") ? false : true));
                return null;
            case "getAccountsByFeatures":
                accountsByFeatures(args);
                return null;
            case "confirmCredentialsAsUser":
            case "updateCredentials":
            case "editProperties":
            case "getAuthTokenLabel":
                passThrough(name.equals("confirmCredentialsAsUser") ? "confirmCredentials" : name, args);
                return null;
            default:
                return defaultFor(method.getReturnType());
        }
    }

    // ---- the calls that answer later, through the caller's IAccountManagerResponse

    private static void addAccount(Object[] args) {
        Object response = args[0];
        String type = (String) args[1];
        String tokenType = (String) args[2];
        String[] features = (String[]) args[3];
        Bundle options = withCaller((Bundle) args[5]);
        run(response, type, (authenticator, auth) -> call(authenticator, "addAccount", auth, type, tokenType, features, options));
    }

    private static void getAuthToken(Object[] args) {
        Object response = args[0];
        Account account = (Account) args[1];
        String tokenType = (String) args[2];
        Bundle options = withCaller((Bundle) args[5]);

        // a token we already have is answered without asking the authenticator
        String cached = Accounts.token(App.host, account, tokenType);
        if (cached != null) {
            Bundle b = new Bundle();
            b.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
            b.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
            b.putString(AccountManager.KEY_AUTHTOKEN, cached);
            reply(response, b);
            return;
        }
        run(response, account.type, (authenticator, auth) -> {
            auth.onFinal = result -> {
                String token = result == null ? null : result.getString(AccountManager.KEY_AUTHTOKEN);
                if (token != null) Accounts.setToken(App.host, account, tokenType, token);
            };
            call(authenticator, "getAuthToken", auth, account, tokenType, options);
        });
    }

    private static void removeAccount(Object[] args) {
        Account account = (Account) args[1];
        boolean removed = Accounts.remove(App.host, account);
        reply(args[0], bool(removed));
    }

    private static void accountsByFeatures(Object[] args) {
        Bundle b = new Bundle();
        b.putParcelableArray(AccountManager.KEY_ACCOUNTS, Accounts.list(App.host, (String) args[1]));
        reply(args[0], b);
    }

    // confirmCredentials, updateCredentials, editProperties, getAuthTokenLabel: the authenticator does it all
    private static void passThrough(String method, Object[] args) {
        Object response = args[0];
        String type = args[1] instanceof Account ? ((Account) args[1]).type : (String) args[1];
        // the flags in the middle are not part of the authenticator's own calls
        run(response, type, (authenticator, auth) -> {
            switch (method) {
                case "confirmCredentials":
                    call(authenticator, method, auth, args[1], withCaller((Bundle) args[2]));
                    break;
                case "updateCredentials":
                    call(authenticator, method, auth, args[1], args[2], withCaller((Bundle) args[4]));
                    break;
                case "getAuthTokenLabel":
                    call(authenticator, method, auth, args[2]);
                    break;
                default:
                    call(authenticator, method, auth, args[1]);
            }
        });
    }

    private interface Task {
        void with(Object authenticator, AuthResponse auth) throws Exception;
    }

    private interface Finish {
        void done(Bundle result);
    }

    // binds the authenticator of this account type and runs the task, on a thread of its own because the
    // bind answers on the main thread
    private static void run(Object clientResponse, String type, Task task) {
        new Thread(() -> {
            AuthResponse auth = new AuthResponse(clientResponse);
            try {
                ComponentName component = authenticatorFor(type);
                if (component == null) throw new IllegalStateException("no authenticator for " + type);
                IBinder[] got = new IBinder[1];
                CountDownLatch connected = new CountDownLatch(1);
                ServiceConnection conn = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        got[0] = service;
                        connected.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                    }
                };
                auth.connection = conn;
                if (!App.host.bindService(new Intent("android.accounts.AccountAuthenticator").setComponent(component), conn, Context.BIND_AUTO_CREATE)
                        || !connected.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("cant reach the authenticator");
                }
                Class<?> stub = Class.forName("android.accounts.IAccountAuthenticator$Stub");
                Object authenticator = stub.getMethod("asInterface", IBinder.class).invoke(null, got[0]);
                task.with(authenticator, auth);
            } catch (Exception e) {
                Log.e(TAG, "account task failed", e);
                auth.onError(1, String.valueOf(e.getMessage()));
            }
        }, "popclone-account").start();
    }

    private static void call(Object authenticator, String name, Object response, Object... rest) throws Exception {
        Class<?> responseType = Class.forName(RESPONSE);
        Object[] all = new Object[rest.length + 1];
        all[0] = Proxy.newProxyInstance(responseType.getClassLoader(), new Class[]{responseType}, (p, m, a) -> {
            AuthResponse auth = (AuthResponse) response;
            switch (m.getName()) {
                case "asBinder":
                    return auth;
                case "onResult":
                    auth.onResult((Bundle) a[0]);
                    return null;
                case "onError":
                    auth.onError((Integer) a[0], (String) a[1]);
                    return null;
                default:
                    return null;
            }
        });
        System.arraycopy(rest, 0, all, 1, rest.length);
        for (Method m : authenticator.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == all.length) {
                try {
                    m.invoke(authenticator, all);
                } catch (InvocationTargetException e) {
                    throw new Exception(e.getCause());
                }
                return;
            }
        }
        throw new IllegalStateException("authenticator has no " + name);
    }

    // what the authenticator sends back, passed on to the app that asked. a result with an intent
    // is not final, the login screen will answer later through the same object.
    private static class AuthResponse extends Binder {
        final Object client;
        ServiceConnection connection;
        Finish onFinal;

        AuthResponse(Object client) {
            this.client = client;
        }

        void onResult(Bundle result) {
            boolean last = result == null || !result.containsKey(AccountManager.KEY_INTENT);
            if (last && onFinal != null) onFinal.done(result);
            reply(client, result == null ? new Bundle() : result);
            if (last && connection != null) {
                try {
                    App.host.unbindService(connection);
                } catch (RuntimeException ignored) {
                }
                connection = null;
            }
        }

        void onError(int code, String message) {
            try {
                Class.forName("android.accounts.IAccountManagerResponse").getMethod("onError", int.class, String.class).invoke(client, code, message);
            } catch (Exception e) {
                Log.w(TAG, "cant report account error: " + e);
            }
        }

        // the aidl of IAccountAuthenticatorResponse: 1 onResult, 2 onRequestContinued, 3 onError
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            switch (code) {
                case 1:
                    data.enforceInterface(RESPONSE);
                    onResult(data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null);
                    return true;
                case 2:
                    data.enforceInterface(RESPONSE);
                    return true;
                case 3:
                    data.enforceInterface(RESPONSE);
                    onError(data.readInt(), data.readString());
                    return true;
                case INTERFACE_TRANSACTION:
                    reply.writeString(RESPONSE);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    // ---- helpers

    private static void reply(Object response, Bundle result) {
        if (response == null) return;
        try {
            Class.forName("android.accounts.IAccountManagerResponse").getMethod("onResult", Bundle.class).invoke(response, result);
        } catch (Exception e) {
            Log.w(TAG, "cant answer an account call: " + e);
        }
    }

    private static Bundle bool(boolean value) {
        Bundle b = new Bundle();
        b.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, value);
        return b;
    }

    // the authenticator wants to know who is asking, android adds these
    private static Bundle withCaller(Bundle options) {
        Bundle b = options == null ? new Bundle() : new Bundle(options);
        Instances.Item me = Instances.find(App.host, Instances.currentSlot());
        b.putInt(AccountManager.KEY_CALLER_UID, Process.myUid());
        b.putInt(AccountManager.KEY_CALLER_PID, Process.myPid());
        if (me != null) b.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, me.pkg);
        return b;
    }

    @SuppressWarnings("unchecked")
    private static <T> T first(Object[] args, Class<T> type) {
        for (Object a : args) {
            if (type.isInstance(a)) return (T) a;
        }
        return null;
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    // ---- which cloned app answers for an account type: read from the authenticators android knows about

    private static synchronized ComponentName authenticatorFor(String type) {
        scan();
        return authenticators.get(type);
    }

    private static synchronized AuthenticatorDescription[] descriptors(Context ctx) {
        scan();
        AuthenticatorDescription[] out = new AuthenticatorDescription[authenticators.size()];
        int i = 0;
        for (Map.Entry<String, ComponentName> e : authenticators.entrySet()) {
            out[i++] = new AuthenticatorDescription(e.getKey(), e.getValue().getPackageName(), 0, 0, 0, 0, false);
        }
        return out;
    }

    private static void scan() {
        if (scanned) return;
        scanned = true;
        PackageManager pm = App.host.getPackageManager();
        List<ResolveInfo> found = pm.queryIntentServices(new Intent("android.accounts.AccountAuthenticator"), PackageManager.GET_META_DATA);
        for (ResolveInfo r : found) {
            ServiceInfo s = r.serviceInfo;
            if (!Instances.hasPackage(App.host, s.packageName) || s.metaData == null) continue;
            int res = s.metaData.getInt("android.accounts.AccountAuthenticator");
            if (res == 0) continue;
            try (XmlResourceParser xml = pm.getXml(s.packageName, res, s.applicationInfo)) {
                while (xml.next() != XmlResourceParser.END_DOCUMENT) {
                    if (xml.getEventType() == XmlResourceParser.START_TAG && xml.getName().equals("account-authenticator")) {
                        String type = xml.getAttributeValue("http://schemas.android.com/apk/res/android", "accountType");
                        if (type != null) authenticators.put(type, new ComponentName(s.packageName, s.name));
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "cant read authenticator of " + s.packageName + ": " + e);
            }
        }
        Log.i(TAG, "account types in clones: " + authenticators.keySet());
    }
}
