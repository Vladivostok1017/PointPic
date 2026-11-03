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
        if (url.startsWith("data:image")) {
            Util.log("data-uri 暂不支持");
            Util.toast(ctx, "内联图片暂不支持");
            return;
        }
        byte[] data = download(url, referer);
        if (data == null || data.length < 64) {
            Util.toast(ctx, "原图下载失败（空响应）");
            return;
        }
        String mime = sniffMime(data);
        String ext = extOf(mime);
        String name = Util.md5(data) + "." + ext;
        String sub = pkg == null ? "unknown" : pkg.replaceAll("[^A-Za-z0-9._-]", "_");

        Uri uri = ImageSaver.insertMedia(ctx, data, name, sub, mime);
        if (uri == null) {
            Util.toast(ctx, "原图保存失败");
            return;
        }
        boolean clip = Util.copyToClipboard(ctx, uri);
        Util.log("SAVE-URL " + data.length + "B " + mime + " clipboard=" + clip + " " + url);
        Util.toast(ctx, "已保存原图 " + (data.length / 1024) + "KB"
                + (clip ? "，已复制到剪贴板" : ""));
    }

    private static byte[] download(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
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
            in.close();
            return bos.toByteArray();
        } finally {
            try {
                c.disconnect();
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
