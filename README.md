# PointPic · 长按取图

**长按任意界面里的图片或控件，存成图片文件，并直接唤起系统分享。**

LSPosed / Vector（Zygisk）模块 · Android 10+ · [GPL-3.0](LICENSE)

当前版本 **v1.10** ｜ 变更记录见 [CHANGELOG.md](CHANGELOG.md)

> 命名：仓库与安装包叫 **PointPic**，App 显示名仍是 **长按取图**。包名 `com.dsh.picker` 与日志前缀 `[PickPic]` 保持不变——改包名会让已有作用域配置全部失效，日志前缀则要和 CHANGELOG 里引用的历史日志一致。

---

## 这是什么

在 App 里看到想存的图，长按它一下：图进相册，同时弹出系统分享面板，可以立刻发出去。

和「全自动抓图」类工具的区别是**只存你按下去的那一张**——不刷信息流、不批量下载、不截屏、不裁剪。拿到的是控件里那张原始位图，不是屏幕像素。

## 功能

| 能力 | 说明 |
|---|---|
| **点哪存哪** | 长按约 0.6 秒，保存指尖下的那张图 |
| **原始位图** | 直接读控件里的 `Bitmap`，不经过屏幕像素，不受屏幕分辨率限制 |
| **一键分享** | 存好立刻弹系统分享面板，可直接发微信 / 抖音 |
| **网页取原图** | `<img>`、CSS 背景图、内联 base64、APK 内置 `assets` 资源都能取到原文件 |
| **多候选点选** | 一个位置上叠了多张图时，弹面板让你挑，不靠猜 |
| **有反馈** | 该处没有可存内容时明确提示，不静默失败 |
| **不做后台抓取** | 只处理你按下的那一次，后台零动作 |

## 安装

**要求**：Android 10 及以上（minSdk 29）｜ LSPosed 或 Vector（Zygisk）
KernelSU / Magisk 环境需先装 ZygiskNext，再由 LSPosed / Vector 加载模块。

1. 下载 [`PointPic.apk`](PointPic.apk) 安装
2. 在管理器里**启用**模块
3. **作用域**：推荐**全选**；至少勾上你要取图的那些 App
4. **强制停止目标 App 再打开**（或重启手机）——作用域只对**新启动的进程**生效，已经开着的 App 不重启不会生效

> **HyperOS / MIUI 提示**：`adb install` 可能被拦，报
> `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。
> 打开开发者选项里的「USB 调试（安全设置）」即可，或者用 root 装：
>
> ```bash
> adb push PointPic.apk /data/local/tmp/
> adb shell su -c "pm install -r -d /data/local/tmp/PointPic.apk"
> ```

不想手点也可以走命令行（Vector 自带 `vector-cli`）：

```bash
# 启用模块
adb shell su -c "/data/adb/lspd/cli modules enable com.dsh.picker"

# 加作用域（格式 pkg/user_id），可一次给多个
adb shell su -c "/data/adb/lspd/cli scope add com.dsh.picker com.miui.gallery/0 com.tencent.mm/0"

# 覆盖式整表写入（先把三方包全列出来，一次写完）
adb shell su -c "/data/adb/lspd/cli scope set com.dsh.picker com.tencent.mm/0 com.ss.android.ugc.aweme/0"

