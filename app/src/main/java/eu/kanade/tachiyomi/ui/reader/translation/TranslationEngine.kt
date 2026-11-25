package eu.kanade.tachiyomi.ui.reader.translation

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * What reads and translates the text of pages.
 */
enum class TranslationEngine(val titleRes: StringResource) {
    /** ML Kit, entirely on the device. */
    ML_KIT(MR.strings.translation_engine_on_device),

    /**
     * Gemini through the Gemini API with the user's key, which sends pages to Google but reads them with the
     * context of the chapter so far.
     */
    GEMINI(MR.strings.translation_engine_gemini),

    /** A language model of the user's choice on OpenRouter, given the text read on the device. */
    OPENROUTER(MR.strings.translation_engine_openrouter),

    /** DeepL, given the text read on the device. */
    DEEPL(MR.strings.translation_engine_deepl),
}
