package com.dsh.picker;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.view.View;

import java.lang.reflect.Method;

/**
 * WebView（含腾讯 X5 / SafeWebView）取图支持。
 *
 * 两条路，优先级从高到低：
 *  1) getHitTestResult()：WebView 自己对上一次触摸做过命中测试，能直接给出
 *     「手指下那个 <img> 的 URL」→ 下载下来就是**原始文件**，质量最高。
 *  2) capturePicture()：拿不到 URL 时退化为把 WebView 当前视口渲染成位图（整页截图级）。
 *
 * 全部用反射，因为 X5 的类是 com.tencent.smtt.sdk.WebView，与原生不同包。
 */
public final class WebGrabber {

    private WebGrabber() {
    }

    /** 命中 View 或其祖先里是否有 WebView。 */
    public static View findWebView(View hit) {
        View cur = hit;
        int guard = 0;
        while (cur != null && guard++ < 12) {
            String name = cur.getClass().getName();
            if (name.contains("WebView")) {
                return cur;
            }
            Object p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return null;
    }

    /**
     * 手指下的图片 URL。
     * HitTestResult 类型：IMAGE_TYPE=5、IMAGE_ANCHOR_TYPE=6、SRC_IMAGE_ANCHOR_TYPE=9。
     */
    public static String imageUrlAt(View webView) {
        try {
            Object result = call(webView, "getHitTestResult");
            if (result == null) {
                return null;
            }
            Object typeObj = call(result, "getType");
            int type = typeObj instanceof Integer ? (Integer) typeObj : -1;
            if (type != 5 && type != 6 && type != 9) {
                return null;
            }
            Object extra = call(result, "getExtra");
            if (extra instanceof String) {
                String url = (String) extra;
                if (url.startsWith("http://") || url.startsWith("https://")
                        || url.startsWith("data:image")) {
                    Util.log("web-url type=" + type + " " + url);
                    return url;
                }
            }
        } catch (Throwable t) {
            Util.log("imageUrlAt: " + t);
        }
        return null;
    }

    public static String pageUrl(View webView) {
        Object u = call(webView, "getUrl");
        return u instanceof String ? (String) u : null;
    }

    public interface UrlCallback {
        void onResult(String url);
    }

    /**
     * 用 JS 找「指尖下那个 &lt;img&gt; 的 src」——这是能拿到原图文件的关键一步。
     *
     * 坐标换算用「比例」而不是 devicePixelRatio：把 WebView 内的局部像素按
     * view 尺寸折算成视口比例，再乘 window.innerWidth/innerHeight 得到 CSS 坐标。
     * 这样固定缩放、dpr 差异都不用管（只有捏合缩放后会有偏差）。
     *
     * evaluateJavascript 的签名在原生与 X5 内核里类型不同（ValueCallback 不同包），
     * 所以用反射找方法 + Proxy 实现回调接口。
     */
    public static void requestImageUrlAt(final View webView, int localX, int localY,
                                         final UrlCallback cb) {
        try {
            int w = webView.getWidth();
            int h = webView.getHeight();
            if (w <= 0 || h <= 0) {
                cb.onResult(null);
                return;
            }
            String js = "(function(){"
                    + "try{"
                    + "var vw=" + w + ",vh=" + h + ",lx=" + localX + ",ly=" + localY + ";"
                    + "var cx=Math.round(lx*((window.innerWidth||vw)/vw));"
                    + "var cy=Math.round(ly*((window.innerHeight||vh)/vh));"
                    + "var el=document.elementFromPoint(cx,cy);"
                    + "var g=0;"
                    + "while(el&&el.tagName!=='IMG'&&g++<8){el=el.parentElement;}"
                    + "if(el&&el.tagName==='IMG'){return el.currentSrc||el.src||'';}"
                    + "return '';"
                    + "}catch(e){return '';}"
                    + "})()";

            java.lang.reflect.Method target = null;
            Class<?> cbType = null;
            java.lang.reflect.Method[] ms = webView.getClass().getMethods();
            for (int i = 0; i < ms.length; i++) {
                if ("evaluateJavascript".equals(ms[i].getName())
                        && ms[i].getParameterTypes().length == 2) {
                    target = ms[i];
                    cbType = ms[i].getParameterTypes()[1];
                    break;
                }
            }
            if (target == null || cbType == null) {
                Util.log("no evaluateJavascript on " + webView.getClass().getName());
                cb.onResult(null);
                return;
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    cbType.getClassLoader(),
                    new Class<?>[]{cbType},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                            try {
                                if (a != null && a.length > 0 && a[0] instanceof String) {
                                    String url = unquote((String) a[0]);
                                    Util.log("js result=" + url);
                                    cb.onResult(url);
                                } else {
                                    cb.onResult(null);
                                }
                            } catch (Throwable t) {
                                return null;
                            }
                            return null;
                        }
                    });
            target.invoke(webView, js, proxy);
        } catch (Throwable t) {
            Util.log("requestImageUrlAt: " + t);
            cb.onResult(null);
        }
    }

    private static String unquote(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        if (v.length() == 0 || "null".equals(v) || "undefined".equals(v)) {
            return null;
        }
        return v;
    }

    /** 把 WebView 当前视口渲染成位图（必须在主线程调用）。 */
    public static Bitmap snapshot(View webView) {
        try {
            Object pic = call(webView, "capturePicture");
            if (pic instanceof Picture) {
                Picture picture = (Picture) pic;
                int w = picture.getWidth();
                int h = picture.getHeight();
                if (w > 0 && h > 0) {
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    picture.draw(new Canvas(bmp));
                    Util.log("web-snapshot(picture) " + w + "x" + h);
                    return bmp;
                }
            }
        } catch (Throwable t) {
            Util.log("snapshot picture: " + t);
        }
        try {
            int w = webView.getWidth();
            int h = webView.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            webView.draw(new Canvas(bmp));
            Util.log("web-snapshot(draw) " + w + "x" + h);
            return bmp;
        } catch (Throwable t) {
            Util.log("snapshot draw: " + t);
            return null;
        }
    }

    private static Object call(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
