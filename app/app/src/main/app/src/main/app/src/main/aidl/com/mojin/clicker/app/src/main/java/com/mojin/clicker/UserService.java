package com.mojin.clicker;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import rikka.shizuku.IUserService;

public class UserService implements IUserService {

    private final ClickerImpl impl = new ClickerImpl();
    private final InputManager im;
    private final Method inject;

    public UserService(Context context) {
        InputManager m = null;
        try {
            m = (InputManager) InputManager.class.getMethod("getInstance").invoke(null);
        } catch (Throwable t) {
            try {
                m = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            } catch (Throwable ignored) {}
        }
        im = m;
        Method mm = null;
        try {
            mm = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
            mm.setAccessible(true);
        } catch (Throwable t) {
            throw new RuntimeException("获取注入接口失败", t);
        }
        inject = mm;
        if (im == null) throw new RuntimeException("获取 InputManager 失败");
    }

    @Override
    public void destroy() { }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public IBinder getUserServiceImpl() {
        return impl;
    }

    private class ClickerImpl extends IClickerService.Stub {
        @Override
        public void tap(int x, int y) {
            doTap(x, y);
        }
    }

    private void doTap(int x, int y) {
        try {
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            inject.invoke(im, down, 0); // 0 = ASYNC
            Thread.sleep(25);           // 模拟按压 25ms
            long t2 = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(now, t2, MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            inject.invoke(im, up, 0);
        } catch (Throwable t) {
            // 反射注入失败时，退回 shell 执行 input 命令（慢一点但保底）
            try {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"input", "tap", String.valueOf(x), String.valueOf(y)});
                p.waitFor();
            } catch (Throwable ignored) {}
        }
    }
      }
