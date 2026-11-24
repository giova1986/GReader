package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.nl.translate.TranslateLanguage
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.reader.settings.languageName
import eu.kanade.tachiyomi.ui.reader.translation.TranslationEngine
import eu.kanade.tachiyomi.ui.reader.translation.TranslationSource
import kotlinx.serialization.Serializable
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Serializable
data object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val readerPref = remember { context.appGraph.readerPreferences }
        val engine by readerPref.translationEngine.collectAsState()
        val apiKey by readerPref.geminiApiKey.collectAsState()
        val targets = remember {
            TranslateLanguage.getAllLanguages().sortedBy(::languageName).associateWith(::languageName)
        }

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPref.translatePages,
                title = stringResource(MR.strings.pref_translate_pages),
                subtitle = stringResource(MR.strings.pref_translate_pages_summary),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = readerPref.translateFrom,
                entries = TranslationSource.entries.associateWith { languageName(it.language) },
                title = stringResource(MR.strings.pref_translate_from),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = readerPref.translateTo,
                entries = targets,
                title = stringResource(MR.strings.pref_translate_to),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPref.detectBubbles,
                title = stringResource(MR.strings.pref_detect_bubbles),
                subtitle = stringResource(MR.strings.pref_detect_bubbles_summary),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = readerPref.translationEngine,
                entries = TranslationEngine.entries.associateWith { stringResource(it.titleRes) },
                title = stringResource(MR.strings.pref_translation_engine),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translation_engine_gemini),
                visible = engine == TranslationEngine.GEMINI,
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_gemini_summary)),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = readerPref.geminiApiKey,
                        title = stringResource(MR.strings.pref_gemini_api_key),
                        // Keeps the key off the screen.
                        subtitle = if (apiKey.isEmpty()) null else "•".repeat(12) + apiKey.takeLast(4),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = readerPref.geminiModel,
                        title = stringResource(MR.strings.pref_gemini_model),
                    ),
                ),
            ),
        )
    }
}
