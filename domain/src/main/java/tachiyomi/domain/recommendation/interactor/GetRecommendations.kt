package tachiyomi.domain.recommendation.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.recommendation.model.Recommendation
import tachiyomi.domain.recommendation.repository.RecommendationRepository

@Inject
class GetRecommendations(
    private val repository: RecommendationRepository,
) {
    suspend fun await(forceRefresh: Boolean = false): List<Recommendation> {
        return repository.getRecommendations(forceRefresh)
    }

    suspend fun dismiss(title: String) {
        repository.dismissRecommendation(title)
    }

    suspend fun like(title: String) {
        repository.likeRecommendation(title)
    }
}
