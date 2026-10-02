package com.moge.app.data.parse

/**
 * 从**流式 JSON 正文**里增量解出顶层 `reply` 字符串。
 *
 * 为什么不用一个跨整个 JSON 的正则：正则无法表达「当前是否在某个字符串里」，
 * 也无法区分转义引号与结束引号，更没法处理被 chunk 切开的代理对 ——
 * 一旦切错，用户就会在正文里看到 `\"` 残片或者半截 LaTeX。
 *
 * 本解码器的职责边界（对应实施文档第四节）：
 * - **只认顶层 `reply`**：进入任意嵌套对象（`plan_actions`、`plots`、`diagrams`、
 *   甚至嵌套对象里恰好也叫 `reply` 的字段）后就不再抓取，避免把内部控制数据当正文；
 * - 只输出字符串**值**，不输出外层 JSON、键名或控制标记；
 * - 覆盖 `\"`、`\\`、`\n`、`\t`、`\uXXXX`、跨 chunk 的代理对；
 * - 同一输入按任意 chunk 切分，最终正文完全一致。
 *
 * 它**不**负责 Markdown 规范化、公式修复或动作解析 —— 那些仍由正式的
 * [ReplyParser] 在完整响应上做一次，最终以解析器结果校正。
 *
 * 实现说明：用显式栈跟踪深度，并给「键名扫描」与「其他字符串跳过」各自
 * 独立的累积缓冲。键名缓冲在读到结束引号时**立即判定**，不会等到冒号 ——
 * 这样 `"reply"` 与 `"reply_extra"` 不会被混淆，嵌套里的同名键也因深度限制被忽略。
 */
class IncrementalReplyDecoder(private val targetKey: String = "reply") {

    private enum class Mode {
        /** 还没进入 JSON 对象：忽略围栏、前言与空白。 */
        BEFORE_OBJECT,

        /** 普通结构位置：等待键名、值或结构字符。 */
        STRUCTURE,

        /** 正在读一个**键名**。 */
        KEY,

        /** 正在读一个**字符串值**（非目标）。 */
        SKIPPED_STRING,

        /** 已确认是顶层 reply 的值，正在等待它的起始引号。 */
        REPLY_OPEN,

        /** 已确认是顶层 reply 的值，正在解码。 */
        REPLY,

        /** 正常结束。 */
        DONE,

        /** 结构不符，不再产出。 */
        FAILED,
    }

    private var mode = Mode.BEFORE_OBJECT
    /** 结构深度：顶层对象为 1。只有 depth==1 时的 `reply` 键才被采纳。 */
    private var depth = 0
    /** 当前容器是否为数组（数组里的字符串永远是值，不是键名）。 */
    private val containerIsArray = ArrayDeque<Boolean>()
    private val keyBuffer = StringBuilder()
    private val out = StringBuilder()

    /**
     * 结构位置是否在等一个**值**。
     *
     * 必须区分键位与值位，否则 `{"note":"reply"}` 里 note 的**字符串值** "reply"
     * 会被当成键名，紧跟着的 `:` 又被当成键值分隔符 —— 于是 "secret" 被当成正文，
     * 真正的 `{"reply":"answer"}` 反而被跳过。
     * 置位时机：冒号之后；复位时机：进入嵌套容器、值字符串结束、或逗号之后
     * （数字/字面量值后紧跟的也是逗号，由它统一复位）。
     */
    private var awaitingValue = false

    private var inEscape = false
    private var unicodeDigits = 0
    private var unicodeValue = 0
    /** 已吃掉的 `\u` 十六进制字符；非法序列时按**原字符**回吐（不补零）。 */
    private val unicodeEaten = StringBuilder()
    private var pendingHighSurrogate: Char? = null

