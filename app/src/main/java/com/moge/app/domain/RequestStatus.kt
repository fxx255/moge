package com.moge.app.domain

/**
 * 一次生成请求的生命周期状态。
 *
 * 只有 [PREPARING] 与 [RUNNING] 表示「在途」：进程重启后仍然停在二者之一的记录，
 * 才是真正遗留、需要转成 [INTERRUPTED] 的请求。
 *
 * 模型恢复请求（reasoning-only 后的补答）和自动续写属于**同一个逻辑请求**的不同轮次，
 * 不产生新状态、更不插入新的用户消息。
 */
enum class RequestStatus {
    /** 已落库、正在准备上下文/附件，尚未发出第一个 HTTP 请求。 */
    PREPARING,

    /** 至少已发出一个 HTTP 请求，正在接收内容。 */
    RUNNING,

    /** 因网络中断、服务超时、无有效正文、附件缺失或配置失效而停止，可重试。 */
    INTERRUPTED,

    /** 已落库且正文已写入固定回答位置。 */
    COMPLETED,

    /** 用户主动取消或删除会话。**不自动重发**。 */
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED
    val isInFlight: Boolean get() = this == PREPARING || this == RUNNING

    companion object {
        fun fromName(raw: String?): RequestStatus? =
            raw?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

/**
 * 失败归类。
 *
 * 用户可以调整配置后重发原问题，因此配置失效也保留重发入口；主动取消除外。
 */
enum class FailureKind {
    /** 连接/读取失败、DNS、TLS 等网络层问题。 */
    NETWORK,

    /** 服务端超时、HTTP 5xx、SSE 中途断开。 */
    SERVER,

    /** 收到响应但没有可用正文（例如只有推理通道）。 */
    NO_CONTENT,

    /** 附件已被清理或读取失败。 */
    ATTACHMENT_MISSING,

    /** 模型/端点配置失效（密钥被删、地址为空等）。 */
    CONFIG_INVALID,

    /** 用户主动取消。 */
    CANCELLED,

    /** 解析不出协议要求的 JSON。 */
    INVALID_RESPONSE,

    /** 其它未分类异常。 */
    UNKNOWN,
    ;

    /** 是否应当提供「重新发送」入口；主动取消不提供。 */
    val isRetryable: Boolean
        get() = this == NETWORK || this == SERVER || this == NO_CONTENT ||
            this == INVALID_RESPONSE || this == ATTACHMENT_MISSING || this == CONFIG_INVALID || this == UNKNOWN

    companion object {
        fun fromName(raw: String?): FailureKind? =
            raw?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
