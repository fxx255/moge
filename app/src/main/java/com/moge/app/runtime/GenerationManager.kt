package com.moge.app.runtime

import android.util.Log
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.db.ConversationDao
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.llm.CONTINUATION_ECHO_CHARS
import com.moge.app.data.llm.CONTINUE_INSTRUCTION
import com.moge.app.data.llm.ChatMessage
import com.moge.app.data.llm.GenerationDiagnostics
import com.moge.app.data.llm.GenerationTimer
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.ModelException
import com.moge.app.data.llm.MonotonicClock
import com.moge.app.data.llm.RequestPolicy
import com.moge.app.data.llm.RequestSnapshot
import com.moge.app.data.llm.SnapshotCodec
import com.moge.app.data.llm.StreamEvent
import com.moge.app.data.llm.UsageSample
import com.moge.app.data.llm.isMeaningfulRound
import com.moge.app.data.llm.mergeContinuation
import com.moge.app.data.llm.offsetFigureAnchors
import com.moge.app.data.llm.localizeFigureAnchors
import com.moge.app.data.llm.polishTruncatedTail
import com.moge.app.data.llm.reconcileStreamedReply
import com.moge.app.data.llm.shouldContinueGeneration
import com.moge.app.data.parse.IncrementalReplyDecoder
import com.moge.app.data.parse.ParsedReply
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.parse.orderedFigures
import com.moge.app.domain.FailureKind
import com.moge.app.domain.RequestStatus
import com.moge.app.domain.SolveMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton


/**
 * 应用级生成管理器。
 *
 * ## 为什么必须存在
 *
 * 「正在生成」若活在 ViewModel 的 `viewModelScope` 里：
 * 页面销毁（切页、旋转、进程处于后台被回收）就会**取消协程**，SSE 流随之断开，
 * 用户切回来只看到半截回答，也没有任何重试入口。
 *
 * 本管理器以 `SupervisorJob` 持有生成任务，**与任何页面生命周期无关**：
 * 页面只订阅 [state]，销毁时只解除订阅，绝不会取消正在进行的生成。
 * 只有三种情况会真正终止任务：用户点取消、删除会话、服务明确超时。
 *
 * ## 关键约束
 *
 * - **不持有 ViewModel 闭包**：管理器只接收纯数据（[GenerationRequest]），
 *   对外以**可重放**的 [state] 暴露进度。页面销毁后不会有悬挂引用。
 * - **收尾在管理器内部完成**：绘图、学科归档、最终持久化都不依赖页面存活。
 *   页面销毁只取消订阅，重新订阅（或新开一个页面）能从 [state] 拿到完整状态 ——
 *   包括已经生成的图。
 * - **全应用最多一个活动生成**：提交前同步占位，避免跨页/连点重复创建任务。
 * - **attemptId 防晚到回调**：每次重试换新 attemptId，旧协程的收尾写入会被
 *   仓库的 `attempt_id` + 在途状态条件共同拦下，不会覆盖新结果、也不会复活终态。
 */
