package com.moge.app.data.parse

import android.app.Application
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * JLatexMath 在**当前依赖组合 / 当前 JDK** 下是否可用。
 *
 * 背景与定位方法见 https://github.com/noties/jlatexmath-android —— 它把字体与
 * 设置资源放在 assets，静态初始化要求先 `JLatexMathAndroid.init(context)`；
 * 若初始化本身失败，后续所有 `TeXFormula` 引用都会抛出
 * `NoClassDefFoundError: Could not initialize class ...TeXFormula`
 * （注意是 `NoClassDefFoundError` 而不是 `ExceptionInInitializerError` —— 首次
 * 初始化失败的异常被吞掉，第二次引用就只剩 "Could not initialize class"）。
 *
 * 这个测试的价值是**给出失败原因**（而不是让别的测试类各自报一句无信息量的
 * NoClassDefFoundError）：它把初始化异常原样抛出来，报告里就能看到真正的根因。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class JLatexMathAvailabilityTest {

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as Application
        try {
            JLatexMathAndroid.init(app)
        } catch (t: Throwable) {
            // 原样抛出：这是根因，必须让它出现在测试报告里
            throw AssertionError(
                "JLatexMathAndroid.init(context) 失败 —— 公式渲染在当前环境不可用。\n" +
                    "根因：${t.javaClass.name}: ${t.message}",
                t,
            )
        }
    }

    @Test
    fun canParseASimpleFormula() {
        // 不依赖 assets 的最简公式：能构造出来就说明静态初始化成功
        val f = TeXFormula("a")
        org.junit.Assert.assertNotNull(f)
    }
}
