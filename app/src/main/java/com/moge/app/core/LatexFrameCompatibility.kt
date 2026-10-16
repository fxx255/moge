package com.moge.app.core

import android.graphics.Paint
import org.scilab.forge.jlatexmath.Atom
import org.scilab.forge.jlatexmath.Box
import org.scilab.forge.jlatexmath.FBoxAtom
import org.scilab.forge.jlatexmath.FramedBox
import org.scilab.forge.jlatexmath.MacroInfo
import org.scilab.forge.jlatexmath.SpaceAtom
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXEnvironment
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXParser
import ru.noties.jlatexmath.awt.Graphics2D

/** Both frame commands must keep strokes out of AndroidGraphics2D's shared glyph paint. */
internal fun installLatexFrameCompatibility() {
    for (command in listOf("fbox", "boxed")) {
        MacroInfo.Commands[command] = object : MacroInfo(1) {
            override fun invoke(parser: TeXParser, args: Array<String>): Atom =
                SolidFrameAtom(FrameContent(parser, args[1]).root)
        }
    }
}

/** Preserve the parser's text mode and locally defined commands, as the original fbox macro does. */
private class FrameContent(parser: TeXParser, source: String) : TeXFormula(parser, source, false)

private class SolidFrameAtom(content: Atom?) : FBoxAtom(content) {
    override fun createBox(env: TeXEnvironment): Box = SolidFrameBox(
        base.createBox(env), env.teXFont.getDefaultRuleThickness(env.style),
        INTERSPACE * SpaceAtom.getFactor(TeXConstants.UNIT_EM, env),
    )
}

private class SolidFrameBox(
    private val content: Box,
    private val rule: Float,
    private val gap: Float,
) : FramedBox(content, rule, gap) {
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rule
    }

    override fun draw(graphics: Graphics2D, x: Float, y: Float) {
        val half = rule / 2f
        framePaint.color = graphics.color.colorInt
        graphics.transform.canvas.drawRect(x + half, y - height + half,
            x + width - half, y + depth - half, framePaint)
        content.draw(graphics, x + gap + rule, y)
    }
}
