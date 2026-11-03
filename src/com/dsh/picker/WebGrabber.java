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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;

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
            // 关键：不要只用 elementFromPoint（命中的常是文字/容器）。
            // 直接遍历所有 <img>，用 getBoundingClientRect 判断哪个落在点上，
            // 取面积最大的那个；再退一步找 CSS 背景图。
            String js = "(function(){"
                    + "try{"
                    + "var vw=" + w + ",vh=" + h + ",lx=" + localX + ",ly=" + localY + ";"
                    + "var sx=lx*((window.innerWidth||vw)/vw);"
                    + "var sy=ly*((window.innerHeight||vh)/vh);"
                    + "var best='',bestArea=0;"
                    + "var imgs=document.getElementsByTagName('img');"
                    + "for(var i=0;i<imgs.length;i++){"
                    + "var r=imgs[i].getBoundingClientRect();"
                    + "if(sx>=r.left&&sx<=r.right&&sy>=r.top&&sy<=r.bottom){"
                    + "var a=(r.width||1)*(r.height||1);"
                    + "if(a>bestArea){bestArea=a;best=imgs[i].currentSrc||imgs[i].src||'';}"
                    + "}}"
                    + "if(best){return best;}"
                    + "var el=document.elementFromPoint(sx,sy);var g=0;"
                    + "while(el&&g++<8){"
                    + "if(el.tagName==='IMG'){return el.currentSrc||el.src||'';}"
                    + "try{var bg=window.getComputedStyle(el).backgroundImage;"
                    + "if(bg&&bg.indexOf('url(')>=0){"
                    + "return bg.replace(/^url\\([\"']?/,'').replace(/[\"']?\\)$/,'');}}catch(e2){}"
                    + "el=el.parentElement;"
                    + "}"
                    // ── 精确命中失败：很多图标不是 <img> 而是 CSS background-image
                    //    （document.images 为 0 也能有图）。按「离指尖的距离」收集，
                    //    只保留同一行附近的，交给用户挑。
                    + "var near=[];"
                    + "try{"
                    + "var all=document.getElementsByTagName('*');"
                    + "for(var mi=0;mi<all.length;mi++){"
                    + "var bu='';"
                    + "try{"
                    + "var bgs=''+window.getComputedStyle(all[mi]).backgroundImage;"
                    + "if(bgs&&bgs.indexOf('url(')>=0){"
                    + "var mm=bgs.match(/url\\([\"']?([^\"')]+)[\"']?\\)/);"
                    + "if(mm){bu=mm[1];}"
                    + "}"
                    + "}catch(e8){}"
                    + "if(!bu){continue;}"
                    + "if(bu.indexOf('data:image')!==0&&"
                    + "!/\\.(png|jpe?g|gif|webp|bmp|svg|avif)/i.test(bu)){continue;}"
                    + "if(bu.length>3000){continue;}"
                    + "var rr=all[mi].getBoundingClientRect();"
                    + "if(!rr.width&&!rr.height){continue;}"
                    + "var ddx=Math.max(rr.left-sx,0,sx-rr.right);"
                    + "var ddy=Math.max(rr.top-sy,0,sy-rr.bottom);"
                    + "near.push([ddx*ddx+ddy*ddy,bu]);"
                    + "}}catch(e9){}"
                    + "near.sort(function(a,b){return a[0]-b[0];});"
                    + "var bestD=near.length?near[0][0]:0;"
                    + "var nout=[],seenN={};"
                    + "for(var q=0;q<near.length&&nout.length<6;q++){"
                    + "if(near[q][0]>bestD+150*150){continue;}"
                    + "if(seenN[near[q][1]]){continue;}"
                    + "seenN[near[q][1]]=1;"
                    + "nout.push(near[q][1]);"
                    + "}"
                    + "if(nout.length){return '#near|'+nout.join('\\n');}"
                    // 到最后都没有图片资源 → 把「该点上的整叠元素」回报出来
                    // 注意用 elementsFromPoint：pointer-events:none 的图标会被
                    // elementFromPoint 跳过，只看到下面那层空 div。
                    + "var stack=null;"
                    + "try{stack=document.elementsFromPoint(sx,sy);}catch(e7){}"
                    + "if(!stack||!stack.length){"
                    + "var t0=document.elementFromPoint(sx,sy);"
                    + "stack=t0?[t0]:[];"
                    + "}"
                    + "if(!stack.length){return '#none|no-element';}"
                    + "var d='';"
                    + "for(var k=0;k<stack.length&&k<4;k++){"
                    + "var t=stack[k];var one=t.tagName;"
                    + "try{one+='.'+((''+t.className).slice(0,30));}catch(e3){}"
                    + "try{var cs=window.getComputedStyle(t);"
                    + "var bg=''+cs.backgroundImage;"
                    + "var mask=''+(cs.webkitMaskImage||cs.maskImage||'');"
                    + "if(bg&&bg.indexOf('none')<0)one+=' BG='+bg.slice(0,50);"
                    + "if(mask&&mask.indexOf('none')<0)one+=' MASK='+mask.slice(0,50);"
                    + "if(cs.fontFamily&&(''+cs.fontFamily).indexOf('sans-serif')<0)"
                    + "one+=' FF='+((''+cs.fontFamily).slice(0,30));"
                    + "}catch(e4){}"
                    + "try{var bc=''+window.getComputedStyle(t,'::before').content;"
                    + "if(bc&&bc!=='none')one+=' BC='+bc.slice(0,30);}catch(e6){}"
                    + "d+='['+one+']';"
                    + "}"
                    + "try{d+=' imgs='+document.images.length;"
                    + "d+=' svgs='+document.querySelectorAll('svg').length;"
                    + "d+=' canvas='+document.querySelectorAll('canvas').length;"
                    + "d+=' totalEls='+document.getElementsByTagName('*').length;}catch(e5){}"
                    + "return '#none|'+d;"
                    + "}catch(e){return '#none|err:'+e;}"
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
                                    if (url != null && url.startsWith("#none|")) {
                                        // 探针结果：那一点到底是什么
                                        Util.log("web probe: " + url);
                                        cb.onResult(null);
                                    } else {
                                        Util.log("js result=" + (url == null ? "null"
                                                : (url.length() > 90 ? url.substring(0, 90) + "..." : url)));
                                        cb.onResult(url);
                                    }
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
        // evaluateJavascript 返回的是 JSON 字符串：换行/引号都是转义形式，
        // 多行结果（#near| 列表）必须先解转义，否则整串会当成一个 URL。
        return unescapeJson(v);
    }

    private static String unescapeJson(String v) {
        if (v.indexOf('\\') < 0) {
            return v;
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '\\' || i + 1 >= v.length()) {
                sb.append(c);
                continue;
            }
            char n = v.charAt(++i);
            switch (n) {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'u':
                    if (i + 4 < v.length()) {
                        try {
                            sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Throwable ignore) {
                            sb.append(n);
                        }
                    } else {
                        sb.append(n);
                    }
                    break;
                default:
                    sb.append(n);   // \" \\ \/ 都退化成字符本身
                    break;
            }
        }
        return sb.toString();
    }

    /** 解析探针返回的 `#near|url\\nurl...` 候选列表。 */
    public static ArrayList<String> parseList(String marker) {
        ArrayList<String> out = new ArrayList<String>();
        if (marker == null) {
            return out;
        }
        String body = marker.startsWith("#near|") ? marker.substring(6) : marker;
        String[] parts = body.split("\n");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i] == null ? "" : parts[i].trim();
            if (p.length() > 8 && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
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
