package dev.popclone;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// knows which apps are cloned and gives out their package info
public class Clones {

    private static final Map<String, PackageInfo> cache = new HashMap<>();

    public static boolean isCloned(Context ctx, String pkg) {
        return new File(Installer.dir(ctx, pkg), "base.apk").exists();
    }

    // the clone's activity info, with paths pointing at our private copy
    public static ActivityInfo activityInfo(Context ctx, ComponentName name) {
        PackageInfo info = packageInfo(ctx, name.getPackageName());
        if (info == null || info.activities == null) return null;
        for (ActivityInfo a : info.activities) {
            if (a.name.equals(name.getClassName())) return a;
        }
        return null;
    }

    public static ApplicationInfo appInfo(Context ctx, String pkg) {
        PackageInfo info = packageInfo(ctx, pkg);
        return info == null ? null : info.applicationInfo;
    }

    // authority -> package and the permissions declared by the cloned packages. read from the apk files
    // without touching packageInfo's cache, rescanned when the clone list may have changed.
    private static final Map<String, String> authorityOwners = new HashMap<>();
    private static final java.util.Set<String> declared = new java.util.HashSet<>();
    private static long scannedAt;

    private static synchronized void scan(Context ctx) {
        if (System.currentTimeMillis() - scannedAt < 2000) return;
        scannedAt = System.currentTimeMillis();
        for (Instances.Item item : Instances.all(ctx)) {
            if (authorityOwners.containsValue(item.pkg)) continue;
            String apk = new File(Installer.dir(ctx, item.pkg), "base.apk").getPath();
            PackageInfo info = ctx.getPackageManager().getPackageArchiveInfo(apk, PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS);
            if (info == null) continue;
            if (info.providers != null) {
                for (ProviderInfo p : info.providers) {
                    if (p.authority == null) continue;
                    for (String a : p.authority.split(";")) authorityOwners.put(a, item.pkg);
                }
            }
            if (info.permissions != null) {
                for (android.content.pm.PermissionInfo perm : info.permissions) declared.add(perm.name);
            }
        }
    }

    public static synchronized String ownerOfAuthority(Context ctx, String authority) {
        scan(ctx);
        return authorityOwners.get(authority);
    }

