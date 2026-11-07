package vn.uneti.ai05

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class LoadingSkeletonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun initialLoadingShowsPlaceholderThenActualEmptyState() {
        var state by mutableStateOf(MessengerState())
        compose.setContent {
            LocTheme { InboxScreen(state, "", {}, "inbox", {}, true, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("loading_skeleton").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = false) }
        compose.onNodeWithTag("loading_skeleton").assertDoesNotExist()
    }
}
