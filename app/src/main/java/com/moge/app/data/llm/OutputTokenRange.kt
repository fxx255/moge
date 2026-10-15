package com.moge.app.data.llm

/** An explicitly stated range for one output parameter, independent of provider and model names. */
internal class OutputTokenRange(parameter: String) {
    private val parameterName = """(?<![A-Za-z0-9_])${Regex.escape(parameter)}(?![A-Za-z0-9_])["'`]*\s*(?::\s*)?"""
    private val requirement = """(?:(?:should|must)\s+be\s+(?:(?:in|within)\s+)?(?:(?:the\s+)?range\s*)?|(?:的)?(?:取值)?范围(?:应|必须)?(?:为|是|在)\s*)"""
    private val prefix = parameterName + requirement
    private val bracketed = Regex("""$prefix(\[|\()\s*([0-9]+)\s*[,，]\s*($NUMBER)\s*(\]|\))""", RegexOption.IGNORE_CASE)
    private val between = Regex("""${parameterName}(?:should|must)\s+be\s+between\s+([0-9]+)\s+and\s+($NUMBER)(?=$|[\s"';}])""", RegexOption.IGNORE_CASE)
    private val rangeDescription = Regex("""(?:$prefix[\[(]|${parameterName}(?:should|must)\s+be\s+between\b)""", RegexOption.IGNORE_CASE)

    fun isDescribedBy(detail: String): Boolean = rangeDescription.containsMatchIn(detail)

    fun reducedMaximum(detail: String, requested: Int): Int? {
        val bracketedLimits = bracketed.findAll(detail).mapNotNull { match ->
            allowedMaximum(match.groupValues[2], match.groupValues[3],
                lowerInclusive = match.groupValues[1] == "[", upperInclusive = match.groupValues[4] == "]", requested)
        }
        val betweenLimits = between.findAll(detail).mapNotNull { match ->
            allowedMaximum(match.groupValues[1], match.groupValues[2], true, true, requested)
        }
        return (bracketedLimits + betweenLimits).minOrNull()
    }

    private fun allowedMaximum(lowerText: String, upperText: String,
        lowerInclusive: Boolean, upperInclusive: Boolean, requested: Int): Int? {
        val lower = lowerText.toIntOrNull()?.toLong() ?: return null
        val upper = upperText.replace(",", "").toIntOrNull()?.toLong() ?: return null
        val firstAllowed = (lower + if (lowerInclusive) 0 else 1).coerceAtLeast(1)
        val lastAllowed = upper - if (upperInclusive) 0 else 1
        return lastAllowed.takeIf { it >= firstAllowed && it < requested }?.toInt()
    }

    companion object {
        private const val NUMBER = """(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)"""
    }
}
