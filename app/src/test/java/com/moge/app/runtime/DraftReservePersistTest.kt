package com.moge.app.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * **ViewModel 集成面适配测试**（不构造完整 VM —— 它需要 20+ 个依赖）。
 *
 * AssistantViewModel 的草稿路径已改为「同步 [DraftStore.reserveSave] +
 * 异步 [DraftStore.persist]」。这组测试站在 VM 的视角，验证它依赖的
 * store 契约：快速编辑的乱序 persist 不会旧写覆盖新编辑、切会话的收尾清理
 * 不会越过更新的 reserve。
 *
 * 磁盘语义与完整验收见 [DraftStoreTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, shadows = [PosixAtomicFileShadow::class])
class DraftReservePersistTest {

    private lateinit var store: DraftStore

    @Before
    fun setUp() {
        store = DraftStore(ApplicationProvider.getApplicationContext<Context>())
    }

    /** freshStore = 重启后只看磁盘：断言真正的落盘结果，而不是进程内 pending。 */
    private fun freshStore(): DraftStore =
        DraftStore(ApplicationProvider.getApplicationContext())

    /** 快速连打三个字，persist 乱序完成：磁盘上必须只有最后一个字。 */
    @Test
    fun `out of order persists never overwrite a newer edit`() = runBlocking {
        val first = store.reserveSave("c1", "第", emptyList())
        val second = store.reserveSave("c1", "第二", emptyList())
        val third = store.reserveSave("c1", "第三", emptyList())
        // 模拟调度乱序：最后 reserve 的先落盘，最早的最后到。
        store.persist(third)
        store.persist(second)
        store.persist(first)
        assertEquals("第三", freshStore().load("c1")?.text)
    }

    /** reserve 是无 IO 的：它必须能在异步 persist 排队期间同步完成并立即生效。 */
    @Test
    fun `load sees the newest pending before its persist lands`() = runBlocking {
        val reservation = store.reserveSave("c1", "还没落盘的最新输入", emptyList())
        // 进程内不等落盘就能读到（VM 切会话时依赖这个语义）。
        assertEquals("还没落盘的最新输入", store.load("c1")?.text)
        // 磁盘上还没有 —— 读的确实是 pending，不是磁盘。
        assertNull(freshStore().load("c1"))
        store.persist(reservation)
        assertEquals("还没落盘的最新输入", freshStore().load("c1")?.text)
    }

    /** 发送收尾：空 reserve 作废在途的旧草稿写，旧 persist 不得让已发送的草稿复活。 */
    @Test
    fun `send-cleanup empty reserve invalidates in-flight draft persists`() = runBlocking {
        val stale = store.reserveSave("c1", "发送前的草稿", emptyList())
        // 收尾（submit 尾部）：同步 reserve 空 → persist（空 = 删文件）。
        val cleanup = store.reserveSave("c1", "", emptyList())
        store.persist(cleanup)
        // 迟到的旧 persist（真实场景里它在另一个慢协程里）：草稿不得复活。
        store.persist(stale)
        assertNull("已发送的草稿不得被迟到的旧写复活", freshStore().load("c1"))
    }

    /** 切会话：当前输入先同步 reserve，再载入目标会话 —— 旧会话的草稿完整落盘。 */
    @Test
    fun `switching conversations persists the outgoing draft by reservation`() = runBlocking {
        val outgoing = store.reserveSave("c1", "会话一的草稿", emptyList())
        val incoming = store.reserveSave("c2", "会话二的草稿", emptyList())
        // 两个会话各自落盘，互不覆盖。
        store.persist(outgoing)
        store.persist(incoming)
        assertEquals("会话一的草稿", freshStore().load("c1")?.text)
        assertEquals("会话二的草稿", freshStore().load("c2")?.text)
    }

    /** 生成期间用户重新输入（未 persist 的 pending）必须挡住 baseline 收尾清理。 */
    @Test
    fun `pending edit during generation blocks the baseline cleanup`() = runBlocking {
        store.save("c1", "", emptyList()) // 发送后留下的基准：空
        store.reserveSave("c1", "生成期间开始写下一句", emptyList()) // 新输入尚未 persist
        val cleared = store.clearIfBaselineMatches("c1", baselineText = "", baselinePhotos = emptyList())
        assertFalse("收尾不得清掉未落盘的新编辑", cleared)
        // 注意：必须用**同一个** store 断言 —— pending 是进程内状态，
        // freshStore 只看磁盘，而这份新编辑还没 persist。
        assertEquals("生成期间开始写下一句", store.load("c1")?.text)
    }

    /** 并发烟雾测试：多协程同时 reserve + persist，最终磁盘收敛到某个最新意图。 */
    @Test
    fun `concurrent reserve and persist keeps the draft intact`() = runBlocking {
        coroutineScope {
            repeat(20) { i ->
                val reservation = store.reserveSave("c1", "第${i}版", emptyList())
                launch(Dispatchers.IO) { store.persist(reservation) }
            }
        }
        // 等结构化子任务全部收尾后读磁盘，避免另一个 store 在原子写中途读取 .new。
        assertEquals("第19版", freshStore().load("c1")?.text)
    }
}
