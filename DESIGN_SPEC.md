# MHTML Lens — Material 3 设计规范

## 1. 网格

- 基础单位：4 dp。
- 页面左右安全留白：20 dp（手机）；主要内容间距：12 / 16 / 20 / 24 dp。
- 触控目标：图标按钮使用标准 Material 48 dp 交互盒。
- 卡片：22–28 dp 圆角；文件图标容器 48 dp，内部 16 dp 圆角。
- 搜索框：28 dp 圆角，形成与 M3 Search Bar 一致的胶囊轮廓。

## 2. 信息层级

Library：
1. Top app bar：产品名 + “按内容识别”能力说明。
2. Hero summary：权限/扫描状态/发现数量，是唯一高强调容器。
3. Search + filters：固定为任务工具层。
4. Archive rows：标题 > 域名/文件名 > 格式徽标、大小、时间。

Reader：
1. Top app bar：网页标题 + 原始域名。
2. Web content：占据最大视觉面积。
3. Search：只在显式触发时展开。
4. Bottom status：离线状态、当前编码、脚本状态；均为低强调 AssistChip。
5. Settings：Modal bottom sheet，避免长期占用网页空间。

## 3. 色彩

- Android 12+：`dynamicLightColorScheme` / `dynamicDarkColorScheme`。
- 低版本：提供完整 light/dark fallback tonal palette。
- 高强调动作使用 `primary` / `primaryContainer`。
- 文件列表使用 `surfaceContainerLow`，避免卡片阴影堆叠。
- 无后缀与普通 MHTML 用不同 tonal container，但不使用警告红色，因为“无后缀”不是错误。

## 4. 字体

基于 Material 3 typography scale，只微调关键层级：
- `headlineLarge/headlineSmall`：Semibold，首页主信息。
- `titleLarge/titleMedium`：Semibold/Medium，页面与文件标题。
- `bodyMedium/bodySmall`：正文与来源信息。
- `labelSmall/labelMedium`：格式、日期、离线/编码状态。

不自行缩小系统字体，也不覆盖用户字体缩放。

## 5. 动效与微交互

- Library → Reader：220 ms fade + 0.985→1 scale，返回 160 ms；避免夸张的全屏位移。
- 扫描：Hero 内嵌 LinearProgressIndicator；不会弹阻塞式 loading dialog。
- Search：AnimatedVisibility 展开/收起；WebView 使用原生 find listener 返回“当前/总数”。
- Press：Material 组件原生 ripple；Android 12+ 自动获得平台 sparkle ripple。
- Scroll：Compose LazyColumn 保留系统 stretch overscroll。
- 错误与外链：Snackbar，不打断页面；外链需要用户显式点击“浏览器打开”。

## 6. 安全与信任设计

- 阅读器始终显示“离线”状态，不让用户误以为页面是在线网站。
- JavaScript 默认关闭并可见展示状态。
- 变更编码是可逆的即时设置，不修改源文件。
- 扫描只读取，不重命名、不移动 Chrome 下载文件。
- 自动扫描的高权限在首屏解释用途，单文件模式可以绕开该权限。
