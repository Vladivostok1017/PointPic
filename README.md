# PickPic · 长按取图

**LSPosed / Vector（Zygisk）模块：长按任意界面里的图片或控件，把它存成一个图片文件。**
只存你按下去的那一张 —— 不做后台全量抓取，不截屏，不裁剪。

当前版本 **v1.9** ｜ 作者 **Wanan（dsh）** ｜ 许可 **MIT**

---

## 1. 它解决了什么

PicCatcher / Evo-PicCatcher 那类模块是「全自动抓取」：刷一遍信息流，相册里多出上千张你并不想要的图。
本模块把口径改成 **手动点选** —— 用户长按哪个控件，就保存那个控件当前显示的那张**原始 Bitmap 对象**。

输出位置：

```
/sdcard/Pictures/长按取图/<来源包名>/<内容MD5>.png
```

## 0. 安装与使用（三步）

1. 安装 `PickPic.apk`，在 **Vector / LSPosed** 里启用模块
2. **作用域**：**推荐全选**；至少要勾上你想取图的那些 App
3. 勾完 **强制停止目标 App 再打开**（或重启手机）—— 作用域只对**新启动的进程**生效，
   已经开着的 App 不重启不会生效，这是最容易漏的一步

然后在任意界面**长按约 0.6 秒**即可。

> KernelSU 环境需先装 ZygiskNext，再由 LSPosed/Vector 加载模块（开发机为 Vector v2.2 / Android 12）。

不想手点也可以走命令行（Vector 自带 `vector-cli`）：

```bash
# 启用模块
adb shell su -c "/data/adb/lspd/cli modules enable com.dsh.picker"

# 加一个作用域（格式 pkg/user_id），可一次给多个
adb shell su -c "/data/adb/lspd/cli scope add com.dsh.picker com.miui.gallery/0 com.tencent.mm/0"

# 覆盖式整表写入（推荐：先把三方包全列出来，一次性写完）
adb shell su -c "/data/adb/lspd/cli scope set com.dsh.picker com.tencent.mm/0 com.ss.android.ugc.aweme/0"

# 查看 / 移除
adb shell su -c "/data/adb/lspd/cli scope ls com.dsh.picker"
adb shell su -c "/data/adb/lspd/cli scope rm com.dsh.picker com.tencent.mm/0"
```

### 保存位置与「交付」

```
相册 / Pictures / 长按取图 / <来源包名> / <内容MD5>.png
```

存好之后立刻弹出**系统分享面板**（`ACTION_SEND` + `createChooser`），可以直接发给微信、抖音等。
不用剪贴板，是因为 Android 上多数 App **不读**剪贴板里的图片 —— `ClipboardManager` 只负责「放」，
接收方愿不愿意读是它自己的事。

同名同内容的文件不会重复存，自动去重。

### 长按没反应？按这个顺序查

1. 那个 App 在作用域里吗？
2. 勾完之后把那个 App **强停**了吗？
3. 是不是按在纯文字 / 空白处？那里没有图可存，会给一句提示。
4. 极少数完全自绘的界面取不到，属于已知限制。

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

---

## 13. v1.4：学 PicCatcher 的 `onLoadResource` 思路

参考 PicCatcher 的 `WebViewCatcherPlugin` / `X5WebViewCatcherPlugin`：**不钩坐标、不钩 JS、不渲染**，
而是钩 `WebViewClient.onLoadResource(webView, url)` —— 页面每加载一个资源都会回调，
直接拿到图片的**原始 URL**。

```
WebViewClient.onLoadResource(WebView, String)              ← 原生内核
com.tencent.smtt.sdk.WebViewClient.onLoadResource(X5WebView, String)  ← 腾讯 X5
```

拿到 URL 后按 WebView 实例归档（`WebUrlCache`，每实例 16 条，最近优先），
长按时弹面板列出「这一页加载过的图」，点哪张下哪张的**原图**。

