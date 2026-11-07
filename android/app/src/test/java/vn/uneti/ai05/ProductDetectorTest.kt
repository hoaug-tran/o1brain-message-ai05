package vn.uneti.ai05

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProductDetectorTest {
    @Test
    fun reportedOtpAndOrderStayHamWithoutReview() {
        val engine = detector()
        for ((text, margin) in listOf(
            "Your verification code is 797132. It expires in 5 minutes. Do not share this code." to -0.5824405763836891,
            "Hi Sarah, your order #d7a6a1d2 has shipped. You can check its status in your account." to -0.47930389703459075,
        )) {
            val result = engine.assess(text)
            assertEquals("ham", result.prediction.label)
            assertEquals(margin, result.prediction.margin, 1e-9)
            assertEquals("normal", result.decision)
            assertTrue(result.reasons.isEmpty())
        }
    }

    @Test
    fun diagnosticRulesWarnWithoutTurningOrdinaryFamilyOrOtpTextsIntoSpam() {
        val engine = detector()
        assertTrue(
            engine
                .assess(
                    "FLASH SALE! Get 50% OFF all items this weekend. Shop now before the offer ends!"
                )
                .reasons
                .contains("promotion_pressure")
        )
        assertTrue(
            engine
                .assess(
                    "Hi, it's Mom. I lost my phone and I'm using a new number. Please send $500 urgently. I'll explain later."
                )
                .reasons
                .contains("familiar_money_request")
        )
        for (text in
            listOf(
                "My mom has a new number. Let's call her tomorrow.",
                "Do not share your verification code with anyone. Your code is 638291.",
                "Your order receipt: a 50% discount has already been applied.",
            )) {
            val result = engine.assess(text)
            assertFalse(result.reasons.contains("credential_request"))
            assertFalse(result.reasons.contains("familiar_money_request"))
            assertNotEquals("spam", result.decision)
        }
    }

    @Test
    fun saveUserDiagnosticDecisionsWithoutTrainingOnThem() {
        val engine = detector()
        val result = org.json.JSONArray()
        for (path in
            listOf(
                "../../tests/fixtures/user_sms_15.json",
                "src/androidTest/assets/ten-sms-parity.json",
            )) {
            val rows = JSONObject(File(path).readText()).getJSONArray("cases")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val risk = engine.assess(row.getString("text"))
                result.put(
                    JSONObject()
                        .put("set", if (path.contains("15")) "user15" else "user10")
                        .put("index", i + 1)
                        .put("expected", row.optString("expected_label", row.optString("expected")))
                        .put("label", risk.prediction.label)
                        .put("margin", risk.prediction.margin)
                        .put("language", risk.language)
                        .put("decision", risk.decision)
                        .put("reasons", org.json.JSONArray(risk.reasons))
                )
            }
        }
        val obfuscated =
            engine.assess("WwZTrucTip<znqbew.hgtbidvak.com]QuatAm8CqqgroupsgooglecomDiU")
        result.put(
            JSONObject()
                .put("set", "obfuscated-image-transcription")
                .put("exact_ocr_verified", false)
                .put("decision", obfuscated.decision)
                .put("margin", obfuscated.prediction.margin)
                .put("reasons", org.json.JSONArray(obfuscated.reasons))
        )
        File("build/product-diagnostics.json")
            .writeText(
                JSONObject()
                    .put(
                        "scope",
                        "User diagnostic examples excluded from training and threshold selection; image text is an approximate transcription",
                    )
                    .put("cases", result)
                    .toString(2)
            )
    }

    @Test
    fun onlyFrozenUciModelCanDetermineBinaryLabel() {
        val engine = detector()
        val model = PortableClassifier(File("src/main/assets/english_char_svm.json").readText())
        val cases =
            JSONObject(File("src/androidTest/assets/ten-sms-parity.json").readText())
                .getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            val text = row.getString("text")
            val prediction = model.classify(text)
            val risk = engine.assess(text)
            assertEquals(row.getString("actual_label"), risk.prediction.label)
            assertEquals(row.getDouble("actual_margin"), risk.prediction.margin, 1e-9)
            assertEquals(prediction.margin, risk.prediction.margin, 0.0)
            if (prediction.margin > 0) assertEquals("spam", risk.decision)
            if (prediction.margin <= 0) assertNotEquals("spam", risk.decision)
            assertTrue(risk.version.startsWith("$PRODUCT_POLICY_VERSION:"))
        }
    }

    @Test
    fun suspiciousRequestsWarnEvenWhenModelPredictsHam() {
        val text =
            "We detected unusual activity on your account. Send us the verification code you just received to prevent suspension."
        val result = detector().assess(text)
        assertEquals("ham", result.prediction.label)
        assertEquals("review", result.decision)
        assertTrue(result.reasons.contains("credential_request"))
    }

    @Test
    fun unsupportedLanguageDoesNotBecomeSafeOrAutomaticSpam() {
        val benign = detector().assess("Mã xác thực của bạn là 482916. Không chia sẻ mã này.")
        assertEquals("und", benign.language)
        assertEquals("unassessed", benign.decision)
        assertEquals(listOf("language_scope"), benign.reasons)
        val hidden = detector().assess("Xem tại https://ex\u200bample.example")
        assertEquals("review", hidden.decision)
        assertTrue(hidden.reasons.contains("hidden_unicode"))
        assertNotEquals("spam", hidden.decision)
    }

    @Test
    fun vietnameseRulesWarnWithoutUsingVietnameseWeights() {
        val request = detector().assess("Vui lòng gửi mã xác thực OTP để xác minh tài khoản.")
        assertEquals("review", request.decision)
        assertTrue(request.reasons.contains("credential_request"))
        val reminder = detector().assess("Không chia sẻ mã xác thực OTP với bất kỳ ai.")
        assertFalse(reminder.reasons.contains("credential_request"))
        assertEquals("unassessed", reminder.decision)
        assertEquals("unassessed", detector().assess("Xin chao, mai gap nhe.").decision)
        val threat =
            detector()
                .assess(
                    "Your account will be disabled. Confirm your password at https://account.example."
                )
        assertTrue(threat.reasons.contains("account_threat_link"))
        assertNotEquals("normal", threat.decision)
    }

    @Test
    fun oldProductAssessmentsMustBeReplaced() {
        for (version in
            listOf(null, "en_old", "policy-1:old", "policy-2:old", "policy-3:expanded", "policy-5:en_old")) assertTrue(
            assessmentNeedsRefresh(version)
        )
        assertFalse(assessmentNeedsRefresh("$PRODUCT_POLICY_VERSION:current"))
        assertFalse(assessmentNeedsRefresh("test-only"))
    }

    private fun detector() =
        ProductDetector(
            PortableClassifier(File("src/main/assets/english_char_svm.json").readText())
        )

    @Test
    fun obfuscatedLinksWarnWithoutClaimingMaliciousDomain() {
        val risk = detector().assess("WwZTrucTip<znqbew.hgtbidvak.com]QuatAm8CqqgroupsgooglecomDiU")
        assertTrue(risk.reasons.contains("obfuscated_link"))
        assertTrue(risk.decision in setOf("review", "spam"))
        val hidden = detector().assess("Visit https://ex\u200bample.example to view details")
        assertTrue(hidden.reasons.contains("hidden_unicode"))
        assertNotEquals("normal", hidden.decision)
    }

    @Test
    fun legitimateCodesAndLinksDoNotTriggerObfuscationRule() {
        val engine = detector()
        for (text in
            listOf(
                "Your verification code is 638291. It will expire in 5 minutes. Do not share this code.",
                "Build checksum: 8c86ceea0aa6a4f8588636894075fa1c4579efc1713837523a798eb7601635da",
                "Your order has shipped. Track it in your account at https://shop.example/orders/48291",
            )) {
            val risk = engine.assess(text)
            assertFalse(risk.reasons.contains("obfuscated_link"))
            assertNotEquals("spam", risk.decision)
        }
        assertEquals(
            "und",
            engine.assess("Mã xác thực của bạn là 482916. Không chia sẻ mã này.").language,
        )
        assertEquals("und", engine.assess("Xin cha\u0300o, mai gặp nhé!").language)
    }

    @Test
    fun samplesNeverRepeatCurrentValue() {
        for (spam in listOf(false, true)) {
            var current = ""
            val generated = mutableSetOf<String>()
            repeat(300) {
                val next = CheckSamples.next(spam, current)
                assertNotEquals(current, next)
                assertTrue(next.all { it.code in 32..126 })
                assertTrue(generated.add(next))
                current = next
            }
        }
    }
}
