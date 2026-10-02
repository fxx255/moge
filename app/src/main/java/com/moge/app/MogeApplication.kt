package com.moge.app

import android.app.Application
import com.moge.app.core.ApplicationScope
import com.moge.app.runtime.GenerationManager
import com.moge.app.runtime.KeepAliveService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.noties.jlatexmath.JLatexMathAndroid
import javax.inject.Inject

@HiltAndroidApp
class MogeApplication : Application() {

    @Inject lateinit var generationManager: GenerationManager

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // JLatexMath 的公式设置和字体放在 assets，静态初始化前必须 init(Context)；
        // 否则 TeXFormula 类初始化失败，所有公式都会退回占位符。
        JLatexMathAndroid.init(this)
        KeepAliveService.ensureChannel(this)
        // 进程级只扫一次：上个进程遗留的在途请求转成「可重试的中断」，不自动重发。
        // 提交入口也会先等这次扫描完成，所以不会把本进程新写的 PREPARING 当成孤儿。
        applicationScope.launch { generationManager.recoverOnStartup() }
    }
}