    /**
     * **待判别的转义字符**（`n`/`t`/`f`/`b`/`r`）与它所属的目标。
     *
     * 为什么必须延迟判别：模型写 LaTeX 时经常只给**单反斜杠**
     * （`\frac`、`\text`、`\beta`、`\rho`），而这些字母在 JSON 里恰好也是
     * 合法转义（换页/制表/退格/回车）。只看当前字符无法区分：
     * - `\frac{a}{b}` 应当渲染成 `\frac{a}{b}`；
     * - 真正的换页 `\f` 后面跟的不是字母。
     *
     * 所以遇到这些字母时**先挂起**，等下一个字符到达再判定：
     * 下一个是 ASCII 字母 ⇒ 按 LaTeX 处理，原样输出 `\` + 字母；
     * 否则 ⇒ 按 JSON 控制字符输出。
     *
     * 判别结果同时决定「目标」：键名缓冲、被跳过的字符串、还是正文 `out`。
     */
    private var pendingEscape: Char? = null
    /** 挂起转义所属的 sink 类型：0 = 正文，1 = 键名，2 = 被跳过的字符串。 */
    private var pendingSink = 0
    /** 挂起转义是否是 `\n`（`\n` 需要按命令表精确匹配，见 [N_COMMANDS]）。 */
    private val pendingLetters = StringBuilder()

    private var sawReply = false
    private var closedValue = false

    /** 只在目标字符串的结束引号到达后为 true；半截答案不能用作最终答案。 */
    val valueComplete: Boolean get() = closedValue

    /** 刚读完 `"reply"` 键名、尚未遇到冒号。 */
    private var awaitingReplyValue = false

    /** 已解码的正文。 */
    val text: String get() = out.toString()

    /** 是否解出过 reply 字段（即使是空串）。 */
    val foundReply: Boolean get() = sawReply

    /** 解码是否仍可能产出更多正文。 */
    val isUsable: Boolean get() = mode != Mode.FAILED

    /** 是否存在可展示正文。 */
    fun hasVisibleText(): Boolean = out.isNotEmpty()

    /**
     * 复位以便复用于下一个 HTTP 轮次（续写 / 恢复）。
     *
     * 每一轮是不同的响应体，正文必须各自从零累积再由调用方合并；
     * 复用同一个解码器省去重复分配，也保证解码规则始终只有一份实现。
     */
    fun reset() {
        closedValue = false
        mode = Mode.BEFORE_OBJECT
        depth = 0
        containerIsArray.clear()
        keyBuffer.setLength(0)
        out.setLength(0)
        inEscape = false
        unicodeDigits = 0
        unicodeValue = 0
        unicodeEaten.setLength(0)
        pendingHighSurrogate = null
        sawReply = false
        awaitingReplyValue = false
        awaitingValue = false
        pendingEscape = null
        pendingSink = SINK_TEXT
        pendingLetters.setLength(0)
        awaitingNWord = false
    }

    /**
     * 喂入一段增量文本，返回**本次新增**的正文片段。
     *
     * 空串表示这一段没有产生新的可见正文（还在外层结构里，或只读到半截转义）。
     */
    fun append(chunk: String): String {
        if (chunk.isEmpty() || mode == Mode.FAILED || mode == Mode.DONE) return ""
        val before = out.length
        for (ch in chunk) consume(ch)
        return out.substring(before)
    }

    private fun consume(ch: Char) {
        when (mode) {
            Mode.FAILED, Mode.DONE -> Unit

            Mode.BEFORE_OBJECT -> if (ch == '{') {
                depth = 1
                containerIsArray.clear()
                containerIsArray.addLast(false)
                mode = Mode.STRUCTURE
            }

            Mode.STRUCTURE, Mode.KEY, Mode.SKIPPED_STRING -> consumeStructural(ch)

            Mode.REPLY_OPEN -> when {
                ch.isWhitespace() -> Unit
                ch == '"' -> mode = Mode.REPLY
                // 值不是字符串（null / 对象 / 数组）：没有可用正文，按协议不符收尾。
                else -> mode = Mode.DONE
            }

            Mode.REPLY -> consumeReplyChar(ch)
        }
    }

