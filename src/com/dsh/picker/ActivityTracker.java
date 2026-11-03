package com.dsh.picker;

import android.app.Activity;

import java.lang.ref.WeakReference;

/** 记住宿主当前处于前台的 Activity，用于在其窗口上弹候选面板（无需悬浮窗权限）。 */
public final class ActivityTracker {

    private static WeakReference<Activity> current = new WeakReference<Activity>(null);

    private ActivityTracker() {
    }

    public static void onResume(Activity activity) {
        if (activity != null) {
            current = new WeakReference<Activity>(activity);
        }
    }

    public static void onPause(Activity activity) {
        Activity c = current.get();
        if (c != null && c == activity) {
            current = new WeakReference<Activity>(null);
        }
    }

    public static Activity current() {
        return current.get();
    }
}
