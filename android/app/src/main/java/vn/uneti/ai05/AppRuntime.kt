package vn.uneti.ai05

import android.content.Context
import java.security.MessageDigest

class AppRuntime private constructor(private val context: Context) {
    private var detector: ProductDetector? = null

    @Synchronized
    fun assessMessage(original: String): MessageRisk? {
        if (inputStatus(original) != null) return null
        return try {
            if (detector == null) {
                val bytes = context.assets.open("english_char_svm.json").use { readBounded(it, 16 * 1024 * 1024) }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(hash == BuildConfig.MODEL_SHA256)
                detector = ProductDetector(PortableClassifier(bytes.toString(Charsets.UTF_8)))
            }
            detector!!.assess(original)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        @Volatile private var instance: AppRuntime? = null

        fun get(context: Context): AppRuntime = instance ?: synchronized(this) {
            instance ?: AppRuntime(context.applicationContext).also { instance = it }
        }
    }
}
