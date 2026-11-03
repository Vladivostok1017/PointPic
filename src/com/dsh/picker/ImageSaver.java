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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 落盘：三级回落。
 * 1) MediaStore 插入（宿主无存储权限也能写，Android 10+ 允许自有插入）
 * 2) root shell 直接拷贝（宿主权限被拒时兜底）
 * 3) 直接 File 写公共目录（宿主自己有权限时）
 */
public final class ImageSaver {

    private static final String ROOT_DIR = "Pictures/长按取图";

    private ImageSaver() {
    }

    public static void saveAsync(final Context ctx, final Bitmap bitmap, final String sourcePkg) {
        saveAsync(ctx, bitmap, sourcePkg, true);
    }

    /**
     * @param share 存好之后是否立刻唤起系统分享面板（现在默认就是这条路）。
     *              剪贴板那条已经不用了 —— 绝大多数 App 不读剪贴板里的图片。
     */
    public static void saveAsync(final Context ctx, final Bitmap bitmap,
                                 final String sourcePkg, final boolean share) {
        if (ctx == null || bitmap == null) {
            return;
        }
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doSave(app, bitmap, sourcePkg, share);
                } catch (Throwable t) {
                    Util.log("save err: " + t);
                    Util.toast(app, "保存失败: " + t.getClass().getSimpleName());
                }
            }
        }, "PickPic-Save").start();
    }

    private static void doSave(Context ctx, Bitmap bitmap, String sourcePkg,
                               boolean share) throws Exception {
        Bitmap src = Util.toSoftware(bitmap);
        if (src == null || src.isRecycled()) {
            Util.toast(ctx, "图片已失效，请重试");
            return;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64 * 1024, src.getByteCount() / 2));
        synchronized (src) {
            if (src.isRecycled()) {
                Util.toast(ctx, "图片已失效，请重试");
                return;
            }
            src.compress(Bitmap.CompressFormat.PNG, 100, bos);
        }
        byte[] data = bos.toByteArray();
        if (data.length == 0) {
            Util.toast(ctx, "编码失败");
            return;
        }
        String name = Util.md5(data) + ".png";
        String sub = safe(sourcePkg);

        Uri uri = saveViaMediaStore(ctx, data, name, sub);
        if (uri != null) {
            Util.toast(ctx, "已保存 " + src.getWidth() + "×" + src.getHeight()
                    + " → 相册/长按取图/" + sub);
            if (share) {
                Util.shareImage(ctx, uri, "image/png");
            }
            Util.log("saved uri=" + uri + " share=" + share);
            return;
        }
        if (saveViaRoot(ctx, data, name, sub)) {
            Util.toast(ctx, "已保存(root) " + src.getWidth() + "×" + src.getHeight());
            return;
        }
        if (saveViaFile(data, name, sub)) {
            Util.toast(ctx, "已保存 " + name);
            return;
        }
        Util.toast(ctx, "保存失败：没有可用的写入通道");
    }

    private static String safe(String s) {
        if (s == null || s.length() == 0) {
            return "unknown";
        }
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Uri saveViaMediaStore(Context ctx, byte[] data, String name, String sub) {
        return insertMedia(ctx, data, name, sub, "image/png");
    }

    /** 通用插入：允许指定 MIME —— WebView 原图可能是 jpeg / webp / gif，要原样保留。 */
    static Uri insertMedia(Context ctx, byte[] data, String name, String sub, String mime) {
        OutputStream os = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            String relPath = ROOT_DIR + "/" + sub + "/";

            // 内容哈希同名 → 已存在则跳过，避免出现 "xxx (1).png"
            // 注意：MediaStore 里可能存在「文件已被删掉但记录还在」的过期行，
            // 因此必须实际打开一次验证；打不开就删掉过期行，继续正常写入。
            android.database.Cursor c = null;
            try {
                c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Images.Media._ID},
                        MediaStore.Images.Media.DISPLAY_NAME + "=? AND "
                                + MediaStore.Images.Media.RELATIVE_PATH + "=?",
                        new String[]{name, relPath}, null);
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(0);
                    Uri existing = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                    boolean alive = false;
                    try {
                        android.content.res.AssetFileDescriptor afd = cr.openAssetFileDescriptor(existing, "r");
                        if (afd != null) {
                            alive = afd.getLength() > 0;
                            afd.close();
                        }
                    } catch (Throwable ignore) {
                    }
                    if (alive) {
                        Util.log("already exists: " + name);
                        return existing;
                    }
                    Util.log("stale row, delete: " + name);
                    try {
                        cr.delete(existing, null, null);
                    } catch (Throwable ignore) {
                    }
                }
            } catch (Throwable ignore) {
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Throwable ignore) {
                    }
                }
            }

            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(MediaStore.Images.Media.RELATIVE_PATH, relPath);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                return null;
            }
            os = cr.openOutputStream(uri);
            if (os == null) {
                return null;
            }
            os.write(data);
            os.flush();
            os.close();
            os = null;
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return uri;
        } catch (Throwable t) {
            Util.log("mediaStore: " + t);
            return null;
        } finally {
            closeQuietly(os);
        }
    }

    private static boolean saveViaRoot(Context ctx, byte[] data, String name, String sub) {
        File tmp = null;
        try {
            tmp = new File(ctx.getCacheDir(), "pick_" + name);
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(data);
            fos.flush();
            fos.close();

            String dir = "/sdcard/" + ROOT_DIR + "/" + sub;
            String dst = dir + "/" + name;
            String cmd = "mkdir -p '" + dir + "' && cp '" + tmp.getAbsolutePath() + "' '" + dst
                    + "' && chmod 644 '" + dst + "'";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int code = p.waitFor();
            if (code != 0) {
                Util.log("root cp exit=" + code);
                return false;
            }
            try {
                MediaScannerConnection.scanFile(ctx, new String[]{dst}, new String[]{"image/png"}, null);
            } catch (Throwable ignore) {
            }
            return true;
        } catch (Throwable t) {
            Util.log("rootSave: " + t);
            return false;
        } finally {
            if (tmp != null) {
                try {
                    tmp.delete();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static boolean saveViaFile(byte[] data, String name, String sub) {
        FileOutputStream fos = null;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "长按取图/" + sub);
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File out = new File(dir, name);
            fos = new FileOutputStream(out);
            fos.write(data);
            fos.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(fos);
        }
    }

    private static void closeQuietly(OutputStream os) {
        if (os != null) {
            try {
                os.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
