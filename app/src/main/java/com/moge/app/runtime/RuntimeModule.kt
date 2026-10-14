package com.moge.app.runtime

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeModule {
    @Binds
    abstract fun bindGuard(impl: ForegroundGenerationGuard): GenerationGuard

    @Binds
    abstract fun bindFigureRenderer(impl: DefaultFigureRenderer): FigureRenderer

    @Binds
    abstract fun bindClock(impl: SystemMonotonicClock): com.moge.app.data.llm.MonotonicClock
}

/** 注入用的单调时钟（测试替换成假时钟）。 */
class SystemMonotonicClock @Inject constructor() : com.moge.app.data.llm.MonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}
