package com.moge.app.data.prefs

/** Stable persisted identifiers; labels and drawing belong to the UI. */
enum class ListMarkerStyle(val hasStroke: Boolean = true) {
    OCTAGON, RING, ROUNDED_DIAMOND, DASH, ROUNDED_SQUARE,
    THEME_DOT(false), CLASSIC_DOT(false);

    companion object {
        fun fromName(value: String?): ListMarkerStyle = entries.firstOrNull { it.name == value } ?: OCTAGON
    }
}

data class ListMarkerSettings(
    val style: ListMarkerStyle = ListMarkerStyle.OCTAGON,
    val sizePercent: Int = 100,
    val strokePercent: Int = 100,
    val indentPercent: Int = 100,
) {
    fun normalized() = copy(
        sizePercent = sizePercent.coerceIn(sizeRange),
        strokePercent = strokePercent.coerceIn(strokeRange),
        indentPercent = indentPercent.coerceIn(indentRange),
    )

    companion object {
        val sizeRange = 50..160
        val strokeRange = 50..160
        // At the smallest indent, the largest mark still fits entirely before the text.
        val indentRange = 75..180
    }
}
