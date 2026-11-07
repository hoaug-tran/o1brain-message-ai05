package vn.uneti.ai05

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceContractTest {
    @Test
    fun apkContainsPinnedExpandedModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = context.assets.list("")!!.toSet()
        assertTrue("english_char_svm.json" in assets)
        assertTrue(assets.none { it.startsWith("product_") })
        val model = JSONObject(context.assets.open("english_char_svm.json").bufferedReader().use { it.readText() })
        assertEquals("bilingual-20261107-v2", model.getString("model_version"))
        assertEquals("[\"en\",\"vi\"]", model.getJSONArray("supported_languages").toString())
    }

    @Test
    fun actualAndroidRuntimeMatchesPythonMargins() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes =
            instrumentation.targetContext.assets.open("english_char_svm.json").use {
                it.readBytes()
            }
        val checksum =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        assertEquals(BuildConfig.MODEL_SHA256, checksum)
        val model = PortableClassifier(bytes.toString(Charsets.UTF_8))
        val fixtures =
            JSONObject(
                instrumentation.context.assets.open("parity.json").bufferedReader().use {
                    it.readText()
                }
            )
        val cases = fixtures.getJSONArray("fixtures")
        var maximum = 0.0
        for (index in 0 until cases.length()) {
            val fixture = cases.getJSONObject(index)
            val result = model.classify(fixture.getString("text"))
            maximum = maxOf(maximum, kotlin.math.abs(result.margin - fixture.getDouble("margin")))
            assertEquals(fixture.getString("label"), result.label)
            assertEquals(
                fixture.getDouble("margin"),
                result.margin,
                fixtures.getDouble("tolerance"),
            )
            assertEquals(fixtures.getString("model_version"), result.modelVersion)
        }
        println("ANDROID_PARITY cases=${cases.length()} max_abs_margin_difference=$maximum")
    }

    @Test
    fun userDiagnosticCasesMatchExactPythonScores() =
        checkDiagnosticParity("user-diagnostic-parity.json")

    @Test fun newTenCasesMatchExactPythonScores() = checkDiagnosticParity("ten-sms-parity.json")

    private fun checkDiagnosticParity(asset: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model =
            PortableClassifier(
                instrumentation.targetContext.assets
                    .open("english_char_svm.json")
                    .bufferedReader()
                    .use { it.readText() }
            )
        val data =
            JSONObject(
                instrumentation.context.assets.open(asset).bufferedReader().use { it.readText() }
            )
        val cases = data.getJSONArray("cases")
        var difference = 0.0
        for (index in 0 until cases.length()) {
            val fixture = cases.getJSONObject(index)
            val result = model.classify(fixture.getString("text"))
            difference =
                maxOf(
                    difference,
                    kotlin.math.abs(result.margin - fixture.getDouble("actual_margin")),
                )
            assertEquals(fixture.getString("actual_label"), result.label)
            assertEquals(fixture.getDouble("actual_margin"), result.margin, 1e-9)
            assertEquals(if (result.margin > result.threshold) "spam" else "ham", result.label)
        }
        println(
            "USER_DIAGNOSTIC_PARITY cases=${cases.length()} max_abs_margin_difference=$difference"
        )
    }

    @Test
    fun syntheticInferenceDoesNotPersistRawMessageOrSender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AppRuntime.get(context)
        val fixture = "Synthetic private-content marker test-privacy-938271"
        val before = context.getSharedPreferences("metadata", 0).all
        assertNotNull(runtime.assessMessage(fixture))
        assertTrue("Quick check must not alter stored metadata", before == context.getSharedPreferences("metadata", 0).all)
        val metadata = context.getSharedPreferences("metadata", 0).all.toString()
        assertFalse(metadata.contains(fixture))
        assertFalse(metadata.contains("938271"))
        assertFalse(metadata.contains("sender"))
        assertEquals(null, inputStatus("English SMS with original URL https://example.invalid/"))
    }

}
