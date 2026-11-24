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
import eu.kanade.tachiyomi.ui.reader.translation.TranslationSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale

@Composable
internal fun ColumnScope.TranslationPage(viewModel: ReaderSettingsViewModel) {
    val translateFrom by viewModel.preferences.translateFrom.collectAsState()
    val translateTo by viewModel.preferences.translateTo.collectAsState()

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

private fun languageName(code: String): String {
    val locale = Locale.getDefault()
    return Locale.forLanguageTag(code).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}
