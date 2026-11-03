package com.dsh.picker;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 捕获池：核心是「控件身份 → 位图」的绑定表。
 *
 * 键 = System.identityHashCode(View)，同一进程内唯一，因此用户长按某个控件时
 * 可以 O(1) 精确定位到该控件最后一次显示的位图，不需要任何图像相似度匹配。
 */
public final class CaptureStore {

    private static final int MAX_VIEWS = 500;
    private static final int MAX_PER_VIEW = 4;
    private static final int MAX_RECENT = 24;

    /** 按控件身份归档（accessOrder=true 便于 LRU 淘汰） */
    private static final LinkedHashMap<Integer, ArrayDeque<Entry>> VIEWS =
            new LinkedHashMap<Integer, ArrayDeque<Entry>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, ArrayDeque<Entry>> eldest) {
                    return size() > MAX_VIEWS;
                }
            };

    /** 无控件归属的全局最近队列（BitmapFactory 等 hook 的兜底） */
    private static final ArrayDeque<Entry> RECENT = new ArrayDeque<Entry>();

    private CaptureStore() {
    }

    public static final class Entry {
        public final WeakReference<Bitmap> ref;
        public final String viewClass;
        public final long ts;
        public final int width;
        public final int height;

        Entry(Bitmap b, String cls) {
            this.ref = new WeakReference<Bitmap>(b);
            this.viewClass = cls;
            this.ts = SystemClock.uptimeMillis();
            this.width = b.getWidth();
            this.height = b.getHeight();
        }

        public Bitmap get() {
            Bitmap b = ref.get();
            if (b == null || b.isRecycled()) {
                return null;
            }
            return b;
        }
    }

    /** 记录「这个控件显示了这张图」。 */
    public static void put(View view, Bitmap bitmap) {
        if (view == null || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        if (bitmap.getWidth() < 32 || bitmap.getHeight() < 32) {
            return;
        }
        try {
            int key = System.identityHashCode(view);
            String cls = view.getClass().getName();
            synchronized (VIEWS) {
                ArrayDeque<Entry> q = VIEWS.get(key);
                if (q == null) {
                    q = new ArrayDeque<Entry>(MAX_PER_VIEW);
                    VIEWS.put(key, q);
                }
                q.addFirst(new Entry(bitmap, cls));
                while (q.size() > MAX_PER_VIEW) {
                    q.removeLast();
                }
            }
            pushRecent(bitmap, cls);
        } catch (Throwable t) {
            Util.log("store.put: " + t);
        }
    }

    /** 无控件归属时只进全局队列。 */
    public static void putRecentOnly(Bitmap bitmap, String source) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        if (bitmap.getWidth() < 64 || bitmap.getHeight() < 64) {
            return;
        }
        pushRecent(bitmap, source);
    }

    private static void pushRecent(Bitmap b, String src) {
        synchronized (RECENT) {
            RECENT.addFirst(new Entry(b, src));
            while (RECENT.size() > MAX_RECENT) {
                RECENT.removeLast();
            }
        }
    }

    /** 查该控件最近一次显示的位图。 */
    public static Bitmap latestFor(View view) {
        return latestFor(view, 0L);
    }

    /**
     * 查该控件最近一次显示的位图，且限制「这条绑定有多新」。
     * RecyclerView 复用会让旧绑定停留在同一个 identityHashCode 上，
     * 超过 maxAgeMs 的绑定不可采信（返回 null 而不是错图）。
     */
    public static Bitmap latestFor(View view, long maxAgeMs) {
        if (view == null) {
            return null;
        }
        try {
            int key = System.identityHashCode(view);
            long now = SystemClock.uptimeMillis();
            synchronized (VIEWS) {
                ArrayDeque<Entry> q = VIEWS.get(key);
                if (q == null) {
                    return null;
                }
                for (Entry e : q) {
                    Bitmap b = e.get();
                    if (b != null) {
                        if (maxAgeMs > 0 && (now - e.ts) > maxAgeMs) {
                            return null;
                        }
                        return b;
                    }
                }
            }
        } catch (Throwable t) {
            Util.log("store.latestFor: " + t);
        }
        return null;
    }

    /** 该控件身份最近显示的位图（含尺寸信息，供面板展示用）。 */
    public static Entry latestEntryFor(View view) {
        if (view == null) {
            return null;
        }
        synchronized (VIEWS) {
            ArrayDeque<Entry> q = VIEWS.get(System.identityHashCode(view));
            if (q == null) {
                return null;
            }
            for (Entry e : q) {
                if (e.get() != null) {
                    return e;
                }
            }
        }
        return null;
    }

    /** 全局最近一张（最后手段）。 */
    public static Bitmap latestRecent() {
        synchronized (RECENT) {
            for (Entry e : RECENT) {
                Bitmap b = e.get();
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    public static int viewCount() {
        synchronized (VIEWS) {
            return VIEWS.size();
        }
    }

    public static int recentCount() {
        synchronized (RECENT) {
            return RECENT.size();
        }
    }
}
