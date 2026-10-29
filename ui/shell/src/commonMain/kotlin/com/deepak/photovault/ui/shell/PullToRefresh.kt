package com.deepak.photovault.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.deepak.photovault.designsystem.LocalGlassTheme
import kotlinx.coroutines.launch

/** Pull down at the top to refresh; the spinner appears just under the top pills. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PullToRefresh(padding: androidx.compose.foundation.layout.PaddingValues, onRefresh: suspend () -> Unit, content: @Composable () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refreshing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    val theme = LocalGlassTheme.current
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                try {
                    onRefresh()
                } finally {
                    refreshing = false
                }
            }
        },
        state = pull,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = refreshing,
                containerColor = theme.glassFallback,
                color = theme.accent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}

