package eu.kanade.tachiyomi.ui.foryou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.foryou.ForYouScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R

data object ForYouTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 1u,
            title = "For You",
            icon = painterResource(R.drawable.sc_explore_48dp),
        )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ForYouViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        ForYouScreen(
            state = state,
            onRefresh = viewModel::refresh,
            onSelectTagFilter = viewModel::selectTagFilter,
            onSelectRecommendation = viewModel::selectRecommendation,
            onSearchInSources = { rec -> viewModel.searchInSources(rec, navigator) },
            onDismissRecommendation = viewModel::dismissRecommendation,
        )
    }
}
