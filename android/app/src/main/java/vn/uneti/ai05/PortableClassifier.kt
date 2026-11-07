package vn.uneti.ai05

import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt
import org.json.JSONObject

data class LocalPrediction(
    val margin: Double,
    val modelVersion: String,
    val threshold: Double,
    private val negativeLabel: String = "ham",
    private val positiveLabel: String = "spam",
) {
    init {
        require(margin.isFinite() && threshold.isFinite())
    }

    val label: String
        get() = if (margin > threshold) positiveLabel else negativeLabel
}

class PortableClassifier(json: String) {
    val threshold: Double
    val version: String
    private val negativeLabel: String
    private val positiveLabel: String
    private val featureIndex: Map<String, Int>
    private val idf: DoubleArray
    private val weights: DoubleArray
    private val intercept: Double
    private val whitespace =
        Regex(
            "[\\t\\n\\u000B\\f\\r\\u001c-\\u001f \\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]{2,}"
        )

    init {
        val document = JSONObject(json)
        threshold = document.getDouble("threshold")
        version = document.getString("model_version")
        val labels = document.getJSONArray("labels")
        val vocabulary = document.getJSONArray("vocabulary")
        val idfValues = document.getJSONArray("idf")
        val weightValues = document.getJSONArray("weights")
        intercept = document.getDouble("intercept")
        require(document.getInt("format_version") == 1)
        require(document.getString("score_kind") == "decision_margin")
        require(threshold == 0.0 && !document.has("folder_threshold"))
        require(labels.toString() == "[\"ham\",\"spam\"]")
        negativeLabel = labels.getString(0)
        positiveLabel = labels.getString(1)
        require(
            vocabulary.length() in 1..500_000 &&
                idfValues.length() == vocabulary.length() &&
                weightValues.length() == vocabulary.length()
        )
        require(intercept.isFinite())
        val indices = HashMap<String, Int>(vocabulary.length())
        idf = DoubleArray(vocabulary.length())
        weights = DoubleArray(vocabulary.length())
        for (index in 0 until vocabulary.length()) {
            require(indices.put(vocabulary.getString(index), index) == null)
            idf[index] = idfValues.getDouble(index)
            weights[index] = weightValues.getDouble(index)
            require(idf[index].isFinite() && idf[index] > 0 && weights[index].isFinite())
        }
        featureIndex = indices
    }

    fun classify(original: String): LocalPrediction {
        require(
            original.codePointCount(0, original.length) <= 2000 &&
                original.toByteArray(Charsets.UTF_8).size <= 6000
        )
        val normalized = whitespace.replace(original.lowercase(Locale.ROOT), " ")
        val points = normalized.codePoints().toArray()
        val counts = HashMap<Int, Int>()
        for (size in 3..5) {
            for (start in 0..(points.size - size)) {
                val index = featureIndex[String(points, start, size)] ?: continue
                counts[index] = (counts[index] ?: 0) + 1
            }
        }
        var squaredNorm = 0.0
        var dot = 0.0
        for ((index, count) in counts) {
            val value = (1.0 + ln(count.toDouble())) * idf[index]
            squaredNorm += value * value
            dot += value * weights[index]
        }
        val margin = intercept + if (squaredNorm > 0) dot / sqrt(squaredNorm) else 0.0
        require(margin.isFinite())
        return LocalPrediction(margin, version, threshold, negativeLabel, positiveLabel)
    }
}
