package com.keeped.app;

import android.app.*;
import android.app.usage.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.Calendar;
import org.json.*;

public class BlockerService extends Service {
    Handler h = new Handler(Looper.getMainLooper());
    View ov;
    String cur = "", prev = "";
    CountDownTimer ct;
    boolean allowed = false;
    int leftSec = 0, ticks = 0;
    JSONObject lastSc = null;

    Runnable tick = new Runnable() {
        public void run() {
            try { check(); } catch (Throwable e) { }
            ticks++;
            if (ticks % 30 == 0) { try { nudge(); } catch (Throwable e) { } }
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

    // ---------- estado ----------
    String dayKey() { return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date()); }

    JSONObject load(SharedPreferences p) throws Exception {
        JSONObject s = new JSONObject(p.getString("state", "{}"));
        String today = dayKey();
        if (!today.equals(s.optString("day"))) {
            JSONArray it = s.optJSONArray("items");
            if (it != null) for (int i = 0; i < it.length(); i++) {
                JSONObject o = it.optJSONObject(i);
                if (o == null) continue;
                String k = o.optString("k");
                if ("meds".equals(k) || "habits".equals(k) || o.optInt("rp") > 0) o.put("d", 0);
            }
            s.put("day", today);
            p.edit().putString("state", s.toString()).apply();
        }
        return s;
    }

    int nowMin() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    boolean dueToday(JSONObject o) {
        if (o.optInt("rp") == 2) return has(o.optJSONArray("rw"), Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1);
        return true;
    }

    JSONArray pendingBase(JSONObject s, boolean onlyEssential) {
        JSONArray out = new JSONArray();
        JSONArray it = s.optJSONArray("items");
        if (it == null) return out;
        int now = nowMin();
        for (int i = 0; i < it.length(); i++) {
            JSONObject o = it.optJSONObject(i);
            if (o == null || o.optInt("d") == 1 || !dueToday(o)) continue;
            if (onlyEssential && o.optInt("e") != 1) continue;
            if (o.has("at") && now < o.optInt("at")) continue;
            out.put(o);
        }
        return out;
    }

    JSONArray pending(JSONObject s) { return pendingBase(s, true); }

    boolean has(JSONArray a, int v) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (a.optInt(i, -1) == v) return true;
        return false;
    }

    boolean hasS(JSONArray a, String v) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    boolean inSleep(JSONObject z) {
        if (z == null || z.optInt("on") != 1) return false;
        int st = z.optInt("s"), en = z.optInt("e");
        if (st == en) return false;
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int dw = c.get(Calendar.DAY_OF_WEEK) - 1;
        JSONArray d = z.optJSONArray("d");
        if (st < en) return m >= st && m < en && has(d, dw);
        if (m >= st) return has(d, dw);
        return m < en && has(d, (dw + 6) % 7);
    }

