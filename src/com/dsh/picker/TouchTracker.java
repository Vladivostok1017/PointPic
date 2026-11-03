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

import android.view.MotionEvent;
import android.view.View;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记录每个控件最近一次按下的坐标（控件自身坐标系）。
 *
 * 用途：长按往往落在「容器」而不是「图片控件」上（RecyclerView 的 item、
 * 卡片、Cell 等）。有了按下点，就能在长按那一刻对控件树做命中测试，
 * 找到用户真正指着的那张图。
 */
public final class TouchTracker {

    private static final int MAX = 400;

    private static final LinkedHashMap<Integer, float[]> POINTS =
            new LinkedHashMap<Integer, float[]>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, float[]> eldest) {
                    return size() > MAX;
                }
            };

    private TouchTracker() {
    }

    public static void onTouch(View view, MotionEvent event) {
        if (view == null || event == null) {
            return;
        }
        try {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                synchronized (POINTS) {
                    POINTS.put(System.identityHashCode(view),
                            new float[]{event.getX(), event.getY()});
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** 取该控件被按下时的坐标；没有记录则返回 null。 */
    public static float[] get(View view) {
        if (view == null) {
            return null;
        }
        synchronized (POINTS) {
            return POINTS.get(System.identityHashCode(view));
        }
    }
}
