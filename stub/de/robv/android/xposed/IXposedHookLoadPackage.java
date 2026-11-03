// SPDX-License-Identifier: GPL-3.0-only
// PointPic —— 长按取图 | Copyright (C) 2026 Wanan (dsh)
// 完整许可条款见仓库根目录 LICENSE（GPL-3.0）。
package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 编译期桩：模块入口接口。
 */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
