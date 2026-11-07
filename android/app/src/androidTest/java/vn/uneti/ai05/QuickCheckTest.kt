package vn.uneti.ai05

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class QuickCheckTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun expandedEvidenceMatchesCurrentModelWithoutSourceDescriptions() {
        compose.setContent {
            LocTheme {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) { ModelEvidence() }
            }
        }
        compose.onNodeWithText(context.getString(R.string.evidence_unavailable)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.external_vietnamese)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("uci_sms_spam_en_v1").assertDoesNotExist()
        val evidence = org.json.JSONObject(context.assets.open("model_evidence.json").bufferedReader().use { it.readText() }).getJSONObject("confusion")
        compose.onNodeWithText("TN ${evidence.getInt("tn")} / FP ${evidence.getInt("fp")}\nFN ${evidence.getInt("fn")} / TP ${evidence.getInt("tp")}").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun singleTrainInferenceValidatesInputAndClearsStaleResult() {
        compose.setContent { LocTheme { QuickCheckScreen() } }
        compose.onNodeWithTag("check_submit").assertIsNotEnabled()
        compose
            .onNodeWithTag("check_input")
            .performTextInput("Congratulations claim your free prize now!")
        compose.onNodeWithTag("check_submit").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("check_result").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithText(context.getString(R.string.spam_result))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(R.string.result_details))
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithText(context.getString(R.string.threshold_frozen, 0.0))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("check_input").performScrollTo().performTextReplacement("Xin chào")
        compose.onNodeWithTag("check_result").assertDoesNotExist()
        compose.onNodeWithTag("check_submit").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("check_result").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("check_error").assertDoesNotExist()
        compose
            .onNodeWithTag("check_input")
            .performScrollTo()
            .performTextReplacement("x".repeat(2001))
        compose.onNodeWithTag("check_submit").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.invalid_input)).assertIsDisplayed()
        compose.onNodeWithTag("check_result").assertDoesNotExist()
    }

    @Test
    fun tenUserCasesDisplayActualRuntimeLabelAndScore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cases =
            org.json
                .JSONObject(
                    instrumentation.context.assets
                        .open("ten-sms-parity.json")
                        .bufferedReader()
                        .use { it.readText() }
                )
                .getJSONArray("cases")
        compose.setContent { LocTheme { QuickCheckScreen() } }
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            compose
                .onNodeWithTag("check_input")
                .performScrollTo()
                .performTextReplacement(row.getString("text"))
            compose.onNodeWithTag("check_submit").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("check_result").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithText(
                    context.getString(
                        riskTitle(
                            AppRuntime.get(context).assessMessage(row.getString("text"))!!.decision
                        )
                    )
                )
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(context.getString(R.string.result_details))
                .performScrollTo()
                .performClick()
            compose
                .onNodeWithText(
                    context.getString(
                        R.string.score_format,
                        row.getDouble("actual_margin"),
                        row.getString("actual_label"),
                    )
                )
                .performScrollTo()
                .assertIsDisplayed()
        }
    }
}
