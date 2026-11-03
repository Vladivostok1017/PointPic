# PickPic · 长按取图（LSPosed / Vector 模块）

**只保存你长按的那一张，不做后台全量抓取，不截图、不裁剪。**

当前版本：**v1.2** ｜ 新增：剪贴板投递、绘制上下文绑定、多候选点选面板

---

## 1. 它解决了什么

PicCatcher / Evo-PicCatcher 那类模块是「全自动抓取」：刷一遍信息流，相册里多出上千张你并不想要的图。
本模块把口径改成 **手动点选** —— 用户长按哪个控件，就保存那个控件当前显示的那张**原始 Bitmap 对象**。

输出位置：

```
/sdcard/Pictures/长按取图/<来源包名>/<内容MD5>.png
```

## 2. 用法

1. 安装 `PickPic.apk`
2. 在 **Vector / LSPosed** 里启用模块
3. **作用域**勾选目标 App（勾完必须强杀该 App 再打开）
4. 在 App 里**长按任意图片**即可

> KernelSU 环境需先装 ZygiskNext 再由 LSPosed/Vector 加载模块（本机为 Vector v2.2）。

也可以用 CLI 配作用域（免手点）：

```bash
adb shell su -c "/data/adb/lspd/cli scope add com.dsh.picker com.miui.gallery/0"
adb shell su -c "/data/adb/lspd/cli modules enable com.dsh.picker"
```

## 3. 实现要点（这是本模块和「截图派」的根本区别）

| 机制 | 做法 | 收益 |
|---|---|---|
| **身份绑定** | hook `ImageView.setImageBitmap/setImageDrawable`，记录 `System.identityHashCode(View) → Bitmap` | 用户长按某控件时 **O(1) 精确取回**，不做任何图像相似度匹配 |
| **自实现长按判定** | 在 `View.dispatchTouchEvent` 里自己判「按住 ≥600ms 且位移 <25px」 | 不依赖宿主调用 `performLongClick()`（大量自定义控件会覆写它，钩基类会漏） |
| **CANCEL 也要触发** | 长按被宿主消费时框架派发的是 `ACTION_CANCEL` 而非 `ACTION_UP` | 否则「相册进入选中态」这类长按永远收不到 |
| **控件树命中测试** | 用按下坐标在 View 树里找最深的 `ImageView` | 长按落在卡片/容器上时，仍能找到用户真正指的那张图 |
| **三级落盘** | MediaStore 插入 → root `cp` → 直接 File 写 | 宿主无存储权限也能保存（实测宿主权限常为 denied） |
| **HARDWARE 位图回读** | `Canvas.drawBitmap(hw, ...)` 到 ARGB_8888 | 视频帧/HWUI 位图不会因 `getPixel` 崩 |

> 全程不产生屏幕像素副本，因此**不受控件形状（圆角/异形/遮罩）影响**，也不会把水印层、浮层、背景一起拍进来。

## 4. 已验证

- 设备：Xiaomi M2002J9E（Android 12 / Magisk 30.7 / Vector v2.2）
- 用例：相册网格长按 → 产出 `241×445 PNG`（该格子的原始缩略位图对象）
- 日志证据：`[PickPic] SAVE 241x445 from RelativeLayout`

## 5. 已知边界（v1.1）

| 情况 | 现状 | 计划 |
|---|---|---|
| 网格/列表里的缩略图 | 拿到的是 App 加载的那张缩略位图（尺寸小是宿主自己的选择） | 大图页/详情页通常是更大位图，效果更好 |
| **一个点上叠着多张图**（大图 + 角标/头像/遮罩） | 按「面积最大且未超屏幕 70%」挑选；仍可能不符合预期 | **v1.2 改成候选面板**：列出指尖下所有候选缩略图，让用户点选确认（彻底消除歧义） |
| 自定义/自绘控件（SurfaceView、Flutter、Compose、Canvas 直绘） | 拿不到 → 直接放弃（不再猜） | v1.2 加「绘制上下文绑定」：hook `View.draw(Canvas)` + `Canvas.drawImage`，把 Bitmap 按 View 归属 |
| WebView 内图片 | 只能取到 WebView 里的 ImageView（通常是图标级） | v1.3 走 `capturePicture` |
| 同一张图重复保存 | 已修：按内容哈希查重，并清理 MediaStore 过期行 | — |

