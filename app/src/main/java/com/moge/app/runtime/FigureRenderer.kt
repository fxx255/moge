package com.moge.app.runtime

import android.content.Context
import android.content.res.Configuration
import com.moge.app.data.figure.DiagramImageStore
import com.moge.app.data.figure.PlotImageStore
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.ui.theme.usesChalk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 把图槽位渲染成 PNG 路径；失败槽位返回空串以保留编号。 */
interface FigureRenderer {
    suspend fun render(figures: List<ReplyFigure>): List<String>
}

/**
 * 默认实现：按**当前外观**渲染一份变体。另一种主题的变体在界面切换日夜时
 * 由 image store 凭 spec 台账按需重画，这里不预先画两份。
 *
 * - 失败槽位返回空串而不是被丢弃，否则 `[[FIGURE:n]]` 的指向会整体错位；
 * - 画不出来绝不影响文字回答：两个 store 内部吞掉异常返回 null。
 */
@Singleton
class DefaultFigureRenderer @Inject constructor(
    private val plotImageStore: PlotImageStore,
    private val diagramImageStore: DiagramImageStore,
    private val settings: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) : FigureRenderer {

    override suspend fun render(figures: List<ReplyFigure>): List<String> {
        if (figures.isEmpty()) return emptyList()
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = usesChalk(settings.current().appearance, systemDark)
        return withContext(Dispatchers.Default) {
            figures.map { figure ->
                when (figure) {
                    is ReplyFigure.Plot -> plotImageStore.render(figure.spec, dark)
                    is ReplyFigure.Diagram -> diagramImageStore.render(figure.spec, dark)
                    ReplyFigure.Missing -> null
                }.orEmpty()
            }
        }
    }
}
