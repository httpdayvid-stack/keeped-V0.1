package com.keeped.app;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;

public class BlockerService extends Service {
    Handler h = new Handler(Looper.getMainLooper());
    View ov;
    String cur = "", prev = "";
    CountDownTimer ct;

    Runnable tick = new Runnable() {
        public void run() {
            try { check(); } catch (Throwable e) { }
            h.postDelayed(this, 1000);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        String ch = "keeped";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(ch, "Keeped", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, ch);
        } else b = new Notification.Builder(this);
        b.setContentTitle("Keeped ativo").setContentText("Vigiando os apps escolhidos").setSmallIcon(android.R.drawable.ic_lock_idle_lock);
        startForeground(7, b.build());
        h.post(tick);
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        hide();
        super.onDestroy();
    }

    void check() throws Exception {
        UsageStatsManager u = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long n = System.currentTimeMillis();
        UsageEvents e = u.queryEvents(n - 3000, n);
        UsageEvents.Event ev = new UsageEvents.Event();
        while (e.hasNextEvent()) {
            e.getNextEvent(ev);
            if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) cur = ev.getPackageName();
        }
        if (cur.equals(prev)) return;
        prev = cur;
        hide();
        if (cur.equals(getPackageName())) return;
        SharedPreferences p = getSharedPreferences("keeped", MODE_PRIVATE);
        JSONObject s = new JSONObject(p.getString("state", "{}"));
        JSONObject pk = s.optJSONObject("pkgs");
        if (pk == null || !pk.has(cur)) return;
        JSONArray it = s.optJSONArray("items");
        if (it == null || it.length() == 0) return;
        if (!Settings.canDrawOverlays(this)) return;
        show(pk.getString(cur), s, it, p);
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    TextView tv(String t, int sp, int color, boolean bold) {
        TextView x = new TextView(this);
        x.setText(t); x.setTextSize(sp); x.setTextColor(color);
        x.setGravity(Gravity.CENTER);
        if (bold) x.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        x.setLayoutParams(lp);
        return x;
    }

    Button btn(String t, int bg) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(16);
        b.setBackgroundColor(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    void show(final String app, JSONObject s, final JSONArray it, final SharedPreferences p) throws Exception {
        String day = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        if (!day.equals(p.getString("day", ""))) p.edit().putString("day", day).putInt("tries", 0).apply();
        int tries = p.getInt("tries", 0);
        int wait = Math.min(s.optInt("max", 60), s.optInt("wait", 10) + s.optInt("inc", 10) * tries);
        p.edit().putInt("tries", tries + 1).apply();
        int ac = Color.parseColor(s.optString("ac", "#9d6abf"));
        String msg, verb;
        if (it.length() == 1) {
            JSONObject o = it.getJSONObject(0);
            String m = o.optString("m", "");
            msg = !m.isEmpty() ? m : ("meds".equals(o.optString("k")) ? "Você ainda não tomou seu remédio." : "Você ainda não concluiu: " + o.optString("t") + ".");
            verb = o.optString("verb", "Já fiz");
        } else { msg = "Você ainda tem tarefas pendentes."; verb = "Já fiz tudo"; }

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(28), dp(28), dp(28), dp(28));
        l.setBackgroundColor(Color.parseColor("#F2100620"));
        l.addView(tv("Você abriu o " + app + " · tentativa " + (tries + 1) + " hoje", 14, 0xFFB9A2CC, false));
        l.addView(tv(msg, 26, Color.WHITE, true));
        if (it.length() > 1) for (int i = 0; i < it.length(); i++) l.addView(tv("• " + it.getJSONObject(i).optString("t"), 16, 0xFFE9D9F4, false));
        Button b1 = btn(verb, ac), b2 = btn("Abrir Keeped", 0xFF2A1740), b3 = btn("Continuar no " + app, 0xFF2A1740);
        b1.setTextColor(0xFF1A052D);
        l.addView(b1); l.addView(b2); l.addView(b3);

        b1.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try {
                JSONArray done = new JSONArray(p.getString("done", "[]"));
                for (int i = 0; i < it.length(); i++) done.put(it.getJSONObject(i).optString("id"));
                JSONObject st = new JSONObject(p.getString("state", "{}"));
                st.put("items", new JSONArray());
                p.edit().putString("done", done.toString()).putString("state", st.toString()).apply();
            } catch (Exception e) { }
            hide();
        }});
        b2.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (li != null) { li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(li); }
            hide();
        }});
        b3.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hide(); }});

        if (wait > 0) {
            b3.setEnabled(false); b3.setAlpha(.4f);
            final Button fb = b3;
            ct = new CountDownTimer(wait * 1000L, 1000) {
                public void onTick(long ms) { fb.setText("Continuar no " + app + " (" + (ms / 1000 + 1) + "s)"); }
                public void onFinish() { fb.setText("Continuar no " + app); fb.setEnabled(true); fb.setAlpha(1f); }
            }.start();
        }

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        wm.addView(l, lp);
        ov = l;
    }

    void hide() {
        if (ct != null) { ct.cancel(); ct = null; }
        if (ov != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(ov); } catch (Exception e) { }
            ov = null;
        }
    }
}
