package com.moge.app.data.db

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens an actual v2 file through the production Room builder; no hand-built v3 schema is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationGroupingMigrationTest {
    private lateinit var context: Context
    private lateinit var migrated: MogeDatabase
    private val tables = listOf("conversation", "message", "request", "notebook_category", "notebook_entry")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("moge.db")
    }

    @After
    fun teardown() {
        if (::migrated.isInitialized) migrated.close()
        context.deleteDatabase("moge.db")
    }

    private fun openMigratedDatabase(): MogeDatabase =
        DatabaseModule.provideDatabase(context).also { migrated = it }

    private fun rows(cursor: Cursor): List<Map<String, String?>> = cursor.use {
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.columnNames.mapIndexed { index, name ->
                    name to if (cursor.isNull(index)) null else cursor.getString(index)
                }.toMap())
            }
        }
    }

    private fun SQLiteDatabase.insertRow(table: String, values: Map<String, Any?>) {
        val columns = values.keys.joinToString(", ")
        val placeholders = values.keys.joinToString(", ") { "?" }
        execSQL("INSERT INTO $table ($columns) VALUES ($placeholders)", values.values.toTypedArray())
    }

    private fun createV2Database(): Map<String, List<Map<String, String?>>> {
        val resource = "com.moge.app.data.db.MogeDatabase/2.json"
        val schema = requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "Exported v2 Room schema is missing: $resource"
        }.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        assertEquals(2, schema.getInt("version"))
        return context.openOrCreateDatabase("moge.db", Context.MODE_PRIVATE, null).use { legacy ->
            legacy.setForeignKeyConstraintsEnabled(true)
            legacy.beginTransaction()
            try {
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    val placeholder = "$" + "{TABLE_NAME}"
                    legacy.execSQL(entity.getString("createSql").replace(placeholder, table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) {
                        for (indexNumber in 0 until indices.length()) {
                            legacy.execSQL(indices.getJSONObject(indexNumber).getString("createSql").replace(placeholder, table))
                        }
                    }
                }
                val setupQueries = schema.getJSONArray("setupQueries")
                for (index in 0 until setupQueries.length()) legacy.execSQL(setupQueries.getString(index))
                seedV2Rows(legacy)
                legacy.setTransactionSuccessful()
            } finally {
                legacy.endTransaction()
            }
            legacy.version = 2
            tables.associateWith { table ->
                rows(legacy.rawQuery("SELECT rowid AS legacy_rowid, * FROM $table ORDER BY rowid", null))
            }
        }
    }

    /** Non-default data in every v2 table, including an active request and independent favorites. */
    private fun seedV2Rows(legacy: SQLiteDatabase) {
        legacy.insertRow("conversation", linkedMapOf(
            "id" to "old", "title" to "历史题", "cover_image" to "/photos/shared.jpg", "solve_mode" to "DETAILED",
            "created_at" to 100L, "updated_at" to 200L, "pinned" to 1,
        ))
        legacy.insertRow("conversation", linkedMapOf(
            "id" to "new", "title" to "新题", "solve_mode" to "SHORT",
            "created_at" to 300L, "updated_at" to 400L,
        ))
        legacy.insertRow("message", linkedMapOf(
            "id" to "question", "conversation_id" to "old", "role" to "user", "content" to "完整提问",
            "display_content" to "提问展示", "image_paths" to "[\"/photos/shared.jpg\"]", "transcript" to "OCR 内容",
            "created_at" to 101L,
        ))
        legacy.insertRow("message", linkedMapOf(
            "id" to "answer", "conversation_id" to "old", "role" to "assistant", "content" to "完整回答",
            "display_content" to "回答展示", "image_paths" to "[\"\",\"/plots/answer.png\"]",
            "model_label" to "模型", "duration_ms" to 456L, "usage_json" to "{\"totalTokens\":8}",
            "final_answer" to "最终答案", "reply_to_message_id" to "question", "created_at" to 102L,
        ))
        legacy.insertRow("message", linkedMapOf(
            "id" to "new-question", "conversation_id" to "new", "role" to "user", "content" to "第二题", "created_at" to 301L,
        ))
        legacy.insertRow("request", linkedMapOf(
            "request_id" to "active", "conversation_id" to "old", "user_message_id" to "question",
            "answer_message_id" to "answer", "attempt_id" to "attempt", "status" to "RUNNING",
            "confirmed_text" to "已确认", "partial_text" to "仍在生成", "figure_slots" to "[null,\"plot\"]",
            "user_text" to "完整提问", "attachment_paths" to "[\"/photos/shared.jpg\"]",
            "snapshot_json" to "{\"mode\":\"DETAILED\"}", "failure_kind" to "offline", "failure_message" to "已记录原因",
            "rounds" to 3, "created_at" to 110L, "updated_at" to 220L,
        ))
        legacy.insertRow("notebook_category", linkedMapOf(
            "id" to "a", "name" to "自定义 A", "sort_order" to 10, "created_at" to 15L, "updated_at" to 16L,
        ))
        legacy.insertRow("notebook_category", linkedMapOf(
            "id" to "b", "name" to "自定义 B", "sort_order" to 20, "created_at" to 25L, "updated_at" to 26L,
        ))
        legacy.insertRow("notebook_entry", linkedMapOf(
            "id" to "favorite", "category_id" to "a", "source_conversation_id" to "old",
            "source_question_id" to "question", "source_answer_id" to "answer", "title" to "收藏标题",
            "question_text" to "收藏提问", "question_transcript" to "收藏 OCR",
            "question_image_paths" to "[\"/photos/shared.jpg\"]", "answer_text" to "收藏正文",
            "final_answer" to "收藏答案", "figure_paths" to "[\"\",\"/plots/answer.png\"]",
            "created_at" to 130L, "updated_at" to 230L,
        ))
        legacy.insertRow("notebook_entry", linkedMapOf(
            "id" to "ungrouped-favorite", "category_id" to null, "source_conversation_id" to "new",
            "source_question_id" to "new-question", "source_answer_id" to "detached-answer", "title" to "独立收藏",
            "question_text" to "另一提问", "answer_text" to "另一回答", "created_at" to 330L, "updated_at" to 430L,
        ))
    }

    private fun assertGroupingSchema(sqlite: SupportSQLiteDatabase) {
        val column = rows(sqlite.query("PRAGMA table_info(conversation)")).single { it["name"] == "category_id" }
        assertEquals("TEXT", column["type"])
        assertEquals("0", column["notnull"])
        assertNull(column["dflt_value"])
        val indices = rows(sqlite.query("PRAGMA index_list(conversation)")).map { it["name"] }.toSet()
        assertTrue(indices.contains("index_conversation_updated_at"))
        assertTrue(indices.contains("index_conversation_category_id"))
        val foreignKey = rows(sqlite.query("PRAGMA foreign_key_list(conversation)")).single()
        assertEquals("notebook_category", foreignKey["table"])
        assertEquals("category_id", foreignKey["from"])
        assertEquals("id", foreignKey["to"])
        assertEquals("NO ACTION", foreignKey["on_update"])
        assertEquals("SET NULL", foreignKey["on_delete"])
        assertEquals("1", rows(sqlite.query("PRAGMA foreign_keys")).single()["foreign_keys"])
        sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test
    fun `registered migration opens exported v2 schema and preserves every old column and row`() = runBlocking {
        val before = createV2Database()
        val room = openMigratedDatabase()
        // Opening runs Room's generated schema validation and checks the registered production migration.
        val sqlite = room.openHelper.writableDatabase
        assertEquals(3, sqlite.version)
        tables.forEach { table ->
            val after = rows(sqlite.query("SELECT rowid AS legacy_rowid, * FROM $table ORDER BY rowid"))
            val legacyColumns = if (table == "conversation") after.map { it - "category_id" } else after
            assertEquals(table, before.getValue(table), legacyColumns)
        }
        assertGroupingSchema(sqlite)
        val repository = ConversationRepository(room.conversationDao(), Dispatchers.Unconfined)
        assertNull(repository.getConversation("old")!!.categoryId)
        assertNull(repository.getConversation("new")!!.categoryId)
        assertEquals(listOf("old", "new"), repository.observeHistory().first().map { it.conversation.id })
        assertEquals(listOf("old", "new"), repository.observeHistory(uncategorizedOnly = true).first().map { it.conversation.id })
        assertTrue(repository.observeHistory(categoryId = "a").first().isEmpty())
    }

    @Test
    fun `migrated grouping persists across reopening and category deletion keeps v2 records`() = runBlocking {
        createV2Database()
        val room = openMigratedDatabase()
        val repository = ConversationRepository(room.conversationDao(), Dispatchers.Unconfined)
        val original = repository.getConversation("old")!!
        val messages = repository.messages("old")
        val request = room.requestDao().getRequest("active")
        val favorite = room.notebookDao().getEntry("favorite")
        assertEquals(1, repository.moveToCategory(setOf("old"), "b"))
        assertEquals(favorite, room.notebookDao().getEntry("favorite"))
        room.close()

        val reopened = openMigratedDatabase()
        val history = ConversationRepository(reopened.conversationDao(), Dispatchers.Unconfined)
        assertEquals(original.copy(categoryId = "b"), history.getConversation("old"))
        assertEquals(listOf("old"), history.observeHistory(categoryId = "b").first().map { it.conversation.id })
        assertEquals(1, reopened.notebookDao().deleteCategory("b"))
        assertEquals(original, history.getConversation("old"))
        assertEquals(messages, history.messages("old"))
        assertEquals(request, reopened.requestDao().getRequest("active"))
        assertEquals(favorite, reopened.notebookDao().getEntry("favorite"))
        assertEquals(listOf("old", "new"), history.observeHistory(uncategorizedOnly = true).first().map { it.conversation.id })
        assertTrue(runCatching {
            reopened.openHelper.writableDatabase.execSQL(
                "UPDATE conversation SET category_id = ? WHERE id = ?", arrayOf("missing", "old"),
            )
        }.isFailure)
        assertEquals(original, history.getConversation("old"))
        assertGroupingSchema(reopened.openHelper.writableDatabase)
    }
}
