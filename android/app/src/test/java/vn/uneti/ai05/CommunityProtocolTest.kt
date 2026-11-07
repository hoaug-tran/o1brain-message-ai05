package vn.uneti.ai05

import org.junit.Assert.*
import org.junit.Test

class CommunityProtocolTest {
    @Test
    fun signatureMatchesPythonAndBindsEveryField() {
        val body = """{"phone":"+84901234567","category":"spam"}""".toByteArray()
        fun signed(path: String = "/v1/reports", payload: ByteArray = body) =
            communitySignature(
                "ab".repeat(32),
                "device_0123456789",
                path,
                "2026-11-05T00:00:00Z",
                "nonce_0123456789",
                "request_0123456789",
                payload,
            )
        assertEquals(
            "v1=b173ee653a1e5e0991c042f4b882f7f0d13c77edeabfc1ca310f4775581fd39e",
            signed(),
        )
        assertNotEquals(signed(), signed(path = "/v1/reports/retract"))
        assertNotEquals(signed(), signed(payload = body + 32.toByte()))
        assertThrows(IllegalArgumentException::class.java) {
            communitySignature("bad", "device", "/v1/reports", "time", "nonce", "key", body)
        }
    }

    @Test
    fun registrationProofMatchesServerContract() {
        val token = "ab".repeat(32)
        val nonce = communityProof(token)
        val digest =
            java.security.MessageDigest.getInstance("SHA-256").digest("$token:$nonce".toByteArray())
        assertEquals(0, digest[0].toInt())
        assertEquals(0, digest[1].toInt())
        assertTrue(nonce in 0..16777215)
        assertThrows(IllegalArgumentException::class.java) { communityProof("bad") }
    }
}
