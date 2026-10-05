package eu.kanade.tachiyomi.data.recommendation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.recommendation.model.Recommendation
import tachiyomi.domain.recommendation.model.TasteProfile

@Inject
@SingleIn(AppScope::class)
class RecommendationScorer {

    fun score(
        candidate: CandidateManga,
        taste: TasteProfile,
    ): Recommendation {
        val candidateTags = (candidate.tags + candidate.genres).distinct()
        var accumulatedTagWeight = 0.0
        val matchedSignificantTags = mutableListOf<Pair<String, Double>>()

        candidateTags.forEach { rawTag ->
            val tag = rawTag.trim()
            // Check direct match or lowercase match
            val weight = taste.tagWeights[tag]
                ?: taste.tagWeights.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.value
                ?: 0.0

            if (weight > 0.25) {
                accumulatedTagWeight += weight
                matchedSignificantTags.add(tag to weight)
            }
        }

        // Tag matching score (45% weight)
        val tagScore = if (matchedSignificantTags.isNotEmpty()) {
            (accumulatedTagWeight / (matchedSignificantTags.size.coerceAtLeast(1))).coerceIn(0.0, 1.0)
        } else {
            0.15
        }

        // Quality rating boost (20% weight)
        val normalizedRating = when {
            candidate.rating != null && candidate.rating > 10.0 -> (candidate.rating / 100.0).coerceIn(0.0, 1.0)
            candidate.rating != null -> (candidate.rating / 10.0).coerceIn(0.0, 1.0)
            else -> 0.70 // Neutral baseline
        }

        // Catalog depth boost (15% weight)
        val chapterScore = when {
            candidate.chapterCount != null && candidate.chapterCount >= 50 -> 1.0
            candidate.chapterCount != null && candidate.chapterCount >= 20 -> 0.7
            else -> 0.5
        }

        // Breadth bonus for multiple strong trope matches (15% weight)
        val matchBreadthScore = (matchedSignificantTags.size / 4.0).coerceIn(0.0, 1.0)

        // Seed title boost (+25% when directly recommended from a user's favorite/completed manga)
        val seedBoost = if (!candidate.seedTitle.isNullOrBlank()) 0.25 else 0.0

        // Combined deterministic score
        val rawFinalScore = (tagScore * 0.35) +
            (normalizedRating * 0.25) +
            (chapterScore * 0.10) +
            (matchBreadthScore * 0.15) +
            seedBoost

        val finalScore = rawFinalScore.coerceIn(0.20, 0.99)
        val matchPct = (finalScore * 100).toInt()

        // Generate explainability reasons
        val topMatchedTags = matchedSignificantTags
            .sortedByDescending { it.second }
            .map { it.first }
            .take(3)

        val reasons = mutableListOf<String>()
        if (!candidate.seedTitle.isNullOrBlank()) {
            reasons.add("Community-voted top recommendation for readers of ${candidate.seedTitle}.")
        }
        if (topMatchedTags.isNotEmpty()) {
            reasons.add("Matches your reading preference for ${topMatchedTags.joinToString(", ")}.")
        } else if (candidate.seedTitle.isNullOrBlank()) {
            reasons.add("Highly rated discovery matching trending reader favorites.")
        }

        candidate.rating?.let { r ->
            val displayRating = if (r > 10) String.format("%.1f", r / 10.0) else String.format("%.1f", r)
            reasons.add("Rated $displayRating / 10 by community readers.")
        }

        if (candidate.chapterCount != null && candidate.chapterCount >= 30) {
            reasons.add("Established series with ${candidate.chapterCount} chapters available.")
        }

        val normTitle = candidate.title.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        val inLibrary = taste.libraryMangaTitles.contains(normTitle)

        return Recommendation(
            id = candidate.id,
            title = candidate.title,
            alternativeTitles = candidate.alternativeTitles,
            coverUrl = candidate.coverUrl,
            synopsis = candidate.synopsis,
            status = candidate.status,
            chapterCount = candidate.chapterCount,
            score = finalScore,
            matchPercentage = matchPct,
            primaryMatchingTags = topMatchedTags,
            allTags = candidateTags.take(8),
            reasons = reasons,
            externalUrl = candidate.externalUrl,
            provider = candidate.provider,
            isInLibrary = inLibrary,
            seedMangaTitle = candidate.seedTitle,
        )
    }
}