    /**
     * REPLY 模式下的单字符消费。
     *
     * 先结清**挂起的待判别转义**（见 [pendingEscape]），再处理当前字符 ——
     * 顺序不能反：挂起转义的判别结果得先落到正文里，当前字符才轮到正常处理。
     */
    private fun consumeReplyChar(ch: Char) {
        if (pendingEscape != null) {
            resolvePendingEscape(ch)
            return
        }
        if (awaitingNWord) {
            continueNWord(ch)
            return
        }
        when {
            inEscape -> consumeEscape(ch, SINK_TEXT) { appendDecoded(it) }
            ch == '\\' -> inEscape = true
            ch == '"' -> {
                flushPendingHighSurrogate()
                closedValue = true
                mode = Mode.DONE
            }
            else -> appendDecoded(ch)
        }
    }

    /**
     * 继续收集 `\n` 后面的字母，直到能判定它是哪个命令。
     *
     * `\nabla`：收词 "nabla" 命中命令表 ⇒ 整体按 LaTeX 输出。
     * `\nf(x)`：收词 "nf" 不再是任何命令的前缀 ⇒ 回退成「换行 + f(x)」。
     */
    private fun continueNWord(ch: Char) {
        val isLetter = ch in 'a'..'z' || ch in 'A'..'Z'
        if (!isLetter) {
            // 收词结束：按最终词判定，当前字符另行正常处理。
            finishNWord()
            consume(ch)
            return
        }
        pendingLetters.append(ch)
        val word = pendingLetters.toString()
        // 命令必须等到词边界才成立：ne 是命令，但 nempty 不是。
        if (!isLatexNPrefixOrExact(word)) {
            // 不再可能是任何命令 ⇒ 判定为真正的换行。
            // 注意缓冲里第一个字符是 `\n` 的那个 `n`（它属于**命令名**，不是正文），
            // 判定为换行后要把这个 `n` 去掉，只输出剩余的、真正跟在换行后面的字母。
            val rest = word.removePrefix("n")
            pendingLetters.setLength(0)
            awaitingNWord = false
            emitPending('\n')
            for (c in rest) emitCurrentAsPlain(c)
        }
        // 否则继续收词（等待下一个字母）。
    }

    /** `\n` 收词结束：命中命令表则按 LaTeX 输出，否则输出换行。 */
    private fun finishNWord() {
        val word = pendingLetters.toString()
        pendingLetters.setLength(0)
        awaitingNWord = false
        if (word.isNotEmpty() && word in N_COMMANDS) {
            // 命令名整体按字面量输出（含开头的 `\`）。
            emitPending('\\')
            for (c in word) emitCurrentAsPlain(c)
        } else {
            // 没拼成命令：`n` 属于命令名而非正文，丢弃它，只留换行 + 后续字母。
            emitPending('\n')
            for (c in word.removePrefix("n")) emitCurrentAsPlain(c)
        }
    }

