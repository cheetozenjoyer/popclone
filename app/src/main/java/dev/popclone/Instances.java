package dev.popclone;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Process;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
                try {
                    // a bad line is skipped, it must not make us drop the good ones on the next save
                    if (p.length == 4) items.add(new Item(Integer.parseInt(p[0]), p[1], p[2], p[3]));
                } catch (NumberFormatException ignored) {
                }
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
            // the label comes from another app, keep tabs and newlines out of our file format
            Item item = new Item(slot, pkg, name.replaceAll("[\\t\\r\\n]", " "), launcher);
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

    private static void killProcess(Context ctx, int slot) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
            if (slotOf(ctx, p.processName) == slot) Process.killProcess(p.pid);
        }
    }

    // "dev.popclone:p3" -> 3, anything else -> -1
    private static int slotOf(Context ctx, String processName) {
        String prefix = ctx.getPackageName() + ":p";
        if (!processName.startsWith(prefix)) return -1;
        try {
            return Integer.parseInt(processName.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // a clone could leave a symlink in its folder, remove the link and never walk through it
    private static void deleteAll(File file) {
        File[] children = Files.isSymbolicLink(file.toPath()) ? null : file.listFiles();
        if (children != null) {
            for (File child : children) deleteAll(child);
        }
        file.delete();
    }

    private static void save(Context ctx, List<Item> items) {
        List<String> lines = new ArrayList<>();
        for (Item i : items) lines.add(i.slot + "\t" + i.pkg + "\t" + i.name + "\t" + i.launcher);
        // write a temp file and rename it, a crash halfway must not wipe the list
        try {
            File tmp = new File(file(ctx).getPath() + ".tmp");
            Files.write(tmp.toPath(), lines);
            Files.move(tmp.toPath(), file(ctx).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    // which slot this process is, -1 in the main app. stub processes are named dev.popclone:p<slot>
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
