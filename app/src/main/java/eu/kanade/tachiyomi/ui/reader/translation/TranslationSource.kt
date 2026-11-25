package eu.kanade.tachiyomi.ui.reader.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * A language pages can be translated from, which decides the script the text is read in.
 *
 * @property language the ML Kit translate language code.
 * @property lineSeparator what joins the lines of a speech bubble, as Chinese and Japanese don't put spaces
 * between words.
 */
enum class TranslationSource(
    val language: String,
    val lineSeparator: String,
) {
    JAPANESE(TranslateLanguage.JAPANESE, ""),
    CHINESE(TranslateLanguage.CHINESE, ""),
    KOREAN(TranslateLanguage.KOREAN, " "),
    ENGLISH(TranslateLanguage.ENGLISH, " "),
    ;

    fun recognizerOptions(): TextRecognizerOptionsInterface {
        return when (this) {
            JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            ENGLISH -> TextRecognizerOptions.DEFAULT_OPTIONS
        }
    }
}
