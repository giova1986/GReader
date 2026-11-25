package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.nl.translate.TranslateLanguage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.translation.Glossary
import eu.kanade.tachiyomi.ui.reader.translation.TranslationEngine
import eu.kanade.tachiyomi.ui.reader.translation.TranslationSource
import eu.kanade.tachiyomi.ui.reader.translation.fetchGeminiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun ColumnScope.TranslationPage(viewModel: ReaderSettingsViewModel) {
    val translateFrom by viewModel.preferences.translateFrom.collectAsState()
    val translateTo by viewModel.preferences.translateTo.collectAsState()
    val engine by viewModel.preferences.translationEngine.collectAsState()

    CheckboxItem(
        label = stringResource(MR.strings.pref_translate_pages),
        pref = viewModel.preferences.translatePages,
    )

    Text(
        text = stringResource(MR.strings.pref_translate_pages_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = SettingsItemsPaddings.Horizontal,
            vertical = SettingsItemsPaddings.Vertical,
        ),
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_translate_downloads),
        pref = viewModel.preferences.translateDownloads,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_detect_bubbles),
        pref = viewModel.preferences.detectBubbles,
    )

    Text(
        text = stringResource(MR.strings.pref_detect_bubbles_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = SettingsItemsPaddings.Horizontal,
            vertical = SettingsItemsPaddings.Vertical,
        ),
    )

    SettingsChipRow(MR.strings.pref_translation_engine) {
        TranslationEngine.entries.forEach {
            FilterChip(
                selected = engine == it,
                onClick = { viewModel.preferences.translationEngine.set(it) },
                label = { Text(stringResource(it.titleRes)) },
            )
        }
    }

    if (engine == TranslationEngine.GEMINI) {
        val apiKey by viewModel.preferences.geminiApiKey.collectAsState()
        val model by viewModel.preferences.geminiModel.collectAsState()
        Text(
            text = stringResource(MR.strings.pref_gemini_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        )
        TextItem(
            label = stringResource(MR.strings.pref_gemini_api_key),
            value = apiKey,
            onChange = viewModel.preferences.geminiApiKey::set,
        )
        val models = rememberGeminiModels(apiKey)
        val ids = (models as? GeminiModelList.Loaded)?.ids.orEmpty()
        if (ids.isEmpty()) {
            TextItem(
                label = stringResource(MR.strings.pref_gemini_model),
                value = model,
                onChange = viewModel.preferences.geminiModel::set,
            )
        } else {
            val options = if (model in ids) ids else listOf(model) + ids
            SelectItem(
                label = stringResource(MR.strings.pref_gemini_model),
                options = options.toTypedArray(),
                selectedIndex = options.indexOf(model),
                onSelect = { viewModel.preferences.geminiModel.set(options[it]) },
            )
        }
        Text(
            text = models.description(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        )
    }

    SettingsChipRow(MR.strings.pref_translate_from) {
        TranslationSource.entries.forEach { source ->
            FilterChip(
                selected = translateFrom == source,
                onClick = { viewModel.preferences.translateFrom.set(source) },
                label = { Text(languageName(source.language)) },
            )
        }
    }

    val targets = remember {
        TranslateLanguage.getAllLanguages().sortedBy(::languageName)
    }
    SelectItem(
        label = stringResource(MR.strings.pref_translate_to),
        options = targets.map(::languageName).toTypedArray(),
        selectedIndex = targets.indexOf(translateTo),
        onSelect = { viewModel.preferences.translateTo.set(targets[it]) },
    )

    val manga by viewModel.mangaFlow.collectAsState()
    manga?.let { GlossaryItem(viewModel, it.id) }
}

/**
 * Opens the glossary of the series being read, where translation keeps the names it finds and the user can set
 * their own.
 */
@Composable
private fun GlossaryItem(viewModel: ReaderSettingsViewModel, mangaId: Long) {
    val preference = remember(mangaId) { viewModel.preferences.translationGlossary(mangaId) }
    val text by preference.collectAsState()
    var editing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { editing = true }
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical),
    ) {
        Text(text = stringResource(MR.strings.pref_translation_glossary), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(MR.strings.pref_translation_glossary_summary, Glossary.parse(text).terms.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (editing) {
        var draft by remember { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(MR.strings.pref_translation_glossary)) },
            text = {
                Column {
                    Text(
                        text = stringResource(MR.strings.pref_translation_glossary_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        minLines = GLOSSARY_LINES,
                        maxLines = GLOSSARY_LINES * 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        preference.set(Glossary.parse(draft).format())
                        // Pages translated with the old names would otherwise be shown again
                        viewModel.pageTranslator.forget(mangaId)
                        editing = false
                    },
                ) {
                    Text(stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

private const val GLOSSARY_LINES = 6

internal fun languageName(code: String): String {
    val locale = Locale.getDefault()
    return Locale.forLanguageTag(code).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

/**
 * The Gemini models [apiKey] can use, as Google lists them, or why they aren't listed.
 */
internal sealed interface GeminiModelList {
    data object NoKey : GeminiModelList
    data object Loading : GeminiModelList
    data class Loaded(val ids: List<String>) : GeminiModelList
    data class Failed(val reason: String) : GeminiModelList
}

@Composable
internal fun rememberGeminiModels(apiKey: String): GeminiModelList {
    val context = LocalContext.current
    val models by produceState<GeminiModelList>(GeminiModelList.NoKey, apiKey) {
        if (apiKey.isBlank()) {
            value = GeminiModelList.NoKey
            return@produceState
        }
        value = GeminiModelList.Loading
        // The key is typed in, so it isn't sent at every letter
        delay(KEY_TYPING_DELAY)
        value = withContext(Dispatchers.IO) {
            try {
                GeminiModelList.Loaded(fetchGeminiModels(context.appGraph.networkHelper.client, apiKey))
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not list Gemini models" }
                GeminiModelList.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
    return models
}

@Composable
internal fun GeminiModelList.description(): String {
    return when (this) {
        GeminiModelList.NoKey -> stringResource(MR.strings.pref_gemini_models_no_key)
        GeminiModelList.Loading -> stringResource(MR.strings.pref_gemini_models_loading)
        is GeminiModelList.Loaded -> stringResource(MR.strings.pref_gemini_models_found, ids.size)
        is GeminiModelList.Failed -> stringResource(MR.strings.pref_gemini_models_failed, reason)
    }
}

private val KEY_TYPING_DELAY = 1.seconds