    private fun consumeStructural(ch: Char) {
        // 结构位置里也可能挂起待判别转义（键名或被跳过字符串内部的 `\f` 等）：
        // 必须先结清，否则判别结果会串到后面的结构字符上。
        if (pendingEscape != null) {
            resolvePendingEscape(ch)
            return
        }
        if (awaitingNWord) {
            continueNWord(ch)
            return
        }
        // 上层对象里刚读完 `"reply"` 键名：下一个非空白字符必须是冒号，
        // 冒号之后才是真正要解码的值。这一步必须在结构分发**之前**处理，
        // 否则冒号会被当作无意义字符跳过，永远进不了 REPLY。
        if (awaitingReplyValue && mode == Mode.STRUCTURE) {
            if (ch.isWhitespace()) return
            awaitingReplyValue = false
            if (ch == ':') {
                sawReply = true
                pendingHighSurrogate = null
                inEscape = false
                unicodeDigits = 0
                // 冒号之后才是值；值的起始引号由 REPLY_OPEN 状态单独吃掉，
                // 否则「冒号」和「引号」分处两个 chunk 时，起始引号会被误判成结束引号。
                mode = Mode.REPLY_OPEN
                return
            }
            // 不是冒号：键名后没有值（模型截断在此），按「已找到 reply 但内容为空」收尾。
            mode = Mode.DONE
            return
        }

        when (mode) {
            Mode.STRUCTURE -> when (ch) {
                '"' -> {
                    when {
                        // 对象里的字符串：值位 ⇒ 一定是字符串值；键位 ⇒ 键名候选。
                        containerIsArray.lastOrNull() == true -> startSkippedString()
                        awaitingValue -> startSkippedString()
                        else -> {
                            keyBuffer.setLength(0)
                            inEscape = false
                            mode = Mode.KEY
                        }
                    }
                }
                '{' -> {
                    depth++
                    containerIsArray.addLast(false)
                    // 嵌套对象的第一个字符串又是键名候选。
                    awaitingValue = false
                }
                '[' -> {
                    depth++
                    containerIsArray.addLast(true)
                    // 数组元素是值（即便元素又是对象）。
                    awaitingValue = false
                }
                '}' , ']' -> {
                    depth--
                    if (containerIsArray.isNotEmpty()) containerIsArray.removeLast()
                    if (depth <= 0) {
                        // 顶层对象结束。已解出 reply 就正常收尾，否则协议不符。
                        mode = if (sawReply) Mode.DONE else Mode.FAILED
                    }
                    awaitingValue = false
                }
                ':' -> awaitingValue = true
                ',' -> awaitingValue = false
                // 逗号、冒号、空白、数字、true/false/null 都无需特殊处理。
                else -> Unit
            }

            Mode.KEY -> when {
                inEscape -> consumeEscape(ch, SINK_KEY) { keyBuffer.append(it) }
                ch == '\\' -> inEscape = true
                ch == '"' -> {
                    // 键名读完：只有顶层且名字恰为 reply 才进入主题解码。
                    val isReply = depth == 1 &&
                        containerIsArray.lastOrNull() == false &&
                        keyBuffer.toString() == targetKey
                    mode = Mode.STRUCTURE
                    if (isReply && !sawReply) {
                        // 等冒号之后才是真正的值；用一个标记等待 ':'。
                        awaitingReplyValue = true
                    }
                }
                else -> keyBuffer.append(ch)
            }

            Mode.SKIPPED_STRING -> when {
                inEscape -> consumeEscape(ch, SINK_SKIPPED) { }
                ch == '\\' -> inEscape = true
                ch == '"' -> {
                    mode = Mode.STRUCTURE
                    // 值字符串结束；对象里下一个字符串是键名候选（等逗号）。
                    awaitingValue = false
                }
                else -> Unit
            }

            else -> Unit
        }
    }

    private fun startSkippedString() {
        inEscape = false
        mode = Mode.SKIPPED_STRING
    }

