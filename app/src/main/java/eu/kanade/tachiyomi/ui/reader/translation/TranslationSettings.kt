package eu.kanade.tachiyomi.ui.reader.translation

/**
 * Whether and how the reader translates the text of pages, all of it part of what the pages are made with.
 *
 * @property to the ML Kit translate language code of the language pages are translated to.
 */
data class TranslationSettings(
    val enabled: Boolean,
    val engine: TranslationEngine,
    val from: TranslationSource,
    val to: String,
    val geminiApiKey: String,
    val geminiModel: String,
)
