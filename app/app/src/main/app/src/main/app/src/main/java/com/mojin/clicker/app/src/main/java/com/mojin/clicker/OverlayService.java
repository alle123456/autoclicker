package com.mojin.clicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class OverlayService extends Service {

    private static final String CHANNEL = "clicker";

    private WindowManager wm;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View ball, panel, crossView, confirmBar;
    private WindowManager.LayoutParams ballLp, panelLp, crossLp, confirmLp;

    private TextView tvState, tvPos;
    private EditText etInterval, etCount;
    private TextView btnStart, btnStop;

    private int tx, ty;
    private long interval = 100;
    private int total = 100;
    private volatile boolean running;
    private int done;
    private HandlerThread clickThread;
    private Handler clickHandler;
    private IClickerService clicker;
    private boolean bindPending;
    private boolean pendingStart;

    private float dRawX, dRawY;
    private int sLpX, sLpY;
    private boolean dragMoved;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        tx = prefs.getInt("x", 0);
        ty = prefs.getInt("y", 0);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (intent != null && "stop".equals(intent.getStringExtra("cmd"))) {
            stopClicking("已停止");
        }
        showBall();
        return START_STICKY;
    }

    // ---------- 通知 ----------
    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "连点指令",
                    NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getBroadcast(this, 1,
                new Intent(this, StopReceiver.class), PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("连点指令")
                .setContentText(running ? "连点中 · 已点 " + done + " 次" : "点击悬浮球控制连点")
                .setOngoing(true);
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                "关闭连点器", pi).build());
        startForeground(1, b.build());
    }

    // ---------- 悬浮球 ----------
    private void showBall() {
        if (ball != null) return;
        FrameLayout f = new FrameLayout(this);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2E7BFF, 0xFF00C9A7});
        gd.setShape(GradientDrawable.OVAL);
        gd.setAlpha(235);
        f.setBackground(gd);
        TextView icon = new TextView(this);
        icon.setText("⚡");
        icon.setTextSize(22);
        icon.setTextColor(Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        f.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        ball = f;

        ballLp = new WindowManager.LayoutParams(dp(52), dp(52), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.START;
        ballLp.x = dp(2);
        ballLp.y = dp(220);

        f.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dRawX = e.getRawX(); dRawY = e.getRawY();
                        sLpX = ballLp.x; sLpY = ballLp.y;
                        dragMoved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - dRawX;
                        float dy = e.getRawY() - dRawY;
                        if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) {
                            dragMoved = true;
                            ballLp.x = sLpX + (int) dx;
                            ballLp.y = Math.max(0, sLpY + (int) dy);
                            try { wm.updateViewLayout(ball, ballLp); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragMoved) {
                            vibrate(25);
                            showPanel();
                        }
                        return true;
                }
                return false;
            }
        });

        try { wm.addView(ball, ballLp); }
        catch (Throwable t) { toast("悬浮窗创建失败：" + t.getMessage()); }
    }

    // ---------- 控制面板 ----------
    private void showPanel() {
        if (panel != null || crossView != null) return;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2121A24);
        bg.setCornerRadius(dp(16));
        root.setBackground(bg);
        int p = dp(14);
        root.setPadding(p, p, p, p);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        r1.addView(label("⚡ 连点指令", 16, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView hide = label("— 收起", 13, 0xFF8FA3B7, false);
        hide.setPadding(dp(8), dp(4), dp(4), dp(4));
        hide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hidePanel(); }
        });
        r1.addView(hide, new LinearLayout.LayoutParams(-2, -2));
        root.addView(r1, new LinearLayout.LayoutParams(-1, -2));

        tvState = label("状态：待机", 12, 0xFF9FB0C0, false);
        root.addView(tvState, rowLp(6));

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.addView(label("位置", 13, 0xFF8FA3B7, false), new LinearLayout.LayoutParams(dp(48), -2));
        tvPos = label(posText(), 13, 0xFFE6EEF5, false);
        r2.addView(tvPos, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView pick = chipBtn("选位置", 0xFF2E7BFF);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickPosition(); }
        });
        r2.addView(pick, new LinearLayout.LayoutParams(dp(76), dp(34)));
        root.addView(r2, rowLp(8));

        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        r3.setGravity(Gravity.CENTER_VERTICAL);
        r3.addView(label("间隔", 13, 0xFF8FA3B7, false), new LinearLayout.LayoutParams(dp(36), -2));
        etInterval = input(String.valueOf(prefs.getLong("interval", 100)));
        r3.addView(etInterval, new LinearLayout.LayoutParams(dp(70), -2));
        r3.addView(label("ms", 12, 0xFF6E8296, false), new LinearLayout.LayoutParams(dp(26), -2));
        r3.addView(label("次数", 13, 0xFF8FA3B7, false), new LinearLayout.LayoutParams(dp(36), -2));
        etCount = input(String.valueOf(prefs.getInt("count", 100)));
        r3.addView(etCount, new LinearLayout.LayoutParams(dp(70), -2));
        root.addView(r3, rowLp(8));

        TextView hint = label("次数 0 = 无限连点；间隔最小 20ms", 11, 0xFF6E8296, false);
        root.addView(hint, rowLp(4));

        LinearLayout r4 = new LinearLayout(this);
        r4.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = chipBtn("▶ 开始", 0xFF00A884);
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onStartClicked(); }
        });
        r4.addView(btnStart, new LinearLayout.LayoutParams(0, dp(42), 1f));
        btnStop = chipBtn("■ 停止", 0xFF4A3438);
        btnStop.setEnabled(false);
        btnStop.setAlpha(0.45f);
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopClicking("已手动停止"); }
        });
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lpT.leftMargin = dp(10);
        r4.addView(btnStop, lpT);
        root.addView(r4, rowLp(12));

        TextView quit = chipBtn("退出悬浮窗", 0xFF2A2F36);
        quit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { quitService(); }
        });
        root.addView(quit, new LinearLayout.LayoutParams(-1, dp(38)));

        panel = root;
        panelLp = new WindowManager.LayoutParams(dp(312), -2, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = dp(8);
        panelLp.y = dp(64);
        try { wm.addView(panel, panelLp); }
        catch (Throwable t) { panel = null; toast("面板创建失败"); }
    }

    private void hidePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Throwable ignored) {}
            panel = null;
        }
    }

    private String posText() {
        return (tx == 0 && ty == 0) ? "未设置" : "( " + tx + " , " + ty + " )";
    }

    // ---------- 准星定位 ----------
    private void pickPosition() {
        hidePanel();
        if (crossView != null) return;

        FrameLayout c = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x4400E5FF);
        ring.setStroke(dp(3), 0xFF00E5FF);
        c.setBackground(ring);
        TextView plus = new TextView(this);
        plus.setText("＋");
        plus.setTextSize(18);
        plus.setTextColor(Color.WHITE);
        plus.setGravity(Gravity.CENTER);
        c.addView(plus, new FrameLayout.LayoutParams(-1, -1));

        crossView = c;
        crossLp = new WindowManager.LayoutParams(dp(44), dp(44), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        crossLp.gravity = Gravity.TOP | Gravity.START;
        if (tx != 0 || ty != 0) {
            crossLp.x = Math.max(0, tx - dp(22));
            crossLp.y = Math.max(0, ty - dp(22));
        } else {
            crossLp.x = dp(100);
            crossLp.y = dp(320);
        }

        c.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dRawX = e.getRawX(); dRawY = e.getRawY();
                        sLpX = crossLp.x; sLpY = crossLp.y;
                        dragMoved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - dRawX;
                        float dy = e.getRawY() - dRawY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) {
                            dragMoved = true;
                            crossLp.x = sLpX + (int) dx;
                            crossLp.y = Math.max(0, sLpY + (int) dy);
                            try { wm.updateViewLayout(crossView, crossLp); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragMoved) vibrate(20);
                        return true;
                }
                return false;
            }
        });

        try { wm.addView(crossView, crossLp); } catch (Throwable t) { crossView = null; }

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(0xF2121A24);
        bbg.setCornerRadius(dp(14));
        bar.setBackground(bbg);
        int bp = dp(10);
        bar.setPadding(bp, bp, bp, bp);
        TextView ok = chipBtn("✓ 定到这里", 0xFF00A884);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmPosition(); }
        });
        bar.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView cancel = chipBtn("✕ 取消", 0xFF2A2F36);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancelPosition(); }
        });
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lpC.leftMargin = dp(10);
        bar.addView(cancel, lpC);

        confirmBar = bar;
        confirmLp = new WindowManager.LayoutParams(-2, -2, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        confirmLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        confirmLp.y = dp(60);
        try { wm.addView(confirmBar, confirmLp); }
        catch (Throwable t) { confirmBar = null; }
    }

    private void confirmPosition() {
        tx = crossLp.x + dp(22);
        ty = crossLp.y + dp(22);
        prefs.edit().putInt("x", tx).putInt("y", ty).apply();
        removeCross();
        showPanel();
        vibrate(35);
        toast("位置已设置 (" + tx + ", " + ty + ")");
    }

    private void cancelPosition() {
        removeCross();
        showPanel();
    }

    private void removeCross() {
        if (crossView != null) {
            try { wm.removeView(crossView); } catch (Throwable ignored) {}
            crossView = null;
        }
        if (confirmBar != null) {
            try { wm.removeView(confirmBar); } catch (Throwable ignored) {}
            confirmBar = null;
        }
    }

    // ---------- 连点 ----------
    private void onStartClicked() {
        if (running) return;
        long iv;
        int ct;
        try { iv = Long.parseLong(etInterval.getText().toString().trim()); }
        catch (Exception e) { iv = 100; }
        try { ct = Integer.parseInt(etCount.getText().toString().trim()); }
        catch (Exception e) { ct = 0; }
        if (iv < 20) { iv = 20; etInterval.setText("20"); }
        if (iv > 600000) iv = 600000;
        if (ct < 0) ct = 0;
        interval = iv;
        total = ct;
        prefs.edit().putLong("interval", iv).putInt("count", ct).apply();

        if (tx == 0 && ty == 0) {
            toast("请先点「选位置」，把准星拖到要连点的地方");
            return;
        }

        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                toast("Shizuku 未授权：请打开主界面 App 点「授权 Shizuku」");
                return;
            }
        } catch (Throwable t) {
            toast("Shizuku 未运行：请打开 Shizuku App 重新启动");
            return;
        }

        pendingStart = true;
        bindIfNeeded();
    }

    private void bindIfNeeded() {
        if (clicker != null) {
            if (pendingStart) { pendingStart = false; reallyStart(); }
            return;
        }
        if (bindPending) return;
        bindPending = true;

        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(this, UserService.class))
                .processNameSuffix("clicker")
                .debuggable(false)
                .version(BuildConfig.VERSION_CODE);

        try {
            Shizuku.bindUserService(args, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    bindPending = false;
                    clicker = IClickerService.Stub.asInterface(binder);
                    if (pendingStart) { pendingStart = false; reallyStart(); }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    clicker = null;
                    bindPending = false;
                    if (running) {
                        ui.post(new Runnable() {
                            @Override public void run() { stopClicking("Shizuku 服务断开"); }
                        });
                    }
                }
            });
        } catch (Throwable t) {
            bindPending = false;
            toast("绑定 Shizuku 失败：" + t.getMessage());
        }
    }

    private void reallyStart() {
        if (running || clicker == null) return;
        running = true;
        done = 0;
        setState("连点中…");
        btnStart.setEnabled(false);
        btnStart.setAlpha(0.45f);
        btnStop.setEnabled(true);
        btnStop.setAlpha(1f);
        startForegroundCompat();
        vibrate(60);
        toast("开始连点：每 " + interval + "ms 一次");

        clickThread = new HandlerThread("clicker");
        clickThread.start();
        clickHandler = new Handler(clickThread.getLooper());
        clickHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                try {
                    clicker.tap(tx, ty);
                    done++;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            setState("连点中 · 已点 " + done + (total > 0 ? " / " + total : "") + " 次");
                        }
                    });
                } catch (Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() { stopClicking("出错，已停止"); }
                    });
                    return;
                }
                if (total > 0 && done >= total) {
                    ui.post(new Runnable() {
                        @Override public void run() { stopClicking("完成！共点了 " + done + " 次 ✓"); }
                    });
                    return;
                }
                clickHandler.postDelayed(this, interval);
            }
        });

        ui.postDelayed(new Runnable() {
            @Override public void run() { if (running) hidePanel(); }
        }, 900);
    }

    private void stopClicking(String reason) {
        boolean was = running;
        running = false;
        if (clickHandler != null) clickHandler.removeCallbacksAndMessages(null);
        if (clickThread != null) { clickThread.quitSafely(); clickThread = null; clickHandler = null; }
        if (btnStart != null) { btnStart.setEnabled(true); btnStart.setAlpha(1f); }
        if (btnStop != null) { btnStop.setEnabled(false); btnStop.setAlpha(0.45f); }
        if (was) vibrate(90);
        setState(reason != null ? reason : "已停止");
        startForegroundCompat();
        if (was && reason != null) toast(reason);
    }

    private void setState(String s) {
        if (tvState != null) tvState.setText("状态：" + s);
    }

    private void quitService() {
        running = false;
        if (clickHandler != null) clickHandler.removeCallbacksAndMessages(null);
        if (clickThread != null) clickThread.quitSafely();
        hidePanel();
        removeCross();
        if (ball != null) {
            try { wm.removeView(ball); } catch (Throwable ignored) {}
            ball = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (clickHandler != null) clickHandler.removeCallbacksAndMessages(null);
        if (clickThread != null) clickThread.quitSafely();
        try { if (panel != null) wm.removeView(panel); } catch (Throwable ignored) {}
        try { if (ball != null) wm.removeView(ball); } catch (Throwable ignored) {}
        try { if (crossView != null) wm.removeView(crossView); } catch (Throwable ignored) {}
        try { if (confirmBar != null) wm.removeView(confirmBar); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    // ---------- 小工具 ----------
    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView chipBtn(String s, int color) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(10));
        b.setBackground(gd);
        return b;
    }

    private EditText input(String value) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(value);
        et.setTextSize(14);
        et.setTextColor(Color.WHITE);
        et.setSingleLine(true);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF1D2833);
        gd.setCornerRadius(dp(8));
        et.setBackground(gd);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        return et;
    }

    private LinearLayout.LayoutParams rowLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Throwable ignored) {}
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
    }
