package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.nl.translate.TranslateLanguage
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.reader.settings.GeminiModelList
import eu.kanade.presentation.reader.settings.description
import eu.kanade.presentation.reader.settings.languageName
import eu.kanade.presentation.reader.settings.rememberGeminiModels
import eu.kanade.presentation.reader.settings.rememberOpenRouterModels
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
        val model by readerPref.geminiModel.collectAsState()
        val models = rememberGeminiModels(apiKey)
        val openRouterKey by readerPref.openRouterApiKey.collectAsState()
        val openRouterModel by readerPref.openRouterModel.collectAsState()
        val openRouterModels = rememberOpenRouterModels()
        val deeplKey by readerPref.deeplApiKey.collectAsState()
        val ids = (models as? GeminiModelList.Loaded)?.ids.orEmpty()
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
                preference = readerPref.translateDownloads,
                title = stringResource(MR.strings.pref_translate_downloads),
                subtitle = stringResource(MR.strings.pref_translate_downloads_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPref.detectBubbles,
                title = stringResource(MR.strings.pref_detect_bubbles),
                subtitle = stringResource(MR.strings.pref_detect_bubbles_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPref.mangaOcr,
                title = stringResource(MR.strings.pref_manga_ocr),
                subtitle = stringResource(MR.strings.pref_manga_ocr_summary),
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
                        subtitle = masked(apiKey),
                    ),
                    if (ids.isEmpty()) {
                        Preference.PreferenceItem.EditTextPreference(
                            preference = readerPref.geminiModel,
                            title = stringResource(MR.strings.pref_gemini_model),
                        )
                    } else {
                        Preference.PreferenceItem.ListPreference(
                            preference = readerPref.geminiModel,
                            entries = (listOf(model) + ids).distinct().associateWith { it },
                            title = stringResource(MR.strings.pref_gemini_model),
                        )
                    },
                    Preference.PreferenceItem.InfoPreference(models.description()),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translation_engine_openrouter),
                visible = engine == TranslationEngine.OPENROUTER,
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_openrouter_summary)),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = readerPref.openRouterApiKey,
                        title = stringResource(MR.strings.pref_openrouter_api_key),
                        subtitle = masked(openRouterKey),
                    ),
                    if (openRouterModels.isEmpty()) {
                        Preference.PreferenceItem.EditTextPreference(
                            preference = readerPref.openRouterModel,
                            title = stringResource(MR.strings.pref_openrouter_model),
                        )
                    } else {
                        Preference.PreferenceItem.ListPreference(
                            preference = readerPref.openRouterModel,
                            entries = (listOf(openRouterModel) + openRouterModels).distinct().associateWith { it },
                            title = stringResource(MR.strings.pref_openrouter_model),
                        )
                    },
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translation_engine_deepl),
                visible = engine == TranslationEngine.DEEPL,
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_deepl_summary)),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = readerPref.deeplApiKey,
                        title = stringResource(MR.strings.pref_deepl_api_key),
                        subtitle = masked(deeplKey),
                    ),
                ),
            ),
        )
    }

    /** Keeps a key off the screen. */
    private fun masked(key: String): String? = if (key.isEmpty()) null else "•".repeat(12) + key.takeLast(4)
}
