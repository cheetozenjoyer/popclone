package dev.popclone;

import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private ListView list;
    private List<Instances.Item> clones = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // follow the system theme by default
        setTheme(isDark() ? R.style.Theme_Popclone_Dark : R.style.Theme_Popclone);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Switch dark = findViewById(R.id.dark);
        dark.setChecked(isDark());
        dark.setOnCheckedChangeListener((button, on) -> {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("dark", on).apply();
            recreate();
        });

        list = findViewById(R.id.clones);
        list.setEmptyView(findViewById(R.id.empty));

        // click -> launch 
        list.setOnItemClickListener((parent, view, position, id) -> launch(clones.get(position)));
        
        // hold -> delete. there must be better design for this
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(clones.get(position));
            return true;
        });
        findViewById(R.id.add).setOnClickListener(v -> pickApp());
    }

    private boolean isDark() {
        int system = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return getSharedPreferences("ui", MODE_PRIVATE).getBoolean("dark", system == Configuration.UI_MODE_NIGHT_YES);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        clones = Instances.all(this);
        List<String> names = new ArrayList<>();
        for (Instances.Item item : clones) names.add(item.name);
        // keep the scroll position, setting a new adapter would jump to the top
        int first = list.getFirstVisiblePosition();
        View row = list.getChildAt(0);
        int offset = row == null ? 0 : row.getTop();
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        list.setSelectionFromTop(first, offset);
    }

    // the hook sees the component belongs to a clone and sends it to that clone's stub
    private void launch(Instances.Item item) {
        Intent intent = new Intent().setComponent(new ComponentName(item.pkg, item.launcher))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ActivityHook.EXTRA_SLOT, item.slot);
        startActivity(intent);
    }

    // list the installed apps that have a launcher icon
    private void pickApp() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        found.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));

        List<String> labels = new ArrayList<>();
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo.packageName.equals(getPackageName())) continue;
            labels.add(info.loadLabel(pm).toString());
            apps.add(info);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("clone which app?")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> create(apps.get(which), labels.get(which)))
                .show();
    }

    // copying can be slow so do it off the main thread
    private void create(ResolveInfo app, String label) {
        Toast.makeText(this, "cloning " + label, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String pkg = app.activityInfo.packageName;
            try {
                Installer.install(this, pkg);
                Instances.Item item = Instances.create(this, pkg, nextName(pkg, label), app.activityInfo.name);
                if (item != null) DeviceInfo.generate().save(this, item.slot);
                runOnUiThread(() -> {
                    refresh();
                    if (item == null) toast("no free slot");
                    else launch(item);
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("failed: " + e.getMessage()));
            }
        }).start();
    }

    // "Label 1", "Label 2", the first number not taken
    private String nextName(String pkg, String label) {
        for (int n = 1; ; n++) {
            String name = label + " " + n;
            boolean taken = false;
            for (Instances.Item item : Instances.all(this)) {
                if (item.pkg.equals(pkg) && item.name.equals(name)) taken = true;
            }
            if (!taken) return name;
        }
    }

    private void confirmDelete(Instances.Item item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("delete " + item.name + "?")
                .setPositiveButton("yes", (dialog, which) -> {
                    Instances.destroy(this, item.slot);
                    refresh();
                })
                .setNegativeButton("no", null)
                .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
