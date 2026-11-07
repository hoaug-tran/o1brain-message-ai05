package vn.uneti.ai05

import org.junit.Assert.*
import org.junit.Test

class MessengerPolicyTest {
    private fun conversation(
        key: String,
        read: Boolean = false,
        spam: Boolean = false,
        archived: Boolean = false,
        trusted: Boolean = false,
    ) =
        Conversation(
            key,
            key,
            listOf(
                MessageItem(
                    key,
                    key,
                    "Raw SMS đ https://example.invalid/OTP-741203",
                    0,
                    false,
                    read,
                    "received",
                )
            ),
            archived,
            spam,
            trusted,
        )

    @Test
    fun duplicateSmsCallbackCannotChangeTerminalState() {
        assertTrue(smsReceiptIsFinal(2))
        assertTrue(smsReceiptIsFinal(5))
        assertFalse(smsReceiptIsFinal(4))
        assertFalse(smsReceiptIsFinal(6))
    }

    @Test
    fun foldersSearchAndUnreadPreserveOriginal() {
        val inbox = conversation("a")
        val all =
            listOf(
                inbox,
                conversation("b", read = true, trusted = true),
                conversation("c", spam = true),
                conversation("d", archived = true),
            )
        assertEquals(listOf("a", "b"), visibleConversations(all, "inbox", "").map { it.key })
        assertEquals(listOf("a"), visibleConversations(all, "unread", "").map { it.key })
        assertEquals(listOf("b"), visibleConversations(all, "trusted", "").map { it.key })
        assertEquals(listOf("c"), visibleConversations(all, "spam", "").map { it.key })
        assertEquals(listOf("d"), visibleConversations(all, "archive", "").map { it.key })
        assertSame(inbox, visibleConversations(all, "inbox", "otp-741203").first())
        assertTrue(visibleConversations(all, "inbox", "missing").isEmpty())
    }

    @Test
    fun mmsFailuresDoNotLookSentOrRemainSending() {
        assertEquals("failed", mmsMessageStatus(false, 4, 192))
        assertEquals("sending", mmsMessageStatus(false, 4, 0))
        assertEquals("sent", mmsMessageStatus(false, 2, 128))
        assertEquals("received", mmsMessageStatus(false, 1, 0))
        assertEquals("mms_pending", mmsMessageStatus(true, 1, 0))
    }

    @Test
    fun spamBadgeCountsUnreadMessagesRatherThanFolders() {
        val readSpam = conversation("read", read = true, spam = true)
        val unreadHam = conversation("ham")
        val unreadSpam = conversation("spam", spam = true)
        assertEquals(0, MessengerState(conversations = listOf(readSpam, unreadHam)).unreadSpamCount)
        assertEquals(
            1,
            MessengerState(conversations = listOf(readSpam, unreadHam, unreadSpam)).unreadSpamCount,
        )
        assertEquals(0, MessengerState().unreadSpamCount)
    }
}
