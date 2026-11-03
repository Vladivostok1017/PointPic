// SPDX-License-Identifier: GPL-3.0-only
/*
 * PointPic —— 长按取图（LSPosed / Vector 模块）
 * Copyright (C) 2026 Wanan (dsh)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
