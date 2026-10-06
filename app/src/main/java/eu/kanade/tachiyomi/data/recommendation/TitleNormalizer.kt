package eu.kanade.tachiyomi.data.recommendation

object TitleNormalizer {

    fun normalize(title: String): String {
        return title.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    fun clean(title: String): String {
        return title
            .replace(Regex("\\[.*?\\]"), " ")
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("\\{.*?\\}"), " ")
            .replace(Regex("【.*?】"), " ")
            .replace(
                Regex("(?i)\\b(season\\s*\\d+|chapter\\s*\\d+|ch\\.\\s*\\d+|part\\s*\\d+|vol\\.?\\s*\\d+|webtoon|manhwa|manga|manhua|official|color|hd|raw|scanlation|scans|scan|comics|comic)\\b"),
                " ",
            )
            .trim()
    }

    fun extractVariants(title: String): Set<String> {
        val variants = mutableSetOf<String>()
        val trimmed = title.trim()
        if (trimmed.isBlank()) return variants

        val rawNorm = normalize(trimmed)
        if (rawNorm.isNotBlank()) {
            variants.add(rawNorm)
        }

        val cleaned = clean(trimmed)
        val cleanedNorm = normalize(cleaned)
        if (cleanedNorm.isNotBlank()) {
            variants.add(cleanedNorm)
        }

        // Without leading articles: "the ", "a ", "an "
        val withoutArticle = cleaned.replace(Regex("(?i)^(the|a|an)\\s+"), "").trim()
        val withoutArticleNorm = normalize(withoutArticle)
        if (withoutArticleNorm.isNotBlank()) {
            variants.add(withoutArticleNorm)
        }

        // Subtitle delimiters
        val subtitleDelimiters = listOf(":", " - ", " – ", " — ", "~", "/", "|", "•")
        for (delim in subtitleDelimiters) {
            if (cleaned.contains(delim)) {
                val prefix = cleaned.substringBefore(delim).trim()
                val prefixNorm = normalize(prefix)
                if (prefixNorm.length >= 4) {
                    variants.add(prefixNorm)
                    val prefixWithoutArticle = prefix.replace(Regex("(?i)^(the|a|an)\\s+"), "").trim()
                    val pwoNorm = normalize(prefixWithoutArticle)
                    if (pwoNorm.length >= 4) {
                        variants.add(pwoNorm)
                    }
                }
            }
        }

        return variants
    }

    fun isMatch(candVariants: Collection<String>, knownVariants: Set<String>): Boolean {
        if (candVariants.isEmpty() || knownVariants.isEmpty()) return false

        // 1. Direct variant match
        for (cand in candVariants) {
            if (cand.isBlank()) continue
            if (knownVariants.contains(cand)) {
                return true
            }
        }

        // 2. Prefix / containment match for titles with length >= 8
        for (cand in candVariants) {
            if (cand.length < 8) continue
            for (known in knownVariants) {
                if (known.length < 8) continue
                if (cand.startsWith(known) || known.startsWith(cand)) {
                    return true
                }
            }
        }

        return false
    }
}
