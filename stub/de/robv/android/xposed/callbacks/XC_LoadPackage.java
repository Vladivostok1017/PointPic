// SPDX-License-Identifier: GPL-3.0-only
// PointPic —— 长按取图 | Copyright (C) 2026 Wanan (dsh)
// 完整许可条款见仓库根目录 LICENSE（GPL-3.0）。
package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/**
 * 编译期桩。
 */
public final class XC_LoadPackage {

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }

    private XC_LoadPackage() {
    }
}
