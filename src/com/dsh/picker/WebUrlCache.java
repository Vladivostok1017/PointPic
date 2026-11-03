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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebView 资源 URL 缓存。
 *
 * 思路来自 PicCatcher 的 WebViewCatcherPlugin / X5WebViewCatcherPlugin：
 * 钩 `WebViewClient.onLoadResource(webView, url)`，页面上每加载一个资源都会回调，
 * 于是能直接拿到**图片的原始 URL** —— 不用坐标、不用 JS、不用渲染。
 *
 * 这里按 WebView 实例（identityHashCode）归档，长按时把「这一页加载过的图」列出来让用户挑，
 * 挑中哪个就下哪个的原图。
 */
public final class WebUrlCache {

    private static final int MAX_PER_VIEW = 16;
    private static final int MAX_VIEWS = 32;

    private static final LinkedHashMap<Integer, ArrayDeque<String>> MAP =
            new LinkedHashMap<Integer, ArrayDeque<String>>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, ArrayDeque<String>> eldest) {
                    return size() > MAX_VIEWS;
                }
            };

    private WebUrlCache() {
    }

    public static void put(View webView, String url) {
        if (webView == null || url == null || !looksLikeImage(url)) {
            return;
        }
        try {
            int key = System.identityHashCode(webView);
            synchronized (MAP) {
                ArrayDeque<String> q = MAP.get(key);
                if (q == null) {
                    q = new ArrayDeque<String>(MAX_PER_VIEW);
                    MAP.put(key, q);
                }
                q.remove(url);
                q.addFirst(url);
                while (q.size() > MAX_PER_VIEW) {
                    q.removeLast();
                }
            }
            Util.log("web-url cached: " + shortUrl(url));
        } catch (Throwable t) {
            Util.log("cache put: " + t);
        }
    }

    /** 取该 WebView 最近加载过的图片 URL。 */
    public static ArrayList<String> recent(View webView, int max) {
        ArrayList<String> out = new ArrayList<String>(max);
        if (webView == null) {
            return out;
        }
        synchronized (MAP) {
            ArrayDeque<String> q = MAP.get(System.identityHashCode(webView));
            if (q != null) {
                for (String u : q) {
                    if (out.size() >= max) {
                        break;
                    }
                    out.add(u);
                }
            }
            // 同一个页面里可能有多个 WebView（嵌套/复用），本实例没有就退回全局最近
            if (out.isEmpty()) {
                for (ArrayDeque<String> dq : MAP.values()) {
                    for (String u : dq) {
                        if (out.size() >= max) {
                            break;
                        }
                        if (!out.contains(u)) {
                            out.add(u);
                        }
                    }
                    if (out.size() >= max) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    public static int size(View webView) {
        if (webView == null) {
            return 0;
        }
        synchronized (MAP) {
            ArrayDeque<String> q = MAP.get(System.identityHashCode(webView));
            return q == null ? 0 : q.size();
        }
    }

    static boolean looksLikeImage(String url) {
        if (url == null || url.length() < 8) {
            return false;
        }
        if (url.startsWith("data:")) {
            return false;   // 内联 base64，面板里没法当 URL 下载
        }
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        String path = q > 0 ? u.substring(0, q) : u;
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
                || path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".bmp")
                || path.endsWith(".avif") || path.endsWith(".heic")) {
            return true;
        }
        return u.contains("/image") || u.contains("imageview") || u.contains("imgurl")
                || u.contains("img=");
    }

    static String shortUrl(String url) {
        return url.length() > 96 ? url.substring(0, 96) + "..." : url;
    }
}
