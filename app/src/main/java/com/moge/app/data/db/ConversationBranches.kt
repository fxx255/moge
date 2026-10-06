package com.moge.app.data.db

/** Pure tree operations shared by display, model history and drafts. */
object ConversationBranches {
    const val ROOT = "__root__"
    const val AUTO_PARENT = "__active__"
    fun key(parentId: String?): String = parentId ?: ROOT

    fun visible(messages: List<MessageEntity>, selections: List<BranchSelectionEntity>): List<MessageEntity> {
        // Also supports old standalone/imported fixtures before their parent links are migrated.
        if (messages.none { it.parentMessageId != null }) return messages
        val children = messages.groupBy { it.parentMessageId }
        val chosen = selections.associate { it.parentKey to it.selectedChildId }
        val path = mutableListOf<MessageEntity>()
        val seen = HashSet<String>()
        var parent: String? = null
        while (true) {
            val options = children[parent].orEmpty()
            val next = options.firstOrNull { it.id == chosen[key(parent)] } ?: options.firstOrNull() ?: break
            check(seen.add(next.id)) { "对话分支出现循环" }
            path += next
            parent = next.id
        }
        return path
    }

    fun ancestors(messages: List<MessageEntity>, tipId: String?): List<MessageEntity> {
        if (tipId == null) return emptyList()
        if (messages.none { it.parentMessageId != null }) {
            val index = messages.indexOfFirst { it.id == tipId }
            require(index >= 0) { "分支起点已不存在" }
            return messages.take(index + 1)
        }
        val byId = messages.associateBy { it.id }
        val path = mutableListOf<MessageEntity>()
        val seen = HashSet<String>()
        var next: String? = tipId
        while (next != null) {
            require(seen.add(next)) { "对话分支出现循环" }
            val message = requireNotNull(byId[next]) { "分支起点已不存在" }
            path += message
            next = message.parentMessageId
        }
        return path.asReversed()
    }

    fun before(messages: List<MessageEntity>, questionId: String): List<MessageEntity> {
        val question = messages.firstOrNull { it.id == questionId } ?: return emptyList()
        if (messages.none { it.parentMessageId != null }) return messages.takeWhile { it.id != questionId }
        return ancestors(messages, question.parentMessageId)
    }

    fun versions(messages: List<MessageEntity>, question: MessageEntity): List<MessageEntity> =
        if (messages.none { it.parentMessageId != null }) listOf(question)
        else messages.filter { it.role == "user" && it.parentMessageId == question.parentMessageId }
}
