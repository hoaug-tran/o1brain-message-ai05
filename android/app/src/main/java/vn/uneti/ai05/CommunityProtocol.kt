package vn.uneti.ai05

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun communitySignature(
    secretHex: String,
    device: String,
    path: String,
    timestamp: String,
    nonce: String,
    key: String,
    body: ByteArray,
): String {
    require(secretHex.matches(Regex("[a-f0-9]{64}")))
    val hash =
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    val canonical =
        listOf("v1", device, "POST", path, timestamp, nonce, key, hash).joinToString("\n")
    val secret = secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
    return "v1=" +
        mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal fun communityProof(challenge: String): Int {
    require(challenge.matches(Regex("[a-f0-9]{64}")))
    val digest = MessageDigest.getInstance("SHA-256")
    for (nonce in 0..16777215) {
        val bytes = digest.digest("$challenge:$nonce".toByteArray(Charsets.UTF_8))
        if (bytes[0] == 0.toByte() && bytes[1] == 0.toByte()) return nonce
    }
    error("ENROLLMENT_PROOF_UNAVAILABLE")
}
