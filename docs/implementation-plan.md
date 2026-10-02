# 墨格 Moge · 实现计划

## 0. 结论

- 新建独立 Android 工程 `F:/moge`（必须是纯英文路径，见 2 节）。生成运行时、解析、渲染和画图代码从砺行（`F:/APP`，包 `com.example.lixing`）移植，UI 全部重写。
- 视觉主题叫「方格本」：日间用方格作业纸、墨水蓝和荧光笔黄，夜间切换成「黑板」，墨绿底、粉笔白字。
- 交互改为取景优先：打开就是取景框，拍完裁剪后进入解题页。历史会话做成「题册」网格。
- 保留：
  - 文字问答，拍照问答（多模态直送 / 指定识题模型）
  - 流式 Markdown + LaTeX、表格、函数图与框图
  - 长回答自动续写、思考过程、多模型配置与模型列表拉取、联网搜索
  - 语音输入、历史会话、失败重发、草稿、后台保活
- 删除（砺行专属）：计划修改与确认、英语积累动作、本机学习数据上下文、饮食校准模型、WebDAV/网盘同步备份、墨墨。
- 新增（原版缺的基础操作）：停止生成、复制回答、重新生成、图表保存/分享、会话重命名、题册搜索、识别文本查看、解题模式预设。

## 1. 源码盘点与处理方式

路径前缀 `S = F:/APP/app/src/main/java/com/example/lixing`

