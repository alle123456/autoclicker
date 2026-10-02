package com.mojin.clicker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ_SHIZUKU = 100;
    private static final int REQ_NOTIF = 101;

    private TextView tvShizuku, tvOverlay;
    private TextView btnShizuku, btnOverlay, btnStart;

    private final Shizuku.OnBinderReceivedListener binderListener = new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            runOnUiThread(new Runnable() {
                @Override public void run() { refresh(); }
            });
        }
    };
    private final Shizuku.OnBinderDeadListener deadListener = new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            runOnUiThread(new Runnable() {
                @Override public void run() { refresh(); }
            });
        }
    };
    private final Shizuku.OnRequestPermissionResultListener permListener = new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode == REQ_SHIZUKU) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { refresh(); }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);
        Shizuku.addRequestPermissionResultListener(permListener);
        setContentView(buildUI());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener);
            Shizuku.removeBinderDeadListener(deadListener);
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private void refresh() {
        boolean installed = false, runningS = false, granted = false;
        try { installed = Shizuku.isInstalled(); } catch (Throwable ignored) {}
        try { runningS = Shizuku.pingBinder(); } catch (Throwable ignored) {}
        if (runningS) {
            try { granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
            catch (Throwable ignored) {}
        }

        if (!installed) {
            tvShizuku.setText("● 未安装 Shizuku");
            tvShizuku.setTextColor(0xFFFF5252);
            btnShizuku.setText("① 下载安装 Shizuku");
        } else if (!runningS) {
            tvShizuku.setText("● Shizuku 未启动（打开 Shizuku → 通过无线调试启动）");
            tvShizuku.setTextColor(0xFFFFB300);
            btnShizuku.setText("① 启动后点此重试");
        } else if (!granted) {
            tvShizuku.setText("● Shizuku 已启动，等待授权");
            tvShizuku.setTextColor(0xFFFFB300);
            btnShizuku.setText("① 授权 Shizuku");
        } else {
            tvShizuku.setText("● Shizuku 就绪");
            tvShizuku.setTextColor(0xFF69F0AE);
            btnShizuku.setText("① Shizuku 已授权 ✓");
        }

        boolean overlay = Settings.canDrawOverlays(this);
        tvOverlay.setText(overlay ? "● 悬浮窗权限已授予" : "● 悬浮窗权限未授予");
        tvOverlay.setTextColor(overlay ? 0xFF69F0AE : 0xFFFF5252);
        btnOverlay.setText(overlay ? "② 悬浮窗权限已授予 ✓" : "② 去授予悬浮窗权限");

        boolean ready = overlay && granted;
        btnStart.setAlpha(ready ? 1f : 0.45f);
        btnStart.setText(ready ? "③ 启动悬浮窗" : "③ 完成①②后可启动");
    }

    private void clickShizuku() {
        boolean installed = false, runningS = false, granted = false;
        try { installed = Shizuku.isInstalled(); } catch (Throwable ignored) {}
        try { runningS = Shizuku.pingBinder(); } catch (Throwable ignored) {}
        if (runningS) {
            try { granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
            catch (Throwable ignored) {}
        }
        if (!installed) {
            openShizukuDownload();
        } else if (!runningS) {
            toast("先打开 Shizuku 应用，点「通过无线调试启动」后回来重试");
        } else if (!granted) {
            try {
                Shizuku.requestPermission(REQ_SHIZUKU);
            } catch (Throwable t) {
                toast("授权请求失败：" + t.getMessage());
            }
        } else {
            toast("Shizuku 已就绪");
        }
    }

    private void openShizukuDownload() {
        String pkg = "moe.shizuku.privileged.api";
        if (tryOpen("mkl://detail/" + pkg)) return;
        if (tryOpen("coolmarket://detail/" + pkg)) return;
        if (tryOpen("market://details?id=" + pkg)) return;
        if (tryOpen("https://shizuku.rikka.app/zh-hans/download/")) return;
        if (tryOpen("https://github.com/RikkaApps/Shizuku/releases")) return;
        toast("请用浏览器搜索下载 Shizuku");
    }

    private boolean tryOpen(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void clickStart() {
        if (!Settings.canDrawOverlays(this)) { toast("先授予悬浮窗权限"); return; }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        Intent it = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(it);
        else startService(it);
        toast("悬浮窗已启动，去游戏里点小球 ⚡");
    }

    private View buildUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0D1218);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(30), pad, dp(30));
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));

        root.addView(text("⚡ 连点指令", 24, Color.WHITE, true), marginLp(0));
        root.addView(text("Shizuku 原生注入 · 免无障碍 · 免 root · 不卡屏", 13, 0xFF8FA3B7, false), marginLp(4));

        LinearLayout card = card();
        tvShizuku = text("", 14, Color.WHITE, false);
        tvOverlay = text("", 14, Color.WHITE, false);
        card.addView(text("准备工作", 16, 0xFFE6EEF5, true), marginLp(0));
        card.addView(tvShizuku, marginLp(10));
        card.addView(tvOverlay, marginLp(6));
        root.addView(card, marginLp(18));

        btnShizuku = bigBtn("检测中…", 0xFF2E7BFF);
        btnShizuku.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clickShizuku(); }
        });
        root.addView(btnShizuku, marginLp(14));

        btnOverlay = bigBtn("去授予悬浮窗权限", 0xFF2E7BFF);
        btnOverlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    toast("请到系统设置里开启悬浮窗权限");
                }
            }
        });
        root.addView(btnOverlay, marginLp(0));

        btnStart = bigBtn("启动悬浮窗", 0xFF00A884);
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clickStart(); }
        });
        root.addView(btnStart, marginLp(0));

        LinearLayout tips = card();
        tips.addView(text("使用步骤", 16, 0xFFE6EEF5, true), marginLp(0));
        tips.addView(text("1. 点①下载安装 Shizuku（也可在酷安搜 Shizuku）", 13, 0xFF9FB0C0, false), marginLp(10));
        tips.addView(text("2. 打开 Shizuku → 点「通过无线调试启动」→ 按提示配对（安卓 11+ 免电脑）", 13, 0xFF9FB0C0, false), marginLp(6));
        tips.addView(text("3. Shizuku 显示「运行中」后，回本 App 点「授权 Shizuku」，勾选始终允许", 13, 0xFF9FB0C0, false), marginLp(6));
        tips.addView(text("4. 授予悬浮窗权限，点「启动悬浮窗」", 13, 0xFF9FB0C0, false), marginLp(6));
        tips.addView(text("5. 游戏里点小球展开面板 → 「选位置」拖准星 → 设间隔次数 → 开始", 13, 0xFF9FB0C0, false), marginLp(6));
        tips.addView(text("• 运行中点小球可查看进度/停止，也可拉通知栏关闭", 13, 0xFF9FB0C0, false), marginLp(6));
        tips.addView(text("• 间隔建议 ≥50ms，太快部分游戏会丢点击", 13, 0xFF9FB0C0, false), marginLp(6));
        tips.addView(text("• 建议在系统设置给本 App 开「允许自启动/锁后台」防清理", 13, 0xFF9FB0C0, false), marginLp(6));
        root.addView(tips, marginLp(18));

        return sv;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF161D26);
        gd.setCornerRadius(dp(14));
        c.setBackground(gd);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        return c;
    }

    private LinearLayout.LayoutParams marginLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private TextView bigBtn(String s, int color) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(12));
        b.setBackground(gd);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
