package vn.uneti.ai05

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun readBounded(input: InputStream, maximum: Int): ByteArray {
    require(maximum in 1..16 * 1024 * 1024)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        require(count <= maximum - output.size()) { "attachment_too_large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
