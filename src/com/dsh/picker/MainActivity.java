package com.dsh.picker;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 说明页。
 *
 * 排序按「用户第一次打开时最需要知道的」来：先让他去设作用域（不设就完全没效果），
 * 再讲一句功能，最后才是细节。这个模块本身没有开关，装上 + 启用 + 勾作用域即生效。
 */
public class MainActivity extends Activity {

    private TextView section(String text, int sizeSp, int topDp) {
        TextView tv = new TextView(this);
        tv.setTextSize(sizeSp);
        tv.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        return tv;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(36));

        // ── 标题 ──
        TextView title = section("长按取图 · PickPic", 24, 0);
        title.setGravity(Gravity.START);
        root.addView(title);

        // ── ① 作用域（最重要，放最前面）──
        root.addView(section("① 先去勾作用域（不勾＝完全没效果）", 17, 22));
        root.addView(section(
                "打开 Vector / LSPosed 管理器 → 模块 → 长按取图 → 作用域。\n\n"
                        + "推荐【全选】。至少要勾上你想取图的那些 App。\n\n"
                        + "勾完必须把目标 App 强制停止再重新打开（或者直接重启手机）。\n"
                        + "作用域只对新启动的进程生效 —— 已经开着的 App 不重启是不会生效的，"
                        + "这一点最容易漏。", 15, 8));

        // ── ② 功能 ──
        root.addView(section("② 它能干什么", 17, 26));
        root.addView(section(
                "在任意界面【长按约 0.6 秒】，就会把指尖下的图片 / 控件保存成一个图片文件。\n\n"
                        + "· 取的是控件里那张原始位图，不是截屏、不做裁剪\n"
                        + "· 存好立刻弹出系统分享面板，可以直接发给微信、抖音等\n"
                        + "· 一次只存你按下去的那一张，后台不做任何全量抓取\n"
                        + "· 网页里的图会尽量取原图（含 APK 内置资源、内联 base64）", 15, 8));

        // ── ③ 保存位置 ──
        root.addView(section("③ 图存在哪", 17, 26));
        root.addView(section(
                "相册 / Pictures / 长按取图 / <来源包名> /\n\n"
                        + "同名同内容的文件不会重复存，自动去重。", 15, 8));

        // ── ④ 没反应怎么办 ──
        root.addView(section("④ 长按没反应？按这个顺序查", 17, 26));
        root.addView(section(
                "1. 那个 App 在作用域里吗？\n"
                        + "2. 勾完之后把那个 App 强停重启了吗？（这一步最常被漏掉）\n"
                        + "3. 是不是按在纯文字 / 空白处？那里没有图可存，会给一句提示。\n"
                        + "4. 极少数完全自绘的界面取不到，属于已知限制。", 15, 8));

        // ── 作者 ──
        root.addView(section("作者：Wanan（dsh）", 15, 30));
        root.addView(section("版本 1.9", 13, 6));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }
}