# 查看 / 移除
adb shell su -c "/data/adb/lspd/cli scope ls com.dsh.picker"
adb shell su -c "/data/adb/lspd/cli scope rm com.dsh.picker com.tencent.mm/0"
```

## 使用

1. 打开目标 App
2. **长按约 0.6 秒**（按住不动，别滑）
3. 存好 → toast 提示 → 系统分享面板弹出
4. 图同时保存在 `相册 / Pictures / 长按取图 / <来源包名> /`

同名同内容的文件不会重复存，自动去重。分享面板里选谁，图就发给谁；不想发直接返回即可。

### 长按没反应？

按这个顺序查：

1. 那个 App 在作用域里吗？
2. 勾完之后把那个 App **强停**了吗？（最常漏的一步）
3. 是不是按在纯文字 / 空白处？那里没有图可存，会给一句提示。
4. 极少数完全自绘的界面取不到，见下方「已知限制」。

## 实现原理

| 环节 | 做法 |
|---|---|
| **长按判定** | hook `ViewRootImpl.enqueueInputEvent`（Window 层，App 绕不开），并以 `View.dispatchTouchEvent` 兜底；按住 ≥600ms 且位移 <25px 触发 |
| **定位图片** | 用屏幕坐标在全窗口做命中测试，取「包含按点、面积最大」的 ImageView；同位置多张图时列候选交给用户 |
| **取位图** | `ImageView` 身份绑定：`identityHashCode(View) → Bitmap` 的 LRU 表，O(1) 精确取回，不做图像比对；自绘控件靠 `Canvas.drawBitmap` 的绘制上下文绑定 |
| **网页** | `getHitTestResult` → JS 探针（`<img>` / CSS 背景图，按指尖距离排序）→ `onLoadResource` 的 URL 缓存 → `capturePicture` |
| **兜底** | 矢量重绘：把控件在 3 倍画布上重画一遍（是重绘，不是截屏） |
| **落盘** | MediaStore 插入 → root `cp` → 直接写文件，三级回落 |
| **交付** | `ACTION_SEND` + `createChooser` 唤起系统分享面板 |

每条通道为什么这么选、踩过哪些坑、怎么验证的，都记在 [CHANGELOG.md](CHANGELOG.md) 里。

## 构建

需要 **Windows + PowerShell 7、JDK 17+、Android SDK（build-tools + platforms）**：

```powershell
pwsh .\build.ps1
# 产物：仓库根目录的 PointPic.apk（自动签名、自动 zipalign）
```

脚本会自动探测 SDK、最新的 `build-tools`、最新的 `platforms/android-*` 和 JDK，不用改任何路径；
也可以用 `ANDROID_HOME` / `JAVA_HOME` 显式指定。

首次构建会在本地生成 `keystore.jks`（口令 `android`），已被 `.gitignore` 排除。
换机器 / 换 keystore 生成的签名不同，覆盖安装会失败，先卸载旧版再装。

## 发布

推一个 `v*` 标签就会自动出包并发 Release，不需要本地打包、也不需要任何 token：

```bash
git tag v1.11
git push origin v1.11
```

`.github/workflows/release.yml` 会在 windows runner 上跑 `build.ps1`，用 GitHub 自动注入的
`GITHUB_TOKEN` 创建 Release 并把 `PointPic.apk` 作为附件上传（`contents: write` 权限）。
Release 正文由 `generate_release_notes` 根据提交自动生成。

两个前置约定：

- **标签必须和 `AndroidManifest.xml` 的 `versionName` 一致**（`v1.10` ↔ `1.10`），
  不一致时 workflow 会直接失败，避免发出「标签写着 A、包里装着 B」的版本。
- **签名要一致**，否则用户无法覆盖安装。在仓库 Settings → Secrets 里加一个 `KEYSTORE_B64`：

  ```bash
  # 本地执行，产出单行 base64
  base64 -w0 keystore.jks      # macOS: base64 -i keystore.jks
  ```

  没有配置这个 secret 时 workflow 仍能出包，但会打一条警告：用的是临时生成的密钥，
  与官方包签名不同，用户需要先卸载再装。
## 已知限制

- 完全自绘、不经过标准 View 绘制流程的界面取不到内容
- 32dp 左右的小头像可能被「太小」阈值过滤掉
- 分享面板里有哪些目标，取决于系统里装了什么 App
- 模块只在被勾选作用域的 App 内生效

## 源码结构

```
picker/
├── AndroidManifest.xml      # xposedmodule 元数据 + 说明页 Activity
├── assets/xposed_init       # 入口类名
├── src/com/dsh/picker/      # 模块源码（12 个类）
│   ├── XposedInit.java      # hook 安装、长按判定、取图决策
│   ├── CaptureStore.java    # 控件身份 → 位图 的 LRU 绑定表
│   ├── ImageSaver.java      # 三级落盘 + MediaStore
│   ├── UrlSaver.java        # 按 URL 取原文件（http / asset / res / content / data:）
│   ├── WebGrabber.java      # WebView 取图（hitTest / JS 探针 / 邻近候选）
│   ├── CandidateDialog.java # 多候选点选面板
│   └── MainActivity.java    # App 内说明页
├── stub/                    # 编译期桩（compileOnly，不打进 APK）
├── build.ps1                # 纯离线构建：aapt2 → javac → d8 → zipalign → apksigner
├── PointPic.apk             # 构建产物，同时也是分发用的安装包
└── CHANGELOG.md             # 逐版本变更与实测记录
```

## 致谢 / Acknowledgements

本项目由 **Wanan** 主导 —— **版权与授权归属 Wanan**，

**思路参考（未使用其中任何代码）**：

- **PicCatcher(https://github.com/Mingyueyixi/PicCatcher) / Evo-PicCatcher(https://github.com/Evo-creative/Evo-PicCatcher)** —— 两个关键启发：`WebViewClient.onLoadResource` 能拿到页面加载过的
  图片 URL；用 View 身份而非图像比对来定位「用户按的是哪一张」。
- **vivo 超级拖放（`com.vivo.globaldragdrop`）** —— 展示了系统级拖放如何借全局输入流实现；
  本模块反其道走进程内长按，只取自己需要的那一张。
- **LSPosed(https://github.com/LSPosed/LSPosed) / Vector(https://github.com/JingMatrix/Vector)** —— 运行框架与 `vector-cli`（作用域、模块启停）。
- **Xposed API(https://github.com/rovo89/Xposed)**—— `stub/` 下是按公开 API **签名自行编写的编译期桩**，
  不含其实现代码。

## 许可 / License

**GNU General Public License v3.0** —— 全文见 [LICENSE](LICENSE)。

```
PointPic — 长按取图
Copyright (C) 2026 Wanan (dsh)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 3 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```


### 关于第三方代码

本仓库代码全部为本项目原创。`stub/` 下只有 Xposed API 的**方法签名声明**（编译期桩，不打进 APK），
不含第三方实现。开发过程中参考过 PicCatcher / Evo-PicCatcher 的**思路**（hook 哪些点），
但不含其代码。
