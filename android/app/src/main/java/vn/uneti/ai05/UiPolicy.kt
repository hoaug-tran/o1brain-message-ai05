package vn.uneti.ai05

fun inputStatus(original: String): String? =
    when {
        original.isBlank() ||
            original.codePointCount(0, original.length) > 2000 ||
            original.toByteArray(Charsets.UTF_8).size > 6000 ||
            original.any { it.code < 32 && it !in "\t\n\r" } ||
            original.codePoints().anyMatch { it in 0xd800..0xdfff } -> "invalid_input"
        else -> null
    }

fun statusTextResource(status: String): Int =
    when (status) {
        "loading" -> R.string.loading
        "processing" -> R.string.processing
        "invalid_input" -> R.string.invalid_input
        "unsupported_language" -> R.string.unsupported_language
        "permission_missing" -> R.string.permission_missing
        "cleared" -> R.string.cleared
        "feedback_saved" -> R.string.feedback_saved
        "busy" -> R.string.busy
        "storage_error" -> R.string.storage_error
        else -> R.string.model_unavailable
    }

fun resultTextResource(label: String): Int =
    when (label) {
        "spam" -> R.string.spam_result
        "ham" -> R.string.ham_result
        else -> R.string.not_evaluated
    }

fun validRecipient(address: String): Boolean =
    address.length in 1..100 &&
        Regex("[+]?[0-9 ()\\-]*").matches(address) &&
        address.count { it in '0'..'9' } in 3..20

fun messengerErrorResource(code: String): Int =
    when (code) {
        "model_unavailable" -> R.string.model_unavailable
        "permission_missing" -> R.string.permission_missing
        "invalid_recipient" -> R.string.recipient_help
        "storage_error" -> R.string.storage_error
        "send_failed" -> R.string.send_error_help
        "delete_failed" -> R.string.delete_error_help
        "busy" -> R.string.busy
        else -> R.string.operation_failed
    }
