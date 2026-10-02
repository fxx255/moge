package com.moge.app.ui.components

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoArrivalsTest {
    @After fun teardown() = PhotoArrivals.reset()

    @Test fun `only newly added photos animate and only once per surface`() {
        assertFalse("旧题照片不飞入", PhotoArrivals.claim("/old.jpg", "question"))
        PhotoArrivals.markNew(listOf("/new.jpg"))
        assertTrue(PhotoArrivals.claim("/new.jpg", "confirm"))
        assertFalse("弹层重开不重播", PhotoArrivals.claim("/new.jpg", "confirm"))
        assertTrue("进入解题页的题目卡再落一次", PhotoArrivals.claim("/new.jpg", "question"))
        assertFalse("列表滑回来不重播", PhotoArrivals.claim("/new.jpg", "question"))
    }
}
