package dev.popclone;

import android.accounts.Account;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// the accounts of the clones, kept inside popclone so nothing is added to the phone's own account list.
// one file shared by every clone, read and written whole on every call (it is small).
// {"accounts":[{"name":"","type":"","password":"","data":{},"tokens":{}}]}
public class Accounts {

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "accounts.json");
    }

    private static synchronized JSONArray load(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.exists()) return new JSONArray();
            return new JSONObject(new String(Files.readAllBytes(f.toPath()))).getJSONArray("accounts");
        } catch (Exception e) {
            Log.w("popclone", "cant read accounts: " + e);
            return new JSONArray();
        }
    }

    // temp file and rename, a crash halfway must not lose the accounts
    private static synchronized void save(Context ctx, JSONArray all) {
        try {
            File tmp = new File(file(ctx).getPath() + ".tmp");
            Files.write(tmp.toPath(), new JSONObject().put("accounts", all).toString().getBytes());
            Files.move(tmp.toPath(), file(ctx).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.e("popclone", "cant save accounts", e);
        }
    }

    private static JSONObject find(JSONArray all, Account a) {
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optString("name").equals(a.name) && o.optString("type").equals(a.type)) return o;
        }
        return null;
    }

    private static void changed(Context ctx) {
        ctx.sendBroadcast(new Intent("android.accounts.LOGIN_ACCOUNTS_CHANGED").setPackage(ctx.getPackageName()));
    }

    // type null means every type
    public static synchronized Account[] list(Context ctx, String type) {
        JSONArray all = load(ctx);
        List<Account> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && (type == null || type.equals(o.optString("type")))) out.add(new Account(o.optString("name"), o.optString("type")));
        }
        return out.toArray(new Account[0]);
    }

    public static synchronized boolean add(Context ctx, Account a, String password, Bundle userData) {
        JSONArray all = load(ctx);
        if (find(all, a) != null) return false;
        try {
            JSONObject data = new JSONObject();
            if (userData != null) {
                for (String key : userData.keySet()) data.put(key, String.valueOf(userData.get(key)));
            }
            all.put(new JSONObject().put("name", a.name).put("type", a.type).put("password", password == null ? "" : password)
                    .put("data", data).put("tokens", new JSONObject()));
        } catch (Exception e) {
            return false;
        }
        save(ctx, all);
        changed(ctx);
        return true;
    }

    public static synchronized boolean remove(Context ctx, Account a) {
        JSONArray all = load(ctx);
        JSONArray left = new JSONArray();
        boolean found = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optString("name").equals(a.name) && o.optString("type").equals(a.type)) found = true;
            else left.put(o);
        }
        if (found) {
            save(ctx, left);
            changed(ctx);
        }
        return found;
    }

    public static synchronized String password(Context ctx, Account a) {
        JSONObject o = find(load(ctx), a);
        if (o == null) return null;
        String p = o.optString("password");
        return p.isEmpty() ? null : p;
    }

    public static synchronized void setPassword(Context ctx, Account a, String password) {
        JSONArray all = load(ctx);
        JSONObject o = find(all, a);
        if (o == null) return;
        try {
            o.put("password", password == null ? "" : password);
        } catch (Exception ignored) {
        }
        save(ctx, all);
    }

    public static synchronized String userData(Context ctx, Account a, String key) {
        JSONObject o = find(load(ctx), a);
        return o == null ? null : o.optJSONObject("data").optString(key, null);
    }

    public static synchronized void setUserData(Context ctx, Account a, String key, String value) {
        JSONArray all = load(ctx);
        JSONObject o = find(all, a);
        if (o == null) return;
        try {
            if (value == null) o.getJSONObject("data").remove(key);
            else o.getJSONObject("data").put(key, value);
        } catch (Exception ignored) {
        }
        save(ctx, all);
    }

    public static synchronized String token(Context ctx, Account a, String tokenType) {
        JSONObject o = find(load(ctx), a);
        return o == null ? null : o.optJSONObject("tokens").optString(tokenType, null);
    }

    public static synchronized void setToken(Context ctx, Account a, String tokenType, String token) {
        JSONArray all = load(ctx);
        JSONObject o = find(all, a);
        if (o == null) return;
        try {
            if (token == null) o.getJSONObject("tokens").remove(tokenType);
            else o.getJSONObject("tokens").put(tokenType, token);
        } catch (Exception ignored) {
        }
        save(ctx, all);
    }

    // a token that stopped working, forget it wherever it is stored
    public static synchronized void invalidateToken(Context ctx, String type, String token) {
        JSONArray all = load(ctx);
        boolean changed = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null || !o.optString("type").equals(type)) continue;
            JSONObject tokens = o.optJSONObject("tokens");
            for (Iterator<String> it = tokens.keys(); it.hasNext(); ) {
                String key = it.next();
                if (token.equals(tokens.optString(key))) {
                    it.remove();
                    changed = true;
                }
            }
        }
        if (changed) save(ctx, all);
    }
}
