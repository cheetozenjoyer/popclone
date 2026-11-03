package dev.illclone;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// copies an installed app into our private dir so we can load it later
// layout: files/apps/<package>/base.apk, split_*.apk (same names as the original) and lib/*.so
// the copy is shared by every clone of the app, each clone only has its own data folder
public class Installer {

    public static File dir(Context ctx, String pkg) {
        return new File(ctx.getFilesDir(), "apps/" + pkg);
    }

    public static void install(Context ctx, String pkg) throws Exception {
        File dir = dir(ctx, pkg);
        if (new File(dir, "base.apk").exists()) return; // already copied for an earlier clone

        ApplicationInfo app = ctx.getPackageManager().getApplicationInfo(pkg, 0);
        File lib = new File(dir, "lib");
        lib.mkdirs();

        // start clean so old splits dont linger
        File[] old = dir.listFiles((d, name) -> name.endsWith(".apk"));
        if (old != null) for (File f : old) f.delete();

        copyApk(app.sourceDir, new File(dir, "base.apk"), lib);
        if (app.splitSourceDirs != null) {
            for (String split : app.splitSourceDirs) {
                copyApk(split, new File(dir, new File(split).getName()), lib);
            }
        }
    }

    // the apk must be read only or android refuses to load code from it
    private static void copyApk(String from, File to, File libDir) throws IOException {
        to.delete();
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            copy(in, out);
        }
        to.setReadOnly();
        extractLibs(from, libDir);
    }

    // pull .so files for the first abi this phone supports (native libs often live in a split)
    private static void extractLibs(String apk, File libDir) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            for (String abi : Build.SUPPORTED_ABIS) {
                boolean found = false;
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry e = all.nextElement();
                    if (!e.getName().startsWith("lib/" + abi + "/") || !e.getName().endsWith(".so")) continue;
                    found = true;
                    File out = new File(libDir, new File(e.getName()).getName());
                    try (InputStream in = zip.getInputStream(e); FileOutputStream os = new FileOutputStream(out)) {
                        copy(in, os);
                    }
                }
                if (found) return;
            }
        }
    }

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
