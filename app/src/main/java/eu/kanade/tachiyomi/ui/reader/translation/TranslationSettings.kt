package eu.kanade.tachiyomi.ui.reader.translation

/**
 * Whether and how the reader translates the text of pages, all of it part of what the pages are made with.
 *
 * @property to the ML Kit translate language code of the language pages are translated to.
 * @property detectBubbles whether text is grouped by the speech bubbles a model finds, rather than by ML Kit alone.
 * @property mangaOcr whether Japanese text the bubble detector finds is read by manga-ocr rather than ML Kit.
 */
data class TranslationSettings(
    val enabled: Boolean,
    val engine: TranslationEngine,
    val from: TranslationSource,
    val to: String,
    val detectBubbles: Boolean,
    val mangaOcr: Boolean,
    val geminiApiKey: String,
    val geminiModel: String,
    val openRouterApiKey: String,
    val openRouterModel: String,
    val deeplApiKey: String,
)