### 一个必须知道的坑：X5 的类不在 App 的 ClassLoader 里

第一次接 X5 钩子直接失败，日志只有一行：

```
x5 classes absent
```

原因是 **TBS（腾讯浏览服务）在运行时从独立 dex 动态加载** `com.tencent.smtt.sdk.*`，
`Application.onCreate` 时刻这些类根本不存在，`findClassIfExists` 必然返回 null。

解法：**钩 `ClassLoader.loadClass`，盯着类名，一出现立刻补挂钩子**（幂等，用 HashSet 防重复）：

```java
XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, hook);
// 类名 startsWith("com.tencent.smtt.sdk.WebViewClient") 时
// 用该 Class 的 ClassLoader 取 X5 WebView，再挂 onLoadResource
```

注意这是**热路径**（每个类加载都会过），判断必须是「一次 startsWith」这种廉价操作。

### WebView 取图的完整优先级（v1.4）

| 级 | 手段 | 结果 | 状态 |
|---|---|---|---|
| ① | `getHitTestResult()` | 原图 URL | 依赖页面结构 |
| ② | JS `elementFromPoint` → `currentSrc` | 原图 URL | 调用链通 |
| ③ | **`onLoadResource` 缓存 + 面板挑选** | **原图 URL** | ✅ 本版新增（X5 需延迟钩子） |
| ④ | `capturePicture()` | 整页渲染图 | ✅ 已验证 |

### 另一个已知限制：钩不住「整条链路都覆写 dispatchTouchEvent」的页面

在小米商城某些页面上，长按完全没有触发我们的逻辑（连 `decide` 日志都没有）——
说明该页面根 View 覆写了 `dispatchTouchEvent` 并且不调用 `super`，我们钩在
`View.dispatchTouchEvent` 上的钩子整条链都收不到事件。

这类页面需要把钩子下沉到 `ViewRootImpl` / `InputEventReceiver` 级别（v1.5 计划）。

---

## 14. v1.5：修「点了小图标却存了整屏」

### ① 不再把"整页渲染"当成功保存

原来 WebView 分支走到底就直接 `SAVE` 整页渲染 —— 用户视角等于**截屏**，是"假成功"。改了：

- 整页渲染现在只是**一个候选**，弹面板让你点，不点不存；
- 全都拿不到时提示「这里没有可保存的图片」并放弃，而不是硬存一张。

### ② 判定顺序反过来：原生 ImageView 优先于 WebView

原来先判 WebView 再判 ImageView，导致**浮在 WebView 之上的原生图标**被 WebView 分支抢走，
最后只能落到整页渲染。现在顺序是：

```
➊ 原生 ImageView 候选（精确）
➋ WebView：getHitTestResult → JS → URL 缓存面板 → 整页渲染候选
```

### ③ JS 改成扫描全部 `<img>` 的 bounding rect

`elementFromPoint` 命中的往往是文字或容器，只靠它向上找 `<img>` 很容易空手。
现在遍历 `document.getElementsByTagName('img')`，用 `getBoundingClientRect()`
判断哪个矩形包含按压点，取面积最大的；再退一步查 CSS `background-image`。

### 关于「协议页上的定位/存储图标存不下来」

实测日志：`js result=null` + `web url-cache=0`，说明那一页里**根本没有可作为图片资源的东西**。
这类图标通常是 **图标字体字形 / 内联 SVG / 纯 CSS 绘制**——它们不是文件，没有 URL 可下，
任何"取原图"的路径都拿不到，只能渲染像素。这不是实现缺陷，是没有源可拿。

真正能取到原图的是**内容图**（商品图、文章配图、信息流图），这类图一定走 `<img>` 或背景图，
JS 那一步就能命中。

---

## 15. v1.6：WebView 原图链路真正打通 + 去掉「整屏假成功」

### 决定性证据

浏览器（Bing 图片页）实测日志：

