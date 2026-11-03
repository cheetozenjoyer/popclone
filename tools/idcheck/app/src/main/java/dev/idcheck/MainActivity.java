package dev.idcheck;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.telephony.TelephonyManager;
import android.widget.ScrollView;
import android.widget.TextView;

// prints every id an app can usually read, one per line
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView text = new TextView(this);
        text.setTextSize(16);
        text.setPadding(40, 80, 40, 40);
        text.setTextIsSelectable(true);

        StringBuilder out = new StringBuilder();
        out.append("package: ").append(getPackageName()).append('\n');
        out.append("android id: ").append(Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)).append('\n');
        out.append("model: ").append(Build.MODEL).append('\n');
        out.append("serial field: ").append(Build.SERIAL).append('\n');
        out.append("serial: ").append(read(() -> Build.getSerial())).append('\n');

        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        out.append("imei: ").append(read(() -> tm.getImei())).append('\n');
        out.append("device id: ").append(read(() -> tm.getDeviceId())).append('\n');
        out.append("imsi: ").append(read(() -> tm.getSubscriberId())).append('\n');
        out.append("iccid: ").append(read(() -> tm.getSimSerialNumber())).append('\n');

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifi = wm.getConnectionInfo();
        out.append("wifi mac: ").append(read(() -> wifi.getMacAddress())).append('\n');
        out.append("wifi ssid: ").append(read(() -> wifi.getSSID())).append('\n');

        text.setText(out);
        Log.i("idcheck", out.toString().replace('\n', '|'));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }

    private interface Reader {
        String get() throws Throwable;
    }

    // a failed read prints the error name instead of crashing
    private static String read(Reader reader) {
        try {
            return String.valueOf(reader.get());
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }
}
