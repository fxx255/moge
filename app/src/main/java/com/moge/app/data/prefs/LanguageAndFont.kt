package com.moge.app.data.prefs

/** Stable persisted identifiers; display names use each language's own writing system. */
enum class AppLanguage(val tag: String, val nativeName: String, val answerInstruction: String, val sourcesHeading: String) {
    SIMPLIFIED_CHINESE("zh-CN", "简体中文", "使用简体中文作答", "来源"),
    TRADITIONAL_CHINESE("zh-TW", "繁體中文", "使用繁體中文作答", "來源"),
    JAPANESE("ja", "日本語", "日本語で回答してください", "出典"),
    ENGLISH("en", "English", "Respond in English", "Sources"),
    ;

    companion object {
        fun fromName(value: String?): AppLanguage = entries.firstOrNull { it.name == value }
            ?: SIMPLIFIED_CHINESE
    }
}

enum class AppFont {
    SYSTEM, WENKAI, SOURCE_SERIF, ROUNDED;

    companion object {
        fun fromName(value: String?): AppFont = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