```
js result=data:image/webp;base64,UklGRkI/AABXRUJQVlA4IDY/...
data-uri decoded 16202B image/webp
SAVE-URL 16202B image/webp clipboard=true
```

落盘产物：`com.android.browser/8ba69ecd...webp`，**16,202 字节的原始 WebP**。
说明「JS 命中 `<img>` → 拿 URL → 下载原图」这条链是通的，拿到的**不是截图**。

### ① 补上 data: URI 支持（之前直接丢弃了）

很多 H5 把小图/图标**内联成 base64**，URL 形如 `data:image/webp;base64,...`。
之前代码一句 `data-uri 暂不支持` 就 return 了 —— 明明图就在手里却丢了。
现在就地解码：`Base64.decode` → 嗅探 MIME → 按原格式存盘（webp 就存 webp，不转码）。

### ② 彻底移除「整屏渲染自动保存」

原来 WebView 链路走到底会存整页渲染 —— 用户视角等于**截屏**，是假成功；后来改成候选面板，
结果那个面板**挡住后续长按**，表现为「长按没反应」。现在两者都删掉：

- 不自动存整屏；
- 不弹整屏候选；
- 只给一句明确提示：**「这一处没有可单独保存的图片（整页画面不存）」**。

有反馈，就不会再出现"点了完全没动静"的困惑。

### ③ 结论：高德那两个页面属于「本来就没有图片资源」

| 页面 | 日志 | 结论 |
|---|---|---|
| 用户协议页（定位/存储图标） | `js=null`、`url-cache=0` | 图标是图标字体/内联 SVG/CSS 绘制，**没有文件可下** |
| 个人主页 | `js=null`、`url-cache=0` | 同上，头像等若为网络图则应命中；未命中说明该处不是 `<img>` |
| 浏览器 Bing 图片页 | `js=<data:uri>` → `SAVE-URL` | ✅ 原图落盘 |

### 验证矩阵（当前）

| 场景 | 手段 | 结果 |
|---|---|---|
| 原生列表/网格里的图 | ImageView 身份绑定 | ✅ 精确 |
| 浮在 WebView 上的原生图标 | ImageView 优先 | ✅ |
| H5 里的内容图（`<img>`） | JS bounding-rect 命中 → URL | ✅ 原图 |
| H5 内联 base64 图 | data: 解码 | ✅ 原图 |
| 图标字体 / SVG / CSS 绘制 | 无资源可下 | ⚠️ 明确提示，不存整屏 |
| 整链覆写 dispatchTouchEvent 的页面 | 收不到触摸 | ❌ 需 v1.7 下沉到 ViewRootImpl |

---

## v1.7：**更正上一版结论** —— 高德那四个"纯图标"其实是 APK 里的真 PNG

v1.6 里我写的"图标字体 / 无资源可下"是**错的**。回到真页面核对后：

```
uiautomator dump → 页面是 android.webkit.WebView（com.autonavi.minimap:id/webview_wrapper）
长按"定位" → js result=file:///android_asset/privacyPolicy/imgs/ic_Location.png
           → asset read privacyPolicy/imgs/ic_Location.png
           → SAVE-URL 2281B image/png clipboard=true
```

### 三个真实缺陷（都已修）

**① 下载器只认 http(s)**

`UrlSaver.download()` 里写死了 `(HttpURLConnection) new URL(url).openConnection()`，
遇到 `file://` 直接 `ClassCastException: FileURLConnection cannot be cast to HttpURLConnection`。
所以图明明找到了，却死在最后一步。现在按协议分流：

| 前缀 | 读取方式 |
|---|---|
| `file:///android_asset/xxx` | `ctx.getAssets().open(xxx)` ← **APK 内置资源，最干净的原始文件** |
| `file:///android_res/type/name.png` | `getIdentifier` + `openRawResource` |
| `file:///sdcard/...` | `FileInputStream` |
| `content://` / `android.resource://` | `ContentResolver.openInputStream` |
| 其它非 http 协议 | `URL.openConnection()`，**不再强转** |
| `http(s)://` | 原来的 HttpURLConnection 逻辑（UA / Referer / 24MB 上限） |