@Singleton
class GenerationManager @Inject constructor(
    private val modelClient: ModelClient,
    private val requestRepository: RequestRepository,
    private val conversationDao: ConversationDao,
    private val credentialStore: AiCredentialStore,
    private val diagnostics: GenerationDiagnostics,
    private val guard: GenerationGuard,
    private val monotonicClock: MonotonicClock,
    /** 绘图与最终收尾所需的纯数据引擎；不依赖任何页面。 */
    private val finalizer: GenerationFinalizer,
    /** 准备阶段（照片转写、历史裁剪）——同样不依赖任何页面。 */
    private val preparer: GenerationPreparer,
) {

    /**
     * 诊断观察者：每次生成收尾时回调一次本次的 HTTP 调用数与 usage 覆盖情况。
     *
     * 默认不做事；测试注入一个收集器即可断言「每个真实请求都记了一次」。
     * 不用 `GenerationDiagnostics` 承担这件事，是因为它按模型聚合并丢弃逐次明细。
     * 用可变属性而不是构造参数，避免给 Hilt 引入一个它无法提供的依赖。
     */
    @Volatile
    var diagnosticObserver: ((GenerationDiagnostic) -> Unit)? = null

    /** 一个逻辑请求的全部输入。纯数据，不含任何页面引用。 */
    data class GenerationRequest(
        val conversationId: String,
        val userMessageId: String,
        val answerMessageId: String,
        val attemptId: String,
        /** 发给模型的用户原文（图片转写后可能已被替换）。 */
        val userText: String,
        /** 历史 + 当前轮（当前轮必须是最后一条）。 */
        val history: List<ChatMessage>,
        /** 当前轮图片（base64）。 */
        val imageBase64s: List<String>,
        val solveMode: SolveMode = SolveMode.DETAILED,
        val webSearchEnabled: Boolean,
        val forceWebSearch: Boolean,
        /** 自动续写上限（用户设置）。 */
        val maxContinuations: Int,
        /** 供诊断用的模型标识（不含密钥/查询参数）。 */
        val modelKey: String,
        val protocol: String,
        /**
         * 诊断/usage 归集键：**安全端点身份 + 模型 + 协议**。
         *
         * 只按模型名归集会把「同一个模型名挂在两个供应商」的样本混在一起。
         * 端点身份由 baseUrl 主机+路径派生，**不含**查询串与凭证。
         */
        val usageGroupKey: String = modelKey,
        /** 回答气泡上展示的模型名（档案名，缺省为模型名）。 */
        val modelLabel: String = modelKey,
        /**
         * 本轮的**不可变运行时策略**（档案 id / 安全端点身份 / 推理档位）。
         *
         * 传给客户端后，整轮 HTTP（主请求、恢复、续写）只用这一份配置，
         * 中途不再重读"当前活动档案"——否则用户在生成中改设置会把请求
         * 发往另一个供应商。
         */
        val policy: RequestPolicy? = null,
    )

    /**
     * 一次生成的诊断摘要（纯数据，供测试与诊断入口消费）。
     *
     * 存在的意义：以前 usage 与 HTTP 调用次数只活在局部变量里，
     * 外部**无法验证**「每个真实请求都记了一次」。
     */
    data class GenerationDiagnostic(
        val requestId: String,
        val protocol: String,
        val usageGroupKey: String,
        /** 本次生成实际发出的 HTTP 调用数（首轮 + 续写 + 恢复）。 */
        val httpCalls: Int,
        /** 其中上报了 usage 的次数。 */
        val callsWithUsage: Int,
        /** 其中**没有**上报 usage 的次数（计为未知，不冒充 0）。 */
        val callsWithoutUsage: Int,
        val outcome: String,
    )

    /**
     * 一次**新提问**的提交意图：只有原始数据，没有网络结果。
     *
     * 照片转写（网络调用）与历史裁剪这些「准备」工作若散在 ViewModel 里，
     * **切页就会把准备阶段连同生成一起取消**，用户回到页面只看到一条没有下文的
     * 用户消息。准备阶段因此也归应用级作用域。
     *
     * 它不含任何密钥：模型/端点身份从凭证存储运行时取。
     */
    data class Submission(
        val conversationId: String,
        val userText: String,
        /** 已固定到私有持久目录的附件路径。 */
        val attachmentPaths: List<String>,
        /** 用户消息的紧凑展示文案（图片题）；null 表示与正文一致。 */
        val userDisplayContent: String? = null,
        val solveMode: SolveMode = SolveMode.DETAILED,
        val forceWebSearch: Boolean = false,
    )

    /** 对外广播的生成事件。全部是纯数据，页面据此更新自己的 UI。 */
    sealed interface GenerationEvent {
        val requestId: String
        val attemptId: String

        data class Started(
            override val requestId: String,
            override val attemptId: String,
            val conversationId: String,
            val answerMessageId: String,
        ) : GenerationEvent

        /**
         * 推理增量；**绝不能**升格为正文。
         *
         * 带 [conversationId]：界面用它做**会话围栏** —— 用户在 A 会话等待时
         * 切到 B 会话，A 的推理增量不能写进 B 的面板。没有这个字段时，
         * 界面只能靠"当前是否在跟踪某个请求"间接判断，切会话后仍可能串台。
         */
        data class Reasoning(
            override val requestId: String,
            override val attemptId: String,
            val conversationId: String,
            val text: String,
        ) : GenerationEvent

        /** 已解码的可见正文增量（来自顶层 JSON `reply`）。 */
        data class AnswerDelta(
            override val requestId: String,
            override val attemptId: String,
            /** 见 [Reasoning.conversationId]：界面据此做会话围栏。 */
            val conversationId: String,
            val text: String,
            /** 本轮累积的完整可见正文。 */
            val accumulated: String,
        ) : GenerationEvent

        data class Completed(
            override val requestId: String,
            override val attemptId: String,
            val conversationId: String,
            val answerMessageId: String,
            val result: ParsedReply,
            val timings: com.moge.app.data.llm.GenerationTimings,
            /** 已渲染好的图片绝对路径（含失败槽位的空串），按轮序累积。 */
            val answerImagePaths: List<String> = emptyList(),
            /** 合并后的完整正文（重试/续写累积），界面直接展示它。 */
            val mergedText: String = "",
        ) : GenerationEvent

        data class Failed(
            override val requestId: String,
            override val attemptId: String,
            val conversationId: String,
            val answerMessageId: String,
            val kind: FailureKind,
            val message: String,
            /** 失败前已生成的部分正文；只在**本机**展示，不同步。 */
            val partialText: String,
            val retryable: Boolean,
        ) : GenerationEvent
    }

    /** 当前活动请求的公开状态。 */
    data class ActiveState(
        val requestId: String? = null,
        val attemptId: String? = null,
        val conversationId: String? = null,
        val phase: RequestStatus? = null,
        /**
         * 正在进行的可见正文（含此前已确认的轮次）。
         *
         * 放在可重放的 StateFlow 里，页面重新订阅后能立刻恢复未完成的正文，
         * 而不是等下一个增量才重新出现。
         */
        val answerMessageId: String? = null,
        val partialText: String = "",
        val reasoning: String = "",
        val answerStarted: Boolean = false,
        val finalAnswer: String = "",
        /** 终态落库后仍需完成通知与释放保护；在真正交还任务所有权前不能接受下一问。 */
        val ownsTask: Boolean = false,
    ) {
        val isRunning: Boolean get() = phase?.isInFlight == true || ownsTask
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 事件流（增量通知）；**状态恢复**一律走可重放的 [state]。 */
    private val _events = MutableSharedFlow<GenerationEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GenerationEvent> = _events.asSharedFlow()

    private val _active = MutableStateFlow(ActiveState())
    val active: StateFlow<ActiveState> = _active.asStateFlow()

    /**
     * 可重放的活动状态：页面订阅它即可拿到「当前这一轮跑到哪了」。
     *
     * 与 [active] 是同一份数据；保留 [active] 这个名字是为了兼容既有调用方。
     */
    val state: StateFlow<ActiveState> get() = _active.asStateFlow()

    /**
     * 一轮任务的**所有者**：requestId + attemptId + 协程句柄 + 本轮结束原因，四者永不分离。
     *
     * ## 为什么不能各自独立存放
     *
     * 早先是三个互不相干的字段：`_active`（状态）、`activeJob`（句柄）、
     * `stopReasons`（结束原因，按 key 存 map）。它们各自"看起来是原子的"，
     * 组合起来却有真实竞态：
     * - `scope.launch { }` 默认调度，`activeJob.set(job)` 晚于 launch ⇒
     *   「已启动但未登记」窗口内的取消会漏掉整个收尾（body 压根没跑，也没人补）；
     * - `markStopReason` 读 `_active`、`cancel` 再读 `activeJob`，三步之间无锁 ⇒
     *   取消与新提交交错时会写到旧原因上、或取消掉新一轮的 job；
     * - 取消时把 `phase` 提前置终态，而 [ActiveState.isRunning] 由 phase 派生 ⇒
     *   单飞在旧协程**还没 settled** 时就放开。
     *
     * 现在：
     * - 登记/取消/清理都在同一把同步锁 [lock] 内，且 owner 一直持有到**全部收尾完成**
     *   （终态落库 + guard 释放 + 状态清理）之后 —— 单飞看的是 owner 是否存在，
     *   不是 `job.isActive`，取消因此不会提前放开入口；
     * - 结束原因存在 owner 上，天然按 (requestId, attemptId) 隔离；
     * - 协程用 [CoroutineStart.ATOMIC] 启动：**取消先于分派也会先把 body 跑起来**
     *   （随后在首个挂起点抛出），所以 `try/finally` 一定执行。再加
     *   `invokeOnCompletion` 兜底：万一 body 从未进入，也按 owner 的原因收尾，
     *   记录绝不停在 PREPARING。
     */
    private class TaskOwner(val requestId: String, val attemptId: String) {
        /**
         * 协程句柄：在同一个临界区内回填；尚未回填时到达的取消先记
         * [cancelRequested]，回填后立即补取消，不会漏。
         */
        @Volatile
        var job: Job? = null

        /** 本轮结束原因（用户取消 / 服务超时）。存在 owner 上 ⇒ 按轮隔离。 */
        @Volatile
        var stopReason: StopReason? = null

        /** 取消已请求但句柄尚未回填时为 true（回填后补一次取消）。 */
        @Volatile
        var cancelRequested: Boolean = false

        /** body 是否真的进入过。false 时由完成回调兜底收尾。 */
        @Volatile
        var enteredBody: Boolean = false

        /** 收尾闩：compareAndSet 保证多条收尾路径只有一个能落账。 */
        private val settled = AtomicBoolean(false)

        /** 抢占本轮的收尾权；只有第一个调用方返回 true。 */
        fun claimSettlement(): Boolean = settled.compareAndSet(false, true)

        fun hasSettled(): Boolean = settled.get()
    }

    /**
     * 当前活动任务的所有者；null 表示空闲。
     *
     * 只在 [lock] 内读写。持有周期覆盖「准备 + 运行 + 全部终态收尾」，
     * 所以同一时刻不可能登记出第二个所有者 ⇒ 全应用单飞成立。
     */
    private var owner: TaskOwner? = null

    /**
     * owner 的同步锁：登记、取消、清理由它串行化。
     *
     * 与 [mutex]（提交串行，内部要调 suspend 的落库）分开：owner 操作全是同步的，
     * 且必须能在已被取消的协程/服务回调里立即生效 —— 用 `Mutex.withLock` 会让
     * 「取消」排队甚至因协程已取消而根本执行不到。
     *
     * 约束：**绝不在持锁期间 join 协程**（协程收尾也要拿这把锁）⇒ 死锁。
     */
    private val lock = Any()

    /** 提交串行锁：把「单飞判定 + 事务落盘 + 登记 owner」包成一个临界区。 */
    private val mutex = Mutex()

    /** 是否有**另一轮**任务在跑（只看 owner 是否存在，不看 phase）。 */
    private fun ownerBusy(requestId: String, attemptId: String): Boolean {
        val current = synchronized(lock) { owner } ?: return false
        return !(current.requestId == requestId && current.attemptId == attemptId)
    }

    /** 读取当前 owner 的快照（requestId, attemptId, job）—— 三者必须同时取。 */
    private fun ownerSnapshot(): Triple<String, String, Job?>? {
        val current = synchronized(lock) { owner } ?: return null
        return Triple(current.requestId, current.attemptId, current.job)
    }

    /**
     * 登记本轮所有者：**任何**已存在的 owner 都拒绝。
     *
     * 早先「同名 requestId+attemptId 允许替换」会让重复的 `start` 拉起第二个
     * 协程并把第一个变成孤儿（两个 body 同时写库、模型被调两次）。
     * 现在同名也直接抛 [AlreadyRunningException]。
     */
    private fun registerOwner(requestId: String, attemptId: String): TaskOwner {
        synchronized(lock) {
            if (owner != null) throw AlreadyRunningException()
            val created = TaskOwner(requestId, attemptId)
            owner = created
            return created
        }
    }

    /**
     * 原子地清掉**这一个 owner 对象**（按引用身份，不按 id 文本）。
     *
     * 按引用比对的原因：同一 (requestId, attemptId) 可能先后存在两个 owner 实例
     * （重试/重复提交）；只比 id 会让旧实例清掉新实例的所有权。
     * 清 active 状态与清 owner 在**同一个临界区**内完成，注册/取消因此不可能
     * 与「清理」交错出半清理状态。
     */
    private fun finishOwnershipAtomic(owner: TaskOwner) {
        synchronized(lock) {
            // 不是当前 owner（已被新一轮顶掉）⇒ 连 ActiveState 都不许碰。
            if (this.owner !== owner) return
            this.owner = null
            _active.update {
                if (it.requestId == owner.requestId && it.attemptId == owner.attemptId) ActiveState() else it
            }
        }
    }

    /** 按双身份清理（无 owner 引用时的兜底，例如放弃一条从未启动的提交）。 */
    private fun clearOwner(requestId: String, attemptId: String) {
        synchronized(lock) {
            val current = owner ?: return
            if (current.requestId == requestId && current.attemptId == attemptId) owner = null
        }
    }

    /**
     * 启动扫描**必须只做一次**。
     *
     * 以前 ViewModel 每次 `init` 都调用 [recoverOnStartup]，于是「反复进出助手页」
     * 会把**当前正在跑**的任务标成中断 —— 用户什么都没做，生成就断了。
     * 现在由应用级初始化持有这个闩，重复调用只会等待第一次完成。
     */
    private val startupMutex = Mutex()
    @Volatile
    private var startupDone = false

    /** 部分正文节流落盘间隔：500–1000ms 之间，避免每 token 写库。 */
    private val partialFlushIntervalMs = PARTIAL_FLUSH_INTERVAL_MS

    /**
     * 启动时调用一次：把**确实遗留**的在途请求转成中断。
     *
     * 幂等且只生效一次；并发调用会等待同一个结果。**不会**扫描本进程
     * 当前正在持有的请求，所以重复初始化不会打断正在跑的任务。
     *
     * 两道保护，缺一不可：
     * - 实例闩 `startupDone`：整个进程只真正扫一次（管理器是单例）；
     * - `excludeRequestIds`：即便闩被绕过（例如未来改成可重置、或测试里
     *   直接调用），也不会把本进程**正在持有**的请求标成中断。
     *
     * 只改状态、不发网络请求 —— 重启后绝不自动调用模型。
     *
     * 所有公开提交入口都会**先等待**它完成（[awaitStartup]），
     * 所以「扫描」永远发生在「写入 PREPARING」之前，不会自相矛盾。
     */
    suspend fun recoverOnStartup() = awaitStartup()

    /**
     * 放弃一条**已落盘但从未开始**的提交。
     *
     * 正常情况下 [submit] 的锁把「单飞判定」和「落盘」包在一起，不会产生
     * 这种记录。这里保留作为兜底与测试接缝：任何原因让一条 PREPARING 记录
     * 没能进入生成（例如调用方在 submit 之后自己崩了），都应该能被收成
     * INTERRUPTED 并给出重试入口，而不是永远停在 PREPARING
     * —— 下次启动扫描会把它当成"上个进程遗留的孤儿"。
     */
    suspend fun abandonSubmission(requestId: String, reason: String) {
        val record = requestRepository.get(requestId) ?: return
        // 不得清掉**正在跑**的那一个 owner：这条记录可能只是"落了盘但没被本轮认领"。
        synchronized(lock) {
            val current = owner
            if (current != null && current.requestId == requestId && current.attemptId == record.attemptId) return
        }
        withContext(NonCancellable) {
            runCatching {
                requestRepository.interrupt(
                    requestId = requestId,
                    attemptId = record.attemptId,
                    partialText = "",
                    kind = FailureKind.NETWORK,
                    message = reason,
                )
            }
        }
        _active.update {
            if (it.requestId == requestId && it.attemptId == record.attemptId) ActiveState() else it
        }
        clearOwner(requestId, record.attemptId)
    }

    /**
     * 恢复扫描的**可重复**内核（不含 `startupDone` 闩）。
     *
     * [recoverOnStartup] 每次进程只调它一次；这里单独暴露是为了让测试能够
     * 反复验证「扫描不会碰本进程持有的请求」这条不变量 —— 只测一次
     * 会掩盖"闩恰好挡住了第二次调用"这种假通过。
     */
    internal suspend fun scanOrphansExcludingOwned(): List<RequestEntity> {
        // 本进程正在持有的请求就是**当前 owner** 的 requestId。
        val owned = synchronized(lock) { owner?.requestId }?.let { setOf(it) } ?: emptySet()
        return requestRepository.recoverOrphans(excludeRequestIds = owned)
    }

    /**
     * 开始一次生成。
     *
     * 调用方必须**先**在事务里创建好请求记录（[RequestRepository.createRequest]），
     * 这里只负责跑网络与状态迁移。
     *
     * 与 [submit]/[submitRetry] 共用同一条登记路径（[launchTurn]）：
     * ATOMIC 启动 + 完成回调兜底，保证「提交返回后立刻取消」也一定收尾。
     *
     * @throws AlreadyRunningException 已有**另一轮** owner 仍持有中。
     */
    suspend fun start(request: GenerationRequest, requestId: String) {
        // 刻意**不**跑启动扫描：调用方已经（在同一个事务里）把记录落盘，
        // 扫描会把这条刚写入的 PREPARING 当成"上个进程遗留的孤儿"直接标成中断。
        val owner = registerOwner(requestId, request.attemptId)
        _active.value = ActiveState(
            requestId = requestId,
            attemptId = request.attemptId,
            conversationId = request.conversationId,
            phase = RequestStatus.RUNNING,
            ownsTask = true,
            answerMessageId = request.answerMessageId,
        )
        launchTurn(owner, request.conversationId, request.answerMessageId) { owned ->
            runGeneration(requestId, request, owner = owned)
        }
    }

    /**
     * 等待应用级初始化完成（最多一次真正的启动扫描）。
     *
     * 所有公开入口都先等它，保证「扫描遗留孤儿」永远发生在
     * 本进程写入任何 PREPARING **之前**。
     */
    private suspend fun awaitStartup() {
        if (startupDone) return
        startupMutex.withLock {
            if (startupDone) return
            runCatching { scanOrphansExcludingOwned() }
                .onFailure { Log.w(TAG, "在途请求恢复扫描失败", it) }
            runCatching { requestRepository.purgeStale() }
            startupDone = true
        }
    }

    /**
     * 启动一轮**已登记**的任务：ATOMIC 启动 + 完成回调兜底。
     *
     * - [CoroutineStart.ATOMIC]：即便 `cancel()` 先于分派发生，body 也会
     *   **真正跑起来**（随后在首个挂起点收到取消），`try/finally` 因此不会漏 ——
     *   这是"返回后立刻取消却完全没有收尾"的根治手段；
     * - 句柄回填后再补一次取消：覆盖「launch 已返回、句柄尚未回填」这一瞬间
     *   到达的取消（此时取消只记了原因与 `cancelRequested`）；
     * - `invokeOnCompletion` 兜底：body 若从未进入，也按 owner 的原因收一次终态，
     *   记录绝不停在 PREPARING。
     */
    private suspend fun launchTurn(
        owner: TaskOwner,
        conversationId: String,
        answerMessageId: String,
        body: suspend (TaskOwner) -> Unit,
    ) {
        val job = scope.launch(start = CoroutineStart.ATOMIC) {
            owner.enteredBody = true
            try {
                body(owner)
            } finally {
                // 所有出口都归还所有权：早于这里的一定已经完成"终态落库 + guard 释放"，
                // 所以 owner 存在 ⇒ 这一轮真的还没收完 ⇒ 单飞不会提前放开。
                finishOwnershipAtomic(owner)
            }
        }
        val pendingCancel = synchronized(lock) {
            if (owner === this.owner) {
                owner.job = job
                owner.cancelRequested
            } else {
                false
            }
        }
        if (pendingCancel) {
            job.cancel(CancellationException(cancelMessage(owner.stopReason)))
        }
        job.invokeOnCompletion {
            if (!owner.enteredBody) settleNeverEntered(owner, conversationId, answerMessageId)
        }
    }

    /**
     * body 从未进入时的兜底收尾（防御性）：按 owner 上的原因落终态并发事件，
     * 并且**无论如何都要归还所有权**（否则管理器永久"正在生成"）。
     */
    private fun settleNeverEntered(owner: TaskOwner, conversationId: String, answerMessageId: String) {
        if (!owner.claimSettlement()) {
            // 已有人收尾过：本路径只负责把所有权还回去。
            finishOwnershipAtomic(owner)
            return
        }
        val timedOut = owner.stopReason == StopReason.SERVICE_TIMEOUT
        val kind = if (timedOut) FailureKind.SERVER else FailureKind.CANCELLED
        val message = if (timedOut) {
            "系统回收了后台运行时长（Android 15 前台服务超时），本轮已中断，可点重试继续"
        } else {
            "已取消"
        }
        scope.launch {
            try {
                withContext(NonCancellable) {
                    runCatching {
                        if (timedOut) {
                            requestRepository.interrupt(owner.requestId, owner.attemptId, "", kind, message)
                        } else {
                            requestRepository.cancel(owner.requestId, owner.attemptId, "")
                        }
                    }
                    _events.emit(
                        GenerationEvent.Failed(
                            requestId = owner.requestId,
                            attemptId = owner.attemptId,
                            conversationId = conversationId,
                            answerMessageId = answerMessageId,
                            kind = kind,
                            message = message,
                            partialText = "",
                            retryable = kind.isRetryable,
                        ),
                    )
                }
            } finally {
                finishOwnershipAtomic(owner)
            }
        }
    }

    /**
     * 提交一轮新提问：**唯一的入口**，落盘与登记在同一把锁内完成。
     *
     * 顺序（每一步都不能换位）：
     * 1. 等启动扫描完成 —— 否则扫描可能把刚写的 PREPARING 当成遗留孤儿；
     * 2. 取**提交串行锁**并检查是否有 owner —— 在锁内检查，连点/多页面
     *    不会有两条都通过检查再各自落盘；
     * 3. 在**锁内**先把请求记录落盘（一个事务：用户消息 + 回答占位 + PREPARING）；
     * 4. 登记 owner（requestId + attemptId）并启动。
     *
     * 早先这是两个公开方法（先 `persistSubmission` 再 `submit`），调用方可以在
     * 锁外落库：连点或另一个页面就能落出两条 PREPARING，其中一条随后被
     * 「单飞」拒绝、永远停在 PREPARING。现在锁把 2–4 全部包住，不可能发生。
     *
     * @return 落盘的请求记录（调用方据此在界面上定位用户消息与回答气泡）。
     * @throws AlreadyRunningException 已有任务在跑。
     */
    suspend fun submit(
        submission: Submission,
        attemptId: String = UUID.randomUUID().toString(),
        answerMessageId: String = UUID.randomUUID().toString(),
        userMessageId: String = UUID.randomUUID().toString(),
        snapshotJson: String = "",
    ): RequestEntity {
        // 1) 启动扫描必须先完成：否则它会把我们刚写的 PREPARING 当成遗留孤儿。
        awaitStartup()
        return mutex.withLock {
            // 2) 锁内单飞判定：owner 存在即有人还在跑（不看 phase）。
            if (hasOwner()) throw AlreadyRunningException()
            // 2.5) **网络之前**钉下初始快照：原问题、原历史、照片路由、档案 id 与设置。
            // 它随请求记录在同一个事务里落库，准备阶段被打断也有东西可重试。
            val initialSnapshot = preparer.captureInitialSnapshot(
                submission = submission,
                conversationId = submission.conversationId,
                userMessageId = userMessageId,
                answerMessageId = answerMessageId,
            )
            // 3) 锁内落盘：用户消息 + 回答占位 + PREPARING 请求记录 + 初始快照（一个事务）。
            val record = try {
                requestRepository.createRequest(
                    conversationId = submission.conversationId,
                    userMessageId = userMessageId,
                    answerMessageId = answerMessageId,
                    attemptId = attemptId,
                    userText = submission.userText,
                    attachmentPaths = submission.attachmentPaths,
                    snapshotJson = snapshotJson.ifBlank { SnapshotCodec.encode(initialSnapshot) },
                    userDisplayContent = submission.userDisplayContent,
                )
            } catch (creationError: Throwable) {
                // 落盘失败 ⇒ 输入草稿由调用方保留，本轮不开始、不发网络。
                throw creationError
            }
            // 4) 登记 owner 并启动。
            val owner = try {
                registerOwner(record.requestId, record.attemptId)
            } catch (rejected: AlreadyRunningException) {
                // 极端交错（另一个入口绕过 mutex 直接 start）：已落盘的记录必须收成终态，
                // 不能永远停在 PREPARING。
                runCatching { abandonSubmission(record.requestId, "另一个会话正在生成回答") }
                throw rejected
            }
            _active.value = ActiveState(
                requestId = record.requestId,
                attemptId = record.attemptId,
                conversationId = record.conversationId,
                phase = RequestStatus.PREPARING,
                ownsTask = true,
                answerMessageId = record.answerMessageId,
            )
            launchTurn(owner, record.conversationId, record.answerMessageId) { owned ->
                runSubmission(record, submission, initialSnapshot, owned)
            }
            record
        }
    }

    /** 提交后的**准备 + 生成**整段；准备与运行共用一个 owner，只收尾一次。 */
    private suspend fun runSubmission(
        record: RequestEntity,
        submission: Submission,
        initialSnapshot: RequestSnapshot,
        owner: TaskOwner,
    ) {
        // 保护令牌覆盖**准备阶段**：照片转写也是网络调用，
        // 没有前台保护时进程被冻结会让整轮卡死。
        val token = guard.acquire(record.requestId, record.conversationId)
        try {
            val prepared = preparer.prepare(submission, record, initialSnapshot)
            if (prepared.failure != null) {
                settleFailureOnce(owner, record, prepared.failure, FailureKind.ATTACHMENT_MISSING, "")
                return
            }
            // **准备结果必须先落库再发主请求**：转写文本/路由补写进同一
            // request+attempt 的快照；补写被拒（attempt 已换/已终态）就中断，
            // 不能"补写失败也继续花钱发网络"。
            val persisted = requestRepository.saveSnapshotForAttempt(record.requestId, record.attemptId, prepared.snapshot, record)
            if (!persisted) {
                settleFailureOnce(
                    owner, record,
                    "本轮恢复信息未能写入（attempt 已过期），已停止",
                    FailureKind.NETWORK,
                    "",
                )
                return
            }
            prepared.transcript?.let { storeTranscript(record.userMessageId, it) }
            runGeneration(
                requestId = record.requestId,
                request = generationRequest(record, record.attemptId, prepared),
                owner = owner,
                record = record,
                // 令牌已由本协程持有，交给 runGeneration 不要重复释放。
                guardToken = token,
            )
        } catch (cancelledError: CancellationException) {
            // 准备或运行被取消：终态只落一次（owner 上的闩保证），原因是本轮自己的。
            settleStoppedOnce(owner, record, "")
            // **必须继续传播**：吞掉会让上层以为任务正常结束，取消语义丢失。
            throw cancelledError
        } catch (error: Throwable) {
            // 运行阶段的失败由它自己处理（内部已 catch Throwable 并广播），
            // 这里只兜准备阶段的异常。
            settleFailureOnce(owner, record, describePreparationError(error), classify(error), "")
        } finally {
            // 令牌**只释放一次**：runGeneration 在 guardToken 非空时不释放，
            // 由这里统一释放（它覆盖准备 + 生成两段）。
            guard.release(token)
        }
    }

    /**
     * 重试：准备阶段同样在应用作用域，与 [submit] 完全同构。
     *
     * 调用方先用 [RequestRepository.beginRetry] 原子换上新 attempt（可附带
     * [captureRetrySnapshot] 重新捕获的快照），再调这里。**记录必须仍在途且 attempt 匹配**：
     * 旧 attempt 的迟到重试会在这里被拒，不会注册 owner、更不会发网络。
     * 问题、附件、路线与设置全部以落库记录和快照为权威。
     */
    suspend fun submitRetry(requestId: String, attemptId: String) {
        val record = requestRepository.get(requestId)
            ?: throw IllegalStateException("请求记录不存在：$requestId")
        if (record.attemptId != attemptId || RequestStatus.fromName(record.status)?.isInFlight != true) {
            throw IllegalStateException("重试请求已过期（attempt/status 不匹配）")
        }
        awaitStartup()
        mutex.withLock {
            if (hasOwner()) throw AlreadyRunningException()
            val owner = registerOwner(requestId, attemptId)
            _active.value = ActiveState(
                requestId = requestId,
                attemptId = attemptId,
                conversationId = record.conversationId,
                phase = RequestStatus.PREPARING,
                ownsTask = true,
                answerMessageId = record.answerMessageId,
            )
            launchTurn(owner, record.conversationId, record.answerMessageId) { owned ->
                runRetry(record, attemptId, owned)
            }
        }
    }

    /**
     * 用户点「重新发送」时按**当前**服务商重新捕获快照（prepared=false），
     * 原附件按当前看图能力重新走路线。解题模式缺省沿用原快照。
     */
    suspend fun captureRetrySnapshot(
        record: RequestEntity,
        attachmentPaths: List<String> = RequestRepository.decodePathList(record.attachmentPaths),
        solveMode: SolveMode? = null,
        forceWebSearch: Boolean? = null,
    ): RequestSnapshot {
        val previous = SnapshotCodec.decode(record.snapshotJson)
        return preparer.captureInitialSnapshot(
            submission = Submission(
                conversationId = record.conversationId,
                userText = record.userText,
                attachmentPaths = attachmentPaths,
                solveMode = solveMode ?: SolveMode.fromName(previous?.solveMode) ?: SolveMode.DETAILED,
                forceWebSearch = forceWebSearch ?: previous?.forceWebSearch ?: false,
            ),
            conversationId = record.conversationId,
            userMessageId = record.userMessageId,
            answerMessageId = record.answerMessageId,
        )
    }

    private suspend fun runRetry(record: RequestEntity, attemptId: String, owner: TaskOwner) {
        // 保护覆盖准备阶段（重试也要重新编码/校验附件）。
        val token = guard.acquire(record.requestId, record.conversationId)
        try {
            val prepared = preparer.prepareRetry(record)
            if (prepared.failure != null) {
                settleFailureOnce(owner, record, prepared.failure, FailureKind.ATTACHMENT_MISSING, "")
                return
            }
            val persisted = requestRepository.saveSnapshotForAttempt(record.requestId, attemptId, prepared.snapshot, record)
            if (!persisted) {
                settleFailureOnce(
                    owner, record,
                    "本轮恢复信息未能写入（attempt 已过期），已停止",
                    FailureKind.NETWORK,
                    "",
                )
                return
            }
            prepared.transcript?.let { storeTranscript(record.userMessageId, it) }
            runGeneration(
                requestId = record.requestId,
                request = generationRequest(record, attemptId, prepared),
                owner = owner,
                record = record,
                guardToken = token,
            )
        } catch (cancelledError: CancellationException) {
            settleStoppedOnce(owner, record, "")
            throw cancelledError
        } catch (error: Throwable) {
            settleFailureOnce(owner, record, describePreparationError(error), classify(error), "")
        } finally {
            guard.release(token)
        }
    }

    private fun generationRequest(
        record: RequestEntity,
        attemptId: String,
        prepared: GenerationPreparer.Prepared,
    ) = GenerationRequest(
        conversationId = record.conversationId,
        userMessageId = record.userMessageId,
        answerMessageId = record.answerMessageId,
        attemptId = attemptId,
        userText = prepared.outgoingText,
        history = prepared.history,
        imageBase64s = prepared.imageBase64s,
        solveMode = prepared.solveMode,
        webSearchEnabled = prepared.webSearchEnabled,
        forceWebSearch = prepared.forceWebSearch,
        maxContinuations = prepared.maxContinuations,
        modelKey = prepared.snapshot.model,
        protocol = prepared.snapshot.protocol,
        usageGroupKey = prepared.usageGroupKey,
        modelLabel = modelLabelFor(prepared.snapshot),
        policy = prepared.policy,
    )

    /** 转写结果存到用户消息上供题目卡核对；写失败不影响生成。 */
    private suspend fun storeTranscript(userMessageId: String, transcript: String) {
        try {
            conversationDao.setTranscript(userMessageId, transcript)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "保存转写文本失败", error)
        }
    }

    /** 气泡上的模型名：提交时档案的名字；档案已删时退回模型名。 */
    private fun modelLabelFor(snapshot: RequestSnapshot): String =
        credentialStore.profiles().firstOrNull { it.id == snapshot.primaryProfileId }?.name
            ?.takeIf { it.isNotBlank() } ?: snapshot.model

    /** 是否有 owner（有即有任务未收完），供单飞判定。 */
    private fun hasOwner(): Boolean = synchronized(lock) { owner != null }

    /**
     * 本轮**被停止**（用户取消 / 服务超时）时的终态收尾：**只发生一次**。
     *
     * 两个坑都在这里堵住：
     * 1. 原因取自 owner（按 request+attempt 隔离），不是全局共享变量 ——
     *    上一轮的超时不会把这一轮的用户取消误判成超时；
     * 2. 准备段与运行段谁先收谁落账，另一段因 `claimSettlement()` 返回 false
     *    直接跳过，不会落两遍状态、也不会双发事件。
     */
    private suspend fun settleStoppedOnce(owner: TaskOwner, record: RequestEntity, partialText: String) {
        val timedOut = owner.stopReason == StopReason.SERVICE_TIMEOUT
        val kind = if (timedOut) FailureKind.SERVER else FailureKind.CANCELLED
        val message = if (timedOut) {
            "系统回收了后台运行时长（Android 15 前台服务超时），本轮已中断，可点重试继续"
        } else {
            "已取消"
        }
        if (!owner.claimSettlement()) return
        // 整段收尾都在 NonCancellable 里：本协程已被取消，任何挂起点都会立刻
        // 再抛 CancellationException，写不进去就成了「取消了但状态还停在 RUNNING」。
        withContext(NonCancellable) {
            runCatching {
                if (timedOut) {
                    requestRepository.interrupt(record.requestId, record.attemptId, partialText, kind, message)
                } else {
                    requestRepository.cancel(record.requestId, record.attemptId, partialText)
                }
            }
            updateActive(owner) {
                it.copy(
                    phase = if (timedOut) {
                        RequestStatus.INTERRUPTED
                    } else {
                        RequestStatus.CANCELLED
                    },
                    partialText = partialText,
                )
            }
            emitFailed(owner, record, kind, message, partialText)
        }
    }

    /** 非取消类失败（准备失败/异常)：同样只收一次。 */
    private suspend fun settleFailureOnce(
        owner: TaskOwner,
        record: RequestEntity,
        message: String,
        kind: FailureKind,
        partialText: String,
    ) {
        if (!owner.claimSettlement()) return
        withContext(NonCancellable) {
            runCatching {
                requestRepository.interrupt(record.requestId, record.attemptId, partialText, kind, message)
            }
            updateActive(owner) { it.copy(phase = RequestStatus.INTERRUPTED, partialText = partialText) }
            emitFailed(owner, record, kind, message, partialText)
        }
    }

    /** UI 状态更新一律带 (requestId, attemptId) 双身份围栏，绝不串轮。 */
    private fun updateActive(owner: TaskOwner, mutate: (ActiveState) -> ActiveState) {
        _active.update {
            if (it.requestId == owner.requestId && it.attemptId == owner.attemptId) mutate(it) else it
        }
    }

    private suspend fun emitFailed(
        owner: TaskOwner,
        record: RequestEntity,
        kind: FailureKind,
        message: String,
        partialText: String,
    ) {
        _events.emit(
            GenerationEvent.Failed(
                requestId = owner.requestId,
                attemptId = owner.attemptId,
                conversationId = record.conversationId,
                answerMessageId = record.answerMessageId,
                kind = kind,
                message = message,
                partialText = partialText,
                retryable = kind.isRetryable,
            ),
        )
    }

    private fun describePreparationError(error: Throwable): String =
        error.message ?: "准备阶段失败（${error::class.simpleName}）"

    private fun cancelMessage(reason: StopReason?): String =
        if (reason == StopReason.SERVICE_TIMEOUT) "前台服务超时" else "用户取消"

    /**
     * 结束原因：决定**取消路径**最终落成哪个终态。
     *
     * 系统服务超时也必须走「取消协程」这条技术路径（要让阻塞中的网络立刻停下），
     * 但它在产品语义上不是「用户主动取消」——
     * - 用户取消 ⇒ `CANCELLED` + **不可重试**（用户就是不想要了）；
     * - 服务超时 ⇒ `INTERRUPTED` + **可重试**（是系统打断了我们）。
     *
     * 原因挂在 owner 上，随这一轮的 (requestId, attemptId) 存在，
     * 因此不存在「上一轮的原因污染下一轮」的问题。
     */
    private enum class StopReason { USER_CANCELLED, SERVICE_TIMEOUT }

    /**
     * 请求停止某一轮：**校验、记录原因、取句柄、取消**，四步在同一把 [lock] 内完成。
     *
     * 关键约束：
     * - requestId 必须与当前 owner 一致才生效（取消错 id 完全无副作用）；
     * - 句柄仍未回填（body 尚未起来）时只记 `cancelRequested`，
     *   由 [launchTurn] 回填后立刻补取消 —— 不会出现「取消了个空」；
     * - 取消在**锁外**执行：`job.cancel()` 会同步触发已注册的 handler，
     *   而完成回调也要拿这把锁，持锁取消必然死锁；
     * - **不在这里把 owner 置空**：owner 一直持有到收尾真正完成，
     *   所以取消不会提前放开单飞和新一轮的门槛。
     */
    private fun markStopped(requestId: String, reason: StopReason, expectedAttemptId: String? = null): Boolean {
        val job = synchronized(lock) {
            val current = owner ?: return false
            if (current.requestId != requestId) return false
            // attempt 也比对：旧 attempt 的**迟到取消**不得掐掉已经换了 attempt 的新一轮。
            if (expectedAttemptId != null && current.attemptId != expectedAttemptId) return false
            // **首次接受的原因固定**：用户取消之后再来一个服务超时，不能把不可重试的
            // CANCELLED 改写成可重试的 INTERRUPTED（反之亦然）。重复停止保持幂等。
            if (current.stopReason == null) current.stopReason = reason
            current.cancelRequested = true
            current.job
        }
        job?.cancel(CancellationException(cancelMessage(reason)))
        return true
    }

    /**
     * 用户主动取消：真正取消 OkHttp 调用（协程取消会让 `execute()` 抛 IOException）。
     *
     * **只取消匹配的请求**：requestId 与当前 owner 不一致时不做任何事，
     * 避免「取消 A 却把 B 的网络掐了」。
     *
     * @param attemptId 传值时表示「这是某个 attempt 的回调」：与当前 attempt 不符
     *   （旧 attempt 的迟到取消）则**完全不生效**，当前这一轮继续跑完。
     */
    suspend fun cancel(requestId: String, attemptId: String? = null) {
        markStopped(requestId, StopReason.USER_CANCELLED, attemptId)
    }

    /**
     * 等待本轮协程**真正结束**（用于超时/取消后需要同步推进的场合）。
     *
     * 只在**锁外** join；owner 由协程自己的收尾路径归还。
     */
    private suspend fun awaitOwnerFinished(requestId: String, attemptId: String) {
        val job = synchronized(lock) {
            val current = owner
            if (current != null && current.requestId == requestId && current.attemptId == attemptId) {
                current.job
            } else {
                null
            }
        }
        job?.join()
    }

    /** 是否有活动任务（用于界面提示）。 */
    fun isRunning(): Boolean = _active.value.isRunning

    fun activeRequestId(): String? = _active.value.requestId

    /** 当前活动请求的会话；无活动任务时为 null。 */
    fun activeConversationId(): String? = _active.value.conversationId

    /**
     * Android 15 dataSync 前台服务超时回调。
     *
     * 系统已经不允许我们继续占用前台，所以必须**主动收尾**，而不是只停服务：
     * 1. 真取消网络（`Call.cancel()`，阻塞中的 SSE 读取立刻返回）；
     * 2. 落 **INTERRUPTED** 终态（不是 CANCELLED）—— 系统打断的任务应当
     *    保留「重新发送」入口，用户并没有主动放弃；
     * 3. 不自动重发。
     *
     * **必须同步登记原因**：回调在系统线程触发，若等 scope.launch 排队后再登记，
     * 迟到的用户取消会抢先占据"首次原因"，把可重试的超时写成不可重试的取消。
     * 这里在回调边界就于 owner 锁内固定 SERVICE_TIMEOUT，慢速落库留给协程。
     */
    fun onForegroundServiceTimeout() {
        val jobToCancel = synchronized(lock) {
            val current = owner ?: return
            if (current.stopReason == null) current.stopReason = StopReason.SERVICE_TIMEOUT
            current.cancelRequested = true
            current.job
        }
        jobToCancel?.cancel(CancellationException(cancelMessage(StopReason.SERVICE_TIMEOUT)))
    }

    /**
     * 跑一轮生成（已落盘、已占活动状态）。
     *
     * [guardToken]：前台服务保护令牌。**由调用方持有并释放**，因为保护必须
     * 覆盖准备阶段（转写/选上下文也是网络调用），而准备在调用方那边跑。
     * 传 null 表示本函数自己 acquire/release（[start] 直连路径）。
     */
    private suspend fun runGeneration(
        requestId: String,
        request: GenerationRequest,
        /** 本轮所有者：终态收尾权与结束原因都在它上面，准备/生成两段共享。 */
        owner: TaskOwner,
        /** 落库记录（对话 id / 回答位置）；[start] 直连路径下可能为合成值。 */
        record: RequestEntity? = null,
        guardToken: String? = null,
    ) {
        val conversationId = record?.conversationId ?: request.conversationId
        val answerMessageId = record?.answerMessageId ?: request.answerMessageId
        val timer = GenerationTimer(monotonicClock)
        // 每个 HTTP 轮次一个解码器：续写/恢复是不同的请求体，正文要分别累积再合并。
        var partial = ""
        var extraRequests = 0
        var confirmedText = ""
        val decoder = IncrementalReplyDecoder()
        val answerDecoder = IncrementalReplyDecoder("answer")
        var lastFlushAt = 0L

        // ── usage 归集 ──
        // **每个真实 HTTP 调用**上报一次：首轮 + 续写 + 自纠正 + 恢复 + 流式不支持回退。
        // 同一请求内的累计块只取最终值（由 UsageAccumulator 保证），绝不累加。
        // 取不到就是「未知」，不冒充 0。
        //
        // totalHttpCalls 就是真实 HTTP 调用总数（含没上报 usage 的那些），
        // 不再用循环轮数冒充 —— 恢复请求与流式不支持回退都不发生在续写循环里，
        // 按轮数统计会整个漏掉。
        var callsWithUsage = 0
        var callsWithoutUsage = 0
        var lastUsage: UsageSample? = null
        val collectUsage: (UsageSample?) -> Unit = { sample ->
            if (sample == null) {
                callsWithoutUsage++
            } else {
                callsWithUsage++
                lastUsage = sample
            }
            // 逐次留痕：失败/缺失也计一次，进入"样本不可得"而不是被丢掉。
            diagnostics.recordHttpUsage(request.usageGroupKey, sample)
        }
        /** 真实 HTTP 调用总数。 */
        fun totalHttpCalls(): Int = callsWithUsage + callsWithoutUsage
        /** 除首轮之外的实际 HTTP 请求数。 */
        fun extraHttpCalls(): Int = (totalHttpCalls() - 1).coerceAtLeast(0)

        // 在途期间持有前台服务保护；按 requestId 持有令牌，结束/异常都会释放。
        val ownsToken = guardToken == null
        val token = guardToken ?: guard.acquire(requestId, request.conversationId)
        try {
            _events.emit(
                GenerationEvent.Started(
                    requestId = requestId,
                    attemptId = request.attemptId,
                    conversationId = request.conversationId,
                    answerMessageId = request.answerMessageId,
                ),
            )
            // 必须先拿到 RUNNING 才发网络：返回 false 说明这一轮已经终态或 attempt 已过期
            // （迟到的旧协程），此时绝不能再请求模型 —— 那会为一个已结束的请求付费。
            if (!requestRepository.markRunning(requestId, request.attemptId)) {
                throw CancellationException("这一轮已不是活动请求（attempt 过期或已终态）")
            }

            var continuation = 0
            var barrenRounds = 0
            var lastResult: ParsedReply? = null
            // 标题与学科取**首个**给出的轮次：续写轮的模型只看到尾部，判断不如首轮可靠。
            var conversationTitle: String? = null
            var finalAnswer = ""
            var shouldStop = false
            // 图表跨轮累积：只用最后一轮的 plots 会丢掉前面轮次已产出的图，
            // 表现为正文写着「见下图」而图整个消失。
            val accumulatedFigures = mutableListOf<ReplyFigure>()

            while (!shouldStop) {
                val firstRound = continuation == 0
                val history = if (firstRound) {
                    request.history
                } else {
                    request.history +
                        ChatMessage("assistant", confirmedText.takeLast(CONTINUATION_ECHO_CHARS)) +
                        ChatMessage("user", CONTINUE_INSTRUCTION)
                }
                decoder.reset()
                answerDecoder.reset()
                extraRequests++
                val roundText = StringBuilder()
                val reply = modelClient.chatStreaming(
                    messages = history,
                    solveMode = request.solveMode,
                    imageBase64s = if (firstRound) request.imageBase64s else emptyList(),
                    webSearchEnabled = request.webSearchEnabled && firstRound,
                    forceWebSearch = request.forceWebSearch && firstRound,
                    // 这一轮 HTTP 调用的 usage：成功/失败/缺失都要计数。
                    onUsage = collectUsage,
                    // 整轮（含恢复/续写）沿用同一份不可变配置，不重读活动档案。
                    policy = request.policy,
                ) { event ->
                    when (event) {
                        is StreamEvent.ReasoningDelta -> {
                            timer.markReasoning()
                            // 推理走独立事件，**永不**写入正文 —— 只有推理没有答案时
                            // 最终是可恢复失败，绝不把推理链当成用户答案。
                            _active.update {
                                // 双身份围栏：requestId **和** attemptId 都要匹配。
                                if (it.requestId == requestId && it.attemptId == request.attemptId) {
                                    it.copy(reasoning = it.reasoning + event.text)
                                } else {
                                    it
                                }
                            }
                            _events.emit(
                                GenerationEvent.Reasoning(
                                    requestId = requestId,
                                    attemptId = request.attemptId,
                                    conversationId = request.conversationId,
                                    text = event.text,
                                ),
                            )
                        }
                        is StreamEvent.AnswerDelta -> {
                            timer.markAnswerByte()
                            answerDecoder.append(event.text)
                            if (firstRound && answerDecoder.valueComplete && answerDecoder.text.isNotBlank()) {
                                finalAnswer = answerDecoder.text.trim()
                                updateActive(owner) { it.copy(finalAnswer = finalAnswer) }
                            }
                            // 只推**解码出的可见正文**：外层 JSON、plan_actions、
                            // plots、diagrams、控制标记都不会露给用户。
                            val delta = decoder.append(event.text)
                            if (delta.isNotEmpty()) {
                                timer.markVisibleText()
                                roundText.append(delta)
                                partial = confirmedText + roundText
                                // 可重放状态：页面重新订阅后立刻能看到当前正文。
                                _active.update {
                                    if (it.requestId == requestId && it.attemptId == request.attemptId) {
                                        it.copy(partialText = partial, answerStarted = true)
                                    } else {
                                        it
                                    }
                                }
                                _events.emit(
                                    GenerationEvent.AnswerDelta(
                                        requestId = requestId,
                                        attemptId = request.attemptId,
                                        conversationId = request.conversationId,
                                        text = delta,
                                        accumulated = partial,
                                    ),
                                )
                                // 节流落盘：避免每 token 写库；终态与阶段变化另即时写。
                                val now = monotonicClock.nanoTime() / 1_000_000
                                if (now - lastFlushAt >= partialFlushIntervalMs) {
                                    lastFlushAt = now
                                    requestRepository.savePartial(requestId, request.attemptId, partial)
                                }
                            }
                        }
                        StreamEvent.AnswerReset -> {
                            decoder.reset()
                            answerDecoder.reset()
                            if (firstRound) finalAnswer = ""
                            roundText.setLength(0)
                            partial = confirmedText
                            updateActive(owner) { it.copy(partialText = partial, answerStarted = false, finalAnswer = finalAnswer) }
                        }
                    }
                }

                lastResult = reply
                val streamedReply = decoder.finish()
                var part = reconcileStreamedReply(streamedReply, reply.reply)
                part = localizeFigureAnchors(part, reply.orderedFigures().size)
                // 续写轮里模型不知道前面已画过几张图，锚点通常从 1 重新编号：
                // 按已累积数量平移，保证 [[FIGURE:n]] 始终指向合并列表里的正确下标。
                if (accumulatedFigures.isNotEmpty()) {
                    part = offsetFigureAnchors(part, accumulatedFigures.size)
                }

                // A model can spend time reasoning and still return only
                // "见上" (or an empty reply). Neither is a completed answer.
                // Clear the transient placeholder before the existing failure
                // path stores partial text, so the user gets a retry entry
                // instead of a successful empty/placeholder bubble.
                if (part.isBlank() && confirmedText.isBlank() && reply.orderedFigures().isEmpty()) {
                    partial = ""
                    throw ModelException(
                        ModelException.Kind.INVALID_RESPONSE,
                        "模型未返回有效正文，请重试",
                    )
                }

                // **保留全部合并正文**：续写必须在前文基础上追加，
                // 绝不能只留最后一轮（那会把用户已经看到的长正文整段抹掉）。
                if (conversationTitle == null) conversationTitle = reply.conversationTitle
                if (firstRound) finalAnswer = localizeFigureAnchors(reply.finalAnswer, reply.orderedFigures().size)
                confirmedText = mergeContinuation(confirmedText, part)
                partial = confirmedText
                // 按轮序累积所有图的槽位（含失败槽位），失败槽位同样占号，
                // 否则后面 [[FIGURE:n]] 的指向会整体错位。
                accumulatedFigures += reply.orderedFigures()
                val hitLimit = reply.truncated
                if (isMeaningfulRound(reply.reply)) barrenRounds = 0 else barrenRounds++
                shouldStop = !shouldContinueGeneration(
                    truncated = hitLimit,
                    barrenRounds = barrenRounds,
                    continuation = continuation,
                    maxContinuations = request.maxContinuations,
                    accumulatedChars = confirmedText.length,
                )
                if (!shouldStop) continuation++
            }

            // 收尾：正文与请求状态**原子提交**。
            timer.markCompleted()
            val result = lastResult?.copy(conversationTitle = conversationTitle, finalAnswer = finalAnswer)
                ?: throw ModelException(
                    ModelException.Kind.INVALID_RESPONSE,
                    "模型没有返回任何内容",
                )
            // 截断收尾时做修饰并明示（作用于整篇而不是最后一段）。
            val finalText = if (result.truncated) polishTruncatedTail(confirmedText) else confirmedText
            // 已被取消（或被新旧轮抢占）的协程不得宣称成功：忽略取消的慢网络
            // 走到这里会把已经取消的一轮写成 COMPLETED。
            //
            // 这是**第二道**防线：DAO 的 attempt + 终态条件本来就会挡住迟到写入，
            // 但"取消后不得宣称成功"不该只靠存储层的条件成立。
            val currentJob = currentCoroutineContext()[Job] ?: return@runGeneration
            if (!currentJob.isActive) {
                throw CancellationException("提交最终结果前这一轮已被取消")
            }
            // ── 绘图 + 学科归档 + 最终持久化，全部在管理器内完成 ──
            // 页面此时可能已经销毁；这些工作与页面无关，所以切页回来图还在。
            //
            // 刻意**不**放在 NonCancellable 里：绘图可能很慢，用户取消期间若不让它
            // 收到取消，后面的事务提交就会把一个已取消的轮写成 COMPLETED。
            // 事务提交本身的原子性由 DAO 的单条 @Transaction 保证。
            val renderResult = finalizer.finalizeTurn(
                requestId = requestId,
                attemptId = request.attemptId,
                answerMessageId = request.answerMessageId,
                mergedText = finalText,
                figures = accumulatedFigures,
                result = result,
                modelLabel = request.modelLabel,
                durationMs = timer.snapshot().completedMs ?: 0L,
                usageJson = encodeUsage(lastUsage),
            )
            // 绘图期间的取消必须在提交终态之前被看到。
            if (!currentJob.isActive) {
                throw CancellationException("提交最终结果前这一轮已被取消")
            }
            // 保存失败必须上报，不能假成功：库里可能仍是 RUNNING 而没有正文。
            if (!renderResult.saved) {
                throw ModelException(
                    ModelException.Kind.INVALID_RESPONSE,
                    "回答没能保存到本地，请重试",
                )
            }
            updateActive(owner) {
                it.copy(partialText = finalText, phase = RequestStatus.COMPLETED)
            }
            diagnostics.record(
                GenerationDiagnostics.Entry(
                    requestId = requestId,
                    // 归集键用**安全端点身份 + 模型 + 协议**：只按模型名会把
                    // 「同一模型名挂在两个供应商」的样本混在一起算命中率。
                    modelKey = request.usageGroupKey,
                    protocol = request.protocol,
                    timings = timer.snapshot(),
                    extraRequests = extraHttpCalls(),
                    outcome = "COMPLETED",
                ),
            )
            // 每个真实 HTTP 调用都要留痕（含没上报 usage 的那些）。
            diagnosticObserver?.invoke(
                GenerationDiagnostic(
                    requestId = requestId,
                    protocol = request.protocol,
                    usageGroupKey = request.usageGroupKey,
                    httpCalls = totalHttpCalls(),
                    callsWithUsage = callsWithUsage,
                    callsWithoutUsage = callsWithoutUsage,
                    outcome = "COMPLETED",
                ),
            )
            _events.emit(
                GenerationEvent.Completed(
                    requestId = requestId,
                    attemptId = request.attemptId,
                    conversationId = request.conversationId,
                    answerMessageId = request.answerMessageId,
                    result = result,
                    timings = timer.snapshot(),
                    answerImagePaths = renderResult.imagePaths,
                    mergedText = finalText,
                ),
            )
            // 成功也是「终态已落定」：准备段的 catch 不应再处理一次。
            owner.claimSettlement()
        } catch (cancelledError: CancellationException) {
            // 取消有**两种**触发源（见 StopReason 注释）：用户取消落 CANCELLED，
            // 前台服务超时落 INTERRUPTED。原因在 owner 上，按本轮隔离。
            timer.markInterrupted()
            val timedOut = owner.stopReason == StopReason.SERVICE_TIMEOUT
            if (timedOut) {
                // 超时也要留诊断记录：否则「系统超时」在诊断里完全看不见。
                diagnostics.record(
                    GenerationDiagnostics.Entry(
                        requestId = requestId,
                        modelKey = request.usageGroupKey,
                        protocol = request.protocol,
                        timings = timer.snapshot(),
                        extraRequests = extraHttpCalls(),
                        outcome = "SERVICE_TIMEOUT",
                    ),
                )
                diagnosticObserver?.invoke(
                    GenerationDiagnostic(
                        requestId = requestId,
                        protocol = request.protocol,
                        usageGroupKey = request.usageGroupKey,
                        httpCalls = totalHttpCalls(),
                        callsWithUsage = callsWithUsage,
                        callsWithoutUsage = callsWithoutUsage,
                        outcome = "SERVICE_TIMEOUT",
                    ),
                )
            }
            // 只收尾一次：准备段/运行段谁先到谁落账，另一段自动跳过。
            settleStoppedOnce(owner, record ?: fallbackRecord(requestId, request, conversationId, answerMessageId), partial)
            // **必须继续传播**：吞掉 CancellationException 会让上层协程以为
            // 任务正常结束，取消语义就此丢失。
            throw cancelledError
        } catch (error: Throwable) {
            timer.markInterrupted()
            val kind = classify(error)
            val message = error.message ?: "未知错误"
            diagnostics.record(
                GenerationDiagnostics.Entry(
                    requestId = requestId,
                    modelKey = request.usageGroupKey,
                    protocol = request.protocol,
                    timings = timer.snapshot(),
                    extraRequests = extraHttpCalls(),
                    outcome = kind.name,
                ),
            )
            diagnosticObserver?.invoke(
                GenerationDiagnostic(
                    requestId = requestId,
                    protocol = request.protocol,
                    usageGroupKey = request.usageGroupKey,
                    httpCalls = totalHttpCalls(),
                    callsWithUsage = callsWithUsage,
                    callsWithoutUsage = callsWithoutUsage,
                    outcome = kind.name,
                ),
            )
            settleFailureOnce(owner, record ?: fallbackRecord(requestId, request, conversationId, answerMessageId), message, kind, partial)
        } finally {
            // 所有出口都释放保护令牌，绝不永久驻留。
            //
            // 这里**绝不**再写一次 partial：以前那句兜底的 `savePartial` 用默认状态
            // RUNNING，会把刚刚写好的 COMPLETED/CANCELLED 又改回 RUNNING，
            // 界面就永远停在「生成中」。终态只由上面的收尾路径写。
            // 令牌归调用方时由调用方释放（保护要覆盖准备阶段）。
            if (ownsToken) guard.release(token)
        }
    }

    /** [start] 直连路径下没有落库记录：用请求本身的信息补一个最小记录供收尾用。 */
    private fun fallbackRecord(
        requestId: String,
        request: GenerationRequest,
        conversationId: String,
        answerMessageId: String,
    ): RequestEntity = RequestEntity(
        requestId = requestId,
        conversationId = conversationId,
        userMessageId = request.userMessageId,
        answerMessageId = answerMessageId,
        attemptId = request.attemptId,
        status = RequestStatus.RUNNING.name,
        userText = request.userText,
    )

    private fun classify(error: Throwable): FailureKind = when (error) {
        is ModelException -> when (error.kind) {
            ModelException.Kind.NETWORK -> FailureKind.NETWORK
            ModelException.Kind.SERVER -> FailureKind.SERVER
            ModelException.Kind.UNAUTHORIZED -> FailureKind.CONFIG_INVALID
            ModelException.Kind.NOT_CONFIGURED -> FailureKind.CONFIG_INVALID
            ModelException.Kind.CONFIG_INVALID -> FailureKind.CONFIG_INVALID
            ModelException.Kind.INVALID_RESPONSE -> FailureKind.NO_CONTENT
        }
        is java.io.FileNotFoundException -> FailureKind.ATTACHMENT_MISSING
        else -> FailureKind.UNKNOWN
    }

    /**
     * 回答气泡展示用的 usage：取最后一次 HTTP 调用的样本（续写轮的输入已含前文）。
     * 缺失字段不写，界面据此显示「未知」而不是 0。
     */
    private fun encodeUsage(sample: UsageSample?): String {
        if (sample == null) return ""
        return buildJsonObject {
            sample.inputTokens?.let { put("inputTokens", it) }
            sample.cachedInputTokens?.let { put("cachedInputTokens", it) }
            sample.outputTokens?.let { put("outputTokens", it) }
            if (sample.source.isNotBlank()) put("source", sample.source)
        }.toString()
    }

    class AlreadyRunningException : IllegalStateException("已有生成任务在进行中")

    companion object {
        private const val TAG = "MogeGen"

        /** 部分正文节流落盘间隔（毫秒）。 */
        internal const val PARTIAL_FLUSH_INTERVAL_MS = 700L
    }
}


