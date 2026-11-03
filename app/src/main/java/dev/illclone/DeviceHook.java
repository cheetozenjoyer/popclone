package dev.illclone;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;

// puts a clone's fake device ids into the answers of system services.
// everything here does nothing in the main app, only in a clone process (DeviceInfo.current is set).
public class DeviceHook {

    // an answer to give instead of asking the system, null means ask the system as usual
    public static Object fakeAnswer(String method) {
        DeviceInfo d = DeviceInfo.current;
        if (d == null) return null;
        switch (method) {
            case "getImeiForSlot":
            case "getDeviceId":
            case "getDeviceIdWithFeature":
            case "getDeviceIdForPhone":
            case "getImeiForSubscriber":
                return d.imei;
            case "getSubscriberId":
            case "getSubscriberIdForSubscriber":
                return d.imsi;
            case "getIccSerialNumber":
            case "getIccSerialNumberForSubscriber":
                return d.iccid;
            case "getSerial":
            case "getSerialForPackage":
                return d.serial;
            default:
                return null;
        }
    }

    // the system answered, change what the clone is allowed to see
    public static Object fixResult(String method, Object result) {
        DeviceInfo d = DeviceInfo.current;
        if (d == null || result == null || !method.equals("getConnectionInfo")) return result;
        try {
            Reflect.field(result.getClass(), "mMacAddress").set(result, d.wifiMac);
            Class<?> ssid = Class.forName("android.net.wifi.WifiSsid");
            Object fake = ssid.getMethod("createFromAsciiEncoded", String.class).invoke(null, d.wifiSsid);
            Reflect.field(result.getClass(), "mWifiSsid").set(result, fake);
        } catch (Exception e) {
            Log.w("illclone", "cant fake wifi info: " + e);
        }
        return result;
    }

    // settings is a content provider, android id is asked as call("GET_secure", "android_id").
    // returns the bundle to give back, null when the call is something else.
    public static Bundle fakeSetting(Object[] args) {
        DeviceInfo d = DeviceInfo.current;
        if (d == null) return null;
        boolean secure = false;
        boolean androidId = false;
        for (Object a : args) {
            if ("GET_secure".equals(a)) secure = true;
            if ("android_id".equals(a)) androidId = true;
        }
        if (!secure || !androidId) return null;
        Bundle b = new Bundle();
        b.putString("value", d.androidId);
        return b;
    }

    // the old Build.SERIAL field
    public static void applyBuild() {
        DeviceInfo d = DeviceInfo.current;
        if (d == null) return;
        try {
            Reflect.field(Build.class, "SERIAL").set(null, d.serial);
        } catch (Exception e) {
            Log.w("illclone", "cant set Build.SERIAL: " + e);
        }
    }
}
