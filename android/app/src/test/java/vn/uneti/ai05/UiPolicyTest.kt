package vn.uneti.ai05

import org.junit.Assert.*
import org.junit.Test

class UiPolicyTest {
    @Test
    fun stateTextNeverTreatsUnknownAsHam() {
        assertEquals(R.string.not_evaluated, riskTitle("unknown"))
        assertEquals(R.string.spam_result, resultTextResource("spam"))
        assertEquals(R.string.ham_result, resultTextResource("ham"))
        assertEquals(R.string.not_evaluated, resultTextResource("not_evaluated"))
        assertEquals(R.string.model_unavailable, statusTextResource("model_unavailable"))
        assertEquals(R.string.unsupported_language, statusTextResource("unsupported_language"))
        assertEquals(R.string.permission_missing, statusTextResource("permission_missing"))
        assertEquals(R.string.model_unavailable, statusTextResource("unknown"))
    }

    @Test
    fun originalMessageIsUnchangedAndInvalidInputRejected() {
        val original = "  English\t\tSMS https://example.invalid/OTP123  "
        val before = original.toByteArray(Charsets.UTF_8)
        assertNull(inputStatus(original))
        assertArrayEquals(before, original.toByteArray(Charsets.UTF_8))
        assertNull(inputStatus("Thông báo học phí"))
        assertEquals("invalid_input", inputStatus(" "))
        assertEquals("invalid_input", inputStatus("x".repeat(2001)))
        assertEquals("invalid_input", inputStatus("😀".repeat(1600)))
        assertEquals("invalid_input", inputStatus("hello\u0000"))
    }

    @Test
    fun recipientsArePhoneNumbersAndLanguageGuardDoesNotChangeInput() {
        listOf("123", "+84 912-345-678", "(012) 345 6789").forEach {
            assertTrue(it, validRecipient(it))
        }
        listOf("", "12", "AI05 preview recipient", "123;456", "+12+34", "123\n456", "1".repeat(21))
            .forEach { assertFalse(it, validRecipient(it)) }
        assertNull(inputStatus("Xin chào"))
        assertNull(inputStatus("Xin cha\u0300o"))
        assertEquals(R.string.send_error_help, messengerErrorResource("send_failed"))
        assertEquals(R.string.delete_error_help, messengerErrorResource("delete_failed"))
    }
}