**② 图标不是 `<img>`，是 CSS `background-image`**

探针里 `imgs=0 svgs=0 canvas=0` —— `document.images.length` 是 0，
但图确实存在。原因是图标写成 `<div class="icon loc">` + `background-image:url(ic_Location.png)`。
精确命中失败后，探针现在会：

- 遍历全文档，取 `getComputedStyle(el).backgroundImage` 里的 `url(...)`；
- 用 `getBoundingClientRect()` 算**指尖到该元素的矩形距离**（点在内部距离为 0）；
- 按距离升序，只保留离最近的候选 150 CSS px 内的，最多 6 张；
- 返回 `#near|url1\nurl2...`。

顺带修了 `unquote()`：`evaluateJavascript` 回的是 JSON 字符串，多行结果里的 `\n`
原来不解转义，整串会被当成一个 URL。

**③ 拿不到就只剩提示**

原来最后一步是 toast 一句"没有可单独保存的图片"。现在顺序是：

```
精确命中 → 邻近候选面板（手动挑）→ onLoadResource 缓存 → 矢量重绘(3x) → 才提示
```

矢量重绘 `renderViewScaled(view, 3)`：让控件在 3 倍画布上 `v.draw(canvas)` 重画一遍，
Path/文字/渐变按矢量超采样，比屏幕像素更清晰 —— 这**不是截屏裁剪**，
形状不规则的控件也不会带背景。真·空白（`isBlank`）才跳过。

### 高德权限页实测（vangogh / Android 12 / Vector）

| 位置 | 资产 | 大小 | 结果 |
|---|---|---|---|
| 定位 | `file:///android_asset/privacyPolicy/imgs/ic_Location.png` | 2,281 B（57×72） | ✅ 原文件 |
| 存储 | `.../ic_File.png` | 495 B | ✅ 原文件（面板挑选） |
| 电话 | `.../ic_Tel.png` | 1,814 B | ✅ 原文件 |
| 通知 | `.../ic_Notice.png` | 1,339 B | ✅ 原文件（面板挑选） |

存下来的是 APK 里那张透明背景 PNG 本体（`file` 校验过 57×72 RGBA），
不是把屏幕那块像素抠下来。

### 验证矩阵（v1.7）

| 场景 | 手段 | 结果 |
|---|---|---|
| 原生列表/网格里的图 | ImageView 身份绑定 | ✅ 精确 |
| 浮在 WebView 上的原生图标 | ImageView 优先 | ✅ |
| H5 `<img>` 内容图 | JS bounding-rect → URL | ✅ 原图 |
| H5 内联 base64 | data: 解码 | ✅ 原图 |
| H5 CSS `background-image` 图标 | 邻近候选 → asset 读取 | ✅ 原图（v1.7） |
| APK assets / res 里的图 | `file:///android_asset` 分流 | ✅ 原文件（v1.7） |
| 真·Canvas 绘制 / 图标字体 | 矢量重绘 3x | ✅ 超采样位图（非截屏） |
| 整链覆写 dispatchTouchEvent 的页面 | 收不到触摸 | ❌ 待下沉到 ViewRootImpl |

> 测试提示：`input motionevent DOWN/UP` 两条命令拼长按**不可靠**（触摸流会被打断，
> 模块收不到完整手势）。请用 `input swipe x y x+1 y 900` 模拟长按。

---

## v1.8：下沉到 Window 层 —— 救回「把整条 dispatchTouchEvent 覆写掉」的页面

### 为什么原来的钩子会哑

`View.dispatchTouchEvent` 只是**基类方法**。宿主任何一层只要出现下面任一写法，
基类方法就一次都不会执行，钩子连日志都没有：

