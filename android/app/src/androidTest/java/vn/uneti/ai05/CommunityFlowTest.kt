package vn.uneti.ai05

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CommunityFlowTest {
    @Test
    fun actualHttpsRegistrationReportLookupAndRetraction() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("community_live") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = CommunityClient.get(context)
        val phone = "+12025550149"
        fun idle() {
            val deadline = System.currentTimeMillis() + 30_000
            while (client.state.value.busy && System.currentTimeMillis() < deadline) Thread.sleep(
                25
            )
            assertFalse("Community operation timed out", client.state.value.busy)
            assertNull("Community operation failed", client.state.value.error)
        }
        val finished = CountDownLatch(1)
        client.report(phone, "spam") { finished.countDown() }
        assertTrue("HTTPS report did not finish", finished.await(30, TimeUnit.SECONDS))
        idle()
        var row = client.state.value.reports.single { it.phone == phone }
        assertEquals("sent", row.status)
        assertTrue(row.reporters >= 1)
        try {
            client.lookup(phone)
            idle()
            assertEquals(phone, client.state.value.lookup?.first)
            assertTrue(client.state.value.lookup!!.second >= 1)
        } finally {
            row = client.state.value.reports.single { it.phone == phone }
            client.retract(row)
            idle()
        }
        assertTrue(client.state.value.reports.none { it.phone == phone })
    }
}
