package eu.kanade.presentation.foryou

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.foryou.components.RecommendationCard
import eu.kanade.presentation.foryou.components.RecommendationDetailDialog
import eu.kanade.tachiyomi.ui.foryou.ForYouState
import tachiyomi.domain.recommendation.model.Recommendation
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun ForYouScreen(
    state: ForYouState,
    onRefresh: () -> Unit,
    onSelectTagFilter: (String?) -> Unit,
    onSelectRecommendation: (Recommendation?) -> Unit,
    onSearchInSources: (Recommendation) -> Unit,
    onDismissRecommendation: (Recommendation) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                titleContent = { AppBarTitle("For You") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = MaterialSymbols.rounded.Refresh,
                            contentDescription = "Refresh Recommendations",
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        when {
            state.isLoading -> {
                LoadingScreen(modifier = Modifier.padding(contentPadding))
            }
            state.recommendations.isEmpty() -> {
                EmptyScreen(
                    message = if (state.errorMessage != null) {
                        "Unable to load recommendations: ${state.errorMessage}"
                    } else {
                        "No recommendations available yet. Read a few chapters in your library to train your taste profile!"
                    },
                    modifier = Modifier.padding(contentPadding),
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                ) {
                    // Tag filter chips row
                    if (state.availableTags.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = state.selectedTagFilter == null,
                                onClick = { onSelectTagFilter(null) },
                                label = { Text("All") },
                                colors = FilterChipDefaults.filterChipColors(),
                            )

                            state.availableTags.forEach { tag ->
                                FilterChip(
                                    selected = state.selectedTagFilter == tag,
                                    onClick = { onSelectTagFilter(tag) },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }

                    // Recommendations List
                    val filtered = state.filteredRecommendations
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                        ) {
                            Text(
                                text = "No titles match filter \"${state.selectedTagFilter}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 80.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = filtered,
                                key = { it.id + it.title },
                            ) { rec ->
                                RecommendationCard(
                                    recommendation = rec,
                                    onClick = { onSelectRecommendation(rec) },
                                    onSearchInSources = { onSearchInSources(rec) },
                                    onDismiss = { onDismissRecommendation(rec) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Detail Dialog
        state.selectedRecommendation?.let { selectedRec ->
            RecommendationDetailDialog(
                recommendation = selectedRec,
                onDismissRequest = { onSelectRecommendation(null) },
                onSearchInSources = { onSearchInSources(selectedRec) },
            )
        }
    }
}