- 覆写 `dispatchTouchEvent` 后直接 `return true`，不调 `super`；
- 自己算命中，手动 `child.onTouchEvent(ev)` 而不调 `child.dispatchTouchEvent(ev)`；
- 自绘容器 / WebView 走 `mProvider.dispatchTouchEvent`，不回落父类。

**但 App 可以选择不把事件交给自己的 View，不能不把事件交给系统。**
所以钩点必须上移到「App 还没参与决策」的那一层。

### 新的触摸管线

```
InputChannel
  ↓ ViewRootImpl$WindowInputEventReceiver.onInputEvent
  ↓ ViewRootImpl.enqueueInputEvent(InputEvent, InputEventReceiver, int, boolean)   ← 挂这里
  ↓ …输入法阶段链…
  ↓ ViewPostImeInputStage.processPointerEvent(QueuedInputEvent)                   ← 再挂这里（吞 UP）
  ↓ mView.dispatchPointerEvent → DecorView → …任意 View…                          ← 原来的钩子（已让位）
```

| 位置 | 覆盖范围 | 用途 |
|---|---|---|
| `ViewRootImpl.enqueueInputEvent` | 本进程**所有窗口**（Activity / Dialog / PopupWindow） | 观察 + 长按判定 |
| `ViewRootImpl$ViewPostImeInputStage.processPointerEvent` | 同上，且有 boolean 返回值 | 吞掉「我们自己抓过的那次手势」的 UP |
| `com.android.internal.policy.DecorView.dispatchTouchEvent` | 仅标准 DecorView 窗口（漏 PopupWindow） | 兜底降级 |
| `View.dispatchTouchEvent` | 只覆盖老老实实调 super 的控件 | 已停用（`INPUT_HOOKED` 后让位） |

三级降级：`enqueueInputEvent` → `WindowInputEventReceiver.onInputEvent` → `DecorView.dispatchTouchEvent`。
装上哪一级都会打日志，方便判断 ROM 改没改签名。

### 关键实现细节

**① 事件到达时已经是屏幕坐标。** 在 `enqueueInputEvent` 这点上 `xOffset/yOffset` 已加好，
`getRawX()/getRawY()` 直接就是屏幕坐标。换算成根 View 本地坐标：减 `getLocationOnScreen`。

**② `MotionEvent` 是对象池复用的。** 回调返回后就被 `recycle()`，跨线程/延迟使用必须
立刻把 `getRawX/getRawY/getDownTime/getActionMasked` 拷贝成基本类型。本模块只存基本类型。

**③ 只吞 UP，绝不吞 DOWN/MOVE。** 吞 DOWN 等于让页面滚不动、点不了。
吞 UP 是为了让「长按已抓图」的这次手势不顺便被宿主当成一次点击跳走。
匹配靠 `MotionEvent.getDownTime()`，只吞我们自己处理过的那一次，弹窗里的点击不受影响。

**④ 顺手修掉一个一直没生效的路径。** `LONG_PRESS_RUNNABLE` 原来在调用 `triggerAt()` **之前**
就写了 `lastGestureHandledAt = now`，而 `triggerAt()` 的第一条守卫正是
`now - lastGestureHandledAt < 500 → return` —— 等于把自己挡在门外。
所以 v1.7 之前所有成功记录其实全是 `performLongClick` 兜底路径打的 `via=fallback`，
「定时器路径」从来没跑过。现在由 `triggerAt()` 自己置位。

**⑤ 主线程被阻塞时的补判。** 如果 DOWN..UP 挤在同一批消息里处理（页面在 loading、
主线程卡住），`postDelayed(600ms)` 根本没机会跑，手势会被 UP 直接收尾 ——
用户明明按够了却是空手。现在 UP/CANCEL 时按**实际按住时长**补判一次：
`held >= 600ms && 尚未触发` → 立刻触发，日志打 `long press settled on UP held=NNNNms`。

### 实测（vangogh / Android 12 / Vector）

