package com.moge.app.data.figure

import com.moge.app.ui.markdown.FigurePathResolver
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把消息里存的图表路径交给对应的 image store 解析成当前主题的变体：
 * 函数图和框图各自认得自己的文件名，都不认识的（比如用户照片）原样返回。
 */
@Singleton
class StoredFigureResolver @Inject constructor(
    private val plots: PlotImageStore,
    private val diagrams: DiagramImageStore,
) : FigurePathResolver {

    override fun resolve(storedPath: String, dark: Boolean): String? = when {
        plots.owns(storedPath) -> plots.resolve(storedPath, dark)
        diagrams.owns(storedPath) -> diagrams.resolve(storedPath, dark)
        else -> storedPath.takeIf { File(it).let { file -> file.isFile && file.length() > 0 } }
    }
}
