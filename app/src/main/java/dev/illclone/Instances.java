package dev.illclone;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.os.Process;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// the list of clones. one clone = one slot = one stub process (:p<slot>) = one data folder.
// stored in files/instances.txt, one line per clone: slot, package, name, launcher activity (tab separated)
public class Instances {

    // must match COUNT in tools/gen_stubs.py
    public static final int MAX = 120;

    public static class Item {
        public final int slot;
        public final String pkg;
        public final String name;
        public final String launcher;

        Item(int slot, String pkg, String name, String launcher) {
            this.slot = slot;
            this.pkg = pkg;
            this.name = name;
            this.launcher = launcher;
        }
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "instances.txt");
    }

    // where a clone keeps its own data
    public static File dir(Context ctx, int slot) {
        return new File(ctx.getFilesDir(), "instances/" + slot);
    }

    public static synchronized List<Item> all(Context ctx) {
        List<Item> items = new ArrayList<>();
        try {
            if (!file(ctx).exists()) return items;
            for (String line : Files.readAllLines(file(ctx).toPath())) {
                String[] p = line.split("\t", -1);
                if (p.length == 4) items.add(new Item(Integer.parseInt(p[0]), p[1], p[2], p[3]));
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    public static Item find(Context ctx, int slot) {
        for (Item item : all(ctx)) {
            if (item.slot == slot) return item;
        }
        return null;
    }

    // gives the clone the first free slot, null when all are used
    public static synchronized Item create(Context ctx, String pkg, String name, String launcher) {
        List<Item> items = all(ctx);
        for (int slot = 0; slot < MAX; slot++) {
            if (find(ctx, slot) != null) continue;
            Item item = new Item(slot, pkg, name, launcher);
            items.add(item);
            save(ctx, items);
            return item;
        }
        return null;
    }

    public static synchronized void remove(Context ctx, int slot) {
        List<Item> items = all(ctx);
        items.removeIf(item -> item.slot == slot);
        save(ctx, items);
    }

    // stops the clone's process, wipes its data and forgets it
    public static void destroy(Context ctx, int slot) {
        killProcess(ctx, slot);
        deleteAll(dir(ctx, slot));
        remove(ctx, slot);
    }

    // only one clone runs at a time. closes every other clone and waits until it is really gone,
    // so the new one never meets a leftover process (webview locks its folder, for one).
    // call this off the main thread.
    public static void stopOthers(Context ctx, int keepSlot) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        String stubPrefix = ctx.getPackageName() + ".Stubs$A";
        for (ActivityManager.AppTask task : am.getAppTasks()) {
            ComponentName root = task.getTaskInfo().baseIntent.getComponent();
            if (root != null && root.getClassName().startsWith(stubPrefix)
                    && !root.getClassName().equals(stubPrefix + keepSlot)) {
                task.finishAndRemoveTask();
            }
        }

        for (int slot = 0; slot < MAX; slot++) {
            if (slot != keepSlot) killProcess(ctx, slot);
        }

        // wait up to 3 seconds for them to disappear
        for (int i = 0; i < 30 && othersRunning(ctx, keepSlot); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static boolean othersRunning(Context ctx, int keepSlot) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
            int slot = slotOf(ctx, p.processName);
            if (slot >= 0 && slot != keepSlot) return true;
        }
        return false;
    }

    private static void killProcess(Context ctx, int slot) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
            if (slotOf(ctx, p.processName) == slot) Process.killProcess(p.pid);
        }
    }

    // "dev.illclone:p3" -> 3, anything else -> -1
    private static int slotOf(Context ctx, String processName) {
        String prefix = ctx.getPackageName() + ":p";
        if (!processName.startsWith(prefix)) return -1;
        try {
            return Integer.parseInt(processName.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void deleteAll(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteAll(child);
        }
        file.delete();
    }

    private static void save(Context ctx, List<Item> items) {
        List<String> lines = new ArrayList<>();
        for (Item i : items) lines.add(i.slot + "\t" + i.pkg + "\t" + i.name + "\t" + i.launcher);
        try {
            Files.write(file(ctx).toPath(), lines);
        } catch (Exception ignored) {
        }
    }

    // which slot this process is, -1 in the main app. stub processes are named dev.illclone:p<slot>
    public static int currentSlot() {
        String name = Application.getProcessName();
        int at = name.lastIndexOf(":p");
        if (at < 0) return -1;
        try {
            return Integer.parseInt(name.substring(at + 2));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