| App | 之前 | 现在 |
|---|---|---|
| 高德 权限页 定位/存储/电话/通知 | `via=fallback` | `via=window` + `file:///android_asset` 原图 + `swallow UP` |
| 相册 照片网格 | `via=fallback` | `via=window` + `SAVE 241x536` + 剪贴板 + `swallow UP` |
| 小米商城 首页 | **连 `decide` 都没有** | `via=window` 正常触发（该页是 `LoadingView`，图未加载） |

小米商城那条是最关键的证据：同一个 App、同一个页面，v1.7 全链路零日志，
v1.8 立刻拿到 `win DOWN raw=500,1500 root=com.android.internal.policy.DecorView`
和 `decide via=window`。

### 诊断日志（保留）

| 日志 | 含义 |
|---|---|
| `hook ViewRootImpl.enqueueInputEvent ok (window-level)` | 装在哪一级 |
| `win DOWN raw=x,y root=类名` | 窗口级确实收到触摸（1.5s 节流） |
| `win cancel by MOVE dx=.. dy=..` | 位移超阈值被取消（滑动） |
| `long press settled on UP held=NNNms` | 定时器没跑，UP 时补判成功 |
| `swallow UP of captured gesture` | 这次手势的 UP 已被吞掉 |

> 测试提示：`input swipe x y x+1 y 900` 现在也能当长按用了（靠第 ⑤ 条的补判）；
> `input motionevent DOWN … UP` 也行，但两条命令间隔要真 sleep 够 600ms。

---

## v1.8.1：修「一保存就闪退」

### 崩溃现场

```
java.lang.ClassCastException: java.lang.Boolean cannot be cast to java.lang.Integer
    at Vector_.processPointerEvent(Unknown Source:15)
    at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess(ViewRootImpl.java:6461)
    at android.view.ViewRootImpl$ViewPostImeInputStage.onProcess
    at android.view.ViewRootImpl$InputStage.deliver(ViewRootImpl.java:5902)
    at android.view.ViewRootImpl.deliverInputEvent(ViewRootImpl.java:8934)
    at android.view.ViewRootImpl.doProcessInputEvents(ViewRootImpl.java:8885)
    at android.view.ViewRootImpl.enqueueInputEvent(ViewRootImpl.java:8853)
    at android.view.InputEventReceiver.dispatchInputEvent(InputEventReceiver.java:281)
```

### 原因

`ViewRootImpl$ViewPostImeInputStage.processPointerEvent(QueuedInputEvent)` 的返回值是 **int**
（`FORWARD=0` / `FINISH_HANDLED=1` / `FINISH_NOT_HANDLED=2`），不是 boolean。
v1.8 初版写的是 `param.setResult(true)` —— Xposed 生成的代理要把它转成 int，
于是抛 `ClassCastException`，**直接在输入分发路径上崩掉宿主进程**。

触发条件正好是「已经抓到了图」：`firedDownTime` 匹配 → 吞 UP → 崩。
所以现象是**一保存就闪退**，而且只在这一条路径上。

### 修法（两层）

**① 取框架里的真实常量**，不硬编码：

```java
Class<?> base = XposedHelpers.findClass("android.view.ViewRootImpl$InputStage", cl);
int finishHandled = XposedHelpers.getStaticIntField(base, "FINISH_HANDLED");   // 实测 = 1
```

**② 按方法声明的真实返回类型给结果**，从根上杜绝这类崩溃：

```java
Class<?> rt = ((java.lang.reflect.Method) param.method).getReturnType();
if (rt == int.class)          param.setResult(finishHandled);
else if (rt == boolean.class) param.setResult(Boolean.TRUE);
else if (rt == void.class)    return;            // void 没法吞，放行
else                          param.setResult(null);
```

并且 hook 内部所有异常都落 `Util.log("pointer sink err: ...")`，不再静默。

### 回归结果（vangogh / Android 12）