    /**
     * 统一的转义消费。
     *
     * [sink] 接收解码后的**单个字符**；`\uXXXX` 完整时也只喂一个字符，
     * 由调用方各自的代理对处理逻辑拼接。
     *
     * [sinkKind] 与 [sink] 对应（0=正文，1=键名，2=被跳过字符串），
     * 供挂起判别结算时重新找到正确的目标。
     */
    private inline fun consumeEscape(ch: Char, sinkKind: Int, sink: (Char) -> Unit) {
        if (unicodeDigits > 0 || ch == 'u') {
            if (unicodeDigits == 0) {
                unicodeDigits = 1
                unicodeValue = 0
                unicodeEaten.setLength(0)
                return
            }
            val digit = Character.digit(ch, 16)
            if (digit < 0) {
                // 非法 \u 序列（后续字符已证明非法）：把 **\u 与已吃掉的真实字符**
                // 按字面量回吐，绝不吞字符。早先用补零代替真实字符，导致 \uZZ9 丢了 \u。
                inEscape = false
                unicodeDigits = 0
                sink('\\')
                sink('u')
                for (eatenChar in unicodeEaten) sink(eatenChar)
                unicodeEaten.setLength(0)
                // 非法转义已经结束；引号/反斜杠仍须按原字符串状态处理。
                consume(ch)
                return
            }
            unicodeValue = unicodeValue * 16 + digit
            unicodeDigits++
            unicodeEaten.append(ch)
            if (unicodeDigits == 5) {
                inEscape = false
                unicodeDigits = 0
                unicodeEaten.setLength(0)
                sink(unicodeValue.toChar())
            }
            return
        }
        inEscape = false
        when (ch) {
            // 这几个字母在 JSON 里是合法转义，却又是 LaTeX 命令的高频开头
            // （\frac \text \beta \rho \nabla …）。**延迟判别**：先挂起，
            // 等下一字符到达再决定是控制字符还是 LaTeX。
            'n', 't', 'r', 'b', 'f' -> {
                pendingEscape = ch
                pendingSink = sinkKind
                pendingLetters.setLength(0)
            }
            '"' -> sink('"')
            '\\' -> sink('\\')
            '/' -> sink('/')
            else -> {
                // 未知的转义序列（`\alpha`、`\gamma` 这类 LaTeX 命令开头）：
                // JSON 语义上不合法，但**绝不能吞掉反斜杠** —— 那会把公式改坏
                // （`\alpha` 变成 `alpha`），而且这个改动下游无法恢复。按字面量回吐。
                sink('\\')
                sink(ch)
            }
        }
    }

    /**
     * 结算挂起的转义判别（由**下一个**字符触发）。
     *
     * 判据与完整响应上的 [sanitizeJsonEscapes] 保持一致：
     * - `\n` 用**精确命令表**匹配（`\nabla` 是命令，而 `\nf(x)` 里的 `\n` 是换行）；
     * - 其余 `t/f/b/r` 只要后面跟 ASCII 字母就按 LaTeX 处理（`\text`、`\frac`、
     *   `\beta`、`\rho` 是高频命令，而制表/换页/退格/回车几乎不会出现在正文里）。
     *
     * 判定为 LaTeX 时输出 `\` + 该字母，并把当前字符当作**普通字符**继续处理；
     * 判定为控制字符时输出控制字符，当前字符照常继续处理。
     */
    private fun resolvePendingEscape(ch: Char) {
        val escaped = pendingEscape ?: return
        pendingEscape = null
        val isLetter = ch in 'a'..'z' || ch in 'A'..'Z'
        when {
            // `\n` + 字母：可能是 `\nabla` 这类命令，也可能是「换行 + 英文单词」。
            // 进入收词状态，等看清整个词再定案（与 sanitizeJsonEscapes 的精确匹配一致）。
            escaped == 'n' && isLetter -> {
                pendingLetters.setLength(0)
                pendingLetters.append('n').append(ch)
                awaitingNWord = true
            }
            // 其余 t/f/b/r + 字母 ⇒ 按 LaTeX 处理（\text \frac \beta \rho 是高频命令，
            // 而制表/换页/退格/回车几乎不会出现在正文里）。
            isLetter -> {
                emitPending('\\')
                emitPending(escaped)
                emitCurrentAsPlain(ch)
            }
            // 后面不是字母：是真正的 JSON 控制字符。
            else -> {
                emitPending(controlCharFor(escaped))
                consume(ch)
            }
        }
    }

    /**
     * `\n` 后续字母的收词状态。
     *
     * 一旦 `\n` 被判为 LaTeX 开头，就要继续累积字母直到能确定是哪个命令
     * （`\nabla` / `\neq` / `\notin`……）。收词期间遇到非字母即结束。
     */
    private var awaitingNWord = false

    /** `\n` + 当前字母是否仍是某个已知 n 命令的前缀（或已经是完整命令）。 */
    private fun isLatexNPrefixOrExact(word: String): Boolean =
        N_COMMANDS.any { it == word || it.startsWith(word) }

