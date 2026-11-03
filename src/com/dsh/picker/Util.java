package com.dsh.picker;

import android.content.Context;
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
     * 把图片放进系统剪贴板。
     *
     * 为什么用 MediaStore 的 content:// uri 而不是 FileProvider：
     * 本模块的代码运行在**宿主进程**里，FileProvider 的 authority 必须由宿主自己在
     * 清单里声明（它没有）；而 MediaStore 的 uri 是宿主插入图片时自己拿到的，
     * 跨应用可读，直接塞进 ClipData 就能被微信/QQ 粘贴。
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
