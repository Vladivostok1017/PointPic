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

    // ---------- v1.8：Window 级输入钩子 ----------
    /**
     * 一旦 Window 级钩子装上，它就是**唯一**的触摸来源。
     * View.dispatchTouchEvent 与 performLongClick 两条老路径必须让位，
     * 否则同一次手势会被处理两遍（存两张图）。
     */
    private static volatile boolean INPUT_HOOKED;

    /** 本次手势的 downTime，用来判断「要吞的是不是我们自己抓过的那一次」的 UP。 */
    private static long firedDownTime = -1;

    /** 屏幕坐标的按下点，只用于位移判定。 */
    private static float rawDownX, rawDownY;

    /** 当前手势的 downTime（MotionEvent.getDownTime()），用于吞 UP 时精确匹配。 */
    private static long downEventTime = -1;

    /** 诊断：窗口级 DOWN 日志节流 + mView 取失败只报一次。 */
    private static long lastWinDownLog;
    private static boolean mViewFailLogged;

    /** 到点即触发：此时手指仍按在屏幕上，提示会立刻出现，符合「长按出结果」的直觉。 */
    private static final Runnable LONG_PRESS_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            try {
                if (downAt == 0 || longPressFired) {
                    return;
                }
                longPressFired = true;
                // 注意：这里**不能**再写 lastGestureHandledAt。
                // triggerAt() 自己的第一条守卫就是 `now - lastGestureHandledAt < 500 → return`，
                // 先置位等于把自己挡在门外（v1.7 之前定时器路径一直没生效、全靠
                // performLongClick 兜底在跑，就是这个原因）。由 triggerAt 自己置位即可。
                // 记下这次手势，供 processPointerEvent 精确吞掉它的 UP
                firedDownTime = downEventTime;
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
        hookWebResources(lp.classLoader);
        // 放在最后：它一旦成功，上面 hookTouch/hookLongClickFallback 就自动让位
        installWindowInputHook(lp.classLoader);

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

        // Window 级钩子已经在更上游接管了长按判定：这里必须让位，
        // 否则同一次手势会跑两遍定时器 → 存两张图。
        if (INPUT_HOOKED) {
            return;
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

    // ---------- 1b. Window 级输入钩子（救「整条 dispatchTouchEvent 被覆写」的页面） ----------
    //
    // 为什么必须下沉：View.dispatchTouchEvent 只是基类方法。宿主任何一层
    //   · 覆写后 return true 不调 super
    //   · 或自己做命中测试、直接 child.onTouchEvent(ev)
    // 都会让基类钩子一次都不执行（小米商城某些页面：日志里连 decide 都没有）。
    //
    // 但 App 可以选择不把事件交给自己的 View，**不能不把事件交给系统**。
    // ViewRootImpl 在 App 代码之前，且每个窗口恒有，是最稳的一层。
    private static void installWindowInputHook(ClassLoader cl) {
        if (INPUT_HOOKED) {
            return;
        }
        // ① 首选 ViewRootImpl.enqueueInputEvent(InputEvent, InputEventReceiver, int, boolean)
        //    覆盖本进程所有窗口（Activity / Dialog / PopupWindow），与根 View 是什么类无关
        try {
            Class<?> vr = XposedHelpers.findClass("android.view.ViewRootImpl", cl);
            // InputEventReceiver 是 @hide，android.jar 里没有，只能按名字取
            Class<?> ieCls = XposedHelpers.findClass("android.view.InputEvent", cl);
            Class<?> ierCls = XposedHelpers.findClass("android.view.InputEventReceiver", cl);
            XposedHelpers.findAndHookMethod(vr, "enqueueInputEvent",
                    ieCls,
                    ierCls,
                    int.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object ev = param.args[0];
                                if (!(ev instanceof MotionEvent)) {
                                    return;     // KeyEvent 不关心
                                }
                                // mView = 这一窗的根 View（DecorView / PopupDecorView / 任意实现）
                                View root = null;
                                try {
                                    root = (View) XposedHelpers.getObjectField(
                                            param.thisObject, "mView");
                                } catch (Throwable t) {
                                    if (!mViewFailLogged) {
                                        mViewFailLogged = true;
                                        Util.log("mView lookup FAIL: " + t);
                                    }
                                }
                                handleWindowTouch(root, (MotionEvent) ev);
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            INPUT_HOOKED = true;
            Util.log("hook ViewRootImpl.enqueueInputEvent ok (window-level)");
            return;
        } catch (Throwable t) {
            Util.log("enqueueInputEvent FAIL: " + t);
        }
        // ② 次选 WindowInputEventReceiver.onInputEvent（入队最早的点）
        try {
            Class<?> wer = XposedHelpers.findClass(
                    "android.view.ViewRootImpl$WindowInputEventReceiver", cl);
            Class<?> ieCls2 = XposedHelpers.findClass("android.view.InputEvent", cl);
            XposedHelpers.findAndHookMethod(wer, "onInputEvent",
                    ieCls2, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object ev = param.args[0];
                                if (!(ev instanceof MotionEvent)) {
                                    return;
                                }
                                Object vr = XposedHelpers.getObjectField(
                                        param.thisObject, "this$0");
                                View root = (View) XposedHelpers.getObjectField(vr, "mView");
                                handleWindowTouch(root, (MotionEvent) ev);
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            INPUT_HOOKED = true;
            Util.log("hook WindowInputEventReceiver.onInputEvent ok");
            return;
        } catch (Throwable t) {
            Util.log("onInputEvent FAIL: " + t);
        }
        // ③ 末选 DecorView.dispatchTouchEvent（漏 PopupWindow，但比没有好）
        try {
            Class<?> decor = XposedHelpers.findClass(
                    "com.android.internal.policy.DecorView", cl);
            XposedHelpers.findAndHookMethod(decor, "dispatchTouchEvent",
                    MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                handleWindowTouch((View) param.thisObject,
                                        (MotionEvent) param.args[0]);
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            INPUT_HOOKED = true;
            Util.log("hook DecorView.dispatchTouchEvent ok");
        } catch (Throwable t) {
            Util.log("DecorView FAIL: " + t);
        }
    }

    /**
     * 解析 Window 级触摸。这里是**屏幕坐标**（事件到达 ViewRootImpl 时
     * xOffset/yOffset 已经加好），所以先换算成根 View 的本地坐标再交给 triggerAt。
     */
    private static void handleWindowTouch(View root, MotionEvent ev) {
        if (root == null || ev == null) {
            return;
        }
        int action = ev.getActionMasked();
        float rx = ev.getRawX();
        float ry = ev.getRawY();

        if (action == MotionEvent.ACTION_DOWN) {
            // 诊断：证明窗口级钩子真的收到了事件（每个 DOWN 只打一行）
            long tn = SystemClock.uptimeMillis();
            if (tn - lastWinDownLog > 1500) {
                lastWinDownLog = tn;
                Util.log("win DOWN raw=" + (int) rx + "," + (int) ry
                        + " root=" + (root == null ? "null" : root.getClass().getName()));
            }
            // 同一个 DOWN 可能被重排队（输入法调整窗口等），别把计时器重启
            if (downAt != 0 && ev.getDownTime() == downEventTime) {
                return;
            }
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            downAt = SystemClock.uptimeMillis();
            downRoot = root;
            downX = rx - loc[0];        // triggerAt 要的是根 View 本地坐标
            downY = ry - loc[1];
            rawDownX = rx;
            rawDownY = ry;
            downEventTime = ev.getDownTime();
            longPressFired = false;
            handler().removeCallbacks(LONG_PRESS_RUNNABLE);
            handler().postDelayed(LONG_PRESS_RUNNABLE, LONG_PRESS_MS);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (downAt != 0
                    && (Math.abs(rx - rawDownX) > MAX_MOVE_PX
                    || Math.abs(ry - rawDownY) > MAX_MOVE_PX)) {
                long tn = SystemClock.uptimeMillis();
                if (tn - lastWinDownLog > 800) {
                    lastWinDownLog = tn;
                    Util.log("win cancel by MOVE dx=" + (int) (rx - rawDownX)
                            + " dy=" + (int) (ry - rawDownY));
                }
                cancelPendingLongPress();
            }
        } else {
            // UP / CANCEL：
            //  ① 定时器没来得及跑（主线程被阻塞，DOWN..UP 挤在同一批里处理）时，
            //     这里按「实际按住时长」补判一次 —— 否则用户明明按够了，却是空手。
            //  ② 否则只收尾。
            long held = downAt == 0 ? 0 : SystemClock.uptimeMillis() - downAt;
            if (downAt != 0 && !longPressFired && held >= LONG_PRESS_MS) {
                handler().removeCallbacks(LONG_PRESS_RUNNABLE);
                longPressFired = true;
                firedDownTime = downEventTime;
                View r = downRoot;
                float lx = downX;
                float ly = downY;
                downAt = 0;
                downRoot = null;
                Util.log("long press settled on UP held=" + held + "ms");
                triggerAt(r, lx, ly);
                return;
            }
            cancelPendingLongPress();
        }
    }

    private static void cancelPendingLongPress() {
        handler().removeCallbacks(LONG_PRESS_RUNNABLE);
        downAt = 0;
        downRoot = null;
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

    // ---------- 5. WebView 资源钩子：拿到页面加载过的图片原始 URL ----------

    /**
     * 钩 `WebViewClient.onLoadResource(webView, url)` —— 页面每加载一个资源都会回调。
     * 原生内核与腾讯 X5（高德/小米商城用）各一套，X5 的类不同包，用 findClassIfExists 拿。
     */
    private static void hookWebResources(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(android.webkit.WebViewClient.class,
                    "onLoadResource", android.webkit.WebView.class, String.class,
                    resourceHook());
            Util.log("hook onLoadResource(stock) ok");
        } catch (Throwable t) {
            Util.log("hook onLoadResource(stock) FAIL: " + t);
        }
        try {
            Class<?> client = XposedHelpers.findClassIfExists(
                    "com.tencent.smtt.sdk.WebViewClient", cl);
            Class<?> webView = XposedHelpers.findClassIfExists(
                    "com.tencent.smtt.sdk.WebView", cl);
            if (client != null && webView != null) {
                XposedHelpers.findAndHookMethod(client, "onLoadResource",
                        webView, String.class, resourceHook());
                Util.log("hook onLoadResource(x5) ok");
            } else {
                // X5（TBS）的类是运行时从独立 dex 动态加载的，此刻还不存在
                Util.log("x5 classes absent -> 安装延迟钩子");
                hookX5Lazy();
            }
        } catch (Throwable t) {
            Util.log("hook onLoadResource(x5) FAIL: " + t);
        }
    }

    /**
     * 腾讯 X5（TBS）的类由它自己的 ClassLoader 在运行时加载，
     * 所以只能盯着 ClassLoader.loadClass：名字一出现就挂 onLoadResource。
     */
    private static void hookX5Lazy() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object n = param.args[0];
                                if (!(n instanceof String)) {
                                    return;
                                }
                                String name = (String) n;
                                if (!name.startsWith("com.tencent.smtt.sdk.WebViewClient")) {
                                    return;
                                }
                                Object r = param.getResult();
                                if (r instanceof Class) {
                                    installX5Hook((Class<?>) r);
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            Util.log("hook ClassLoader.loadClass ok");
        } catch (Throwable t) {
            Util.log("hook loadClass FAIL: " + t);
        }
    }

    private static final java.util.HashSet<String> X5_HOOKED = new java.util.HashSet<String>();

    private static void installX5Hook(Class<?> clientCls) {
        try {
            String key = clientCls.getName();
            synchronized (X5_HOOKED) {
                if (X5_HOOKED.contains(key)) {
                    return;
                }
                X5_HOOKED.add(key);
            }
            ClassLoader cl = clientCls.getClassLoader();
            Class<?> wv = null;
            try {
                wv = Class.forName("com.tencent.smtt.sdk.WebView", false, cl);
            } catch (Throwable ignore) {
            }
            if (wv == null) {
                Util.log("x5 client found but WebView class missing");
                return;
            }
            XposedHelpers.findAndHookMethod(clientCls, "onLoadResource",
                    wv, String.class, resourceHook());
            Util.log("hook onLoadResource(x5-lazy) ok: " + key);
        } catch (Throwable t) {
            Util.log("installX5Hook FAIL: " + t);
        }
    }

    private static XC_MethodHook resourceHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object wv = param.args[0];
                    Object u = param.args[1];
                    if (wv instanceof View && u instanceof String) {
                        WebUrlCache.put((View) wv, (String) u);
                    }
                } catch (Throwable ignore) {
                }
            }
        };
    }

    // ---------- 6. 兜底：宿主自己走 performLongClick 的情况 ----------

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
        decide(root, sx, sy, hit, INPUT_HOOKED ? "window" : "timer");
    }

    /** 两条触发路径（定时器 / performLongClick 兜底）共用同一套判定。 */
    private static void decide(View root, float sx, float sy, View hit, String via) {
        long now = SystemClock.uptimeMillis();
        lastSaveAt = now;
        lastGestureHandledAt = now;
        Util.log("decide via=" + via + " point=" + (int) sx + "," + (int) sy
                + " hit=" + (hit == null ? "null" : hit.getClass().getName()));

        // ---- ① 原生 ImageView 候选优先 ----
        // 浮在 WebView 之上的原生图标（例如协议页的定位/存储图标）必须走这条，
        // 否则会被 WebView 分支抢走，最后只能落到"整页渲染"。
        ArrayList<Bitmap> cands = pickCandidates(root, sx, sy, hit);
        if (!cands.isEmpty()) {
            final String from = (hit != null ? hit.getClass().getSimpleName() : "root")
                    + "@" + (int) sx + "," + (int) sy;
            final View anchor = hit != null ? hit : root;
            if (cands.size() == 1) {
                Util.log("save(view) " + from);
                doSave(anchor, cands.get(0), from);
                return;
            }
            Activity act = ActivityTracker.current();
            Util.log("candidates=" + cands.size() + " activity=" + (act != null));
            if (act == null) {
                doSave(anchor, cands.get(0), from + " (largest)");
                return;
            }
            final ArrayList<Bitmap> list = cands;
            CandidateDialog.show(act, list, new CandidateDialog.OnPick() {
                @Override
                public void onPick(Bitmap bitmap) {
                    doSave(anchor, bitmap, from + " (picked)");
                }
            });
            return;
        }

        // ---- ② 没有 ImageView 才轮到 WebView ----
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
                    // A. JS 精确命中指尖下的图片元素
                    if (url != null && !url.startsWith("#near|")) {
                        Util.log("web hit -> " + url);
                        UrlSaver.saveFromUrlAsync(wctx, url, pageUrl, wctx.getPackageName());
                        return;
                    }
                    // B. 精确命中失败（图标是 CSS background-image、或指尖落在文字的空白处）
                    //    → 用「离指尖最近的几张图」让用户挑，这比一句提示有用得多
                    final ArrayList<String> picked = new ArrayList<String>();
                    if (url != null) {
                        picked.addAll(WebGrabber.parseList(url));
                        Util.log("web near=" + picked.size());
                    }
                    // C. 再退到 onLoadResource 缓存
                    if (picked.isEmpty()) {
                        picked.addAll(WebUrlCache.recent(webView, 8));
                        Util.log("web url-cache=" + picked.size());
                    }
                    if (picked.isEmpty()) {
                        // D. 真的没有图片资源 → 矢量重绘，再不行才提示
                        snapshotFallback(webView, sx, sy);
                        return;
                    }
                    if (picked.size() == 1) {
                        UrlSaver.saveFromUrlAsync(wctx, picked.get(0), pageUrl,
                                wctx.getPackageName());
                        return;
                    }
                    Activity act = ActivityTracker.current();
                    if (act == null) {
                        UrlSaver.saveFromUrlAsync(wctx, picked.get(0), pageUrl,
                                wctx.getPackageName());
                        return;
                    }
                    CandidateDialog.showUrls(act, picked, pageUrl,
                            new CandidateDialog.OnPickUrl() {
                                @Override
                                public void onPickUrl(String u) {
                                    Util.log("web pick -> " + WebUrlCache.shortUrl(u));
                                    UrlSaver.saveFromUrlAsync(wctx, u, pageUrl,
                                            wctx.getPackageName());
                                }

                                @Override
                                public void onAbort() {
                                    snapshotFallback(webView, sx, sy);
                                }
                            });
                }
            });
            return;
        }

        // ---- ③ 不是 WebView 也没有缓存位图：矢量重绘 ----
        View small = smallestViewAt(root, sx, sy);
        if (small != null) {
            Bitmap redraw = renderViewScaled(small, 3);
            if (worthSaving(redraw)) {
                Util.log("vector-redraw " + small.getClass().getName()
                        + " -> " + redraw.getWidth() + "x" + redraw.getHeight());
                doSave(small, redraw, "vector-redraw(3x)");
                return;
            }
        }
        Util.log("skip: 指尖下没有可确定的图片 (" + (int) sx + "," + (int) sy + ")");
        Util.toast(appContext, "这一处没有可保存的图片内容");
    }

    /** 由 performLongClick 触发 —— 仅在「定时器路径没有处理过这次手势」时兜底。 */
    private static void triggerOn(View v) {
        if (v == null) {
            return;
        }
        // 注：Window 级钩子生效时这条兜底也不再关掉。
        // 它自带「1.5s 内定时器处理过就退出」的互斥，重复触发不会发生；
        // 留着是为了在 Window 级钩子被 ROM 改签名挡掉的机型上仍有退路。
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

    // ---------- 矢量重绘通道：解决「图标/矢量图形没有图片文件」 ----------

    /**
     * 把某个控件**重绘**到一张放大后的 Bitmap。
     *
     * 和"截图裁剪"的本质区别：
     *  - 截图：取屏幕像素，受屏幕分辨率限制，控件形状不规则就废；
     *  - 重绘：让控件在任意尺寸的画布上重新绘制一次，矢量图形可以超采样，
     *    得到比屏幕更清晰的图（Path/文字/渐变都不会糊）。
     *
     * 必须在主线程调用（触摸回调里天然满足）。
     */
    private static Bitmap renderViewScaled(View v, int scale) {
        if (v == null || scale < 1) {
            return null;
        }
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            Bitmap bmp = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.scale(scale, scale);
            v.draw(c);
            if (isBlank(bmp)) {
                return null;
            }
            return bmp;
        } catch (Throwable t) {
            Util.log("renderViewScaled: " + t);
            return null;
        }
    }

    /** 全透明就认为没画出东西。 */
    private static boolean isBlank(Bitmap b) {
        try {
            int stepX = Math.max(1, b.getWidth() / 12);
            int stepY = Math.max(1, b.getHeight() / 12);
            for (int y = 0; y < b.getHeight(); y += stepY) {
                for (int x = 0; x < b.getWidth(); x += stepX) {
                    if ((b.getPixel(x, y) >>> 24) != 0) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从窗口根开始，用屏幕坐标找「包含该点、面积最小」的控件（不限类型）。 */
    private static View smallestViewAt(View anyView, float sx, float sy) {
        View top = topMost(anyView);
        View best = null;
        long bestArea = Long.MAX_VALUE;
        long screenArea = 0;
        try {
            screenArea = (long) top.getWidth() * top.getHeight();
        } catch (Throwable ignore) {
        }
        ArrayList<View> all = new ArrayList<View>(8);
        collectAllAt(top, sx, sy, all, 0);
        for (int i = 0; i < all.size(); i++) {
            View v = all.get(i);
            Rect r = new Rect();
            if (!v.getGlobalVisibleRect(r)) {
                continue;
            }
            long area = (long) r.width() * r.height();
            if (area <= 0) {
                continue;
            }
            // 太小（几个像素）和太大（几乎整屏的容器）都排除
            if (area < 24 * 24) {
                continue;
            }
            if (screenArea > 0 && area > screenArea * 4 / 10) {
                continue;
            }
            if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 3) {
                continue;   // 复杂容器重绘没意义
            }
            if (area < bestArea) {
                bestArea = area;
                best = v;
            }
        }
        return best;
    }

    private static void collectAllAt(View v, float sx, float sy,
                                     ArrayList<View> out, int depth) {
        if (v == null || depth > 24 || out.size() > 24) {
            return;
        }
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r) || !r.contains((int) sx, (int) sy)) {
            return;
        }
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAllAt(g.getChildAt(i), sx, sy, out, depth + 1);
            }
        }
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
     * 所有通道都失败时的最后一招：**矢量重绘**。
     *
     * 图标字体、内联 SVG、Path/Canvas 画的图标 —— 它们在页面里没有图片文件，
     * 但都对应某个真实控件。把这个控件在放大 3 倍的画布上重画一遍，
     * 就能得到比屏幕更清晰的 PNG（矢量超采样，不是截图裁剪）。
     *
     * 再不行才提示用户。
     */
    private static void snapshotFallback(View anchor, float sx, float sy) {
        try {
            View small = smallestViewAt(anchor, sx, sy);
            if (small != null) {
                Bitmap redraw = renderViewScaled(small, 3);
                if (worthSaving(redraw)) {
                    Util.log("vector-redraw " + small.getClass().getName()
                            + " -> " + redraw.getWidth() + "x" + redraw.getHeight());
                    doSave(small, redraw, "vector-redraw(3x)");
                    return;
                }
                Util.log("vector-redraw blank: " + small.getClass().getName());
            }
            Util.log("web: 无 URL 可下，且重绘无内容");
            Context ctx = anchor != null ? anchor.getContext() : appContext;
            Util.toast(ctx, "这一处没有可保存的图片内容");
        } catch (Throwable t) {
            Util.log("snapshotFallback: " + t);
        }
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
