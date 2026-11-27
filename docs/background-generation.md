# 后台生成保护与排查

生成协程属于应用作用域。页面退出、切换会话和息屏后，请求继续由应用持有。生成成功、网络失败、用户取消或前台服务超时都会触发收尾。连接断开时保留已生成正文。启用自动续写且已有可见正文时，应用最多自动发起一次新的续写请求。

`ForegroundGenerationGuard` 按请求令牌持有 CPU、可用的 Wi-Fi 锁和前台服务。服务启动被拒绝时仍保留其余保护，最后一个有效令牌释放全部资源。`activeCount()` 表示资源租约数，不能用于确认服务已进入前台。服务先调用 `startForeground`，然后读取业务依赖，通过实际生命周期回报前台状态。Android 14 及以上的高性能 Wi-Fi 锁会映射成仅亮屏生效的低延迟锁，故不使用该锁假装保障息屏联网。

发送、重新发送、继续回答和重新生成在点击时申请保护，早于附件固定和请求入库。写入成功后，应用级生成任务接管同一个令牌。校验失败或交接前取消会归还令牌。服务已收到启动请求时，完成前台提升后会检查还有没有租约，避免快速取消时过早停止服务。

设置 → 后台生成 → **复制后台诊断** 可读取本机最近 48 条状态。记录包括时间、进程号、亮屏/省电/Doze/电池豁免、前台服务、实际 CPU/Wi-Fi 锁状态、网络类型/验证状态/系统阻止标记、异常类型链。只在有生成租约时监听屏幕和网络，收尾注销；过期回调不进入下一轮。记录异步保存，重启后仍可读取；不采集题目、回答、密钥、端点、网络名称或异常原文。

默认只在生成、服务、屏幕、网络状态改变和请求失败时记录，不启动周期采样。诊断构建可显式传入 `-PMOGE_BACKGROUND_LIVENESS_SAMPLING=true`：锁屏期间每 2 秒留一条 `liveness`；亮屏时不定时写盘，只记录异常延迟的调度。每条状态同时记录 `elapsed`（含深度休眠）、`uptime`（不含深度休眠）和进程 `importance`，用于区分进程调度间断与设备休眠。周期采样不唤醒屏幕、不发起网络请求，但其调度、系统查询与写盘可能干扰厂商的休眠策略，不能认为对被测行为完全无影响；最后一个请求释放后取消。应用对象的 `cpu=true` 只证明唤醒锁仍标记为持有，不能证明系统实际允许它阻止休眠。异常仍不保存原文，只额外提取固定名单中的 OS 错误码（如 `ECONNRESET`）。