    String hm(int m) { return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60); }

    void markDone(String id, SharedPreferences p) {
        try {
            JSONObject st = load(p);
            JSONArray it = st.optJSONArray("items");
            if (it != null) for (int i = 0; i < it.length(); i++) {
                JSONObject o = it.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) o.put("d", 1);
            }
            JSONArray done = new JSONArray(p.getString("done", "[]"));
            done.put(id + "@" + dayKey());
            p.edit().putString("state", st.toString()).putString("done", done.toString()).apply();
        } catch (Exception e) { }
    }

    // ---------- bloqueio ----------
    void check() throws Exception {
        UsageStatsManager u = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long n = System.currentTimeMillis();
        UsageEvents e = u.queryEvents(n - 3000, n);
        UsageEvents.Event ev = new UsageEvents.Event();
        while (e.hasNextEvent()) {
            e.getNextEvent(ev);
            if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) cur = ev.getPackageName();
        }
        if (!cur.equals(prev)) { prev = cur; allowed = false; hide(); }
        else if (allowed || ov != null) return;
        if (cur.isEmpty() || cur.equals(getPackageName())) return;
        SharedPreferences p = getSharedPreferences("keeped", MODE_PRIVATE);
        JSONObject s = load(p);
        JSONObject pk = s.optJSONObject("pkgs");
        if (pk == null || !pk.has(cur)) return;
        if (!Settings.canDrawOverlays(this)) return;
        JSONObject a = pk.getJSONObject(cur);
        JSONObject sc = null;
        JSONArray sch = s.optJSONArray("sch");
        if (sch != null) for (int i = 0; i < sch.length(); i++) {
            JSONObject z = sch.optJSONObject(i);
            if (z != null && inSleep(z)) {
                JSONArray ap = z.optJSONArray("ap");
                if (ap == null || ap.length() == 0 || hasS(ap, cur)) { sc = z; break; }
            }
        }
        String mode = sc != null ? "z" : null;
        JSONArray items = pending(s);
        if (mode == null && a.optInt("t") == 1 && items.length() > 0) mode = "t";
        if (mode == null) return;
        show(mode, a.optString("n", cur), s, items, p, sc);
    }

    // ---------- lembretes criativos ----------
    String pick(JSONObject pools, String key) {
        JSONArray a = pools == null ? null : pools.optJSONArray(key);
        if (a == null || a.length() == 0) return null;
        return a.optString(new java.util.Random().nextInt(a.length()));
    }

    void nudge() throws Exception {
        SharedPreferences p = getSharedPreferences("keeped", MODE_PRIVATE);
        JSONObject s = load(p);
        JSONObject nd = s.optJSONObject("nudge");
        if (nd == null || nd.optInt("on") != 1) return;
        int m = nowMin();
        if (m < nd.optInt("s") || m > nd.optInt("e")) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong("lastN", 0) < nd.optInt("every", 120) * 60000L) return;
        if (cur.equals(getPackageName())) return;
        JSONArray all = pendingBase(s, false);
        JSONObject pools = nd.optJSONObject("p");
        String txt = null;
        int st = nd.optInt("st", 0);
        JSONObject med = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.getJSONObject(i);
            if ("meds".equals(o.optString("k"))) { med = o; break; }
        }
        if (med != null) {
            txt = pick(pools, "meds");
            if (txt != null) txt = txt.replace("{med}", med.optString("t"));
        } else if (all.length() > 0) {
            boolean risk = st > 0 && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 18;
            txt = pick(pools, risk ? "risk" : "tasks");
        } else if (s.optJSONArray("items") != null && s.optJSONArray("items").length() > 0 && !dayKey().equals(p.getString("praise", ""))) {
            txt = pick(pools, "done");
            p.edit().putString("praise", dayKey()).apply();
        }
        if (txt == null) return;
        txt = txt.replace("{n}", String.valueOf(all.length())).replace("{s}", String.valueOf(st));
        p.edit().putLong("lastN", now).apply();
        String ch = "keeped_nudge";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(ch, "Lembretes do Keeped", NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(this, ch);
        } else b = new Notification.Builder(this);
        b.setContentTitle("Keeped").setContentText(txt).setStyle(new Notification.BigTextStyle().bigText(txt))
                .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true);
        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (li != null) {
            b.setContentIntent(PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)));
        }
        nm.notify(100 + (int) (now % 1000), b.build());
    }

    // ---------- interface ----------
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * (1 - t) + Color.red(b) * t),
                (int) (Color.green(a) * (1 - t) + Color.green(b) * t),
                (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    GradientDrawable round(int color, int rdp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rdp));
        return g;
    }

    TextView tv(String t, int sp, int color, boolean bold) {
        TextView x = new TextView(this);
        x.setText(t); x.setTextSize(sp); x.setTextColor(color);
        x.setGravity(Gravity.CENTER);
        if (bold) x.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        x.setLayoutParams(lp);
        return x;
    }

    Button btn(String t, int bg, int fg) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextColor(fg); b.setTextSize(16);
        b.setBackground(round(bg, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    static class LogoView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int col;
        LogoView(Context c, int col) { super(c); this.col = col; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), cx = w / 2, cy = getHeight() / 2, u = w / 24f;
            p.setColor(col);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.3f * u);
            cv.save();
            cv.rotate(-28, cx, cy);
            cv.drawOval(new RectF(cx - 11 * u, cy - 4.4f * u, cx + 11 * u, cy + 4.4f * u), p);
            cv.restore();
            p.setStyle(Paint.Style.FILL);
            Path s = new Path();
            s.moveTo(cx, cy - 9 * u);
            s.quadTo(cx + .9f * u, cy - .9f * u, cx + 9 * u, cy);
            s.quadTo(cx + .9f * u, cy + .9f * u, cx, cy + 9 * u);
            s.quadTo(cx - .9f * u, cy + .9f * u, cx - 9 * u, cy);
            s.quadTo(cx - .9f * u, cy - .9f * u, cx, cy - 9 * u);
            cv.drawPath(s, p);
            cv.drawCircle(cx + 8 * u, cy - 7 * u, 1.1f * u, p);
        }
    }

    void show(String mode, String app, JSONObject s, JSONArray items, SharedPreferences p, JSONObject sc) throws Exception {
        boolean sl = mode.equals("z");
        String day = dayKey();
        if (!day.equals(p.getString("day", ""))) p.edit().putString("day", day).putInt("tries", 0).apply();
        int tries = p.getInt("tries", 0);
        int base = (sl && sc != null) ? sc.optInt("w", 30) : s.optInt("wait", 10);
        int inc = (sl && sc != null) ? sc.optInt("i", 15) : s.optInt("inc", 10);
        int max = sl ? 300 : s.optInt("max", 60);
        int wait = Math.min(max, base + inc * tries);
        p.edit().putInt("tries", tries + 1).apply();
        build(mode, app, s, items, p, wait, sc);
    }

    void build(final String mode, final String app, final JSONObject s, final JSONArray items, final SharedPreferences p, int wait, final JSONObject sc) throws Exception {
        hide();
        leftSec = wait;
        lastSc = sc;
        final boolean sl = mode.equals("z");
        final boolean moon = sc != null && "moon".equals(sc.optString("ic"));
        final int ac = Color.parseColor(s.optString("ac", "#9d6abf"));
        final int ink = 0xFF0C0414;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(26), dp(40), dp(26), dp(30));
        int c1 = 0xF8000000 | (mix(ac, ink, .62f) & 0xFFFFFF);
        int c2 = 0xF8000000 | (mix(ac, ink, .88f) & 0xFFFFFF);
        l.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{c1, c2}));

        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(dp(64), dp(64));
        lp0.bottomMargin = dp(16);
        l.addView(new LogoView(this, 0xFFFFFFFF), lp0);

        TextView chip = tv(app + " · tentativa " + p.getInt("tries", 1) + " hoje", 13, 0xFFE9D9F4, false);
        chip.setBackground(round(0x26FFFFFF, 20));
        chip.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.bottomMargin = dp(16);
        chip.setLayoutParams(cl);
        l.addView(chip);

        String title, sub;
        if (sl) {
            String ti = sc.optString("ti", "");
            String m = sc.optString("m", "");
            title = !ti.isEmpty() ? ti : (moon ? "Hora de dormir" : "Modo " + sc.optString("n"));
            sub = !m.isEmpty() ? m : "São " + hm(nowMin()) + ". Este bloqueio vai até as " + hm(sc.optInt("e")) + ". Que tal deixar o " + app + " para depois?";
        } else if (items.length() == 1) {
            JSONObject o = items.getJSONObject(0);
            String m = o.optString("m", "");
            title = !m.isEmpty() ? m : ("meds".equals(o.optString("k")) ? "Você ainda não tomou seu remédio." : "Você ainda não concluiu: " + o.optString("t") + ".");
            sub = "Conclua o essencial para liberar o " + app + " sem esperar.";
        } else {
            title = "Você ainda tem tarefas pendentes.";
            sub = "Toque em uma tarefa para marcá-la como feita.";
        }
        l.addView(tv(title, 27, Color.WHITE, true));
        l.addView(tv(sub, 15, 0xFFD3C2E3, false));

        if (!sl && items.length() > 1) {
            for (int i = 0; i < items.length(); i++) {
                final String id = items.getJSONObject(i).optString("id");
                TextView r = tv("○   " + items.getJSONObject(i).optString("t"), 16, Color.WHITE, false);
                r.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                r.setPadding(dp(16), dp(12), dp(16), dp(12));
                r.setBackground(round(0x22FFFFFF, 14));
                ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = dp(8);
                r.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            markDone(id, p);
                            JSONObject st = load(p);
                            JSONArray rem = pending(st);
                            if (rem.length() == 0) hide(); else build(mode, app, st, rem, p, leftSec, lastSc);
                        } catch (Exception e) { hide(); }
                    }
                });
                l.addView(r);
            }
        }

        final ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(Math.max(1, wait * 10));
        pb.setProgress(0);
        pb.getProgressDrawable().setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(4));
        pl.topMargin = dp(14);
        pb.setLayoutParams(pl);
        if (wait > 0) l.addView(pb);

        Button b1 = btn(sl ? (moon ? "Ir dormir" : "Voltar ao início") : (items.length() == 1 ? items.getJSONObject(0).optString("verb", "Já fiz") : "Já fiz tudo"), 0xFFFFFFFF, 0xFF1A052D);
        Button b2 = btn("Abrir Keeped", 0x33FFFFFF, Color.WHITE);
        final Button b3 = btn("Continuar no " + app, 0x33FFFFFF, Color.WHITE);
        l.addView(b1); l.addView(b2); l.addView(b3);

        final JSONArray fi = items;
        b1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (sl) {
                    Intent hm = new Intent(Intent.ACTION_MAIN);
                    hm.addCategory(Intent.CATEGORY_HOME);
                    hm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(hm);
                } else {
                    for (int i = 0; i < fi.length(); i++) markDone(fi.optJSONObject(i).optString("id"), p);
                }
                hide();
            }
        });
        b2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (li != null) { li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(li); }
                hide();
            }
        });
        b3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { allowed = true; hide(); }
        });

        if (wait > 0) {
            b3.setEnabled(false);
            b3.setAlpha(.45f);
            final long total = wait * 1000L;
            ct = new CountDownTimer(total, 100) {
                public void onTick(long ms) {
                    leftSec = (int) Math.ceil(ms / 1000.0);
                    b3.setText("Continuar no " + app + " (" + leftSec + "s)");
                    pb.setProgress((int) ((total - ms) / 100));
                }
                public void onFinish() {
                    leftSec = 0;
                    b3.setText("Continuar no " + app);
                    b3.setEnabled(true);
                    b3.setAlpha(1f);
                    pb.setProgress(pb.getMax());
                }
            }.start();
        }

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        l.setAlpha(0f);
        wm.addView(l, lp);
        l.animate().alpha(1f).setDuration(260).start();
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
