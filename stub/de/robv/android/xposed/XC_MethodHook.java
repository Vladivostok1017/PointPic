// SPDX-License-Identifier: GPL-3.0-only
// PointPic —— 长按取图 | Copyright (C) 2026 Wanan (dsh)
// 完整许可条款见仓库根目录 LICENSE（GPL-3.0）。
package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * 编译期桩：签名必须与 Xposed/Vector 运行时提供的一致。
 * 本类不会被打进 APK（仅 -cp 引用）。
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }

        public Object getObjectExtra(String key) {
            return null;
        }

        public void setObjectExtra(String key, Object o) {
        }
    }

    public class Unhook {
        public void unhook() {
        }
    }
}
