package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.mlkit.nl.translate.TranslateLanguage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.translation.TranslationEngine
import eu.kanade.tachiyomi.ui.reader.translation.TranslationSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale

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
        TextItem(
            label = stringResource(MR.strings.pref_gemini_model),
            value = model,
            onChange = viewModel.preferences.geminiModel::set,
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
}

internal fun languageName(code: String): String {
    val locale = Locale.getDefault()
    return Locale.forLanguageTag(code).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}