| 模块 | 源文件 | 处理 |
| --- | --- | --- |
| 网络与流式 | data/assistant/AssistantModelClient(2100行)、AssistantSseReader、OkHttpCancellation、IncrementalReplyDecoder、AssistantUsageParser | 移植；删掉 chooseContext、计划/英语守卫、饮食模型；改写 SYSTEM_PROMPT |
| 回复解析 | data/assistant/AssistantDtos(1723行) | 拆出：reply/标题解析、plots 解析、LaTeX 反斜杠修复、截断抢救、Markdown/LaTeX 规范化；删 plan/english actions |
| 生成运行时 | assistant/AssistantGenerationManager、GenerationPreparer、AssistantKeepAliveService、AssistantDraftStore；data/assistant/AssistantRequestSnapshot、AssistantGenerationSupport | 移植；删计划自纠重试、上下文推断、StudyClock、review envelope |
| 状态机 | domain/assistant/AssistantRequestStatus | 原样 |
| 密钥与配置 | data/assistant/AiCredentialStore | 移植；Keystore alias、prefs 名改成新应用的；删饮食模型字段和旧版单 key 迁移 |
| 画图 | domain/plot/*、domain/diagram/*、ui/plot/PlotBitmapRenderer、ui/diagram/DiagramRenderer、data/plot/PlotImageStore、data/diagram/DiagramImageStore、assistant/DefaultFigureRenderer | 基本原样；补浅色图表主题，函数图也加 sidecar（见 §5.3） |
| 文本渲染 | ui/screen/assistant/StreamingMarkdown、AssistantMarkdownTextView、AssistantMarkdownTables、AssistantMarkdownContent；data/assistant/FormulaWrapping | 移植；配色接新主题；res/values/ids.xml 的两个 tag id 一并带上 |
| 照片 | ui/photo/PhotoTransforms、LocalPhotoImporter；ui/screen/today/dialog/PhotoKit 中的 PhotoCropDialog + cropAndSave；AssistantPhotoViewer | 逻辑移植，界面重做 |
| 语音 | ui/screen/assistant/AssistantVoiceInput + AssistantScreen 里的 RecognitionListener | 逻辑移植（含 4 秒分段重启），界面重做 |
| ViewModel / Screen | AssistantViewModel(2626行)、AssistantScreen(1350行)、AssistantUiState | 重写；逐条对照迁入事件围栏（isSameTurn、attempt fencing）、草稿恢复、数据库对账 |
| 不移植 | AssistantContextBuilder、PlanChangeApplier、AssistantChangeReview、AssistantReviewPreviewBuilder、AssistantContextSelection、AssistantReviewTransactionRepository | — |

## 2. 工程与技术栈

- 应用名「墨格」（英文 Moge），包名 `com.moge.app`，单 module `:app`，`git init`。
- 版本对齐砺行：Gradle 8.14.3、AGP 8.13.2、Kotlin 2.2.21、Compose BOM 2025.09.00、Material3、Hilt 2.57.2、Room 2.8.4、DataStore 1.1.7、OkHttp 4.12.0、kotlinx-serialization 1.9.0、Markwon 4.6.2（core / inline-parser / ext-latex / ext-tables）、exifinterface 1.4.1、desugar。
- 新增 CameraX（core / camera2 / lifecycle / view），实现时从阿里云镜像取最新稳定版并钉死。
- compileSdk 36、targetSdk 35、minSdk 26；release 只打 arm64-v8a，debug 额外带 x86_64。
- 构建使用 JDK 21（`D:/tools/jdk-21.0.12+8`）。与砺行共用 `GRADLE_USER_HOME=F:/APP/.gradle-local`，`org.gradle.java.home` 已写在那里，依赖缓存也能复用。JDK 25 会导致 AGP 构建失败，JLatexMath 测试也会误报红。
- 工程必须放在纯英文路径下。中文路径下 AGP 默认拒绝构建；即便关掉检查，Gradle 测试 worker 的 @argfile 按 UTF-8 写、JVM 按 GBK 读，单元测试会报 ClassNotFoundException。
- 仓库走阿里云镜像，设置 `android.builder.sdkDownload=false`，SDK 在 `D:/Android/Sdk`。
- 调试签名用工程内固定 keystore，防止主目录密钥被清理后无法覆盖安装。

## 3. UI 设计：「方格本」

### 3.1 视觉语言

| 元素 | 日间 · 方格本 | 夜间 · 黑板 |
| --- | --- | --- |
| 背景 | 纸色 `#F7F5EF`，淡蓝方格线 `#E1E7EE`（24dp 格，0.5dp） | 板面墨绿 `#1E2B26`，方格 `#2A3A33` |
| 主色 | 墨水蓝 `#1F3A8A` | 粉笔黄 `#F2D675` |
| 强调 | 荧光笔黄 `#FFE066`（标题下划、选中态） | 粉笔蓝 `#8EC5FC` |
| 错误/批注 | 红笔 `#D9362B` | 粉笔红 `#FF8A80` |
| 文字 | `#1A1B1E` / 次要 `#6B7280` | 粉笔白 `#ECEDE6` / 次要 `#9AA8A0` |
| 卡片 | 白纸 + 1dp 墨线描边，不用阴影 | `#24332D` + 粉笔描边 |

- 字体不打包，控制安装包体积。标题用系统衬线体（`FontFamily.Serif`，多数机型会落到 Noto Serif CJK），带出作业本的感觉；正文用系统无衬线体；题号和用时用等宽体，比如 `No.027`、`38s`。
- 招牌组件，放在 `ui/components`：
  - `GridPaper`：用 drawBehind 画方格背景，只在页面底层使用。
  - `Highlighter`：在文字后面画一道略倾斜的荧光笔涂抹，用于页面标题和选中的 Tab、Chip。
  - `Tape`：半透明胶带，把题目照片「贴」在题目卡上。
  - `MarginLine`：解答纸左侧的红色页边线。
  - `Stamp`：印章样式，用于快门按钮和「未完成」状态。
- 形状：卡片圆角 6dp，有纸张感，明显区别于砺行的 20dp 大圆角；按钮用全圆角胶囊。
- 动效：拍照后照片「飞入」题目卡；生成期间页边线由上往下描绘；思考区展开用弹簧动画。系统开启「移除动画」时全部降级。

### 3.2 页面与流程

```
取景(首页) ──拍照/相册──▶ 裁剪 ──▶ 确认面板 ──开始解题──▶ 解题页 ◀──▶ 追问
   │                                                ▲
   ├─ 键盘图标 → 纯文字提问 ─────────────────────────┘
   ├─ 题册 → 历史网格 → 解题页
   └─ 设置 → 模型配置 / 识题模型 / 偏好 / 外观
```

1. 取景（首页）：
   - 全屏 CameraX 预览，四角扫描框，底部提示「对准一道题」，右侧有闪光灯和水平仪开关。
   - 底部从左到右是 `相册 | 快门印章 | 文字提问`。
   - 快门上方是解题模式胶囊：「详细讲解 / 只要答案 / 帮我查错 / 举一反三」。
   - 顶部左边是题册，中间是模型芯片（主模型 · 识题模型），右边是设置。
   - 支持连拍，最多 9 张，右下角显示照片叠放计数。
   - 未配置模型时显示横幅「先添加一个模型」，直接跳转设置页。
2. 裁剪：
   - 黑底，四角手柄，支持双指缩放和平移，比例可选自由/1:1/4:3/3:4/16:9/3:1，可旋转 90°。
   - 底部三个按钮：重拍 / 使用原图 / 确定。
   - 裁剪逻辑沿用 PhotoCropDialog。
3. 确认面板（底部弹层）：显示照片条（可删、可再拍一张）、模式胶囊、可选的「补充说明」输入框和「开始解题」大按钮。
4. 解题页：内容按文档式从上往下排，不用聊天气泡。
   - 题目卡：用胶带贴着的照片缩略图、用户文字，以及可折叠的「识别文本」（识题模型给出的转写，可以对照检查有没有认错）。
   - 草稿区：思考过程放在虚线框的「草稿纸」里，默认折叠成一行「草稿中… 38s」。
   - 解答纸：
     - 白纸加红色页边线，页眉是「解答 · 模型名 · 用时」。
     - 正文是流式 Markdown/LaTeX，图表内嵌并标注「图 1」，点开可以放大、保存、分享。
     - 页脚操作行：复制 · 重新生成 · 分享 · 用量（token 数）。
   - 追问：用户的追问以右对齐的黄色便利贴出现，下面接新的解答纸。
   - 失败：红笔印章「未完成 · 原因」加「重新发送」。
   - 底栏：一个追问输入胶囊（相机 / 相册 / 语音 / 发送），生成中发送键变成停止键。
   - 输入框上方有快捷追问：「没看懂，再细一点」「换种方法」「出道类似题」「只看答案」。
5. 题册：
   - 两列瀑布流卡片，显示首张照片或首行文字、标题、学科标签和日期。
   - 顶部有搜索框和学科筛选（数学/物理/化学/生物/英语/语文/其他）。
   - 长按进入多选，可重命名、删除。
6. 设置：
   - 模型：配置卡片列表，编辑页字段有名称、Base URL、Key、模型、多模态、搜索协议、推理强度，可拉取模型列表、测试连接。
   - 识题模型：选择器，只列出开启了多模态的配置。
   - 解题偏好：默认模式、自动续写 0-8 次、联网搜索、称呼。
   - 外观：跟随系统 / 方格本 / 黑板。
   - 数据：图表缓存大小和清理、清空题册（二次确认）。

## 4. 架构

```
com.moge.app
├─ MogeApplication         Hilt、JLatexMathAndroid.init、通知渠道、启动时孤儿请求恢复
├─ MainActivity            singleTask、adjustResize、configChanges 同砺行
├─ core/                   dispatcher、clock、DI 模块
├─ data/
│  ├─ llm/                 ModelClient、SSE、取消、增量解码、用量解析、提示词组装
│  ├─ parse/               ReplyParser（reply/title/subject/plots/diagrams + 抢救）、Markdown/LaTeX 规范化、FormulaWrapping
│  ├─ credential/          AiCredentialStore（Keystore AES-GCM）
│  ├─ prefs/               SettingsRepository（DataStore）
│  ├─ db/                  MogeDatabase v1：conversation / message / request 三张表
│  └─ figure/              PlotImageStore、DiagramImageStore、FigureRenderer
├─ domain/                 plot/*、diagram/*、RequestStatus、SolveMode、Subject
├─ runtime/                GenerationManager、GenerationPreparer、KeepAliveService/Guard、DraftStore、RequestSnapshot
└─ ui/
   ├─ theme/               Grid/Chalk 两套 ColorScheme + 自定义 LocalPaperColors
   ├─ components/          GridPaper、Highlighter、Tape、MarginLine、Stamp、ModeChips…
   ├─ markdown/            StreamingMarkdown、MarkdownTextView、Tables、MarkdownBody、InlineFigure
   ├─ capture/             CameraScreen、CropScreen、ConfirmSheet
   ├─ solve/               SolveScreen、SolveViewModel、QuestionCard、ScratchPad、AnswerSheet、FollowUpBar
   ├─ notebook/            NotebookScreen、NotebookViewModel
   ├─ viewer/              PhotoViewer（缩放、旋转、保存到相册、分享）
   └─ settings/            SettingsScreen、ProfileEditor、SettingsViewModel
```

运行时沿用砺行已经验证过的分层：

- `GenerationManager` 是单例，负责生成、画图和持久化。ViewModel 只订阅状态，页面旋转或退出页面都不会中断生成。
- 所有 DAO 写入都用 `attempt_id` 加进行中状态做围栏，终态只结算一次。
- 进程被杀后重启，进行中的请求会标记为 INTERRUPTED，界面上可以重发。
- 生成期间开前台服务（dataSync）保活，同时处理 Android 15 的前台服务超时回调。
- 草稿（文字和附件）按会话落盘，重启后恢复。

## 5. 关键设计点

### 5.1 数据库（v1，全新 schema）

- `conversation`：id、title、subject、cover_image、solve_mode、created_at、updated_at、pinned。
- `message`：id、conversation_id(FK CASCADE)、role、content、display_content、image_paths(JSON)、transcript(识别文本)、model_label、duration_ms、usage_json、created_at。
- `request`：沿用砺行 assistant_request 的字段，去掉 sync 相关列。
- 导出 schema JSON，从 v1 起写迁移测试。

### 5.2 提示词与输出协议

- 重写 SYSTEM_PROMPT，角色是「拍题解题老师」。保留砺行的这些规则：LaTeX 规范、表格、长推导、plots/diagrams 协议（第 13、14 条）、`[[FIGURE:n]]` 锚点和 JSON 输出格式。
- 删掉第 5 条中已经过时的 OCR 表述，也删掉计划、英语相关的全部协议。
- 输出 JSON 精简为 `{conversation_title, subject, reply, plots, diagrams}`。其中 `subject` 从固定枚举里取，用来给题册分类；解析失败时归为「其他」。
- 解题模式通过易变的 system 段注入，不改稳定前缀，保证 prompt cache 命中：
  - 详细讲解：分析、步骤、答案、易错点
  - 只要答案：最终答案加一句关键依据
  - 帮我查错：逐步批改用户的解答，用红笔标注错在哪里
  - 举一反三：先解原题，再给 2 道同类变式题，答案折叠在最后
- 框图的 `textbook_dual_branch` 和 `iq_demodulator` 两个 profile 保留。它们对通信原理题有用，代码也已经写好。

### 5.3 图表

- 流程照旧：模型输出规格，本地用 Canvas 渲染成 PNG，再按锚点插入正文。
- 改进：
  - `PlotBitmapRenderer` 增加浅色主题。原版只有深色，放在「方格本」白纸上很突兀。
  - 函数图的 PNG 从 cacheDir 改存到 filesDir，同时写 spec sidecar，和框图一样丢失后能重新渲染，避免出现「图表已过期」。
  - 切换日夜主题时，根据 sidecar 重新渲染对应的变体。
  - 图表查看器支持保存到相册（MediaStore，不需要存储权限）和系统分享。

### 5.4 拍照识题

- 路线在请求快照中固定：
  - 主模型支持多模态：直接把图片发给主模型。
  - 主模型不支持多模态，但配置了识题模型：先由识题模型转写成 Markdown+LaTeX，再交给主模型解答。
  - 两者都没有：报错，提示去设置页配置。
- 转写结果存进 `message.transcript`，在题目卡里展示，方便用户核对有没有认错。
- 图片预处理沿用 `AssistantImagePrep`：EXIF 转正，长边 2048，JPEG q95。
- 预留第二阶段选项「识别前增强」：灰度化加对比度拉伸，改善照片偏暗、纸面偏黄的情况，默认关闭。

### 5.5 权限

- 需要声明：INTERNET、CAMERA（CameraX 取景用，首次进入时申请）、RECORD_AUDIO、POST_NOTIFICATIONS、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC。另外保留 SpeechRecognizer 的 `<queries>`。
- 拒绝相机权限时，首页降级成「相册 + 文字提问」，并提供去授权的入口。
- 不申请定位，砺行里的城市记忆功能删掉。
- 保留 `usesCleartextTraffic`，支持局域网或自建的 http 接口，设置页对 http 地址给出提示。
- 网络安全：没有自建服务，所有请求直连用户自己配置的模型接口。API Key 用 Keystore 加密存储，并排除在系统备份之外。

## 6. 分阶段实施

每个阶段结束时都要能跑通 `assembleDebug` 和单元测试，再进入下一阶段。

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 工程骨架 | Gradle/版本目录、镜像、JDK 配置、Hilt、Application、主题骨架、导航、git init | 空壳 APK 能安装启动，日/夜两套主题可切换 |
| P1 纯逻辑移植 | domain/plot、domain/diagram、状态机、SSE、取消、增量解码、用量解析、FormulaWrapping、Markdown/LaTeX 规范化、ReplyParser（精简协议） | 迁入砺行对应的 JVM/Robolectric 测试并全部通过（约 25 个测试类） |
| P2 数据与运行时 | Room v1、CredentialStore、Settings DataStore、ModelClient（新提示词）、GenerationManager/Preparer、保活服务、草稿、FigureRenderer 和两个 ImageStore | 迁入 GenerationManager、提交流程、快照、请求仓库、HTTP 负载等测试；用 MockWebServer 跑通「流式 → 续写 → 完成 → 画图」 |
| P3 渲染组件 | 流式 Markdown、表格、公式换行、内嵌图表、浅色图表主题、PhotoViewer | Robolectric 渲染测试通过；真机实测长公式、宽表格、多图混排 |
| P4 设置与模型 | 设置页、配置编辑、拉取模型列表、测试连接、识题模型选择 | 能配置 DeepSeek 或 OpenAI 兼容接口，并测试连通 |
| P5 解题页 | SolveScreen/VM、题目卡、草稿区、解答纸、追问、停止、复制、重新生成、失败重发、快捷追问、语音 | 纯文字问答全流程可用；杀进程后能恢复并重发 |
| P6 取景与拍照 | CameraX 取景、连拍、相册多选、裁剪、确认面板、解题模式、两条识题路线 | 真机拍题直送和转写两条路线都跑通；拒绝相机权限后能降级 |
| P7 题册 | 网格、搜索、学科筛选、重命名、多选删除 | 可以按学科和关键词找回旧题 |
| P8 打磨 | 动效、无障碍（contentDescription、字号放大、触控 48dp）、深色细节、release 混淆规则（Markwon/JLatexMath/serialization keep）、图标 | 编出 release 包，真机回归通过 |

一旦 P2 的接口定下来，P3、P4 可以并行做；P6 和 P7 也互不依赖。

## 7. 测试策略

- JVM 测试：表达式求值、plots/diagrams 解析、截断抢救、锚点切分与偏移、流式分块、公式换行、快照编解码、状态机。
- Robolectric（sdk 33，JDK 21）：框图布局和走线、PlotBitmapRenderer、DAO 与迁移、GenerationManager 集成（MockWebServer 模拟 SSE）。
- Compose UI 测试：解题页的停止、复制、重发，取景页在权限被拒时的降级。
- 真机（adb）：拍题两条路线、长回答续写、后台生成时切走再回来、横竖屏旋转、深浅色切换后图表重新渲染。
- 砺行中的计划、英语、上下文选择、同步备份等相关测试不迁移。

## 8. 风险

| 风险 | 应对 |
| --- | --- |
| AssistantModelClient 与 AssistantDtos 体量大、耦合深，拆分时容易漏掉边界处理 | 先原样迁入对应测试再删代码，测试全绿才算完成；删除动作逐项对照 §1 的清单 |
| JLatexMath 对 CJK 的度量不准，个别公式渲染失败 | 保留砺行的三层降级（单条公式占位 / 整段纯文本 / 写本地日志） |
| CameraX 在部分国产 ROM 上预览黑屏或旋转方向错 | 提供降级入口：调用系统相机（TakePicture），沿用砺行的方案 |
| Android 15 前台服务 dataSync 超时 | 沿用 onTimeout 结算为 INTERRUPTED，界面给出重发入口 |
| 部分厂商语音识别约 5 秒后自动断开 | 沿用 4 秒分段重启 |
| 渲染后的 PNG 占用存储越来越多 | 设置页显示图表缓存大小，并提供清理；有 sidecar 可随时重新渲染 |

## 9. 已确认（2026-09-30）

1. 应用名「墨格」（Moge），包名 `com.moge.app`。
2. 主题采用「方格本 / 黑板」。
3. 保留联网搜索，以及框图的 `textbook_dual_branch`、`iq_demodulator` 两个 profile。
4. 不从砺行导入历史会话。
