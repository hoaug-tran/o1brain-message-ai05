package vn.uneti.ai05

internal fun mmsMessageStatus(pending: Boolean, box: Int, response: Int): String =
    when {
        pending -> "mms_pending"
        box == 4 && response >= 192 -> "failed"
        box == 4 -> "sending"
        box == 2 -> "sent"
        else -> "received"
    }

internal fun smsReceiptIsFinal(type: Int): Boolean = type == 2 || type == 5
