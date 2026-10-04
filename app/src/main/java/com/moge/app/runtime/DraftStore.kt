package com.moge.app.runtime

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按会话保存**未发送**的草稿（文字 + 待发送附件引用），跨进程存活。
 *
 * 为什么必须落盘：以前草稿只在 界面输入框 里，进程被回收
 * （切后台被查杀、用户手动杀掉）后用户刚打的一半内容连同拍好的照片全没了。
 * 附件尤其可惜 —— 照片是用户现拍的，丢了只能重拍。
 *
 * 存储用应用私有目录下的普通文件，**不进数据库、不进备份**：
 * 草稿是本机瞬时状态。
 *
 * 文件格式（单行分节，避免引入额外序列化依赖）：
 * ```
 * <text>
 * \u0000<path1>
 * \u0000<path2>
 * ```
 *
 * 类为 open、带 internal open 持久层钩子（写出 / 提交校验 / 删除校验）：
 * 仅供测试注入真实磁盘故障，不引入 DI 复杂层。
 */
@Singleton
open class DraftStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 一条草稿。[photoPaths] 是已固定在私有持久目录里的绝对路径。 */
    data class Draft(val text: String, val photoPaths: List<String>, val documentPaths: List<String> = emptyList())

    /**
     * [reserveSave] 返回的句柄。只有当它仍是该会话**最新** revision 时，
     * [persist] 才会把它对应的 pending 落盘。
     */
    class Reservation internal constructor(
        internal val conversationId: String?,
        internal val revision: Long,
    )

    /** 最新一次 reserve 的内容与序号。 */
    private class Pending(val revision: Long, val text: String, val photoPaths: List<String>)

    /** 每个会话键一份：锁、revision 计数、最新 pending，只在该键的锁内读写。 */
    private class KeyState {
        val lock = Any()
        var lastRevision = 0L
        var pending: Pending? = null
    }

    private val states = java.util.concurrent.ConcurrentHashMap<String, KeyState>()

    private fun stateFor(conversationId: String?): KeyState =
        states.computeIfAbsent(conversationId ?: NEW_CONVERSATION_KEY) { KeyState() }

    private fun dir(): File = File(context.filesDir, "drafts").apply { mkdirs() }

    private fun fileFor(conversationId: String?): File {
        val name = (conversationId ?: NEW_CONVERSATION_KEY).replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir(), "draft_$name.txt")
    }

    private fun atomicFileFor(conversationId: String?): AtomicFile = AtomicFile(fileFor(conversationId))

    /**
     * 读取草稿；没有或内容为空时返回 null。
     * **优先 pending**：存在尚未落盘的最新 reserve 时直接返回它，不等 persist。
     */
    suspend fun load(conversationId: String?): Draft? = withContext(Dispatchers.IO) {
        withLock(conversationId) { state -> loadLocked(conversationId, state) }
    }

    /**
     * 保存草稿；内容为空即删除。便捷写法：
     * 内部就是"同步 reserve → 同锁内 persist"，失败时异常向上传播、磁盘保旧。
     */
    suspend fun save(conversationId: String?, text: String, photoPaths: List<String>, documentPaths: List<String> = emptyList()) =
        withContext(Dispatchers.IO) {
            withLock(conversationId) { state ->
                val revision = reserveLocked(state, text, photoPaths + documentPaths.map { DOCUMENT_PREFIX + it })
                persistReservedLocked(conversationId, state, revision)
            }
        }

    /**
     * 删除会话时清理其草稿。**任何图片文件都不删**：无法可靠判断照片是否已被
     * 历史消息引用，删错一张，用户回看那条消息时图片就打不开 —— 照片是现拍的，
     * 丢了找不回。宁留少量孤儿文件，不冒误删历史内容的风险。
     *
     * 同时**使未决 reservation 失效**：登记"空草稿"为最新 revision 再走统一落盘
     * （空 = 删文件），还在路上的旧 persist 会因 revision 过期被跳过，草稿不会复活。
     */
    suspend fun clear(conversationId: String?) =
        withContext(Dispatchers.IO) {
            withLock(conversationId) { state ->
                val revision = reserveLocked(state, "", emptyList())
                persistReservedLocked(conversationId, state, revision)
            }
        }

    /**
     * **条件清理**：只有**最新 pending**（未落盘的最新意图；没有 pending 时看落盘内容）
     * 的文字与附件都仍等于"发送时留下的基准"才清掉 —— 清理本身也是一次
     * reserve（递增 revision、使旧 persist 失效）+ persist。
     *
     * 用户在生成期间**重新输入了文字或加了新照片**（pending 已变）时保留草稿，
     * **绝不覆盖更晚的编辑**。没有基准（[baselineText] 为 null）时保守起见**不清**。
     *
     * 返回 true 表示确实清理了。
     */
    suspend fun clearIfBaselineMatches(
        conversationId: String?,
        baselineText: String?,
        baselinePhotos: List<String> = emptyList(),
        baselineDocuments: List<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        withLock(conversationId) { state ->
            if (baselineText == null) return@withLock false
            val current = loadLocked(conversationId, state)
            if (current != null) {
                val textChanged = current.text != baselineText
                val photosChanged = current.photoPaths != baselinePhotos
                if (textChanged || photosChanged || current.documentPaths != baselineDocuments) return@withLock false
            }
            val revision = reserveLocked(state, "", emptyList())
            persistReservedLocked(conversationId, state, revision)
            true
        }
    }

    // ── reserve / persist：UI 意图与落盘解耦 ──

    /**
     * **同步**登记一次保存意图：分配该会话下一个 revision 并登记为最新 pending。
     * 无 IO，UI 线程可安全调用 —— 供 VM 在 launch 之前先 reserve：
     * ```
     * val reservation = draftStore.reserveSave(conversationId, text, photos)
     * viewModelScope.launch { draftStore.persist(reservation) }
     * ```
     * 同一会话更新的 reserve 会让更早的 reservation 过期（[persist] 只认最新）。
     */
    fun reserveSave(conversationId: String?, text: String, photoPaths: List<String>, documentPaths: List<String> = emptyList()): Reservation =
        withLock(conversationId) { state ->
            val revision = reserveLocked(state, text, photoPaths + documentPaths.map { DOCUMENT_PREFIX + it })
            Reservation(conversationId, revision)
        }

    /**
     * 把 [reservation] 对应的 pending 落盘。**只有最新 revision 会真正写盘**：
     * 已被更新的 reserve 取代（或已被 clear 失效）时直接返回，旧内容绝不反写磁盘。
     * 写失败异常向上传播、磁盘保持旧内容；pending 保留，进程内仍可重试。
     */
    suspend fun persist(reservation: Reservation) = withContext(Dispatchers.IO) {
        withLock(reservation.conversationId) { state ->
            persistReservedLocked(reservation.conversationId, state, reservation.revision)
        }
    }

    /**
     * 所有草稿引用的照片：先取尚未落盘的最新编辑，再读磁盘上的正式文件、`.bak` 和 `.new`，
     * 正在恢复的草稿也不能丢图。只用于孤儿文件回收，宁可多留。
     * 任何草稿读不出来都直接抛出（含与提交并发时文件刚被改名），调用方据此放弃本轮回收。
     */
    suspend fun referencedPhotoPaths(): Set<String> = withContext(Dispatchers.IO) {
        val result = mutableSetOf<String>()
        states.values.forEach { state -> synchronized(state.lock) { state.pending?.let { result += it.photoPaths } } }
        val files = dir().listFiles() ?: throw IOException("无法列出草稿目录")
        files.filter { it.name.startsWith("draft_") }.forEach { file ->
            result += file.readText(Charsets.UTF_8).split(DRAFT_SEPARATOR).drop(1)
        }
        result.filter { it.isNotBlank() }.map { it.removePrefix(DOCUMENT_PREFIX) }.toSet()
    }

    // ── 内部实现：每键串行 + revision + AtomicFile 原子写 ──

    private inline fun <T> withLock(conversationId: String?, block: (KeyState) -> T): T {
        val state = stateFor(conversationId)
        synchronized(state.lock) { return block(state) }
    }

    /** 仅在已持有该键锁时调用：登记最新 pending 并分配下一个 revision。 */
    private fun reserveLocked(state: KeyState, text: String, photoPaths: List<String>): Long {
        val revision = ++state.lastRevision
        state.pending = Pending(revision, text, photoPaths.toList())
        return revision
    }

    /** 仅在已持有该键锁时调用：pending 仍是 [revision] 时才落盘；成功后清 pending。 */
    private fun persistReservedLocked(conversationId: String?, state: KeyState, revision: Long) {
        val entry = state.pending ?: return            // 早前一次 persist 已把它落盘
        if (entry.revision != revision) return         // 已被更新的 reserve / clear 取代：跳过
        saveLocked(conversationId, entry.text, entry.photoPaths)
        state.pending = null
    }

    /**
     * pending 优先：它可能是尚未落盘、甚至内容为空的最新意图（如 clear 登记后写失败），
     * 都要压过磁盘上的旧内容。没有 pending 才读磁盘。
     */
    private fun loadLocked(conversationId: String?, state: KeyState): Draft? {
        state.pending?.let { entry -> return normalize(entry.text, entry.photoPaths) }
        return loadDiskLocked(conversationId)
    }

    /** 空草稿读成 null；只剔除空串段（格式噪声），**不**剔除已不存在的文件引用。 */
    private fun normalize(text: String, photoPaths: List<String>): Draft? {
        val paths = photoPaths.filter { it.isNotBlank() }
        val photos = paths.filterNot { it.startsWith(DOCUMENT_PREFIX) }
        val documents = paths.filter { it.startsWith(DOCUMENT_PREFIX) }.map { it.removePrefix(DOCUMENT_PREFIX) }
        return if (text.isEmpty() && paths.isEmpty()) null else Draft(text, photos, documents)
    }

    /**
     * 读取必须走 [AtomicFile.openRead]：上次提交中途被杀留下的 `.bak` 备份，
     * openRead 会**先恢复成正式文件再读**。所以不能用 `base.exists()` 之类的
     * 判断挡在前面 —— 恰好"只剩备份、正式文件还没落地"时，那种前置判断会把
     * 本可恢复的草稿读成 null。
     *
     * "无草稿"的唯一判定：base / .bak / .new **三个文件都不存在**（openRead 抛
     * [FileNotFoundException] 时再核对一遍，防止把"文件在但打不开"误当空草稿）；
     * 其余读失败（存在但打不开、读中断等）**不吞，向上抛**。
     * NUL 分节单行文本；缺图引用**不过滤**，
     * 留给发送时的 [persistAttachments] 明确报错。
     */
    private fun loadDiskLocked(conversationId: String?): Draft? {
        val raw = try {
            atomicFileFor(conversationId).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: FileNotFoundException) {
            // openRead 找不到 base 时才抛 FNFE；此时再确认 .bak/.new 也不在，
            // 三个文件均不存在才算"本来就没有草稿"。
            if (draftFileExists(conversationId)) {
                throw IOException("草稿文件存在但无法读取: ${fileFor(conversationId).name}", e)
            }
            return null
        }
        val parts = raw.split(DRAFT_SEPARATOR)
        return normalize(parts.firstOrNull().orEmpty(), parts.drop(1))
    }

    /** base / .bak / .new 任一存在（含被打成目录等"存在但打不开"的情况）即视为有草稿数据。 */
    private fun draftFileExists(conversationId: String?): Boolean {
        val base = fileFor(conversationId)
        return base.exists() ||
            File(base.parentFile, "${base.name}.bak").exists() ||
            File(base.parentFile, "${base.name}.new").exists()
    }

    /**
     * **原子写**：[AtomicFile.startWrite] 给出指向临时文件的流；写失败则
     * [AtomicFile.failWrite] 删掉半截临时文件，正式文件保持旧内容，异常向上传播，
     * **绝不退化为直接覆盖**。
     *
     * 为什么不能只信"没抛异常"：`finishWrite` 在部分 Android 实现里对 rename
     * 失败只记一条 log 不抛（`File.renameTo` 覆盖既有文件是平台相关行为）。
     * 所以提交前后都做显式校验（见 [writeCommitted]）。
     */
    private fun saveLocked(conversationId: String?, text: String, photoPaths: List<String>) {
        val atomic = atomicFileFor(conversationId)
        if (text.isEmpty() && photoPaths.isEmpty()) {
            deleteCommitted(atomic)
            return
        }
        writeCommitted(atomic, (listOf(text) + photoPaths).joinToString(DRAFT_SEPARATOR).toByteArray(Charsets.UTF_8))
    }

    /**
     * 真正的持久层：startWrite → 写出 → 显式 `fd.sync()` → finishWrite →
     * **读回校验** base 确实等于本次 bytes、且无未提交的 `.new` 残留。
     * 任何一环失败：failWrite 保旧 + 异常向上传播。
     */
    private fun writeCommitted(atomic: AtomicFile, bytes: ByteArray) {
        val stream = atomic.startWrite() // 打不开临时文件：尚无可回滚之物，异常直接向上
        try {
            writeDraftBytes(stream, bytes)
            stream.fd.sync() // 显式刷盘：sync 错误在这里抛，而不是藏在 finishWrite 的 close 里
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            // failWrite 自身也可能抛（sync 失败等），runCatching 保证原始异常优先传播。
            runCatching { atomic.failWrite(stream) }
            throw t
        }
        verifyCommittedHook(atomic, bytes)
    }

    /**
     * 提交校验：base 内容必须等于预期 bytes，且 `.new` 必须已被 rename 走。
     * 不满足即视为本次提交未生效 —— 此刻正式文件仍是旧内容（保旧），向上抛。
     */
    private fun verifyCommitted(atomic: AtomicFile, bytes: ByteArray) {
        val base = atomic.baseFile
        val committed = runCatching { base.takeIf { it.isFile }?.readBytes() }.getOrNull()
        val leftover = File(base.parentFile, "${base.name}.new")
        if (committed == null || !committed.contentEquals(bytes) || leftover.isFile) {
            throw IOException("草稿提交校验失败，正式文件保持旧内容: ${base.name}")
        }
    }

    /** 删除也要校验：base/.bak/.new 必须全部消失，否则抛 IO（不静默当成已清空）。 */
    private fun deleteCommitted(atomic: AtomicFile) {
        atomic.delete()
        verifyDeletedHook(atomic)
    }

    /** 持久层钩子：仅供测试注入真实磁盘故障；默认就是真实字节写出。 */
    internal open fun writeDraftBytes(stream: FileOutputStream, bytes: ByteArray) {
        stream.write(bytes)
    }

    /** 持久层钩子：仅供测试注入"提交未生效"（base 与预期不符）；默认为真实校验。 */
    internal open fun verifyCommittedHook(atomic: AtomicFile, bytes: ByteArray) = verifyCommitted(atomic, bytes)

    /** 持久层钩子：仅供测试注入删除校验故障；默认为真实校验。 */
    internal open fun verifyDeletedHook(atomic: AtomicFile) = verifyDeleted(atomic)

    /** base / .bak / .new 全部消失才算删除成功。 */
    private fun verifyDeleted(atomic: AtomicFile) {
        val base = atomic.baseFile
        val leftovers = listOf(
            base,
            File(base.parentFile, "${base.name}.bak"),
            File(base.parentFile, "${base.name}.new"),
        ).filter { it.exists() }
        if (leftovers.isNotEmpty()) {
            throw IOException("草稿删除校验失败，仍残留: ${leftovers.joinToString { it.name }}")
        }
    }

    /**
     * 把外部来源的（临时）照片**复制**到应用私有持久目录。
     *
     * 为什么必须复制：相机/相册给的是短时 URI 授权或 cacheDir 里的临时文件，
     * 缓存被系统回收或授权过期后，重试就再也读不到原图，
     * 而带图题目会**静默降级**成纯文本发给模型 —— 用户看到的答案完全错位。
     *
     * 任何一张附件**缺失、为 0 字节或复制失败都明确抛异常**，绝不静默丢图：
     * 丢一张图，调用方必须整单失败，而不是发出去一道缺图的题。
     */
    suspend fun persistAttachments(paths: List<String>): List<String> = withContext(Dispatchers.IO) {
        // 目录边界用 canonicalPath 并带分隔符：既解析掉 "../"、符号链接，
        // 又避免 filesDirX 这类同名前缀目录造成误判。
        val privateRootPrefix = context.filesDir.canonicalPath + File.separator
        paths.map { source ->
            val src = File(source)
            require(src.isFile && src.length() > 0L) { "附件不存在或为空: $source" }
            val canonicalPath = src.canonicalPath
            // 已经在私有持久目录里：原样复用，不重复复制。
            if (canonicalPath.startsWith(privateRootPrefix)) return@map canonicalPath
            val target = File(attachmentsDir(), "att_${UUID.randomUUID()}_${src.name}")
            try {
                src.copyTo(target, overwrite = true)
            } catch (e: IOException) {
                runCatching { target.delete() } // 不留半截副本
                throw IOException("附件复制失败: $source", e)
            }
            require(target.isFile && target.length() > 0L) { "附件复制后为空: $source" }
            target.canonicalPath
        }
    }

    private fun attachmentsDir(): File = File(context.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }

    companion object {
        /** 尚未落库的新对话使用的草稿键。 */
        const val NEW_CONVERSATION_KEY = "__new__"

        /** 从私有目录外复制进来的附件（见 [persistAttachments]）。 */
        const val ATTACHMENTS_DIR = "attachments"

        private const val DOCUMENT_PREFIX = "document:"
        private const val DRAFT_SEPARATOR = "\u0000"
    }
}
