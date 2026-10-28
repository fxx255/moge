package com.moge.app.runtime

import com.moge.app.data.llm.tools.FigureToolRenderer
import com.moge.app.data.parse.ReplyFigure
import javax.inject.Inject

/** The existing image stores also retain theme-independent specs and reuse rendered PNGs. */
class RuntimeFigureToolRenderer @Inject constructor(private val renderer: FigureRenderer) : FigureToolRenderer {
    override suspend fun render(figure: ReplyFigure): Boolean = renderer.render(listOf(figure)).singleOrNull()?.isNotBlank() == true
}
