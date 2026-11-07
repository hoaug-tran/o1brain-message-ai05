package vn.uneti.ai05

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FilteringFlowTest {
    private fun detector(margin: Double) = ProductDetector(PortableClassifier(JSONObject()
        .put("format_version", 1).put("score_kind", "decision_margin")
        .put("threshold", 0.0).put("model_version", "contract")
        .put("labels", org.json.JSONArray(listOf("ham", "spam")))
        .put("supported_languages", org.json.JSONArray(listOf("en", "vi")))
        .put("warning_threshold", 0.2).put("automatic_threshold", 0.8)
        .put("vocabulary", org.json.JSONArray(listOf("abc")))
        .put("idf", org.json.JSONArray(listOf(1.0)))
        .put("weights", org.json.JSONArray(listOf(0.0)))
        .put("intercept", margin).toString()))

    @Test fun uncertainScoresWarnWithoutAutomaticSpam() {
        assertEquals("normal", detector(0.2).assess("Meet tomorrow at nine.").decision)
        assertEquals("review", detector(0.5).assess("Meet tomorrow at nine.").decision)
        assertEquals("review", detector(0.8).assess("Meet tomorrow at nine.").decision)
        assertEquals("spam", detector(0.81).assess("Meet tomorrow at nine.").decision)
    }

    @Test fun vietnameseUsesModelButUnknownScriptsDoNotAutoFilter() {
        assertEquals("normal", detector(-1.0).assess("Mã OTP của bạn là 123456. Không chia sẻ mã.").decision)
        assertEquals("review", detector(0.5).assess("Tin nhắn tiếng Việt có dấu.").decision)
        assertEquals("spam", detector(1.0).assess("Khuyến mãi hôm nay.").decision)
        assertEquals("unassessed", detector(1.0).assess("明天见").decision)
    }
    @Test fun spacedAndDefangedLinksWarnWithoutChangingRawModelLabel() {
        for (text in listOf(
            "I will pay you 1000$ so go here to take it http: //hackermoney/getmoney. org",
            "Collect your reward at hxxps://reward.example/claim",
            "Open reward[.]example to collect the money",
        )) {
            val risk = detector(-1.0).assess(text)
            assertEquals("ham", risk.prediction.label)
            assertEquals("review", risk.decision)
            assertTrue(risk.reasons.contains("obfuscated_link"))
        }
    }

    @Test fun ordinaryPaymentsAndValidLinksDoNotTriggerObfuscatedLinkRule() {
        for (text in listOf(
            "I will pay you $1000 tomorrow. Thank you.",
            "The payment of $1000 was received.",
            "Check the invoice at https://billing.example.org.",
            "Our developer is a hacker and writes security tools.",
        )) {
            val risk = detector(-1.0).assess(text)
            assertEquals("normal", risk.decision)
            assertFalse(risk.reasons.contains("obfuscated_link"))
        }
    }

}
