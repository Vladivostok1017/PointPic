package com.dsh.picker;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 入口：把「控件 → 位图」的绑定关系和「长按即存」装进宿主进程。
 *
 * 三个关键设计：
 *  1) 身份绑定：hook ImageView.setImageXxx，记录 identityHashCode(View) → Bitmap，
 *     用户长按时 O(1) 精确取回，不做任何图像相似度匹配。
 *  2) 自实现长按判定：不依赖宿主调用 View.performLongClick（大量自定义控件会覆写它，
 *     钩基类会漏），改为在根 View 的 dispatchTouchEvent 里自己判定「按住 ≥600ms 且位移 <25px」。
 *  3) 命中测试：长按往往落在容器上，用按下点在 View 树里找最深的 ImageView。
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String SELF = "com.dsh.picker";
    private static final long LONG_PRESS_MS = 600;
    private static final float MAX_MOVE_PX = 25f;

    private static Context appContext;
    private static boolean inited;

    // 长按判定状态
    private static long downAt;
    private static float downX, downY;
    private static View downRoot;
    private static boolean longPressFired;
    private static long lastSaveAt;
    private static long lastDownLog;

    /**
     * 注意：不能在静态字段初始化里 new Handler(getMainLooper())。
     * Vector 注入得极早，那时主 Looper 还没建好，会直接
     * NullPointerException 导致整个类初始化失败（模块完全不加载）。
     * 必须懒创建，等到真正有触摸事件时（主线程一定已就绪）再建。
     */
    private static android.os.Handler mainHandler;

    private static android.os.Handler handler() {
        if (mainHandler == null) {
            try {
                mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            } catch (Throwable t) {
                try {
                    mainHandler = new android.os.Handler();
                } catch (Throwable ignore) {
                }
            }
        }
        return mainHandler;
    }

    /** 最近一次由定时器路径处理完手势的时刻；兜底路径在此之后 1.5s 内不再插手。 */
    private static long lastGestureHandledAt;

    /** 到点即触发：此时手指仍按在屏幕上，提示会立刻出现，符合「长按出结果」的直觉。 */
    private static final Runnable LONG_PRESS_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            try {
                if (downAt == 0 || longPressFired) {
                    return;
                }
                longPressFired = true;
                lastGestureHandledAt = SystemClock.uptimeMillis();
                triggerAt(downRoot, downX, downY);
            } catch (Throwable t) {
                Util.log("longPress runnable: " + t);
            }
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam == null || lpparam.packageName == null) {
                return;
            }
            if (SELF.equals(lpparam.packageName)) {
                return;
            }
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        init((Context) param.thisObject, lpparam);
                    } catch (Throwable t) {
                        Util.log("init err: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Util.log("handleLoadPackage err: " + t);
        }
    }

    private static synchronized void init(Context ctx, XC_LoadPackage.LoadPackageParam lp) {
        if (inited || ctx == null) {
            return;
        }
        inited = true;
        appContext = ctx;
        Util.log("init in " + lp.packageName);

        hookTouch();
        hookImageView();
        hookLongClickFallback();
        hookBitmapFactory();
        hookActivity();
        hookDrawContext();

        Util.log("init done for " + lp.packageName);
    }

    // ---------- 1. 触摸 + 自实现长按判定 ----------

    private static void hookTouch() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent",
                    MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                handleTouch((View) param.thisObject, (MotionEvent) param.args[0]);
                            } catch (Throwable ignore) {
                            }
                        }
                    });
        } catch (Throwable t) {
            Util.log("hookTouch FAIL: " + t);
        }
    }

    private static void handleTouch(View v, MotionEvent ev) {
        if (v == null || ev == null) {
            return;
        }
        int action = ev.getActionMasked();

        // 记录每个控件自己的按下点（供命中测试用）
        if (action == MotionEvent.ACTION_DOWN) {
            TouchTracker.onTouch(v, ev);
        }

        long now = SystemClock.uptimeMillis();

        if (action == MotionEvent.ACTION_DOWN) {
            // 只抓住本手势里「最先出现的那个 DOWN」：dispatch 顺序是外→内，
            // 第一个到达的必然是最外层可见容器，它的坐标系足以覆盖整块内容。
            if (downAt == 0) {
                downAt = now;
                downRoot = v;
                downX = ev.getX();
                downY = ev.getY();
                longPressFired = false;
                // 关键：按下时就挂定时器 —— 到点立刻触发（手指还按着），
                // 而不是等松手才判断，那样提示会在松手后才出现，不符合直觉。
                handler().removeCallbacks(LONG_PRESS_RUNNABLE);
                handler().postDelayed(LONG_PRESS_RUNNABLE, LONG_PRESS_MS);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            // 位移超过阈值 → 判定为滑动，取消长按
            if (downAt != 0 && v == downRoot) {
                if (Math.abs(ev.getX() - downX) > MAX_MOVE_PX
                        || Math.abs(ev.getY() - downY) > MAX_MOVE_PX) {
                    handler().removeCallbacks(LONG_PRESS_RUNNABLE);
                    downAt = 0;
                    downRoot = null;
                }
            }
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            // 无论 UP 还是 CANCEL，都只负责收尾；触发已在定时器里完成
            handler().removeCallbacks(LONG_PRESS_RUNNABLE);
            downAt = 0;
            downRoot = null;
        }
    }

    // ---------- 2. View 锚点：记下「这个控件显示了这张图」 ----------

    private static void hookImageView() {
        try {
            XposedHelpers.findAndHookMethod(ImageView.class, "setImageDrawable",
                    Drawable.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                CaptureStore.put((View) param.thisObject,
                                        Util.drawableToBitmap((Drawable) param.args[0]));
                            } catch (Throwable ignore) {
                            }
                        }
                    });
        } catch (Throwable t) {
            Util.log("hook setImageDrawable FAIL: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(ImageView.class, "setImageBitmap",
                    Bitmap.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                CaptureStore.put((View) param.thisObject,
                                        (Bitmap) param.args[0]);
                            } catch (Throwable ignore) {
                            }
                        }
                    });
        } catch (Throwable t) {
            Util.log("hook setImageBitmap FAIL: " + t);
        }
    }

    // ---------- 3. 宿主 Activity 追踪（候选面板用其窗口，免悬浮窗权限） ----------

    private static void hookActivity() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ActivityTracker.onResume((Activity) param.thisObject);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Util.log("hook onResume FAIL: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        ActivityTracker.onPause((Activity) param.thisObject);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Util.log("hook onPause FAIL: " + t);
        }
    }

    // ---------- 4. 绘制上下文绑定（自绘/Canvas 控件靠这条归属） ----------

    private static final ThreadLocal<View> DRAWING = new ThreadLocal<View>();
    private static long lastDrawPut;

    private static void hookDrawContext() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "draw", Canvas.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    DRAWING.set((View) param.thisObject);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    DRAWING.remove();
                }
            });
        } catch (Throwable t) {
            Util.log("hook View.draw FAIL: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Canvas.class, "drawBitmap",
                    Bitmap.class, float.class, float.class, Paint.class, drawBitmapHook());
        } catch (Throwable t) {
            Util.log("hook drawBitmap(f) FAIL: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Canvas.class, "drawBitmap",
                    Bitmap.class, Rect.class, Rect.class, Paint.class, drawBitmapHook());
        } catch (Throwable t) {
            Util.log("hook drawBitmap(rect) FAIL: " + t);
        }
    }

    private static XC_MethodHook drawBitmapHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = DRAWING.get();
                    if (v == null) {
                        return;
                    }
                    Bitmap b = (Bitmap) param.args[0];
                    if (b == null || b.getWidth() < 96 || b.getHeight() < 96) {
                        return;
                    }
                    long now = SystemClock.uptimeMillis();
                    if (now - lastDrawPut < 30) {   // 绘制极热，限流
                        return;
                    }
                    lastDrawPut = now;
                    CaptureStore.put(v, b);
                } catch (Throwable ignore) {
                }
            }
        };
    }

    // ---------- 5. 兜底：宿主自己走 performLongClick 的情况 ----------

    private static void hookLongClickFallback() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "performLongClick",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View v = (View) param.thisObject;
                                triggerOn(v);
                            } catch (Throwable ignore) {
                            }
                        }
                    });
        } catch (Throwable t) {
            Util.log("hook performLongClick FAIL: " + t);
        }
    }

    // ---------- 4. 触发与取图 ----------

    /** 控件绑定关系的最大可采信时长；超过视为 RecyclerView 复用后的过期数据。 */
    private static final long MAX_ENTRY_AGE_MS = 60000L;

    /** 由自实现长按命中点触发：downRoot 坐标系下的点。 */
    private static void triggerAt(View root, float x, float y) {
        if (root == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastSaveAt < 800) {
            return;
        }
        // 兜底路径刚刚处理过这次手势（performLongClick 可能比定时器更早触发）→ 不重复
        if (now - lastGestureHandledAt < 500) {
            return;
        }
        // 标记本次手势已被处理，避免兜底路径再跑一遍
        lastGestureHandledAt = now;
        // 换算成屏幕坐标：后续用 getGlobalVisibleRect 做跨子树判定
        float sx = x;
        float sy = y;
        try {
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            sx = x + loc[0];
            sy = y + loc[1];
        } catch (Throwable ignore) {
        }

        View hit = null;
        try {
            hit = ViewHitTester.deepest(root, x, y);
        } catch (Throwable ignore) {
        }
        decide(root, sx, sy, hit, "timer");
    }

    /** 两条触发路径（定时器 / performLongClick 兜底）共用同一套判定。 */
    private static void decide(View root, float sx, float sy, View hit, String via) {
        long now = SystemClock.uptimeMillis();
        lastSaveAt = now;
        lastGestureHandledAt = now;
        Util.log("decide via=" + via + " point=" + (int) sx + "," + (int) sy
                + " hit=" + (hit == null ? "null" : hit.getClass().getSimpleName()));

        // ---- WebView 优先：能拿到 <img> 的 URL 就存「原始文件」 ----
        View web = null;
        try {
            web = WebGrabber.findWebView(hit != null ? hit : root);
        } catch (Throwable t) {
            Util.log("findWebView: " + t);
        }
        if (web != null) {
            final Context wctx = web.getContext() != null ? web.getContext() : appContext;
            final View webView = web;
            // 先试 getHitTestResult（快，且不用等 JS）
            String imgUrl = WebGrabber.imageUrlAt(web);
            if (imgUrl != null && wctx != null) {
                Util.log("web <img>(hittest) -> " + imgUrl);
                UrlSaver.saveFromUrlAsync(wctx, imgUrl, WebGrabber.pageUrl(web),
                        wctx.getPackageName());
                return;
            }
            // 再用 JS 精确找指尖下的 <img>
            int lx = 0;
            int ly = 0;
            try {
                int[] loc = new int[2];
                web.getLocationOnScreen(loc);
                lx = (int) (sx - loc[0]);
                ly = (int) (sy - loc[1]);
            } catch (Throwable ignore) {
            }
            final String pageUrl = WebGrabber.pageUrl(web);
            WebGrabber.requestImageUrlAt(web, lx, ly, new WebGrabber.UrlCallback() {
                @Override
                public void onResult(String url) {
                    if (url != null && wctx != null) {
                        Util.log("web <img>(js) -> " + url);
                        UrlSaver.saveFromUrlAsync(wctx, url, pageUrl, wctx.getPackageName());
                        return;
                    }
                    // JS 也没找到 → 退化为把 WebView 内容渲染成位图
                    Bitmap shot = WebGrabber.snapshot(webView);
                    if (worthSaving(shot)) {
                        doSave(webView, shot, "webview-snapshot");
                    } else {
                        Util.log("web: 无 img URL 且渲染无内容");
                    }
                }
            });
            return;
        }

        ArrayList<Bitmap> cands = pickCandidates(root, sx, sy, hit);
        if (cands.isEmpty()) {
            Util.log("skip: 指尖下没有可确定的图片 (" + (int) sx + "," + (int) sy + ")");
            return;
        }
        final String from = (hit != null ? hit.getClass().getSimpleName() : "root")
                + "@" + (int) sx + "," + (int) sy;
        final View anchor = hit != null ? hit : root;
        if (cands.size() == 1) {
            doSave(anchor, cands.get(0), from);
            return;
        }
        Activity act = ActivityTracker.current();
        Util.log("candidates=" + cands.size() + " activity=" + (act != null));
        if (act == null) {
            doSave(anchor, cands.get(0), from + " (fallback largest)");
            return;
        }
        final ArrayList<Bitmap> list = cands;
        CandidateDialog.show(act, list, new CandidateDialog.OnPick() {
            @Override
            public void onPick(Bitmap bitmap) {
                doSave(anchor, bitmap, from + " (picked)");
            }
        });
    }

    /** 由 performLongClick 触发 —— 仅在「定时器路径没有处理过这次手势」时兜底。 */
    private static void triggerOn(View v) {
        if (v == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        // 定时器路径刚刚处理过这次手势 → 不许兜底再来一遍
        // （否则会拿「控件中心」当按点：高德这种整屏一个 WebView 的 App
        //   会永远解析到同一张图，正是「不管点哪都是同一张」的原因）
        if (now - lastGestureHandledAt < 1500) {
            return;
        }
        if (now - lastSaveAt < 800) {
            return;
        }
        // 必须用真实按点，绝不用控件中心
        float[] pt = TouchTracker.get(v);
        if (pt == null) {
            return;
        }
        float sx = pt[0];
        float sy = pt[1];
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            sx = pt[0] + loc[0];
            sy = pt[1] + loc[1];
        } catch (Throwable ignore) {
        }
        decide(v, sx, sy, v, "fallback");
    }

    private static void doSave(View v, Bitmap bmp, String from) {
        Context ctx = v != null && v.getContext() != null ? v.getContext() : appContext;
        if (ctx == null) {
            return;
        }
        Util.log("SAVE " + bmp.getWidth() + "x" + bmp.getHeight() + " from " + from);
        ImageSaver.saveAsync(ctx, bmp, ctx.getPackageName(), true);
    }

    /**
     * 「指尖下到底是哪几张图」——用屏幕坐标在全窗口范围内判定，返回候选（最大的在前）。
     *
     * 为什么不用父子链：图片上方常盖着一层浮层（选中遮罩、标题条），
     * 触摸命中的是浮层，图片是它的**兄弟**而不是父/子，沿树上下找都会失手。
     *
     * 为什么返回列表：一个点上常叠着大图 + 角标 + 头像，靠启发式只能"猜"；
     * 交给用户点选才不会拿错。
     */
    private static ArrayList<Bitmap> pickCandidates(View anyView, float sx, float sy, View hit) {
        ArrayList<Bitmap> out = new ArrayList<Bitmap>(4);

        // ① 命中的 View 自己就是 ImageView（最精确）
        addUnique(out, imageOf(hit));

        // ② 全窗口范围内：可见区域包含该点的 ImageView
        View top = topMost(anyView);
        ArrayList<View> cands = new ArrayList<View>(4);
        collectAt(top, sx, sy, cands, 0);
        long screenArea = 0;
        try {
            screenArea = (long) top.getWidth() * top.getHeight();
        } catch (Throwable ignore) {
        }
        View best = null;
        long bestArea = -1;
        for (int i = 0; i < cands.size(); i++) {
            View v = cands.get(i);
            Rect r = new Rect();
            if (!v.getGlobalVisibleRect(r)) {
                continue;
            }
            long area = (long) r.width() * r.height();
            Bitmap bmp = imageOf(v);
            Util.log("cand#" + i + " " + v.getClass().getSimpleName()
                    + " rect=" + r.width() + "x" + r.height() + "@" + r.left + "," + r.top
                    + " area=" + area
                    + (bmp == null ? " bmp=null" : (" bmp=" + bmp.getWidth() + "x" + bmp.getHeight()))
                    + ((screenArea > 0 && area > screenArea * 7 / 10) ? " [bg-skip]" : ""));
            if (area <= 0) {
                continue;
            }
            if (screenArea > 0 && area > screenArea * 7 / 10) {
                continue;   // 背景层，不是用户想取的那张
            }
            if (area > bestArea) {
                bestArea = area;
                best = v;
            }
        }
        Util.log("pick point=" + (int) sx + "," + (int) sy
                + " best=" + (best == null ? "null" : best.getClass().getSimpleName())
                + " area=" + bestArea + " screen=" + screenArea
                + " hit=" + (hit == null ? "null" : hit.getClass().getSimpleName()));
        addUnique(out, imageOf(best));
        for (int i = 0; i < cands.size(); i++) {
            if (cands.get(i) != best) {
                addUnique(out, imageOf(cands.get(i)));
            }
        }

        // ③ 兜底：命中 View 子树里的 ImageView（含绘制期绑定的自绘内容）
        if (out.isEmpty()) {
            ArrayList<View> inTree = new ArrayList<View>(4);
            collectImageViews(hit, inTree, 0);
            for (int i = 0; i < inTree.size(); i++) {
                addUnique(out, imageOf(inTree.get(i)));
            }
        }
        return out;
    }

    /** 按位图对象身份去重，并过滤掉「不值得存」的图（加载转圈、小图标等）。 */
    private static void addUnique(ArrayList<Bitmap> out, Bitmap b) {
        if (!worthSaving(b) || out.size() >= 6) {
            return;
        }
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) == b) {
                return;
            }
        }
        out.add(b);
    }

    /**
     * 值不值得存：用「面积 + 最长边」判定，而不是最短边。
     * 这样既挡掉 48×48 的加载转圈/小图标，又不会误杀「又长又细」的横幅图。
     */
    private static boolean worthSaving(Bitmap b) {
        if (b == null || b.isRecycled()) {
            return false;
        }
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        long area = (long) w * h;
        int maxSide = Math.max(w, h);
        return area >= 10000L && maxSide >= 96;
    }

    private static View topMost(View v) {
        View cur = v;
        int guard = 0;
        while (cur != null && guard++ < 32) {
            Object p = cur.getParent();
            if (!(p instanceof View)) {
                return cur;
            }
            cur = (View) p;
        }
        return cur != null ? cur : v;
    }

    private static void collectAt(View v, float sx, float sy, ArrayList<View> out, int depth) {
        if (v == null || depth > 24 || out.size() > 12) {
            return;
        }
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r)) {
            return;
        }
        boolean contains = r.contains((int) sx, (int) sy);
        if (!contains) {
            return;
        }
        if (v instanceof ImageView && imageOf(v) != null) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAt(g.getChildAt(i), sx, sy, out, depth + 1);
            }
        }
    }

    private static Bitmap imageOf(View v) {
        if (!(v instanceof ImageView)) {
            return null;
        }
        Bitmap b = Util.drawableToBitmap(((ImageView) v).getDrawable());
        if (valid(b)) {
            return b;
        }
        Bitmap c = CaptureStore.latestFor(v, MAX_ENTRY_AGE_MS);
        return valid(c) ? c : null;
    }

    private static void collectImageViews(View v, ArrayList<View> out, int depth) {
        if (v == null || depth > 12 || out.size() > 8) {
            return;
        }
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (v instanceof ImageView) {
            if (imageOf(v) != null) {
                out.add(v);
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectImageViews(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    private static boolean valid(Bitmap b) {
        return b != null && !b.isRecycled();
    }

    // ---------- 5. 无控件归属的兜底捕获 ----------

    private static void hookBitmapFactory() {
        try {
            XposedHelpers.findAndHookMethod(BitmapFactory.class, "decodeStream",
                    InputStream.class, Rect.class, BitmapFactory.Options.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                CaptureStore.putRecentOnly((Bitmap) param.getResult(), "decodeStream");
                            } catch (Throwable ignore) {
                            }
                        }
                    });
        } catch (Throwable t) {
            Util.log("hook decodeStream FAIL: " + t);
        }
    }
}