---

## 6. 两个高频疑问

### Q1：长按会不会和 App 自己的点击/长按监听冲突？

**不会。** 本模块的 hook 挂在 `View.dispatchTouchEvent` 的 **before**，
只读 `MotionEvent`，**不 `setResult`、不消费、不改变返回值**，也没有拦截任何 `OnClickListener`。

| 用户操作 | App 原本行为 | 本模块 |
|---|---|---|
| 短按（< 600ms） | 照常 `onClick` | **完全不做任何事** |
| 长按（≥ 600ms） | 照常弹菜单 / 进选中态 | 额外保存一张图 |

也就是说：App 该怎么响应还怎么响应，本模块只在旁边"顺手"存一张。

> 注意一个语义区别：`performLongClick` 是「App 自己决定要处理长按」时才调用，
> 而大量自定义控件（相册网格、RecyclerView item、WebView）**会覆写它或在更上层拦截手势**，
> 所以本模块不依赖它，而是自己判定长按 —— 这也是为什么"相册进入选中态"时也能取到图。

### Q2：为什么"点的和拿到的"曾经不一样？现在怎么保证？

v1.0 有三处在**猜**：向上爬任意祖先找图（会抓到卡片背景）、全局「最近一张」兜底（会拿到无关图）、
控件复用后映射过期（会拿到同位置的上一张）。v1.1 全部改掉：

| 机制 | 说明 |
|---|---|
| **fail-closed** | 只有「指尖下确实存在一个可确定的 ImageView」才保存；否则**什么都不存**（宁缺勿错） |
| **屏幕坐标判定** | 用 `getGlobalVisibleRect` + 按压点做命中，而不是沿父子链找 —— 图片上方常盖着浮层，它是图片的**兄弟**而非父/子 |
| **面积规则** | 多个候选时取「面积最大且 < 屏幕 70%」的那个（排除背景层，避开角标/头像） |
| **绑定时效** | 控件绑定超过 60s 视为过期，不采信（防 RecyclerView 复用错配） |
| **内容哈希去重** | 同一张图不会重复落盘 |

> 仍存在"一个点上叠了多张同等重要的图"的情况，那时启发式无能为力 —— 这就是 v1.2 候选面板要解决的。

## 6. 源码结构

```
picker/
├── AndroidManifest.xml          # xposedmodule 元数据 + 说明页 Activity
├── assets/xposed_init           # 入口类名
├── res/values/strings.xml
├── stub/                        # 编译期桩（compileOnly，不进 APK）
│   └── de/robv/android/xposed/{IXposedHookLoadPackage,XC_MethodHook,XposedHelpers,XposedBridge,callbacks/XC_LoadPackage}
├── src/com/dsh/picker/
│   ├── XposedInit.java          # 入口：hook 安装 + 长按判定 + 取图
│   ├── CaptureStore.java        # 控件身份 → 位图 的绑定表（LRU）
│   ├── TouchTracker.java        # 每个控件的按下点
│   ├── ViewHitTester.java       # 控件树命中测试
│   ├── ImageSaver.java          # 三级落盘
│   └── Util.java                # 日志 / Drawable→Bitmap / HARDWARE 回读 / MD5
└── build.ps1                    # 纯离线构建：aapt2 → javac → d8 → zipalign → apksigner
```

## 7. 构建（无需 Gradle / 无需联网）

```powershell
# 依赖：JDK 21 + Android SDK build-tools 36.0.0 + platform android-36.1
.\build.ps1
# 产物：PickPic.apk（约 20KB，已签名）
```

之所以不用 Gradle：本机 Gradle 8.2 不支持 JDK 21，且 Kotlin 插件未缓存，AGP 8.2.1 与 compileSdk 36 也属跨版本组合。手工链全程可控、可复现。

## 8. 免责

仅限在自己设备上保存自己有权保存的内容；不绕过 DRM、不破解付费内容。

---

## 9. v1.2 变更（2026-11）

### ① 剪贴板投递（跨应用流转闭环）

长按取图后，除了落相册，还会把图片**放进系统剪贴板**，于是可以直接切到微信/QQ 输入框长按粘贴。

