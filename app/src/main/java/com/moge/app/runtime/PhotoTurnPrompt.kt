package com.moge.app.runtime

import com.moge.app.domain.ConversationMode

/** An image without a caption continues an answered conversation unless it is clearly a new question. */
internal object PhotoTurnPrompt {
    const val INITIAL_QUESTION =
        "请先准确识别图片中的题目，再给出细致解答。数学公式使用 LaTeX，并写出完整推导过程。"

    const val FOLLOW_UP =
        "这是本轮补充的图片。请结合前文和上一轮回答，优先处理此前待确认、要求补图或需要修正的部分，" +
            "说明这张图带来的补充或更正，已有且不受影响的解答不必重复。" +
            "如果图片明显是另一道新题，请按新题解答；无法判断时先确认用户意图。"

    fun defaultText(mode: ConversationMode, hasPriorReply: Boolean): String = when {
        mode == ConversationMode.CHAT -> "请描述这些图片。"
        hasPriorReply -> FOLLOW_UP
        else -> INITIAL_QUESTION
    }
}
