package com.dsh.picker;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** 极简说明页：这个模块没有开关，装上、启用、勾作用域即生效。 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setPadding(48, 72, 48, 72);
        tv.setTextSize(15f);
        tv.setText(
                "长按取图 PickPic\n\n" +
                "用法（三步）：\n" +
                "1. 在 Vector / LSPosed 里启用本模块\n" +
                "2. 作用域勾选你要取图的 App（勾完请强杀那个 App 再打开）\n" +
                "3. 在 App 里【长按图片】即可保存\n\n" +
                "保存位置：\n" +
                "  相册 / Pictures / 长按取图 / <来源包名> /\n\n" +
                "特点：\n" +
                "  · 直接取宿主控件里的原始 Bitmap，不截图、不裁剪\n" +
                "  · 长按在容器上时，按你的按点做控件树命中测试\n" +
                "  · 只保存你长按的那一张，不做后台全量抓取\n" +
                "  · 支持 HARDWARE 位图回读；无存储权限时用 root 兜底\n\n" +
                "如果长按没反应：\n" +
                "  · 确认该 App 在作用域里，且已被强杀重启\n" +
                "  · 部分 App 用自绘/跨平台框架，见后续版本（绘制上下文绑定）\n\n" +
                "版本 1.0  ·  作者：DSH");
        tv.setTextIsSelectable(false);
        sv.addView(tv);
        setContentView(sv);
    }
}
