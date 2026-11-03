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
