# GitHub 分发与应用更新

公开源码仓库为 [fxx255/moge](https://github.com/fxx255/moge)，项目自身源码使用 MIT 许可证。分支推送会执行自动检查；正式 APK 分发和应用内更新还需要配置专用长期签名并发布正式版本。当前 0.2.6 为开发版本。

## 已完成的应用接入

- 设置中的“版本与检查更新”已接入 `com.moge.app.ui.update.AppUpdateDialog(onDismiss: () -> Unit)`。内部 `AppUpdateViewModel` 使用 Hilt，显示当前版本、更新说明、进度、重试与 GitHub 项目入口。
- `MogeNavHost` 根部已接入一次 `com.moge.app.ui.update.AppUpdateNotice()`；它通过 `AppUpdateViewModel.maybeCheckOnLaunch()` 启动节流检查，有新版时显示“发现新版本 / 查看更新 / 稍后”，查看更新进入现有对话框。启动不会申请权限或自动下载；只提示启动检查发现的版本，手动检查不重复弹出启动通知。
- `AppUpdateRepository` 是 Hilt 单例。`checkOnStartup()` 最多每 24 小时尝试一次（包括失败尝试），异步执行，不等待网络结果，不影响解题。对话框中的 `check()` 始终允许手动重试。未配置或开发签名时不会联网。
- Manifest 已添加 `REQUEST_INSTALL_PACKAGES`，更新交给系统安装器确认。
- FileProvider authority 保持 `${applicationId}.files`，已添加仅覆盖 `filesDir/updates` 的路径。
- 更新文件只放在 `filesDir/updates`，与导出器目录分开。无需存储权限。`UpdateManifestIntegrationTest` 检查上述声明。
- 设置、导航、Manifest 与 FileProvider 已统一整合，并纳入整包构建检查。

## 本地构建配置

`MOGE_UPDATE_REPOSITORY` 接受 Gradle property 或同名环境变量，默认空。只允许 `owner/repo`，不接受 URL、自定义 HTTP 服务器或令牌。客户端使用未经授权的公共 GitHub API；私有仓库不能作为当前分发渠道。

正式签名通过以下四项 Gradle property 或同名环境变量提供：

- `MOGE_RELEASE_STORE_FILE`：现有专用 keystore 的文件路径。
- `MOGE_RELEASE_STORE_PASSWORD`：keystore 密码。
- `MOGE_RELEASE_KEY_ALIAS`：专用密钥别名。
- `MOGE_RELEASE_KEY_PASSWORD`：专用密钥密码。

全部为空时，本地 release 仍可构建，使用现有开发调试签名；构建日志及 `BuildConfig.SIGNING_STATUS` 明确标记 `DEVELOPER`。部分填写会报错，不能意外退回调试签名。全部提供时，校验文件存在，拒绝现有 debug.keystore 和 androiddebugkey 别名。工作流另外通过 APK 签名验证拒绝 Android Debug 证书。不生成私钥，不在源码保存正式签名密码。

只有正式签名的 release 才生成非空 `BuildConfig.UPDATE_REPOSITORY` 和可用的 `UPDATE_ENABLED`。debug 与开发 release 更新入口显示“暂未配置应用更新”。应用界面不显示签名状态、异常堆栈、密码或技术配置。

例如在用户自己的 Gradle 配置或终端中设置已确认的仓库标识和正式签名。不要将配置写入工程的 `gradle.properties`，不要提交 keystore。`.gitignore` 排除正式密钥、签名属性、环境文件和本地发布产物；已有开发 `app/debug.keystore` 仅保留在本地，不提交到 GitHub；文件存在时继续使用其签名，新克隆和 CI 则使用 Android Gradle Plugin 默认生成的调试密钥。

`MOGE_REQUIRE_OFFICIAL_SIGNING=true` 禁止缺少签名或仓库配置的发布构建。正式版本必须修改 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`；标签必须严格对应 `v` 加 APK 内的版本名，`versionCode` 必须高于所有已有正式 Release，并保持包名及签名身份一致。

## GitHub Actions

`android-checks.yml` 在分支推送和 PR 运行 Python 发布工具测试、Android 单元测试、Lint 和 debug 构建，没有使用任何 signing secrets。两条工作流显式安装 JDK 21、Android SDK 36 和 Build Tools 36.0.0，关闭配置缓存，避免把签名材料写入缓存。

正式发布使用 `tag-release.yml`，仅 `v*` 标签触发，要求标签是稳定版 `v数字.数字.数字` 且指向远程默认分支历史中的提交。配置 `github-release` environment，可设置审批人与允许发布的标签范围；在其中设置四个 Secrets：

- `MOGE_RELEASE_KEYSTORE_BASE64`：用户提供的专用 keystore 编码。
- `MOGE_RELEASE_STORE_PASSWORD`
- `MOGE_RELEASE_KEY_ALIAS`
- `MOGE_RELEASE_KEY_PASSWORD`

发布目标自动取该 GitHub 工作流所属仓库的真实标识，不在工程中捏造 URL。工作流明确要求该分发仓库公开。若将来源码必须私有，应另行设计公开分发仓库及其授权流程；当前工作流不自动创建或跨仓库推送。

工作流执行测试、Lint、签名 arm64 构建，运行 `aapt dump badging` 和 `apksigner verify --print-certs`，由实际 APK 生成 `release-output/update.json`。内容包含 schemaVersion、versionCode、versionName、minSdk、packageName、abi、文件大小、SHA-256、签名证书 SHA-256、真实仓库/Release/APK URL 与标签。

元数据生成器通过无凭证的公共接口检查此前所有正式 Release（有界分页），拒绝相同或更低版本编号和签名变化。已有正式 Release 缺少元数据时会停止，需要人工核对此前的发布历史；不会猜测已发布版本。

只有全部检查通过才创建 draft Release，上传固定命名的 `Moge-arm64.apk` 和 `update.json`，重新下载草稿资产验证大小、SHA-256 与元数据内容，最后一次操作将草稿转为正式 Release。中途失败保留不可见草稿，不覆盖同标签已有 Release。修复并核对后删除失败草稿再重新运行。所有发布标签共用并发组，防止版本检查互相竞争。

## 客户端安全与恢复

客户端读取 `/repos/{owner}/{repo}/releases/latest`，排除草稿和预发布；元数据和 APK 必须是配置仓库该标签的固定资产。JSON 最大 256 KiB，APK 最大 512 MiB，重定向只允许 GitHub API、GitHub 和其 Release 资产 HTTPS 主机。没有携带长期 GitHub token，也不复用模型请求的认证配置。

仅更高 `versionCode`、设备支持 arm64 且系统满足 minSdk 时允许下载。后台下载有进度、超时、取消和重试，写入临时 `.part`，校验完整大小与 SHA-256 后再验证 APK 的包名、版本编号/名称、最低 SDK、实际签名证书与当前安装身份。只有全部通过才改名为安装文件。首版要求同一签名身份，拒绝签名轮换；需要轮换时应另行实现并测试证书历史兼容。

交给系统安装器前再次校验。首次未知来源授权时，安装请求和元数据保存在本地；回到对话框或进程重启后，只在已获授权时继续安装。拒绝授权不会反复打开设置，可取消并继续使用。关闭对话框不会取消后台下载，重新打开可继续查看；点击“取消下载”删除临时文件。系统安装取消后保留已校验文件以便重试。完成升级后旧请求因版本校验失效而清理。

## 验证

更新模块已纳入正式工程的 Gradle/KSP、单元测试、debug/release 构建与 Lint。完整验证结果见 [本次修改报告](revision-progress-report.md)。11 项 Python 发布工具测试已通过；源码发布后的工作流运行结果可在仓库 Actions 页面查看；正式签名、正式 Release 和覆盖安装仍待配置后验收。

发布工具可单独运行：`python -m unittest discover -s scripts/release -p 'test_*.py'`。更新模块可单独运行 `testDebugUnitTest --tests 'com.moge.app.data.update.*'`；发布前应运行完整测试、release 构建与 Lint。

有真实公开仓库及长期签名后，仍需用两次正式签名构建进行真机验收：正常升级保留对话、收藏、分类与模型设置；未知来源允许/拒绝、进程终止恢复、系统安装取消、断网、取消下载、损坏文件、低版本及签名不匹配都不会破坏当前使用。源码开源不代表已经发布正式签名的 APK；当前尚未完成正式版本发布和覆盖安装验收。
