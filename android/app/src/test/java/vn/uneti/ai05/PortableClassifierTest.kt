package vn.uneti.ai05

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableClassifierTest {
    @Test
    fun parityWithFrozenPythonModel() {
        val model = PortableClassifier(File("src/main/assets/english_char_svm.json").readText())
        val fixtures = JSONObject(javaClass.classLoader!!.getResource("parity.json")!!.readText())
        val cases = fixtures.getJSONArray("fixtures")
        var maximum = 0.0
        for (i in 0 until cases.length()) {
            val fixture = cases.getJSONObject(i)
            val result = model.classify(fixture.getString("text"))
            val difference = kotlin.math.abs(result.margin - fixture.getDouble("margin"))
            maximum = maxOf(maximum, difference)
            assertEquals(fixture.getString("label"), result.label)
            assertEquals(
                fixture.getDouble("margin"),
                result.margin,
                fixtures.getDouble("tolerance"),
            )
            assertEquals(fixtures.getString("model_version"), result.modelVersion)
        }
        println("PARITY cases=${cases.length()} max_abs_margin_difference=$maximum")
    }

    @Test
    fun nonCompetitionLanguageLabelsAreRejected() {
        val json = JSONObject(File("src/main/assets/english_char_svm.json").readText())
        json.put("labels", org.json.JSONArray(listOf("en", "vi")))
        assertThrows(IllegalArgumentException::class.java) { PortableClassifier(json.toString()) }
    }

    @Test
    fun excessiveInputRejected() {
        val model = PortableClassifier(File("src/main/assets/english_char_svm.json").readText())
        try {
            model.classify("x".repeat(2001))
            fail("Expected validation error")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun labelAlwaysFollowsTheSameMarginAndThreshold() {
        assertEquals("ham", LocalPrediction(-1.1352, "test", 0.0).label)
        assertEquals("ham", LocalPrediction(0.0, "test", 0.0).label)
        assertEquals("spam", LocalPrediction(1.1352, "test", 0.0).label)
        assertEquals("ham", LocalPrediction(.5, "test", .5).label)
        try {
            LocalPrediction(Double.NaN, "test", 0.0)
            fail("Non-finite margin accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun exactUserDiagnosticsMatchPythonMargins() {
        val model = PortableClassifier(File("src/main/assets/english_char_svm.json").readText())
        val data = JSONObject(File("src/androidTest/assets/user-diagnostic-parity.json").readText())
        val cases = data.getJSONArray("cases")
        var maximum = 0.0
        for (index in 0 until cases.length()) {
            val fixture = cases.getJSONObject(index)
            val result = model.classify(fixture.getString("text"))
            maximum =
                maxOf(maximum, kotlin.math.abs(result.margin - fixture.getDouble("actual_margin")))
            assertEquals(fixture.getString("actual_label"), result.label)
            assertEquals(fixture.getDouble("actual_margin"), result.margin, 1e-9)
        }
        println("USER_JVM_PARITY cases=${cases.length()} max_abs_margin_difference=$maximum")
    }
}
