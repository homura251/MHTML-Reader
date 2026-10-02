# MHTML Lens

[![Android CI](https://github.com/homura251/MHTML-Reader/actions/workflows/android-ci.yml/badge.svg)](https://github.com/homura251/MHTML-Reader/actions/workflows/android-ci.yml)

一个面向 Android 的离线 MHTML 扫描浏览器。重点解决 Android Chrome 保存网页时“文件没有 `.mhtml` 后缀”和部分查看器把 UTF-8 错读成 Latin-1/Windows-1252 的问题。

## 核心能力

- **按内容识别，不按扩展名识别**：读取文件前 512 KiB，检查 MIME `multipart/related`、boundary、Blink snapshot 标记。
- **扫描 Downloads**：Android 11+ 在用户授予“所有文件访问”后递归扫描 `Download/`；Android 10 及以下使用系统文件夹选择器（SAF）。
- **直接打开任意文件**：文件选择器使用 `*/*`，所以无后缀 Chrome 存档也可以选择；打开后再做内容 sniff。
- **正确处理 MHTML MIME**：支持 quoted-printable、base64、7bit/8bit/binary，按 MIME boundary 拆分资源。
- **编码不靠 WebView 猜**：根 HTML 的字符集优先级是 `Content-Type charset` → BOM → HTML `<meta charset>` → 严格 UTF-8；解析成 Unicode 后统一重新输出 UTF-8，并注入第一条 `<meta charset="utf-8">`。
- **编码人工覆盖**：UTF-8、GB18030/GBK、Big5、Shift_JIS、EUC-KR、Windows-1252。
- **完整离线资源**：HTML/CSS/图片等通过 `Content-Location` / `Content-ID` 映射给 WebView，不需要把源文件重命名成 `.mhtml`。
- **默认安全**：应用不声明 `INTERNET` 权限；WebView 禁止 file/content 访问；JavaScript 默认关闭；未包含在存档中的 HTTP/HTTPS 请求返回离线 404；主页面的 file/content/intent/自定义协议跳转也会被拦截。
- **Material Design 3 / Material You**：Android 12+ 使用系统动态色；包含明暗主题、原生 ripple/overscroll、M3 container color roles、圆角层级、页内搜索与底部设置 sheet。

## 已用真实 Chrome MHTML 验证编码

本工程的 `MhtmlParser` 已对提供的 11 MB Chrome/Blink MHTML 进行烟雾测试，结果：

```text
title=Free & unlimited Avatar/World ripping! | RipperStore Forums
charset=UTF-8
parts=40
containsChinese=true
containsMojibake=false
```

测试明确找到正常中文：`你好！看起来您对这段对话很感兴趣……`，且没有出现 `ä½...` 形式的 mojibake。

## 项目结构

```text
app/src/main/java/com/example/mhtmllens/
├── MainActivity.kt            # Compose App、文件选择、权限入口
├── MainViewModel.kt           # 扫描/打开状态
├── browser/
│   └── ArchiveWebView.kt      # 离线 WebView、资源拦截、UTF-8 重发射
├── mhtml/
│   └── MhtmlParser.kt         # MIME/MHTML 与编码解析核心
├── model/
│   └── ArchiveItem.kt
├── scanner/
│   └── ArchiveScanner.kt      # 无后缀扫描、SAF/Downloads
└── ui/theme/
    └── Theme.kt               # Material You 动态色与 Typography
```

## 构建

建议环境：

- Android Studio 2026.x
- JDK 17 或更高
- Android SDK 37
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- Kotlin 2.2.10
- Compose BOM 2026.09.00
- Material 3 1.4.0

仓库包含 `gradle-wrapper.properties`，但当前没有提交 Gradle wrapper JAR。GitHub Actions 使用 `gradle/actions/setup-gradle` 固定安装 Gradle 9.4.1，因此 CI 不依赖 wrapper；本地可直接用 Android Studio，或者安装 Gradle 后运行：

```bash
gradle wrapper --gradle-version 9.4.1
./gradlew assembleDebug
```

APK 通常生成在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

`.github/workflows/android-ci.yml` 会在 `main` 分支 Push、Pull Request 和手动触发时运行：安装 JDK 17、Gradle 9.4.1、Android SDK 37，执行 `:app:testDebugUnitTest` 和 `:app:assembleDebug`，并把 debug APK 作为 Actions Artifact 保存 14 天。

单元测试包含无后缀 Blink MHTML 内容识别，以及 quoted-printable UTF-8 中文防 mojibake 回归测试。

## Android 11+ 的 Downloads 限制

Android 11+ 明确禁止通过 `ACTION_OPEN_DOCUMENT_TREE` 授权整个 `Download` 根目录，所以“自动扫描 Chrome 下载的无后缀文件”不能只靠普通 SAF 文件夹权限。本项目因此把“所有文件访问”作为自动扫描模式；单文件打开仍然走 SAF，不需要全盘权限。

如果准备发布到 Google Play，请先检查 `MANAGE_EXTERNAL_STORAGE` 的 Play 政策适用性；个人使用、企业分发或侧载不受商店审核流程影响。

## 编码策略为什么能避免 `ä½ å¥½...`

`ä½...` 的本质是 UTF-8 字节先被错误解码成单字节字符。本项目不会让 WebView直接猜原始 MHTML 字节：

1. 先按 MIME 的 `Content-Transfer-Encoding` 还原原始字节；
2. 再从 MIME/BOM/HTML meta 判定字符集；
3. 在 Kotlin 层正确解码成 Unicode；
4. 移除可能冲突的旧 charset meta；
5. 统一编码成 UTF-8 后喂给 WebView。

因此“传输编码”和“字符编码”不会混在一起处理。

## 安全取舍

离线存档属于不可信输入，所以默认关闭 JavaScript，而且不提供 `addJavascriptInterface`。如果用户手动开启脚本，外部网络请求仍由 `WebViewClient` 拦截；应用本身也没有 INTERNET 权限。这样能兼顾多数 Chrome 静态快照和本地浏览安全。
