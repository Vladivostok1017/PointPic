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
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** 通用工具：日志、Toast、MD5。 */
public final class Util {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Util() {
    }

    public static void log(String msg) {
        try {
            de.robv.android.xposed.XposedBridge.log("[PickPic] " + msg);
        } catch (Throwable ignore) {
        }
    }

    /** 直接落盘调试：绕过任何日志子系统，用于确认 hook 是否真的被调用。 */
    public static void dbg(android.content.Context ctx, String msg) {
        try {
            java.io.File f = new java.io.File(ctx.getCacheDir(), "pickpic_dbg.log");
            if (f.length() > 512 * 1024) {
                f.delete();
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
            fos.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignore) {
        }
    }

    public static void toast(final Context ctx, final String msg) {
        if (ctx == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {
                }
            }
        });
    }

    public static String md5(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            char[] out = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (Throwable t) {
            return Long.toHexString(System.currentTimeMillis());
        }
    }

    /** 任意 Drawable 转 Bitmap；能取到 BitmapDrawable 就取原对象，避免重绘损失。 */
    public static android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable d) {
        if (d == null) {
            return null;
        }
        try {
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (b != null && !b.isRecycled()) {
                    return b;
                }
            }
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = Math.max(1, d.getBounds().width());
                h = Math.max(1, d.getBounds().height());
            }
            if (w <= 0 || h <= 0) {
                return null;
            }
            android.graphics.Bitmap out =
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return out;
        } catch (Throwable t) {
            log("drawableToBitmap: " + t);
            return null;
        }
    }

    /** HARDWARE 位图不能 getPixel/copy，必须回读到软件位图。 */
    public static android.graphics.Bitmap toSoftware(android.graphics.Bitmap b) {
        if (b == null) {
            return null;
        }
        if (b.getConfig() != android.graphics.Bitmap.Config.HARDWARE) {
            return b;
        }
        try {
            android.graphics.Bitmap out =
                    android.graphics.Bitmap.createBitmap(b.getWidth(), b.getHeight(),
                            android.graphics.Bitmap.Config.ARGB_8888);
            new android.graphics.Canvas(out).drawBitmap(b, 0f, 0f, null);
            return out;
        } catch (Throwable t) {
            log("toSoftware: " + t);
            return null;
        }
    }

    /**
     * 唤起系统分享面板（Sharesheet）把刚保存的图发出去。
     *
     * 为什么走系统分享而不是剪贴板：Android 上绝大多数 App **不读**剪贴板里的图片
     * （ClipboardManager 只负责「放」，接收方愿不愿意读是它自己的事），
     * 而 ACTION_SEND + content:// 是各家都实现了的通用通道。
     *
     * 三个关键点：
     *  1) 必须 setClipData()。只放 EXTRA_STREAM 时，FLAG_GRANT_READ_URI_PERMISSION
     *     在部分 ROM 上不会随 chooser 传递，接收方拿到 uri 打不开。
     *  2) 从 Activity 启动不要加 NEW_TASK（否则会另起任务栈，返回键行为异常）；
     *     从 Application 启动必须加，否则直接被后台启动限制挡掉。
     *  3) 必须在主线程 startActivity。
     */
    public static void shareImage(final Context ctx, final Uri uri, final String mime) {
        if (ctx == null || uri == null) {
            return;
        }
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime == null || mime.length() == 0 ? "image/*" : mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.setClipData(android.content.ClipData.newRawUri("image", uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    Activity act = ActivityTracker.current();
                    Context from = act != null ? act : app;
                    Intent chooser = Intent.createChooser(send, "分享图片");
                    if (!(from instanceof Activity)) {
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    from.startActivity(chooser);
                    log("share sheet -> " + uri);
                } catch (Throwable t) {
                    log("share FAIL: " + t);
                    toast(app, "已保存，但分享面板打不开");
                }
            }
        });
    }

    /**
     * 把图片放进系统剪贴板。
     *
     * 目前**不再走这条路**（改成系统分享面板了），代码留着是为了以后想加开关时
     * 不用重写。为什么不能只靠它：宿主进程里没有 FileProvider 的 authority
     * （authority 必须由宿主自己在清单里声明），所以只能借 MediaStore 的
     * content:// uri —— 但接收方 App 普遍不读剪贴板图片，实际没什么用。
     */
    public static boolean copyToClipboard(android.content.Context ctx, android.net.Uri uri) {
        if (ctx == null || uri == null) {
            return false;
        }
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                            ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            android.content.ClipData clip = android.content.ClipData.newUri(
                    ctx.getContentResolver(), "PickPic image", uri);
            cm.setPrimaryClip(clip);
            return true;
        } catch (Throwable t) {
            log("copyToClipboard: " + t);
            return false;
        }
    }
}
