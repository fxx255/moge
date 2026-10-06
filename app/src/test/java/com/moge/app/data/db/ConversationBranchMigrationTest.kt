package com.moge.app.data.db

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationBranchMigrationTest {
    @Test fun realV4DatabaseMigratesToOrderedChainsWithoutChangingExistingFields() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("moge.db")
        val schema = requireNotNull(javaClass.classLoader?.getResourceAsStream("com.moge.app.data.db.MogeDatabase/4.json"))
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val before = mutableMapOf<String, List<String?>>()
        context.openOrCreateDatabase("moge.db", Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                val placeholder = "$" + "{TABLE_NAME}"
                old.execSQL(entity.getString("createSql").replace(placeholder, table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace(placeholder, table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO conversation(id,title,created_at,updated_at) VALUES('c','原对话',1,1),('d','其他对话',1,1)")
            listOf("q1", "a1", "q2", "a2").forEachIndexed { index, id ->
                old.execSQL("""INSERT INTO message(id,conversation_id,role,content,image_paths,document_paths,created_at,reply_to_message_id)
                    VALUES(?,?,?,?,?,?,?,?)""", arrayOf<Any?>(id, "c", if (index % 2 == 0) "user" else "assistant",
                    "原内容 " + id, "[\"/shared.jpg\"]", "[\"/source.pdf\"]", 10, if (index % 2 == 1) "q" + (index + 1) / 2 else ""))
            }
            old.execSQL("INSERT INTO message(id,conversation_id,role,content,created_at) VALUES('dq','d','user','其他题目',5)")
            old.rawQuery("SELECT * FROM message ORDER BY rowid", null).use { cursor ->
                while (cursor.moveToNext()) before[cursor.getString(0)] = (0 until cursor.columnCount).map {
                    if (cursor.isNull(it)) null else cursor.getString(it)
                }
            }
            old.version = 4
        }
        val db = DatabaseModule.provideDatabase(context)
        try {
            val messages = db.conversationDao().getMessages("c")
            assertEquals(listOf(null, "q1", "a1", "q2"), messages.map { it.parentMessageId })
            assertNull(db.conversationDao().getMessages("d").single().parentMessageId)
            val sqlite = db.openHelper.writableDatabase
            assertEquals(5, sqlite.version)
            sqlite.query("SELECT * FROM message ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) assertEquals(before[cursor.getString(0)], (0 until cursor.columnCount - 1).map {
                    if (cursor.isNull(it)) null else cursor.getString(it)
                })
            }
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            db.conversationDao().deleteConversations(listOf("c"))
            assertTrue(db.conversationDao().getMessages("c").isEmpty())
            assertEquals(1, db.conversationDao().getMessages("d").size)
        } finally { db.close(); context.deleteDatabase("moge.db") }
    }
}
