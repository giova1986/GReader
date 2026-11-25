package eu.kanade.tachiyomi.ui.reader.translation

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/**
 * How the names and terms of one series are translated, so they read the same on every page. Kept as lines of
 * `original = translation` the user can edit.
 */
data class Glossary(val terms: Map<String, String>) {

    /**
     * [text] with each original term replaced by its translation, longest first so a name isn't split by a shorter
     * one inside it.
     */
    fun applyTo(text: String): String {
        return terms.keys.sortedByDescending { it.length }.fold(text) { result, original ->
            result.replace(original, terms.getValue(original))
        }
    }

    fun format(): String = terms.entries.joinToString("\n") { (original, translation) -> "$original = $translation" }

    companion object {
        val EMPTY = Glossary(emptyMap())

        fun parse(text: String): Glossary {
            val terms = text.lineSequence()
                .mapNotNull { line ->
                    val original = line.substringBefore('=', "").trim()
                    val translation = line.substringAfter('=', "").trim()
                    if (original.isEmpty() || translation.isEmpty()) null else original to translation
                }
                .toMap()
            return Glossary(terms)
        }
    }
}

/**
 * The glossary of each series, which translation adds the names it finds to.
 */
class SeriesGlossaries(private val preferences: ReaderPreferences) {

    fun of(mangaId: Long): Glossary = Glossary.parse(preferences.translationGlossary(mangaId).get())

    /**
     * Adds the [terms] [mangaId]'s glossary doesn't have yet, leaving the user's own translations as they are.
     */
    @Synchronized
    fun learn(mangaId: Long, terms: Map<String, String>) {
        val glossary = of(mangaId)
        val added = terms
            .filterKeys { it.isNotBlank() && it !in glossary.terms }
            .filterValues { it.isNotBlank() }
            .entries.take((MAX_TERMS - glossary.terms.size).coerceAtLeast(0))
        if (added.isEmpty()) return
        val learned = Glossary(glossary.terms + added.associate { it.key.trim() to it.value.trim() })
        preferences.translationGlossary(mangaId).set(learned.format())
    }

    private companion object {
        /** Keeps the glossary readable and the Gemini prompt short. */
        const val MAX_TERMS = 200
    }
}