    // every authority of a cloned package, joined with ;
    public static synchronized String authoritiesOf(Context ctx, String pkg) {
        scan(ctx);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : authorityOwners.entrySet()) {
            if (!e.getValue().equals(pkg)) continue;
            if (out.length() > 0) out.append(';');
            out.append(e.getKey());
        }
        return out.toString();
    }

    public static synchronized boolean declaresPermission(Context ctx, String permission) {
        scan(ctx);
        return declared.contains(permission);
    }

    public static ServiceInfo serviceInfo(Context ctx, ComponentName name) {
        PackageInfo info = packageInfo(ctx, name.getPackageName());
        if (info == null || info.services == null) return null;
        for (ServiceInfo s : info.services) {
            if (s.name.equals(name.getClassName())) return s;
        }
        return null;
    }

    // fields that are hidden in the public sdk
    private static void setField(ApplicationInfo app, String name, Object value) {
        try {
            Reflect.field(ApplicationInfo.class, name).set(app, value);
        } catch (Exception e) {
            Log.e("popclone", "cant set " + name, e);
        }
    }

    public static List<ProviderInfo> providers(Context ctx, String pkg) {
        PackageInfo info = packageInfo(ctx, pkg);
        if (info == null || info.providers == null) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(info.providers));
    }

    // only providers meant for the main process, other processes are not supported yet
    private static ProviderInfo[] ownProviders(PackageInfo info, ApplicationInfo app, String mainProcess) {
        List<ProviderInfo> mine = new ArrayList<>();
        if (info.providers != null) {
            for (ProviderInfo p : info.providers) {
                // some roms leave the process name empty, that means the package name
                String process = p.processName != null ? p.processName : p.packageName;
                if (mainProcess != null && !mainProcess.equals(process)) continue;
                p.applicationInfo = app;
                p.processName = app.processName;
                mine.add(p);
            }
        }
        return mine.toArray(new ProviderInfo[0]);
    }

    // if the original app is still installed, load code from where android installed it.
    // android already compiled it there, our copy would run slow until it warms up.
    // when the original is gone (or updated away) we keep using our own copy.
    private static void useInstalledApk(Context ctx, String pkg, ApplicationInfo app) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo original = pm.getApplicationInfo(pkg, 0);
            if (!new File(original.sourceDir).canRead()) return;

            app.sourceDir = original.sourceDir;
            app.publicSourceDir = original.publicSourceDir;
            app.splitSourceDirs = original.splitSourceDirs;
            app.splitPublicSourceDirs = original.splitPublicSourceDirs;
            // system libraries the app asked for (org.apache.http.legacy)
            app.sharedLibraryFiles = original.sharedLibraryFiles;
            setField(app, "splitNames", pm.getPackageInfo(pkg, 0).splitNames);
            // feature splits (play services) need to know which split depends on which
            for (String field : new String[]{"splitDependencies", "splitClassLoaderNames", "splitFlags"}) {
                setField(app, field, Reflect.field(ApplicationInfo.class, field).get(original));
            }
            Log.i("popclone", "using installed apk for " + pkg);
        } catch (Exception e) {
            // not installed, keep our copy
        }
    }

    // android wants the split names too: split_config.en.apk -> config.en
    private static void setSplitNames(ApplicationInfo app) {
        if (app.splitSourceDirs == null) return;
        String[] names = new String[app.splitSourceDirs.length];
        for (int i = 0; i < names.length; i++) {
            String file = new File(app.splitSourceDirs[i]).getName();
            names[i] = file.substring("split_".length(), file.length() - ".apk".length());
        }
        setField(app, "splitNames", names);
    }

    // every split_*.apk the installer copied, null when there are none
    private static String[] splits(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("split_"));
        if (files == null || files.length == 0) return null;
        String[] paths = new String[files.length];
        for (int i = 0; i < files.length; i++) paths[i] = files[i].getPath();
        java.util.Arrays.sort(paths);
        return paths;
    }

    private static synchronized PackageInfo packageInfo(Context ctx, String pkg) {
        PackageInfo info = cache.get(pkg);
        if (info != null) return info;

        File dir = Installer.dir(ctx, pkg);
        String apk = new File(dir, "base.apk").getPath();
        info = ctx.getPackageManager().getPackageArchiveInfo(apk, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS);
        if (info == null) return null;

        // each clone has its own data. the main app has no slot and never runs a clone.
        int slot = Instances.currentSlot();
        File home = slot >= 0 ? Instances.dir(ctx, slot) : dir;

        // android builds the classloader and Application from these paths
        File data = new File(home, "data");
        data.mkdirs();
        ApplicationInfo app = info.applicationInfo;
        String originalProcess = app.processName != null ? app.processName : app.packageName;
        app.sourceDir = apk;
        app.publicSourceDir = apk;
        File libs = Installer.libDir(dir);
        app.nativeLibraryDir = (libs != null ? libs : new File(dir, "lib")).getPath();
        // android 15 gives flags 0 for an apk file. without HAS_CODE the app gets an empty classloader.
        app.flags |= ApplicationInfo.FLAG_HAS_CODE;
        app.splitSourceDirs = splits(dir);
        app.splitPublicSourceDirs = app.splitSourceDirs;
        setSplitNames(app);
        useInstalledApk(ctx, pkg, app);
        app.dataDir = data.getPath();
        // credential storage is the normal one. device protected must be a different folder
        // or android thinks the app is running in direct boot mode
        File deviceData = new File(home, "data_de");
        deviceData.mkdirs();
        app.deviceProtectedDataDir = deviceData.getPath();
        setField(app, "credentialProtectedDataDir", data.getPath());
        app.uid = Process.myUid();
        app.processName = Application.getProcessName();

        if (info.activities != null) {
            for (ActivityInfo a : info.activities) {
                a.applicationInfo = app;
                a.processName = app.processName;
            }
        }
        if (info.services != null) {
            for (ServiceInfo sv : info.services) {
                sv.applicationInfo = app;
                sv.processName = app.processName;
            }
        }
        Instances.Item me = Instances.find(ctx, slot);
        // a helper (play services) has providers for many processes, they all live in this one
        boolean helper = me != null && me.launcher.isEmpty();
        info.providers = ownProviders(info, app, helper ? null : originalProcess);
        cache.put(pkg, info);
        return info;
    }
}