实现上**没有走 FileProvider**，原因值得记一下：

> 本模块的代码运行在**宿主进程**里（不是自己的进程），而 FileProvider 的 authority
> 必须由宿主在自己的清单里声明 —— 宿主没有。所以改用**插入 MediaStore 后拿到的
> `content://media/...` URI**，直接 `ClipData.newUri()` + `setPrimaryClip()`。
> 等价、更少一步，而且跨应用可读。

日志验证：`clipboard=true uri=content://media/external/images/media/1369`

### ② 绘制上下文绑定（自绘控件）

hook `View.draw(Canvas)`（进/出压弹 `ThreadLocal`）+ `Canvas.drawBitmap(...)` 两个重载，
把「某次绘制用到的位图」归属到「正在绘制它的 View」。

于是像 `Canvas` 直绘、自定义控件这类**不在无障碍树里、也没有 ImageView 的场景**，
只要它画的是 Bitmap，长按就能归属到它。绘制极热，加了 30ms 限流 + 96px 面积门槛。

### ③ 多候选点选面板

一个点上常叠着大图 + 角标 + 头像。启发式只能猜，所以改成：**候选 ≥2 时弹面板让用户点选**。

弹窗的关键技巧：不申请 `SYSTEM_ALERT_WINDOW`，而是 hook `Activity.onResume/onPause`
拿到宿主当前 Activity，**借它的窗口**弹一个 Dialog。菜单位于宿主窗口内，不受后台弹窗限制，
也不需要任何额外权限。

### 验证状态（诚实标注）

| 功能 | 状态 |
|---|---|
| 长按取图 → 相册 | ✅ 实测通过（相册网格，`SAVE 241x241`） |
| 剪贴板投递 | ✅ 实测通过（`clipboard=true`，uri 有效） |
| 绘制上下文绑定 | ⚠️ 代码已装、hook 安装成功，**尚未在纯 Canvas 场景实测** |
| 多候选面板 | ⚠️ 代码已装，**尚未触发过真实多候选**（测试机的相册/商城都没出现一屏多图叠层） |
| 无图必不误存 | ✅ 实测：小米商城整页无图 → 连续 `skip`，未误存任何东西 |

### v1.3 待办

- WebView 内图片（`capturePicture`）
- 面板支持"长按预览大图"
- 设置项持久化（保存目录、是否写剪贴板）

---

## 10. v1.2.1 修复（实测反馈驱动）

### ① 长按改为「按住到点即触发」，不再等松手

原实现是在 `ACTION_UP/CANCEL` 里**事后**判断按住时长 —— 于是提示总在松手之后才出现，很别扭。
改为：`ACTION_DOWN` 时 `postDelayed(600ms)`，**到点立刻触发**（手指还按着）；
`ACTION_MOVE` 位移超阈值或 `UP/CANCEL` 只负责 `removeCallbacks` 收尾。

验证方式（不用手）：`input motionevent DOWN x y` → `sleep 1.3` → 查日志，此时还没发 `UP`，
日志里已经出现 `SAVE` 即证明"按住即触发"。

### ② 缩略图不再被裁成方块

原来用 `CENTER_CROP`（按短边填满、超出裁掉），又长又细的图会被切成一小块。
改为**先按原始宽高比算出显示尺寸**（长边固定 112dp，短边按比例、下限 32dp），
再配 `FIT_CENTER` —— 长条图完整显示，方图也正常。

### ③ 加了尺寸门槛，避免存到「加载转圈」

实测抓到过 `SAVE 48x48 from LoadingView` —— 把 loading 动画存下来了。
判据用「**面积 ≥ 10000 且最长边 ≥ 96**」而不是最短边：
挡掉 48×48 的转圈/小图标，同时不会误杀又长又细的横幅图。

### ④ 一个必须记下的坑：静态字段里不能建 Handler

```java
// ❌ 会让整个模块静默不加载
private static final Handler H = new Handler(Looper.getMainLooper());
```

Vector 注入得极早，那时**主 Looper 还没创建**，`getMainLooper()` 返回 null →
`ExceptionInInitializerError: NullPointerException ... Looper.mQueue on a null object reference`
→ 类初始化失败 → 模块完全不生效，而且**日志里只有一行 "Failed to load class"**，
不在 LSPosed 模块日志里，得去 `verbose_*.log` 才能看到。

