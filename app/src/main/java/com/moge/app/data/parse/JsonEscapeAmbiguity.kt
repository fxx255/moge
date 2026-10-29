package com.moge.app.data.parse

/**
 * A JSON line break followed by e= also spells the TeX command \ne.
 * Since an inequality immediately followed by = is not that command's normal use,
 * prefer the JSON newline here. Explicitly doubled backslashes bypass this repair.
 * Keep this decision shared by complete and incremental JSON decoding.
 */
internal fun isNewlineBeforeAssignment(word: String, following: Char?): Boolean =
    word == "ne" && following == '='
