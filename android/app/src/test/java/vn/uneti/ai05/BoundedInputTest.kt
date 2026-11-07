package vn.uneti.ai05

import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
    @Test
    fun limitsBeforeGrowingBuffer() {
        assertArrayEquals(byteArrayOf(1, 2), readBounded(byteArrayOf(1, 2).inputStream(), 2))
        try {
            readBounded(byteArrayOf(1, 2, 3).inputStream(), 2)
            fail("Oversized attachment accepted")
        } catch (_: IllegalArgumentException) {}
    }
    @Test
    fun modelLimitDoesNotChangeAttachmentLimits() {
        assertArrayEquals(byteArrayOf(1), readBounded(byteArrayOf(1).inputStream(), 16 * 1024 * 1024))
        assertThrows(IllegalArgumentException::class.java) { readBounded(byteArrayOf(1).inputStream(), 17 * 1024 * 1024) }
        assertThrows(IllegalArgumentException::class.java) { readBounded(byteArrayOf(1, 2).inputStream(), 1) }
    }

}
