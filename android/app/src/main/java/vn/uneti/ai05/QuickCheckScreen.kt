package vn.uneti.ai05

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickCheckScreen() {
    val context = LocalContext.current
    var editor by remember { mutableStateOf(TextFieldValue()) }
    val content = editor.text
    var submitted by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<MessageRisk?>(null) }
    var elapsed by remember { mutableStateOf(0.0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf(false) }
    fun useSample(spam: Boolean) {
        val sample = CheckSamples.next(spam, content)
        editor = TextFieldValue(sample, TextRange(sample.length))
        submitted = null
        result = null
        error = null
        details = false
    }
    LaunchedEffect(submitted, request) {
        result = null
        error = null
        details = false
        val text =
            submitted
                ?: run {
                    busy = false
                    return@LaunchedEffect
                }
        error = inputStatus(text)
        if (error != null) return@LaunchedEffect
        busy = true
        val generation = request
        try {
            val (prediction, time) =
                withContext(Dispatchers.Default) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val value = AppRuntime.get(context).assessMessage(text)
                    value to (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                }
            if (request == generation && submitted == text) {
                result = prediction
                elapsed = time
                if (prediction == null) error = "model_unavailable"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (request == generation && submitted == text) error = "model_unavailable"
        } finally {
            if (request == generation && submitted == text) busy = false
        }
    }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.quick_check_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.quick_check_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = editor,
            onValueChange = {
                if (editor.text != it.text) {
                    submitted = null
                    result = null
                    error = null
                }
                editor = it
            },
            label = { Text(stringResource(R.string.manual_label)) },
            minLines = 4,
            maxLines = 8,
            isError = error != null,
            trailingIcon = {
                if (editor.text.isNotEmpty())
                    IconButton(
                        onClick = {
                            editor = TextFieldValue()
                            submitted = null
                            result = null
                            error = null
                        }
                    ) {
                        Icon(
                            androidx.compose.material.icons.Icons.Default.Close,
                            stringResource(R.string.clear_message_input),
                        )
                    }
            },
            modifier = Modifier.fillMaxWidth().testTag("check_input"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(shape = MaterialTheme.shapes.small, onClick = { useSample(false) }) {
                Text(stringResource(R.string.sample_meeting))
            }
            TextButton(shape = MaterialTheme.shapes.small, onClick = { useSample(true) }) {
                Text(stringResource(R.string.sample_prize))
            }
        }
        Button(
            shape = MaterialTheme.shapes.small,
            onClick = {
                submitted = content
                request++
            },
            enabled = content.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().testTag("check_submit"),
        ) {
            Text(stringResource(if (busy) R.string.processing else R.string.analyze))
        }
        if (busy) LoadingSkeleton()
        error?.let {
            Text(
                stringResource(statusTextResource(it)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("check_error"),
            )
        }
        result?.let { risk ->
            val prediction = risk.prediction
            Surface(
                color =
                    if (risk.decision in setOf("spam", "review"))
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("check_result"),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(riskTitle(risk.decision)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    risk.reasons.forEach {
                        Text(
                            stringResource(riskReason(it)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TextButton(
                        shape = MaterialTheme.shapes.small,
                        onClick = { details = !details },
                    ) {
                        Text(stringResource(R.string.result_details))
                    }
                    if (details) {
                        Text(
                            stringResource(
                                R.string.score_format,
                                prediction.margin,
                                prediction.label,
                            )
                        )
                        Text(
                            stringResource(R.string.score_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(stringResource(R.string.folder_threshold, risk.folderThreshold))
                        Text(
                            stringResource(
                                R.string.language_route,
                                if (risk.language == "en") "Tiếng Anh"
                                else stringResource(R.string.language_not_assessed),
                            )
                        )
                        Text(
                            stringResource(R.string.threshold_frozen, prediction.threshold),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.latency_format, elapsed),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.model_format, prediction.modelVersion),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(stringResource(R.string.caution), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.quick_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ModelEvidence() {
    val context = LocalContext.current
    val evidence = remember {
        runCatching {
                val bytes = context.assets.open("model_evidence.json").use { it.readBytes() }
                val hash =
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    }
                check(hash == BuildConfig.EVIDENCE_SHA256)
                JSONObject(bytes.toString(Charsets.UTF_8)).also {
                    check(it.getString("portable_sha256") == BuildConfig.MODEL_SHA256)
                }
            }
            .getOrNull()
    }
    if (evidence == null) {
        Text(stringResource(R.string.evidence_unavailable))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.model_evaluation),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(
                R.string.validation_scope,
                evidence.getJSONObject("support").getInt("ham"),
                evidence.getJSONObject("support").getInt("spam"),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        listOf("Spam F1" to "spam_f1", "Spam Precision" to "spam_precision", "Ham FPR" to "ham_fpr")
            .forEach { (label, key) ->
                Text(
                    "$label: " +
                        String.format(
                            java.util.Locale.forLanguageTag("vi-VN"),
                            "%.2f%%",
                            100 * evidence.getJSONObject("metrics").getDouble(key),
                        )
                )
            }
        val c = evidence.getJSONObject("confusion")
        Text(
            "TN ${c.getInt("tn")} / FP ${c.getInt("fp")}\nFN ${c.getInt("fn")} / TP ${c.getInt("tp")}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            evidence.getString("dataset_id") + "\n" + evidence.getString("split_id"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.model_format, evidence.getString("model_version")),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.threshold_frozen, evidence.getDouble("threshold")),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