    private fun controlCharFor(escaped: Char): Char = when (escaped) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        'b' -> '\b'
        else -> '\u000C'
    }

    private fun emitPending(c: Char) {
        when (pendingSink) {
            1 -> keyBuffer.append(c)
            2 -> Unit
            else -> appendDecoded(c)
        }
    }

    private fun emitCurrentAsPlain(c: Char) {
        when (pendingSink) {
            1 -> keyBuffer.append(c)
            2 -> Unit
            else -> appendDecoded(c)
        }
    }

    /**
     * 追加一个字符并处理代理对。
     *
     * 高位代理必须等下一个字符才能确定是否为完整一对；流结束时由 [finish]
     * 把落单的代理换成替换字符，而不是让非法 char 进到 UI 字符串里。
     */
    private fun appendDecoded(ch: Char) {
        val pending = pendingHighSurrogate
        if (pending != null) {
            if (ch.isLowSurrogate()) {
                out.append(pending).append(ch)
                pendingHighSurrogate = null
                return
            }
            out.append('\uFFFD')
            pendingHighSurrogate = null
        }
        if (ch.isHighSurrogate()) {
            pendingHighSurrogate = ch
            return
        }
        if (ch.isLowSurrogate()) {
            out.append('\uFFFD')
            return
        }
        out.append(ch)
    }

    private fun flushPendingHighSurrogate() {
        if (pendingHighSurrogate != null) {
            out.append('\uFFFD')
            pendingHighSurrogate = null
        }
    }

    /**
     * 结束输入（EOF）。
     *
     * 截断时（对象没闭合）仍保留已解出的正文 —— 这正是长回答撞上限、
     * 流被中断时用户最想看到的那部分内容。
     */
    fun finish(): String {
        // EOF 时仍在挂起的转义：**尚未证明**它是不是 LaTeX（后面的字符没来）。
        // - 已确定为控制字符的语义不可能了，但也不该把半截 `\uAB` 泄漏到界面；
        // - `\n` 收词中断：退化成换行 + 已收字母（保守且不会丢内容）。
        if (pendingEscape != null) {
            val escaped = pendingEscape!!
            pendingEscape = null
            // 只有 t/f/b/r 这种「后面没跟字母」的情形才有确定语义；这里保守地
            // 按控制字符输出，与「下一个字符不是字母」的判定一致。
            emitPending(controlCharFor(escaped))
        }
        if (awaitingNWord) {
            finishNWord()
        }
        // 未完成的 `\u`：丢弃，绝不泄漏半截转义（见 truncated unicode 测试）。
        unicodeDigits = 0
        unicodeEaten.setLength(0)
        inEscape = false
        flushPendingHighSurrogate()
        when (mode) {
            Mode.REPLY, Mode.REPLY_OPEN -> mode = Mode.DONE
            Mode.STRUCTURE, Mode.KEY, Mode.SKIPPED_STRING ->
                mode = if (sawReply) Mode.DONE else Mode.FAILED
            else -> Unit
        }
        return text
    }

    companion object {
        /** 挂起转义的目标：正文。 */
        private const val SINK_TEXT = 0
        /** 挂起转义的目标：键名缓冲。 */
        private const val SINK_KEY = 1
        /** 挂起转义的目标：被跳过的字符串（丢弃）。 */
        private const val SINK_SKIPPED = 2

        /**
         * `\n` 开头的常见 LaTeX 命令。
         *
         * 直接用完整响应层那**唯一一份**表（同 package 的顶层 `LATEX_N_COMMANDS`）：
         * 两层判别结果必须一致，否则流式展示与最终正文会不一样。
         * 刻意不在这里复制 —— 复制就会漂移。
         */
        private val N_COMMANDS get() = LATEX_N_COMMANDS
    }
}

/**
 * 一次性增量解码（测试与离线校正用）。
 *
 * 与流式路径共用同一实现，保证「同一输入不同切分得到完全相同结果」。
 */
internal fun decodeReplyIncremental(raw: String): String =
    IncrementalReplyDecoder().let { decoder ->
        decoder.append(raw)
        decoder.finish()
    }
