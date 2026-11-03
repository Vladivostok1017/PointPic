// SPDX-License-Identifier: GPL-3.0-only
// PointPic —— 长按取图 | Copyright (C) 2026 Wanan (dsh)
// 完整许可条款见仓库根目录 LICENSE（GPL-3.0）。
package de.robv.android.xposed;

/**
 * 编译期桩：真实实现在 Vector / LSPosed 运行时。
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }
}
