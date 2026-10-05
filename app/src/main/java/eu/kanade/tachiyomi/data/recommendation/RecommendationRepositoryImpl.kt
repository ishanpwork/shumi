package eu.kanade.tachiyomi.data.recommendation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
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
) : RecommendationRepository {

    private val dismissedTitles = ConcurrentHashMap.newKeySet<String>()
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
                val normTitle = rec.title.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
                val altNormTitles = rec.alternativeTitles.map { it.trim().lowercase().replace(Regex("[^a-z0-9]"), "") }
                taste.dislikedMangaTitles.contains(normTitle) ||
                    taste.libraryMangaTitles.contains(normTitle) ||
                    altNormTitles.any { taste.libraryMangaTitles.contains(it) }
            }
            .sortedByDescending { it.score }
            .take(60)
    }

    override suspend fun dismissRecommendation(title: String) {
        val normTitle = title.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        dismissedTitles.add(normTitle)
    }

    override suspend fun likeRecommendation(title: String) {
        val normTitle = title.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        likedTitles.add(normTitle)
    }
}