正解：懒创建（第一次真正用到时再 new），或放进 `init()` 里（那时 `Application.onCreate` 之后，Looper 已就绪）。

---

## 11. v1.2.2 修复：「不管点哪里都拿到同一张图」

**现象**：在高德地图里，长按任意位置，保存下来的永远是同一张图。

**根因**（靠加候选日志看出来的）：每次长按会打**两条** `pick`：

```
pick point=540,852   best=null hit=SafeWebView   ← 控件中心，不是手指位置
pick point=300,700   best=null hit=SafeWebView   ← 真实按点
```

第二条来自 `performLongClick` 兜底路径，它当时用的是 `getGlobalVisibleRect().centerX/Y`
（**控件中心**）。而高德整个内容区就是一个铺满全屏的 `SafeWebView`（腾讯 X5）——
中心永远一样，所以永远解析到同一张图。更糟的是这条路径会比定时器更早跑，等于劫持了整个判定。

**修法**：

1. 兜底路径**绝不再用控件中心**，改用 `TouchTracker` 记下的真实按点（并换算到屏幕坐标）；
2. 两条路径**互斥**：谁先处理就把 `lastGestureHandledAt` 置位，另一条在时间窗内直接返回；
3. 兜底路径在没有按点记录时**直接放弃**（宁可不动，也不猜）。

**结果**：三个不同位置 → 三次独立的 `pick`，各自解析自己的点。

### 附带结论：高德属于「取不到」那一类

日志显示命中目标恒为 `SafeWebView` —— 高德的内容是 **X5 WebView 渲染**，不是 `ImageView`，
所以现在会正确地 `skip`（而不是像以前那样存一张错的）。

要让这类 App 也能取图，必须上 **v1.3 的 WebView 支持**（`WebView.capturePicture()` /
X5 同名接口，或 `Canvas` 抓绘制）。这也印证了之前的判断：**WebView 是当前最大的空洞**。

---

## 12. v1.3：WebView 支持（三级策略）

WebView 里的图**不是 ImageView**，所以前面所有 View 手段都无效。v1.3 按「质量从高到低」三级回落：

| 级 | 手段 | 拿到什么 | 说明 |
|---|---|---|---|
| ① | `getHitTestResult()` | **原图 URL** | WebView 自己对上次触摸做过命中测试，`IMAGE_TYPE`/`SRC_IMAGE_ANCHOR_TYPE` 时 `getExtra()` 就是图片地址。最快，但依赖页面结构 |
| ② | `evaluateJavascript` + `elementFromPoint` | **原图 URL** | 把按压点按**比例**换算成 CSS 坐标（`lx*innerWidth/vw`），从该点向上找 `<img>` 取 `currentSrc` |
| ③ | `capturePicture()` → 绘制到 Bitmap | **整页渲染图** | 前两级都失败时兜底。实测能拿到整页（Bing 图片页给出 `1080×6026`） |

拿到 URL 后走 `UrlSaver`：**原样下载字节**、按魔数嗅探真实类型（jpeg/png/gif/webp/avif/heic）、
按原 MIME 插入 MediaStore —— 所以存下来的是**未经屏幕缩放的原图文件**，不是截屏。

实现细节（都是坑）：

- **X5 / SafeWebView 与原生不同包**，`getHitTestResult` / `capturePicture` / `evaluateJavascript`
  全部用反射调用；`evaluateJavascript` 的第二个参数类型两边不同（`ValueCallback` 不同包），
  所以用 `Proxy.newProxyInstance` 动态实现回调接口；
- 坐标换算**不要用 `devicePixelRatio`**（缩放后不准），用「视口比例」映射，固定缩放与 dpr 差异都能吃下；
- `capturePicture()` 已废弃但仍是唯一能整页渲染的手段，必须 try/catch 兜死。

**实测状态**：③ 已验证（整页 1080×6026 成功落盘 + 进剪贴板）；② 已跑通调用链且能正常返回
（在 Bing 图片页返回空，说明该点没落在 `<img>` 上，随后按设计落下到 ③）；① 依赖具体页面。
