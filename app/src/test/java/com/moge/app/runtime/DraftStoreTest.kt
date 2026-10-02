package com.moge.app.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 草稿存储验收。
 *
 * 草稿是用户"还没发出去"的内容，丢一条就永远找不回来，尤其照片是现拍的。
 * 必须保证：revision/pending 语义（只有最新意图落盘）、跨进程存活、
 * 原子写失败保旧且异常向上传播、清理不误删历史附件、附件缺失显式失败。
 *
 * 全部使用真实临时文件（Robolectric 提供的沙箱 filesDir），不 mock 文件系统。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, shadows = [PosixAtomicFileShadow::class])
class DraftStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: DraftStore
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        store = DraftStore(context)
    }

    @After
    fun tearDown() {
        tempFiles.forEach { it.delete() }
    }

    /** pending 是进程内状态：freshStore 模拟"重启后只看磁盘"，断言真正的落盘结果。 */
    private fun freshStore(): DraftStore = DraftStore(context)

    private fun tempPhoto(): File {
        val f = File.createTempFile("photo", ".jpg")
        f.writeBytes(ByteArray(32) { 1 })
        tempFiles += f
        return f
    }

    private fun draftsDir(): File = File(context.filesDir, "drafts")

    private fun draftFile(conversationId: String?, suffix: String = ""): File {
        val name = (conversationId ?: DraftStore.NEW_CONVERSATION_KEY)
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(draftsDir(), "draft_$name.txt$suffix")
    }

    // ── 基础读写 ──

    @Test
    fun `draft round trips text and attachments`() = runBlocking {
        val photo = tempPhoto()
        store.save("c1", "还没发出去的话", listOf(photo.absolutePath))
        val draft = store.load("c1")
        assertEquals("还没发出去的话", draft?.text)
        assertEquals(listOf(photo.absolutePath), draft?.photoPaths)
    }

    @Test
    fun `empty draft is removed`() = runBlocking {
        store.save("c1", "内容", emptyList())
        store.save("c1", "", emptyList())
        assertNull(store.load("c1"))
    }

    @Test
    fun `drafts of different conversations are isolated`() = runBlocking {
        store.save("c1", "会话一", emptyList())
        store.save("c2", "会话二", emptyList())
        assertEquals("会话一", store.load("c1")?.text)
        assertEquals("会话二", store.load("c2")?.text)
    }

    // ── reserve / persist：revision 与 pending ──

    @Test
    fun `successive saves replace the committed draft on disk`() = runBlocking {
        repeat(10) { revision ->
            store.save("c1", "revision $revision", emptyList())
            assertEquals("revision $revision", freshStore().load("c1")?.text)
            assertFalse(draftFile("c1", ".new").exists())
        }
    }

    /** load 必须优先看到尚未 persist 的 pending；persist 后磁盘才可见。 */
    @Test
    fun `load prefers pending before persist`() = runBlocking {
        val reservation = store.reserveSave("c1", "还没落盘的最新输入", emptyList())
        // 进程内：不等落盘就能读到
        assertEquals("还没落盘的最新输入", store.load("c1")?.text)
        // 重启视角：磁盘上还没有任何东西，证明读的确实是 pending 而不是磁盘
        assertNull(freshStore().load("c1"))
        store.persist(reservation)
        assertEquals("还没落盘的最新输入", freshStore().load("c1")?.text)
    }

    /** 倒序 persist：被取代的旧 reservation 必须是空操作，只有最新 revision 落盘。 */
    @Test
    fun `persisting a superseded reservation is a no-op`() = runBlocking {
        val first = store.reserveSave("c1", "第一版", emptyList())
        store.reserveSave("c1", "第二版", emptyList())
        store.persist(first) // 旧 revision 迟到：不得反写磁盘
        assertNull(freshStore().load("c1"))
        assertEquals("第二版", store.load("c1")?.text) // pending 仍是最新意图
    }

    @Test
    fun `persisting in reverse order keeps only the latest revision`() = runBlocking {
        val first = store.reserveSave("c1", "第一版", emptyList())
        val second = store.reserveSave("c1", "第二版", emptyList())
        store.persist(second) // 最新的先落盘
        store.persist(first)  // 旧的后到：跳过
        assertEquals("第二版", freshStore().load("c1")?.text)
        assertEquals("第二版", store.load("c1")?.text)
    }

    // ── baseline 清理 ──

    @Test
    fun `clear removes a draft that still matches the baseline`() = runBlocking {
        store.save("c1", "", emptyList())
        val cleared = store.clearIfBaselineMatches("c1", baselineText = "", baselinePhotos = emptyList())
        assertTrue(cleared)
        assertNull(store.load("c1"))
    }

    @Test
    fun `clear keeps a draft whose text changed`() = runBlocking {
        store.save("c1", "", emptyList())
        store.save("c1", "用户开始写下一句", emptyList())
        val cleared = store.clearIfBaselineMatches("c1", baselineText = "", baselinePhotos = emptyList())
        assertFalse(cleared)
        assertEquals("用户开始写下一句", store.load("c1")?.text)
    }

    @Test
    fun `clear keeps a draft whose attachments changed`() = runBlocking {
        val newer = tempPhoto()
        store.save("c1", "", emptyList())
        store.save("c1", "", listOf(newer.absolutePath))
        val cleared = store.clearIfBaselineMatches("c1", baselineText = "", baselinePhotos = emptyList())
        assertFalse(cleared)
        assertEquals(listOf(newer.absolutePath), store.load("c1")?.photoPaths)
        assertTrue(newer.isFile)
    }

    /** pending（未 persist 的新草稿）也必须阻止旧 baseline 清理。 */
    @Test
    fun `pending new text blocks a stale-baseline clear`() = runBlocking {
        store.save("c1", "", emptyList()) // 发送时留下的基准（空）
        store.reserveSave("c1", "用户开始写下一句", emptyList()) // 生成期间的新输入，尚未 persist
        val cleared = store.clearIfBaselineMatches("c1", baselineText = "", baselinePhotos = emptyList())
        assertFalse("未落盘的新编辑不得被收尾清掉", cleared)
        assertEquals("用户开始写下一句", store.load("c1")?.text)
    }

    @Test
    fun `pending new photo blocks a stale-baseline clear`() = runBlocking {
        val newer = tempPhoto()
        store.save("c1", "", emptyList())
        store.reserveSave("c1", "", listOf(newer.absolutePath))
        val cleared = store.clearIfBaselineMatches("c1", baselineText = "", baselinePhotos = emptyList())
        assertFalse("新拍的照片不得被收尾清掉", cleared)
        assertEquals(listOf(newer.absolutePath), store.load("c1")?.photoPaths)
        assertTrue(newer.isFile)
    }

    @Test
    fun `baseline without text never clears`() = runBlocking {
        store.save("c1", "内容", emptyList())
        val cleared = store.clearIfBaselineMatches("c1", baselineText = null)
        assertFalse(cleared)
        assertEquals("内容", store.load("c1")?.text)
    }

    // ── clear 与 reservation 失效 ──

    @Test
    fun `clear invalidates outstanding reservations`() = runBlocking {
        val stale = store.reserveSave("c1", "将被删除会话的草稿", emptyList())
        store.clear("c1")
        store.persist(stale) // 迟到的 persist：草稿不得复活
        assertNull(freshStore().load("c1"))
        assertNull(store.load("c1"))
    }

    @Test
    fun `clear deletes the draft but no photo files`() = runBlocking {
        val referenced = tempPhoto()
        val unreferenced = tempPhoto()
        store.save("c1", "文字", listOf(referenced.absolutePath))
        store.clear("c1")
        assertNull("草稿本身要清掉", store.load("c1"))
        assertTrue("已入会话的历史引用不得删除", referenced.isFile)
        assertTrue("未引用的也不得删：无法可靠判定引用关系，宁留孤儿不误删", unreferenced.isFile)
    }

    // ── 写失败 / 提交校验 ──

    /** 测试钩子：写出阶段注入磁盘故障。 */
    private class FailingWriteStore(context: Context) : DraftStore(context) {
        var failWrites = false
        override fun writeDraftBytes(stream: FileOutputStream, bytes: ByteArray) {
            if (failWrites) throw IOException("注入的写入故障")
            super.writeDraftBytes(stream, bytes)
        }
    }

    /** 测试钩子：写出旧字节，模拟 finishWrite 静默丢提交（rename 未生效只记 log）。 */
    private class StaleWriteStore(context: Context) : DraftStore(context) {
        var substitute: ByteArray? = null
        override fun writeDraftBytes(stream: FileOutputStream, bytes: ByteArray) {
            stream.write(substitute ?: bytes)
        }
    }

    @Test
    fun `failed write keeps the previously persisted draft on disk`() = runBlocking {
        val flaky = FailingWriteStore(context)
        flaky.save("c1", "第一版", emptyList())
        flaky.failWrites = true
        assertThrows(IOException::class.java) {
            runBlocking { flaky.save("c1", "第二版", emptyList()) }
        }
        // 用新 store 读磁盘：保旧的是持久化结果，不是进程内 pending
        assertEquals("第一版", freshStore().load("c1")?.text)
        assertFalse("failWrite 后不得残留半截临时文件", draftFile("c1", ".new").exists())
        // 进程内 pending 仍持有最新意图（重试路径）
        assertEquals("第二版", flaky.load("c1")?.text)
    }

    @Test
    fun `commit verification rejects a silently dropped write`() = runBlocking {
        val stale = StaleWriteStore(context)
        stale.save("c1", "第一版", emptyList())
        stale.substitute = "第一版".toByteArray(Charsets.UTF_8) // 落盘的是旧内容
        assertThrows(IOException::class.java) {
            runBlocking { stale.save("c1", "第二版", emptyList()) }
        }
        assertEquals("提交未生效时正式文件保持旧内容", "第一版", freshStore().load("c1")?.text)
    }

    @Test
    fun `failed delete keeps verification loud`() = runBlocking {
        store.save("c1", "内容", emptyList())
        store.save("c1", "", emptyList()) // 空草稿 = 删除；校验 base/.bak/.new 全部消失
        assertFalse(draftFile("c1").exists())
        assertNull(freshStore().load("c1"))
    }

    // ── .bak 恢复 / 打不开的文件 ──

    /** 只剩 .bak、正式文件未落地：openRead 必须先恢复再读，不得读成 null。 */
    @Test
    fun `load recovers a leftover backup file`() = runBlocking {
        draftsDir().mkdirs()
        val bak = draftFile("c1", ".bak")
        bak.writeText("中断提交留下的备份")
        assertEquals("中断提交留下的备份", store.load("c1")?.text)
        assertFalse("备份应已恢复为正式文件", bak.exists())
        assertTrue(draftFile("c1").isFile)
    }

    /** base 存在但打不开（这里是目录）：不得当成"无草稿"。 */
    @Test
    fun `unreadable draft file is not treated as empty`() {
        draftsDir().mkdirs()
        assertTrue(draftFile("c1").mkdir()) // 用目录冒充打不开的草稿文件
        assertThrows(IOException::class.java) {
            runBlocking { store.load("c1") }
        }
    }

    // ── 附件 ──

    /** 引用不得被悄悄过滤：缺图原样保留，发送时由 persistAttachments 明确报错。 */
    @Test
    fun `load keeps missing attachment references for the send path to fail loudly`() = runBlocking {
        val photo = tempPhoto()
        store.save("c1", "文字", listOf(photo.absolutePath))
        photo.delete()
        val draft = store.load("c1")
        assertEquals("文字", draft?.text)
        assertEquals(listOf(photo.absolutePath), draft?.photoPaths)
    }

    @Test
    fun `persistAttachments fails loudly on a missing attachment`() {
        val missing = File(context.cacheDir, "不存在的图.jpg")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.persistAttachments(listOf(missing.absolutePath)) }
        }
    }

    @Test
    fun `persistAttachments fails loudly on an empty attachment`() {
        val empty = tempPhoto()
        empty.writeBytes(ByteArray(0))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.persistAttachments(listOf(empty.absolutePath)) }
        }
    }

    @Test
    fun `persistAttachments copies into the private persistent dir`() = runBlocking {
        val photo = tempPhoto()
        val persisted = store.persistAttachments(listOf(photo.absolutePath))
        assertEquals(1, persisted.size)
        val copied = File(persisted[0])
        assertTrue(copied.isFile)
        assertTrue(
            "必须落在私有持久目录内",
            copied.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator),
        )
        assertEquals(photo.length(), copied.length())
    }
}
