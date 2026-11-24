package eu.kanade.tachiyomi.ui.reader.translation

/**
 * Why a page wasn't translated as asked, for the reader to tell the user rather than show the page unchanged.
 */
sealed interface TranslationProblem {
    /** Gemini failed with [reason], and the page was translated on the device instead. */
    data class GeminiFailed(val reason: String) : TranslationProblem

    /** The page couldn't be translated at all, for [reason]. */
    data class Failed(val reason: String) : TranslationProblem

    /** No text was read, often as the page is in another language than the one set to translate from. */
    data object NoText : TranslationProblem
}
