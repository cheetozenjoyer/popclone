package dev.popclone;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.Properties;

// the fake device ids of one clone. made when the clone is created, saved in its folder.
// inside a clone process `current` holds them, in the main app it is null.
public class DeviceInfo {

    public static DeviceInfo current;

    public String androidId;
    public String serial;
    public String imei;
    public String imsi;
    public String iccid;
    public String wifiMac;
    public String wifiSsid;

    private static final SecureRandom random = new SecureRandom();

    public static DeviceInfo generate() {
        DeviceInfo d = new DeviceInfo();
        d.androidId = hex(16);
        d.serial = serial();
        d.imei = imei();
        d.imsi = "310260" + digits(9);
        d.iccid = "898600" + digits(14);
        d.wifiMac = mac();
        d.wifiSsid = hex(6 + random.nextInt(4));
        return d;
    }

    private static File file(Context ctx, int slot) {
        return new File(Instances.dir(ctx, slot), "device.properties");
    }

    public static DeviceInfo load(Context ctx, int slot) {
        File file = file(ctx, slot);
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            DeviceInfo d = new DeviceInfo();
            d.androidId = p.getProperty("androidId");
            d.serial = p.getProperty("serial");
            d.imei = p.getProperty("imei");
            d.imsi = p.getProperty("imsi");
            d.iccid = p.getProperty("iccid");
            d.wifiMac = p.getProperty("wifiMac");
            d.wifiSsid = p.getProperty("wifiSsid");
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    public void save(Context ctx, int slot) throws Exception {
        Instances.dir(ctx, slot).mkdirs();
        Properties p = new Properties();
        p.setProperty("androidId", androidId);
        p.setProperty("serial", serial);
        p.setProperty("imei", imei);
        p.setProperty("imsi", imsi);
        p.setProperty("iccid", iccid);
        p.setProperty("wifiMac", wifiMac);
        p.setProperty("wifiSsid", wifiSsid);
        try (FileOutputStream out = new FileOutputStream(file(ctx, slot))) {
            p.store(out, null);
        }
    }

    private static String hex(int length) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < length; i++) s.append(Integer.toHexString(random.nextInt(16)));
        return s.toString();
    }

    private static String digits(int length) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < length; i++) s.append(random.nextInt(10));
        return s.toString();
    }

    // one letter then 12 letters and digits, like a samsung serial
    private static String serial() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";
        StringBuilder s = new StringBuilder();
        s.append(chars.charAt(random.nextInt(24)));
        for (int i = 0; i < 12; i++) s.append(chars.charAt(random.nextInt(chars.length())));
        return s.toString();
    }

    // 14 digits plus the luhn check digit, so it looks like a real imei
    private static String imei() {
        String body = "35" + digits(12);
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int n = body.charAt(body.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
        }
        return body + ((10 - sum % 10) % 10);
    }

    // locally administered mac, the first byte ends in 2 so it never clashes with a real vendor
    private static String mac() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        b[0] = (byte) ((b[0] & 0xfc) | 0x02);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) s.append(':');
            s.append(String.format("%02x", b[i]));
        }
        return s.toString();
    }
}
