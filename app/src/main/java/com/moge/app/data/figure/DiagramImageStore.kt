package com.moge.app.data.figure

import android.content.Context
import android.graphics.Bitmap
import com.moge.app.domain.diagram.DiagramSpec
import com.moge.app.ui.figure.DiagramRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 把 [DiagramSpec] 渲染成 PNG 并持久化到 `filesDir/diagrams/`，同时写 spec 台账。 */
@Singleton
class DiagramImageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FigureImageStore<DiagramSpec>(
    rootDir = { context.filesDir },
    dirName = "diagrams",
    prefix = "diagram",
    serializer = DiagramSpec.serializer(),
    // 布局几何、字体或标签位置变化时递增，否则旧 PNG 会盖住新模板
    renderVersion = "d3",
) {
    override fun draw(spec: DiagramSpec, dark: Boolean): Bitmap = DiagramRenderer.render(spec, dark = dark)

    override fun describe(spec: DiagramSpec): String = "title=${spec.title} nodes=${spec.nodes.size}"
}
