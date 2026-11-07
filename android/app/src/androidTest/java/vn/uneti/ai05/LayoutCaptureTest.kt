package vn.uneti.ai05

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class LayoutCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(300, 5000)
        val variant = InstrumentationRegistry.getArguments().getString("layout_variant") ?: "layout"
        require(variant.matches(Regex("[a-z0-9-]+")))
        val folder = File(context.getExternalFilesDir(null), "layout-review").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$variant-$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    fun controlsRemainReachableWithLargeFontsAndKeyboard() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("layout_capture") == "true"
        )
        check(
            context
                .getSharedPreferences("messenger_settings", 0)
                .getBoolean("fixture_preview", false)
        )
        compose.onNodeWithTag("nav_inbox").performClick()
        compose.onNodeWithTag("conversation_filter").performClick()
        compose.onNodeWithTag("filter_archive").performClick()
        capture("filters")
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText(context.getString(R.string.filter_details)))
        compose.onNodeWithText(context.getString(R.string.filter_details)).performClick()
        compose.onNodeWithText(context.getString(R.string.close)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("nav_check").performClick()
        compose
            .onNodeWithTag("check_input")
            .performScrollTo()
            .performTextInput("Hello friend, see you tomorrow.")
        compose.onNodeWithTag("check_submit").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("check_result").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("check_result").performScrollTo().assertIsDisplayed()
        capture("result")
        compose.onNodeWithTag("nav_inbox").performClick()
        compose
            .onNodeWithContentDescription(context.getString(R.string.new_conversation))
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithText(context.getString(R.string.recipient))
            .performTextInput("+1 202-555-0147")
        compose
            .onAllNodes(hasSetTextAction())
            .onLast()
            .performTextReplacement("Synthetic draft only. Never send this message.")
        compose
            .onNode(
                hasSetTextAction() and hasText("Synthetic draft only. Never send this message.")
            )
            .performClick()
        compose
            .onNodeWithContentDescription(context.getString(R.string.send_message))
            .assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("require_ime") == "true") {
            compose.runOnUiThread {
                compose.activity
                    .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(compose.activity.currentFocus, 0)
            }
            compose.waitUntil(10_000) {
                compose.activity.window.decorView.rootWindowInsets?.let {
                    it.isVisible(WindowInsets.Type.ime()) &&
                        it.getInsets(WindowInsets.Type.ime()).bottom > 0
                } == true
            }
            compose
                .onNodeWithContentDescription(context.getString(R.string.send_message))
                .assertIsDisplayed()
        }
        capture("composer")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
}