/**
 * 一轮生成的**收尾纯数据引擎**：绘图 → 最终持久化（正文、图、模型名、耗时、usage、标题、学科）。
 *
 * 属于生成管理器、与页面生命周期无关：用户切页/旋转/锁屏后回来，图和学科归档都在。
 */
@Singleton
class GenerationFinalizer @Inject constructor(
    private val requestRepository: RequestRepository,
    private val figureRenderer: FigureRenderer,
) {
    /** 收尾结果：[saved] 为 false 表示持久化被拒（attempt 过期/终态/位置不符）。 */
    data class Result(
        val saved: Boolean,
        val imagePaths: List<String> = emptyList(),
    )

    /**
     * 绘图**可取消**：渲染抛出的 CancellationException 必须原样传播，
     * 绝不能当成「这张图渲染失败」吞掉，否则取消期间仍会走到提交 COMPLETED。
     */
    private suspend fun renderFigures(figures: List<ReplyFigure>): List<String> =
        try {
            figureRenderer.render(figures)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 渲染整体失败保留空串占位，不能让整轮回答失败。
            figures.map { "" }
        }

    suspend fun finalizeTurn(
        requestId: String,
        attemptId: String,
        answerMessageId: String,
        mergedText: String,
        figures: List<ReplyFigure>,
        result: ParsedReply,
        modelLabel: String = "",
        durationMs: Long = 0,
        usageJson: String = "",
    ): Result = withContext(Dispatchers.Default) {
        // 按槽位渲染：失败槽位保留空串占位，绝不能把后面的图往前挪，
        // 否则正文里 [[FIGURE:n]] 的指向会整体错位。
        val imagePaths = renderFigures(figures)
        val saved = requestRepository.complete(
            requestId = requestId,
            attemptId = attemptId,
            answerMessageId = answerMessageId,
            text = mergedText,
            answerImagePaths = imagePaths,
            modelLabel = modelLabel,
            durationMs = durationMs,
            usageJson = usageJson,
            conversationTitle = result.conversationTitle,
            finalAnswer = result.finalAnswer,
        )
        Result(saved = saved, imagePaths = imagePaths)
    }
}
