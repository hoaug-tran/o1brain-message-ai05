package vn.uneti.ai05

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

private class CommunityHttpError(val code: Int) : RuntimeException("HTTP_$code")

data class CommunityReport(
    val phone: String,
    val category: String,
    val status: String,
    val reporters: Int,
    val retract: Boolean = false,
)

data class CommunityState(
    val busy: Boolean = false,
    val reports: List<CommunityReport> = emptyList(),
    val error: String? = null,
    val lookup: Pair<String, Int>? = null,
)

class CommunityClient private constructor(private val context: Context) {
    private val running = AtomicBoolean(false)
    private val preferences = context.getSharedPreferences("community", Context.MODE_PRIVATE)
    private val cipher = LocalCipher()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))
    private val main = Handler(Looper.getMainLooper())
    private val mutableState =
        MutableStateFlow(
            try {
                readState()
            } catch (_: Exception) {
                CommunityState(error = "community_failed")
            }
        )
    val state: StateFlow<CommunityState> = mutableState

    init {
        if (mutableState.value.reports.any { it.status in setOf("pending", "failed") })
            scheduleSync()
    }

    private fun credentials(): JSONObject? =
        preferences.getString("credentials", null)?.let { JSONObject(cipher.decrypt(it)) }

    private fun readState(): CommunityState {
        val records = preferences.getString("reports", null)?.let { JSONArray(cipher.decrypt(it)) }
        return CommunityState(
            reports =
                (0 until (records?.length() ?: 0)).map { index ->
                    val row = records!!.getJSONObject(index)
                    CommunityReport(
                        row.getString("phone"),
                        row.getString("category"),
                        row.getString("status"),
                        row.getInt("reporters"),
                        row.optBoolean("retract"),
                    )
                }
        )
    }

    private fun persist(records: List<CommunityReport>) {
        val array = JSONArray()
        records.forEach { row ->
            array.put(
                JSONObject()
                    .put("phone", row.phone)
                    .put("category", row.category)
                    .put("status", row.status)
                    .put("reporters", row.reporters)
                    .put("retract", row.retract)
            )
        }
        check(preferences.edit().putString("reports", cipher.encrypt(array.toString())).commit())
        mutableState.value = mutableState.value.copy(reports = records)
    }

    private fun phone(address: String): String {
        require(address.length <= 32 && address.matches(Regex("[+0-9 ()-]+"))) { "INVALID_PHONE" }
        val normalized = PhoneNumberUtils.formatNumberToE164(address, "VN") ?: address
        require(normalized.matches(Regex("\\+[1-9][0-9]{7,14}"))) { "INVALID_PHONE" }
        return normalized
    }

    private fun request(
        path: String,
        payload: JSONObject,
        authenticated: Boolean = true,
    ): JSONObject {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val connection =
            URL(BuildConfig.COMMUNITY_URL + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "O1BrainMessage/${BuildConfig.VERSION_NAME}",
            )
            if (authenticated) {
                val identity = registeredCredentials()
                val device = identity.getString("device_id")
                val timestamp = Instant.now().toString()
                val nonce = UUID.randomUUID().toString()
                val key = UUID.randomUUID().toString()
                connection.setRequestProperty("X-Device-ID", device)
                connection.setRequestProperty("X-Timestamp", timestamp)
                connection.setRequestProperty("X-Nonce", nonce)
                connection.setRequestProperty("X-Idempotency-Key", key)
                connection.setRequestProperty(
                    "X-Signature",
                    communitySignature(
                        identity.getString("secret_hex"),
                        device,
                        path,
                        timestamp,
                        nonce,
                        key,
                        bytes,
                    ),
                )
            }
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status != 200) throw CommunityHttpError(status)
            val response =
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (output.size() <= 8192) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 8193 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            check(response.size <= 8192)
            return JSONObject(response.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun submit(finished: (Boolean) -> Unit = {}, work: () -> Unit) {
        if (!running.compareAndSet(false, true)) {
            main.post { finished(false) }
            return
        }
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try {
            executor.execute {
                var success = false
                try {
                    work()
                    success = true
                } catch (failure: Exception) {
                    mutableState.value =
                        mutableState.value.copy(
                            error =
                                if (failure.message == "INVALID_PHONE") "community_invalid_phone"
                                else "community_failed"
                        )
                } finally {
                    mutableState.value = mutableState.value.copy(busy = false)
                    running.set(false)
                    main.post { finished(success) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            running.set(false)
            mutableState.value = mutableState.value.copy(busy = false, error = "community_failed")
            main.post { finished(false) }
        }
    }

    private fun scheduleSync() {
        context
            .getSystemService(JobScheduler::class.java)
            .schedule(
                JobInfo.Builder(401, ComponentName(context, CommunitySyncService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(30_000)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build()
            )
    }

    fun syncPending(finished: (Boolean) -> Unit) =
        submit(finished) {
            mutableState.value.reports
                .filter { it.status in setOf("pending", "failed") }
                .forEach { send(it) }
        }

    private fun registeredCredentials(): JSONObject {
        var identity = credentials()
        if (identity == null) {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            identity =
                JSONObject()
                    .put("secret_hex", bytes.joinToString("") { "%02x".format(it) })
                    .put(
                        "device_id",
                        MessageDigest.getInstance("SHA-256")
                            .digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                            .take(32),
                    )
            check(
                preferences
                    .edit()
                    .putString("credentials", cipher.encrypt(identity.toString()))
                    .putBoolean("registered", false)
                    .commit()
            )
        }
        if (!preferences.getBoolean("registered", true)) {
            val challenge = request("/v1/challenge", JSONObject(), false)
            check(challenge.getInt("zero_bytes") == 2)
            val token = challenge.getString("challenge")
            require(token.matches(Regex("[a-f0-9]{64}")))
            val proof = communityProof(token)
            val result =
                request(
                    "/v1/enroll",
                    JSONObject(identity.toString()).put("challenge", token).put("nonce", proof),
                    false,
                )
            check(result.getString("device_id") == identity.getString("device_id"))
            check(preferences.edit().putBoolean("registered", true).commit())
        }
        return identity
    }

    fun report(address: String, category: String, complete: () -> Unit = {}) = submit {
        require(category in setOf("spam", "phishing", "advertising"))
        val number = phone(address)
        check(
            mutableState.value.reports.size < 200 ||
                mutableState.value.reports.any { it.phone == number }
        )
        val row = CommunityReport(number, category, "pending", 0)
        persist(mutableState.value.reports.filterNot { it.phone == number } + row)
        send(row)
        main.post(complete)
    }

    fun retry(row: CommunityReport) = submit { send(row) }

    fun retract(row: CommunityReport) = submit {
        val pending = row.copy(status = "pending", retract = true)
        persist(mutableState.value.reports.map { if (it.phone == row.phone) pending else it })
        send(pending)
    }

    private fun send(row: CommunityReport) {
        try {
            val payload = JSONObject().put("phone", row.phone)
            if (!row.retract) payload.put("category", row.category)
            val response =
                request(if (row.retract) "/v1/reports/retract" else "/v1/reports", payload)
            val remaining = mutableState.value.reports.filterNot { it.phone == row.phone }
            persist(
                if (row.retract) remaining
                else remaining + row.copy(status = "sent", reporters = response.getInt("reporters"))
            )
        } catch (failure: Exception) {
            val retryable =
                failure !is CommunityHttpError ||
                    failure.code in setOf(408, 429) ||
                    failure.code >= 500
            persist(
                mutableState.value.reports.map {
                    if (it.phone == row.phone)
                        row.copy(status = if (retryable) "failed" else "rejected")
                    else it
                }
            )
            if (retryable) scheduleSync()
            throw failure
        }
    }

    fun lookup(address: String) = submit {
        val number = phone(address)
        val response = request("/v1/reputation", JSONObject().put("phone", number))
        mutableState.value =
            mutableState.value.copy(lookup = number to response.getInt("reporters"))
    }

    companion object {
        @Volatile private var instance: CommunityClient? = null

        fun get(context: Context): CommunityClient =
            instance
                ?: synchronized(this) {
                    instance ?: CommunityClient(context.applicationContext).also { instance = it }
                }
    }
}
