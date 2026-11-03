package com.dsh.picker;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选面板：当指尖下有多张图片时，把候选缩略图列出来让用户点选。
 *
 * 为什么能弹出来：这段代码运行在宿主进程里，直接借宿主的 Activity 当上下文，
 * 弹的是宿主窗口内的 Dialog —— 不需要 SYSTEM_ALERT_WINDOW，也不受后台弹窗限制。
 */
public final class CandidateDialog {

    public interface OnPick {
        void onPick(Bitmap bitmap);
    }

    private CandidateDialog() {
    }

    public static void show(final Activity act, final List<Bitmap> items, final OnPick onPick) {
        if (act == null || items == null || items.isEmpty() || onPick == null) {
            return;
        }
        try {
            if (act.isFinishing() || act.isDestroyed()) {
                return;
            }
        } catch (Throwable ignore) {
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    build(act, items, onPick).show();
                } catch (Throwable t) {
                    Util.log("dialog: " + t);
                }
            }
        });
    }

    private static Dialog build(final Activity act, final List<Bitmap> items, final OnPick onPick) {
        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 14);
        root.setPadding(pad, dp(act, 10), pad, dp(act, 10));
        root.setBackgroundColor(0xF0202020);

        TextView title = new TextView(act);
        title.setText("选一张保存（共 " + items.size() + " 个候选）");
        title.setTextSize(14f);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(dp(act, 4), 0, 0, dp(act, 6));
        root.addView(title);

        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 缩略图按「原始宽高比」摆进一个正方形盒子：
        // 又长又细的图不会被裁成一小块方，而是等比缩到贴边。
        final int box = dp(act, 112);
        final int minSide = dp(act, 32);

        for (int i = 0; i < items.size(); i++) {
            final Bitmap bmp = items.get(i);
            int bw = Math.max(1, bmp.getWidth());
            int bh = Math.max(1, bmp.getHeight());
            float ar = (float) bw / (float) bh;
            int w;
            int h;
            if (ar >= 1f) {
                w = box;
                h = Math.max(minSide, Math.round(box / ar));
            } else {
                h = box;
                w = Math.max(minSide, Math.round(box * ar));
            }

            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF303030);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMargins(dp(act, 5), 0, dp(act, 5), 0);
            iv.setLayoutParams(lp);
            try {
                iv.setImageBitmap(bmp);
            } catch (Throwable ignore) {
            }
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignore) {
                    }
                    onPick.onPick(bmp);
                }
            });
            row.addView(iv);
        }
        scroll.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        TextView hint = new TextView(act);
        hint.setText("点空白处取消");
        hint.setTextSize(11f);
        hint.setTextColor(0xFFAAAAAA);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(act, 6), 0, 0);
        root.addView(hint);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.CENTER);
            w.setLayout(dp(act, 340), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    // ---------------- WebView 用：候选是 URL，不是 Bitmap ----------------

    public interface OnPickUrl {
        void onPickUrl(String url);

        void onAbort();
    }

    /**
     * 展示「这一页加载过的图片」让用户挑一张，挑中就下原图。
     *
     * 缩略图在后台线程下载（最多 6 张），下不下来就跳过；
     * 一张都拿不到时回 onAbort()，由调用方退到「整页渲染」兜底。
     */
    public static void showUrls(final Activity act, final List<String> urls,
                                final String referer, final OnPickUrl cb) {
        if (act == null || urls == null || urls.isEmpty() || cb == null) {
            cb.onAbort();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Bitmap> thumbs = new ArrayList<Bitmap>();
                final ArrayList<String> kept = new ArrayList<String>();
                for (int i = 0; i < urls.size() && thumbs.size() < 6; i++) {
                    String u = urls.get(i);
                    byte[] data = UrlSaver.fetch(act, u, referer);
                    Bitmap b = UrlSaver.decodeThumb(data, 360);
                    if (b == null) {
                        Util.log("thumb fail: " + WebUrlCache.shortUrl(u));
                        continue;
                    }
                    thumbs.add(b);
                    kept.add(u);
                }
                if (thumbs.isEmpty()) {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            cb.onAbort();
                        }
                    });
                    return;
                }
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            buildUrlDialog(act, thumbs, kept, cb).show();
                        } catch (Throwable t) {
                            Util.log("url dialog: " + t);
                            cb.onAbort();
                        }
                    }
                });
            }
        }, "PickPic-Thumbs").start();
    }

    private static Dialog buildUrlDialog(final Activity act, final List<Bitmap> thumbs,
                                         final List<String> urls, final OnPickUrl cb) {
        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));
        root.setBackgroundColor(0xF0202020);

        TextView title = new TextView(act);
        title.setText("本页图片（" + thumbs.size() + " 张），点一张存原图");
        title.setTextSize(14f);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(dp(act, 4), 0, 0, dp(act, 6));
        root.addView(title);

        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final int box = dp(act, 112);
        final int minSide = dp(act, 32);
        for (int i = 0; i < thumbs.size(); i++) {
            final Bitmap bmp = thumbs.get(i);
            final String url = urls.get(i);
            int bw = Math.max(1, bmp.getWidth());
            int bh = Math.max(1, bmp.getHeight());
            float ar = (float) bw / (float) bh;
            int w;
            int h;
            if (ar >= 1f) {
                w = box;
                h = Math.max(minSide, Math.round(box / ar));
            } else {
                h = box;
                w = Math.max(minSide, Math.round(box * ar));
            }
            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF303030);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMargins(dp(act, 5), 0, dp(act, 5), 0);
            iv.setLayoutParams(lp);
            try {
                iv.setImageBitmap(bmp);
            } catch (Throwable ignore) {
            }
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignore) {
                    }
                    cb.onPickUrl(url);
                }
            });
            row.addView(iv);
        }
        scroll.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        TextView hint = new TextView(act);
        hint.setText("点空白处取消");
        hint.setTextSize(11f);
        hint.setTextColor(0xFFAAAAAA);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(act, 6), 0, 0);
        root.addView(hint);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setGravity(Gravity.CENTER);
            w.setLayout(dp(act, 340), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static int dp(Activity act, int value) {
        return (int) (value * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
