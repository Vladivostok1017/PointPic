package com.dsh.picker;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 从 URL 保存「原始文件」。
 *
 * 为什么这条路比截屏好：WebView 给的是 <img> 的 URL，
 * 下下来的就是**未经屏幕缩放的原图**（原格式、原分辨率、含原始字节）。
 */
public final class UrlSaver {

    private static final int MAX_BYTES = 24 * 1024 * 1024;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private UrlSaver() {
    }

    public static void saveFromUrlAsync(final Context ctx, final String url,
                                        final String referer, final String sourcePkg) {
        if (ctx == null || url == null) {
            return;
        }
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doSave(app, url, referer, sourcePkg);
                } catch (Throwable t) {
                    Util.log("url save err: " + t);
                    Util.toast(app, "原图下载失败: " + t.getClass().getSimpleName());
                }
            }
        }, "PickPic-UrlSave").start();
    }

    private static void doSave(Context ctx, String url, String referer, String pkg) throws Exception {
        byte[] data;
        String mime = null;
        if (url.startsWith("data:image")) {
            // 内联 base64 图片：很多 H5 把图标/小图直接内联，这类必须就地解码
            int comma = url.indexOf(',');
            if (comma < 0) {
                Util.toast(ctx, "内联图片格式异常");
                return;
            }
            String meta = url.substring(5, comma);          // image/webp;base64
            String payload = url.substring(comma + 1);
            boolean base64 = meta.contains("base64");
            mime = meta.split(";")[0];
            if (mime == null || mime.length() == 0) {
                mime = "image/png";
            }
            if (base64) {
                data = android.util.Base64.decode(payload, android.util.Base64.DEFAULT);
            } else {
                data = java.net.URLDecoder.decode(payload, "UTF-8").getBytes("ISO-8859-1");
            }
            Util.log("data-uri decoded " + (data == null ? 0 : data.length) + "B " + mime);
        } else {
            data = download(ctx, url, referer);
        }
        if (data == null || data.length < 64) {
            Util.toast(ctx, "原图下载失败（空响应）");
            return;
        }
        if (mime == null || !mime.startsWith("image/")) {
            mime = sniffMime(data);
        }
        String ext = extOf(mime);
        String name = Util.md5(data) + "." + ext;
        String sub = pkg == null ? "unknown" : pkg.replaceAll("[^A-Za-z0-9._-]", "_");

        Uri uri = ImageSaver.insertMedia(ctx, data, name, sub, mime);
        if (uri == null) {
            Util.toast(ctx, "原图保存失败");
            return;
        }
        Util.toast(ctx, "已保存原图 " + (data.length / 1024) + "KB");
        Util.shareImage(ctx, uri, mime);
        Util.log("SAVE-URL " + data.length + "B " + mime + " share=true "
                + (url.length() > 80 ? url.substring(0, 80) + "..." : url));
    }

    /** 供候选面板取缩略图用（同一下载通道，失败返回 null）。 */
    static byte[] fetch(Context ctx, String url, String referer) {
        try {
            return download(ctx, url, referer);
        } catch (Throwable t) {
            Util.log("fetch: " + t);
            return null;
        }
    }

    static byte[] fetch(String url, String referer) {
        return fetch(null, url, referer);
    }

    /** 只解出不超过 maxSide 的缩略图，避免面板把内存吃爆。 */
    static android.graphics.Bitmap decodeThumb(byte[] data, int maxSide) {
        if (data == null || data.length < 64) {
            return null;
        }
        try {
            android.graphics.BitmapFactory.Options probe = new android.graphics.BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, probe);
            int bigger = Math.max(probe.outWidth, probe.outHeight);
            int sample = 1;
            while (bigger / sample > maxSide * 2) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] download(Context ctx, String url, String referer) throws Exception {
        String u = url;
        // ── ① file:///android_asset/...（大量 App 的内置 H5 页面把图标放在 APK 的 assets 里）
        if (u.startsWith("file:///android_asset/")) {
            String path = u.substring("file:///android_asset/".length());
            int q = path.indexOf('?');
            if (q > 0) {
                path = path.substring(0, q);
            }
            if (ctx == null) {
                return null;
            }
            Util.log("asset read " + path);
            return readAll(ctx.getAssets().open(path));
        }
        // ── ② file:///android_res/drawable/xxx.png → APK 里的 drawable 资源
        if (u.startsWith("file:///android_res/")) {
            String path = u.substring("file:///android_res/".length());
            int slash = path.indexOf('/');
            if (slash < 0 || ctx == null) {
                return null;
            }
            String type = path.substring(0, slash);
            String name = path.substring(slash + 1);
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            int id = ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
            Util.log("res read " + type + "/" + name + " id=" + Integer.toHexString(id));
            return id == 0 ? null : readAll(ctx.getResources().openRawResource(id));
        }
        // ── ③ 本地文件
        if (u.startsWith("file://")) {
            String path = Uri.parse(u).getPath();
            if (path == null) {
                return null;
            }
            Util.log("file read " + path);
            return readAll(new java.io.FileInputStream(path));
        }
        // ── ④ content:// / android.resource://
        if (u.startsWith("content://") || u.startsWith("android.resource://")) {
            if (ctx == null) {
                return null;
            }
            InputStream ci = ctx.getContentResolver().openInputStream(Uri.parse(u));
            return ci == null ? null : readAll(ci);
        }
        // ── ⑤ 其它非 http 协议：交给平台的 URL 实现，不要强转成 HttpURLConnection
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            java.net.URLConnection uc = new URL(u).openConnection();
            uc.setConnectTimeout(8000);
            uc.setReadTimeout(10000);
            InputStream ni = uc.getInputStream();
            return ni == null ? null : readAll(ni);
        }
        // ── ⑥ 网络
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            if (referer != null && referer.length() > 0) {
                c.setRequestProperty("Referer", referer);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                Util.log("http " + code + " " + url);
                return null;
            }
            InputStream in = c.getInputStream();
            return readAll(in);
        } finally {
            try {
                c.disconnect();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 读流，带 24MB 上限。 */
    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > MAX_BYTES) {
                    Util.log("too large, abort");
                    break;
                }
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String sniffMime(byte[] d) {
        if (d.length < 12) {
            return "image/jpeg";
        }
        int b0 = d[0] & 0xFF;
        int b1 = d[1] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) {
            return "image/jpeg";
        }
        if (b0 == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            return "image/gif";
        }
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        if (d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            String brand = new String(d, 8, 4);
            if (brand.startsWith("avi")) {
                return "image/avif";
            }
            if (brand.startsWith("hei") || brand.startsWith("mif")) {
                return "image/heic";
            }
        }
        return "image/jpeg";
    }

    private static String extOf(String mime) {
        if ("image/png".equals(mime)) {
            return "png";
        }
        if ("image/gif".equals(mime)) {
            return "gif";
        }
        if ("image/webp".equals(mime)) {
            return "webp";
        }
        if ("image/avif".equals(mime)) {
            return "avif";
        }
        if ("image/heic".equals(mime)) {
            return "heic";
        }
        return "jpg";
    }
}
