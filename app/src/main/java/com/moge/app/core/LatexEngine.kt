package com.moge.app.core

import android.content.Context
import android.graphics.Paint
import org.scilab.forge.jlatexmath.Atom
import org.scilab.forge.jlatexmath.Box
import org.scilab.forge.jlatexmath.DefaultTeXFont
import org.scilab.forge.jlatexmath.JavaFontRenderingAtom
import org.scilab.forge.jlatexmath.MacroInfo
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXEnvironment
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXParser
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.awt.Font
import ru.noties.jlatexmath.awt.Graphics2D

/** Initialize Android formula compatibility before any asynchronous rendering starts. */
internal object LatexEngine {
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        // TeXFormula's static initializer reads the Android assets.
        JLatexMathAndroid.init(context.applicationContext)
        for ((closed, open) in listOf("oiint" to "iint", "oiiint" to "iiint")) {
            MacroInfo.Commands[closed] = ClosedIntegralMacro("\\$open")
        }
        TeXFormula.symbolFormulaMappings['∯'.code] = "\\oiint"
        TeXFormula.symbolFormulaMappings['∰'.code] = "\\oiiint"
        // CM stores Omega at U+00AD. Android shapes that code point as an
        // invisible soft hyphen, including inside integral subscripts.
        MacroInfo.Commands["Omega"] = VisibleOmegaMacro()
        initialized = true
    }
}

private class VisibleOmegaMacro : MacroInfo(0) {
    override fun invoke(parser: TeXParser, args: Array<String>): Atom = VisibleOmegaAtom()
}

private class VisibleOmegaAtom : Atom() {
    override fun createBox(env: TeXEnvironment): Box {
        val font = env.teXFont
        val style = (if (font.bold) Font.BOLD else Font.PLAIN) or
            (if ((font as? DefaultTeXFont)?.isIt == true) Font.ITALIC else Font.PLAIN)
        return JavaFontRenderingAtom("Ω", style).createBox(env)
    }
}

private class ClosedIntegralMacro(private val integrals: String) : MacroInfo(0) {
    // \limits mutates its preceding Atom. Each parse needs its own operator,
    // rather than an Atom shared through predefinedTeXFormulas.
    override fun invoke(parser: TeXParser, args: Array<String>): Atom = ClosedIntegralAtom(integrals)
}

/** A single closure ellipse crosses all integral strokes; scripts belong to the whole operator. */
private class ClosedIntegralAtom(private val integrals: String) : Atom() {
    init {
        type = TeXConstants.TYPE_BIG_OPERATOR
        type_limits = TeXConstants.SCRIPT_NOLIMITS
    }

    override fun createBox(env: TeXEnvironment): Box {
        // Build per layout: RowAtom keeps mutable spacing state and must not be shared by workers.
        val base = TeXFormula(integrals).root.createBox(env)
        return ClosedIntegralBox(base, env.teXFont.getAxisHeight(env.style),
            env.teXFont.getDefaultRuleThickness(env.style))
    }
}

private class ClosedIntegralBox(
    private val base: Box,
    private val axis: Float,
    thickness: Float,
) : Box() {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = thickness
    }

    init {
        width = base.width
        height = base.height
        depth = base.depth
        shift = base.shift
    }

    override fun draw(graphics: Graphics2D, x: Float, y: Float) {
        base.draw(graphics, x, y)
        val ringWidth = width * 0.72f
        val ringHeight = (height + depth) * 0.22f
        val left = x + (width - ringWidth) / 2f
        val top = y - axis - ringHeight / 2f
        ringPaint.color = graphics.color.colorInt
        // Use an independent Paint: AndroidGraphics2D.draw(shape) leaves its shared
        // text paint in STROKE mode, making following letters hollow or invisible.
        graphics.transform.canvas.drawOval(left, top, left + ringWidth, top + ringHeight, ringPaint)
    }

    override fun getLastFontId(): Int = base.lastFontId
}
