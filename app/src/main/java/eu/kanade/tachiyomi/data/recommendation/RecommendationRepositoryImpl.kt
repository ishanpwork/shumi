package eu.kanade.tachiyomi.data.recommendation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.recommendation.model.Recommendation
import tachiyomi.domain.recommendation.model.TasteProfile
import tachiyomi.domain.recommendation.repository.RecommendationRepository
import java.util.concurrent.ConcurrentHashMap

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RecommendationRepositoryImpl(
    private val tasteBuilder: MihonTasteBuilder,
    private val metadataApi: RecommendationMetadataApi,
    private val scorer: RecommendationScorer,
    private val preferenceStore: PreferenceStore,
) : RecommendationRepository {

    private val dismissedTitlesPref = preferenceStore.getStringSet("foryou_dismissed_titles", emptySet())
    private val dismissedTitles = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(dismissedTitlesPref.get())
    }
    private val likedTitles = ConcurrentHashMap.newKeySet<String>()

    override suspend fun getTasteProfile(): TasteProfile {
        val baseProfile = tasteBuilder.buildTasteProfile()
        return baseProfile.copy(
            dislikedMangaTitles = dismissedTitles.toSet(),
        )
    }

    override suspend fun getRecommendations(forceRefresh: Boolean): List<Recommendation> {
        val taste = getTasteProfile()
        val keywords = taste.topTags.take(5)

        val candidates = try {
            metadataApi.fetchCandidates(taste.topFavoriteTitles, keywords, forceRefresh)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to retrieve candidates" }
            emptyList()
        }

        if (candidates.isEmpty()) {
            return emptyList()
        }

        return candidates
            .map { scorer.score(it, taste) }
            .filterNot { rec ->
                val candVariants = mutableSetOf<String>()
                candVariants.addAll(TitleNormalizer.extractVariants(rec.title))
                for (alt in rec.alternativeTitles) {
                    candVariants.addAll(TitleNormalizer.extractVariants(alt))
                }

                TitleNormalizer.isMatch(candVariants, taste.dislikedMangaTitles) ||
                    TitleNormalizer.isMatch(candVariants, taste.libraryMangaTitles) ||
                    TitleNormalizer.isMatch(candVariants, taste.completedMangaTitles)
            }
            .sortedByDescending { it.score }
            .take(60)
    }

    override suspend fun dismissRecommendation(title: String) {
        val variants = TitleNormalizer.extractVariants(title)
        dismissedTitles.addAll(variants)
        dismissedTitlesPref.set(dismissedTitles.toSet())
    }

    override suspend fun likeRecommendation(title: String) {
        val variants = TitleNormalizer.extractVariants(title)
        likedTitles.addAll(variants)
    }
}
