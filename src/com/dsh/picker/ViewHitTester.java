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

import android.view.View;
import android.view.ViewGroup;

/**
 * 控件树命中测试：给定某控件内的坐标，返回包含该点的最深子控件。
 * 纯 View 树遍历，不涉及任何屏幕像素/截图。
 */
public final class ViewHitTester {

    private ViewHitTester() {
    }

    /** x,y 为 root 自身坐标系下的点。 */
    public static View deepest(View root, float x, float y) {
        if (root == null) {
            return null;
        }
        try {
            return descend(root, x, y, 0);
        } catch (Throwable t) {
            Util.log("hitTest: " + t);
            return null;
        }
    }

    private static View descend(View v, float x, float y, int depth) {
        if (depth > 24 || v == null) {
            return null;
        }
        if (v.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (y < 0 || y > v.getHeight() || x < 0 || x > v.getWidth()) {
            return null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            // 后添加的通常在上层，倒序优先
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View c = g.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE) {
                    continue;
                }
                float cx = x - c.getLeft() + g.getScrollX();
                float cy = y - c.getTop() + g.getScrollY();
                View hit = descend(c, cx, cy, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return v;
    }
}
