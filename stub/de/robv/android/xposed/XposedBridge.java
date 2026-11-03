package de.robv.android.xposed;

/**
 * 编译期桩：真实实现在 Vector / LSPosed 运行时。
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }
}
