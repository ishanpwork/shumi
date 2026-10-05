package eu.kanade.tachiyomi.ui.foryou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.Navigator
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.recommendation.MihonTasteBuilder
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.recommendation.interactor.GetRecommendations
import tachiyomi.domain.recommendation.model.Recommendation

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ForYouViewModel(
    private val getRecommendations: GetRecommendations,
    private val tasteBuilder: MihonTasteBuilder,
) : ViewModel() {

    private val _state = MutableStateFlow(ForYouState())
    val state: StateFlow<ForYouState> = _state.asStateFlow()

    init {
        loadRecommendations(forceRefresh = false)
    }

    fun refresh() {
        loadRecommendations(forceRefresh = true)
    }

    private fun loadRecommendations(forceRefresh: Boolean) {
        viewModelScope.launch {
            _state.update {
                if (forceRefresh) it.copy(isRefreshing = true, errorMessage = null)
                else it.copy(isLoading = true, errorMessage = null)
            }

            try {
                val taste = tasteBuilder.buildTasteProfile()
                val recs = getRecommendations.await(forceRefresh)

                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        recommendations = recs,
                        tasteProfile = taste,
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load for you recommendations" }
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = e.message ?: "Failed to load recommendations",
                    )
                }
            }
        }
    }

    fun selectTagFilter(tag: String?) {
        _state.update {
            val newFilter = if (it.selectedTagFilter == tag) null else tag
            it.copy(selectedTagFilter = newFilter)
        }
    }

    fun selectRecommendation(recommendation: Recommendation?) {
        _state.update { it.copy(selectedRecommendation = recommendation) }
    }

    fun dismissRecommendation(recommendation: Recommendation) {
        viewModelScope.launch {
            getRecommendations.dismiss(recommendation.title)
            _state.update { current ->
                val updated = current.recommendations.filterNot { it.id == recommendation.id }
                current.copy(
                    recommendations = updated,
                    selectedRecommendation = if (current.selectedRecommendation?.id == recommendation.id) null else current.selectedRecommendation,
                )
            }
        }
    }

    fun likeRecommendation(recommendation: Recommendation) {
        viewModelScope.launch {
            getRecommendations.like(recommendation.title)
        }
    }

    fun searchInSources(recommendation: Recommendation, navigator: Navigator) {
        _state.update { it.copy(selectedRecommendation = null) }
        navigator.push(GlobalSearchScreen(searchQuery = recommendation.title))
    }
}