- `service_failed`：系统拒绝启动或拒绝进入前台；查看后面的异常类型。
- `service_foreground` 后 `screen_off`：确认锁屏时的实际前台与 CPU 状态。
- `network_lost`、`network_blocked_changed blocked=true`：网络切换、不可用或系统报告阻止。`blocked=false` 不能排除厂商在框架之外断网。
- `request_failed`：在归还令牌之前记录异常类型和现场，区分超时、socket 中断等。
- `liveness gapMs=… awakeGapMs=…`：前者包含深度休眠，后者不含。采样长时间没有运行、但设备仍清醒，需结合系统日志查进程冻结、调度阻塞等；仅凭一段间隔不能断言是厂商冻结。
- `service_destroyed_while_generating`：任务尚未收尾，服务却已被销毁。
- `service_timeout` 表示系统调用了前台服务的超时回调。针对 Android 15 的应用在 Android 15 及以上运行时，`dataSync` 服务受后台累计时长配额约束。[Android 前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)
- `lowPowerStandbyEnabled`（Android 13 起）和 `lowPowerStandbyExempt`（Android 14 起）描述系统低电耗待机策略。若前者为 `true` 且后者为 `false`，息屏时该策略可能限制前台服务的网络并忽略唤醒锁。这两个值只描述配置，旧系统或查询失败时显示 `unknown`。原有 `exempt` 字段表示常规电池优化豁免。[Android PowerManager](https://developer.android.com/reference/android/os/PowerManager#isLowPowerStandbyEnabled())
- 上一进程记录在生成中结束、没有收尾记录：结合进程号和系统日志排查进程死亡；异步落盘本身不能保证留下进程被杀前最后一条记录。

后台生成仍受 Android 的电池与网络管理策略影响。

## 如何定位一次中断

复现时记录「按 Home 或锁屏时是否已出字」「多久后发现停止」「回到页面时的提示」，随即在设置中复制后台诊断。对照同一个进程号、同一次生成的记录。

- **发送后立即离开。** 先看 `service_requested` 后是否出现 `service_foreground`。旧版在附件固定和请求入库后才申请服务，可能因窗口已退到后台而收到 `ForegroundServiceStartNotAllowedException`。可用同一附件再发一次，等到开始出字后才锁屏作对照。[Android 后台启动前台服务限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- **已经出字，锁屏后报网络中断。** 先找 `service_foreground`，再看失败前是否有 `network_lost`、`blocked=true` 或 `idle=true`。设置 → 后台生成 → **允许息屏联网** 可以向系统申请电池优化豁免。保持同一模型、网络和锁屏时长复测。如果问题只在使用 VPN 时复现，还需要检查 VPN 自身的后台连接。单独一条 `ECONNRESET` 无法区分手机网络、代理或模型网关。[Android Doze 说明](https://developer.android.com/training/monitoring-device-state/doze-standby)
- **系统已解除网络阻止，界面仍在等待。** Android 10 起，同一个默认网络可以仅通过 `onBlockedStatusChanged` 通知应用恢复访问。等待新请求或续写时需要同时核对默认网络的 Internet 能力和该应用的阻止状态。对于已断开的流，有正文时可手动继续，也可在下一轮发送前启用「网络恢复后自动续写一次」。续写会发起新的模型调用。[Android 网络状态回调](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
- **返回时提示「应用上次退出时这一轮还没结束」。** 这是启动时发现了上次遗留的请求。对照诊断中的进程号和故障时间，查看系统进程退出记录；继续回答会使用已落盘的正文。

设备回归覆盖申请保护后立即切后台，以及服务进入前台后通过本机回环地址持续接收约 20 秒数据。验证锁屏网络还需要由另一台机器提供持续输出，分别对照亮屏切后台和真正锁屏，并在故障机上测试实际使用的 Wi-Fi、蜂窝或 VPN 路径。

## 0.5.6 现场记录

用户后续在已发布的 0.5.6 上确认：Wi-Fi 和移动网络均会中断，5 秒内亮屏可继续，接近 10 秒亮屏显示“模型网络连接已中断，请检查网络及息屏联网设置后重试”。这条提示来自 `withModelNetworkErrors` 的普通 IO 异常分支，不能用它确定断网、进程暂停或服务端断开的具体原因。正在读取响应体时，读取超时与生成进度监视均为 10 分钟；10 秒连接超时仅用于建连，5 秒 `WhileSubscribed` 仅用于界面订阅，模型列表的短超时只影响非致命的能力发现。现有证据不足以把根因归于 Wi-Fi，也不能直接认定是某个 5 秒取消逻辑。

该对比同时改变了保护实现、诊断采样、调试标记、优化方式及安装包身份，不能据此锁定某一项是修复原因。原先准备关闭调试、启用 R8/资源缩减的对比构建；用户提出采样可能影响保活后，已中止该构建，优先隔离采样：保持诊断版包名、签名、debug 构建方式、后台保护和被动事件记录，只关闭周期采样。新包可覆盖诊断版并保留其配置，不覆盖正式版、不公开发布。诊断首部补充包名、构建类型、调试标记、调试器连接与采样开关，每次开始也记录 `liveness=periodic` 或 `liveness=events_only`，避免旧记录与当前构建混淆。即使无采样包仍正常，也还不能排除正式版包名对应的厂商策略差异。
