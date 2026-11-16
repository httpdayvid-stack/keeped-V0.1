package com.keeped.app;

import android.app.AppOpsManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Blocker")
public class BlockerPlugin extends Plugin {

    private boolean hasUsage() {
        AppOpsManager a = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        int m = a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getContext().getPackageName());
        return m == AppOpsManager.MODE_ALLOWED;
    }

    private void open(Intent i, PluginCall c) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        c.resolve();
    }

    @PluginMethod
    public void status(PluginCall c) {
        JSObject o = new JSObject();
        o.put("usage", hasUsage());
        o.put("overlay", Settings.canDrawOverlays(getContext()));
        c.resolve(o);
    }

    @PluginMethod
    public void openUsageSettings(PluginCall c) {
        open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), c);
    }

    @PluginMethod
    public void openOverlaySettings(PluginCall c) {
        open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName())), c);
    }

    @PluginMethod
    public void openBatterySettings(PluginCall c) {
        open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), c);
    }

    @PluginMethod
    public void setState(PluginCall c) {
        Context ctx = getContext();
        ctx.getSharedPreferences("keeped", Context.MODE_PRIVATE).edit().putString("state", c.getData().toString()).apply();
        Intent s = new Intent(ctx, BlockerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s); else ctx.startService(s);
        } catch (Exception e) { }
        c.resolve();
    }

    @PluginMethod
    public void getDone(PluginCall c) {
        SharedPreferences p = getContext().getSharedPreferences("keeped", Context.MODE_PRIVATE);
        String d = p.getString("done", "[]");
        p.edit().remove("done").apply();
        JSObject o = new JSObject();
        try { o.put("done", new JSArray(d)); } catch (Exception e) { o.put("done", new JSArray()); }
        c.resolve(o);
    }

    private String icon(Drawable d) {
        Bitmap b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, 48, 48);
        d.draw(cv);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 90, bo);
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    @PluginMethod
    public void listApps(final PluginCall c) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Context ctx = getContext();
                    PackageManager pm = ctx.getPackageManager();
                    Intent i = new Intent(Intent.ACTION_MAIN, null);
                    i.addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
                    Set<String> seen = new HashSet<String>();
                    JSArray arr = new JSArray();
                    for (ResolveInfo r : l) {
                        String pkg = r.activityInfo.packageName;
                        if (pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) continue;
                        JSObject o = new JSObject();
                        o.put("p", pkg);
                        o.put("n", r.loadLabel(pm).toString());
                        try { o.put("ic", icon(r.loadIcon(pm))); } catch (Throwable e) { }
                        arr.put(o);
                    }
                    JSObject res = new JSObject();
                    res.put("apps", arr);
                    c.resolve(res);
                } catch (Throwable e) {
                    c.reject("Falha ao listar apps");
                }
            }
        }).start();
    }
}
