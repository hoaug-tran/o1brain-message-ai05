package vn.uneti.ai05

import android.content.Intent
import android.net.Uri
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeyboardFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun editorRemainsNextToKeyboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SENDTO).setData(Uri.parse("smsto:+12025550149"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodes(hasSetTextAction()).onLast().performClick()
        compose.runOnUiThread {
            val view = compose.activity.currentFocus
            compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(view, 0)
        }
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val density = context.resources.displayMetrics.density
        fun gap(): Float {
            val field = compose.onAllNodes(hasSetTextAction()).onLast().fetchSemanticsNode().boundsInWindow
            return decor.height - decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom - field.bottom
        }
        try {
            compose.waitUntil(5_000) { gap() >= -density * 4 && gap() <= density * 40 }
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            fail("Keyboard gap=${gap()} px; root=${decor.height}; ime=${decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom}; field=${compose.onAllNodes(hasSetTextAction()).onLast().fetchSemanticsNode().boundsInWindow.bottom}")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putFloat("editor_gap_dp", gap() / density) })
        assertTrue("Editor position not stable", gap() >= -density * 4 && gap() <= density * 40)
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.waitUntil(5_000) { decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
        compose.onNodeWithTag("nav_inbox").assertIsDisplayed()
    }
}