| 场景 | 结果 |
|---|---|
| 相册长按保存 | `SAVE 433x241` + 剪贴板，pid 不变，crash buffer 空 |
| 高德权限页 定位（asset 原图） | `SAVE-URL 2281B` + `swallow UP`，pid 不变 |
| 高德 通知 → 候选面板 → 点第一张 | `web pick -> ic_Notice.png` → `SAVE-URL 1339B`，pid 不变 |
| 小米商城（LoadingView，无图） | `decide` + `skip` + `swallow UP`，pid 不变 |

`logcat -b crash` 全程 0 条。

> 教训：**Xposed 里改返回值必须按签名给类型。** `setResult(Object)` 不做类型转换，
> 错了不会在 hook 里报错，而是在**被 hook 的方法返回时**由框架代理抛 CCE，
> 现场完全不在自己代码里。以后凡是 `setResult`，先看 `param.method` 的返回类型。

---

## v1.9：交付方式换成系统分享面板 + 说明页重写

### 剪贴板 → 系统分享

原来的收尾是「存盘 + 塞进剪贴板」。实测没意义：Android 上绝大多数 App **不读**剪贴板里的图片
（`ClipboardManager` 只负责「放」，接收方愿不愿意读是它自己的事）。现在改成：

```
保存到 MediaStore（Pictures/长按取图/<包名>/）
  → toast「已保存 WxH」
  → Intent.ACTION_SEND + Intent.createChooser() 弹系统面板
```

三条容易踩的实现细节：

1. **`setClipData()` 必须设**。只放 `EXTRA_STREAM` 时，`FLAG_GRANT_READ_URI_PERMISSION`
   在部分 ROM 上不会随 chooser 传给接收方，对方拿到 uri 打不开。
2. **`setType()` 用真实 mime**（png / webp 原样），不要一律 `image/*`，否则部分接收方会转码。
3. **从当前 Activity 启动，不要加 `NEW_TASK`**：加了会另起任务栈，返回键行为变怪；
   只有从 `Application` 启动时才必须加，否则被后台启动限制挡掉。

`Util.copyToClipboard()` 保留但不再调用（想改回去就是一行的事）。

### 说明页（`MainActivity`）

按「用户第一眼最需要知道的」重排：

1. **① 先去勾作用域（不勾＝完全没效果）** —— 放最前面，并写明「推荐全选」「勾完必须强停 App
   或重启，作用域只对新进程生效」，这是最容易漏的一步；
2. **② 它能干什么** —— 一句话：任意界面长按约 0.6 秒，把指尖下的图片/控件存成图片文件；
3. **③ 图存在哪** —— 相册 / Pictures / 长按取图 / <来源包名> /；
4. **④ 长按没反应？按这个顺序查** —— 四步排查；
5. 页脚：`作者：Wanan（dsh）`、`版本 1.9`。

管理器里的模块简介（`xposeddescription`）也一起改了，同样先讲「记得勾作用域」。

### 顺带确认（不是 bug）

MIUI 分享面板里那句「没有任何推荐的分享对象」指的是**直接分享（快捷联系人）那一行为空**，
不是目标列表为空。`logcat` 里其实是：

```
ChooserListAdapter: position: 23 offset: 1 rankedTargetCount: 4
ChooserActivity: List built querying services
```

这台机器上声明 `ACTION_SEND` + `image/png` 的界面有 **28 个**（微信 6 个入口、抖音、微博、
WPS 4 个、短信、邮件、蓝牙、打印、笔记、云盘……）。微信未登录也会出现在面板里，点进去才提示登录。

---

## 许可 / License

MIT —— 见 [LICENSE](LICENSE)。

```
Copyright (c) 2026 Wanan (dsh)
```

本仓库代码全部为本项目原创。`stub/` 下只有 Xposed API 的**方法签名声明**（编译期桩，不打进 APK），
不含第三方实现。开发过程中参考过 PicCatcher / Evo-PicCatcher 的**思路**（hook 哪些点），
但不含其代码。
