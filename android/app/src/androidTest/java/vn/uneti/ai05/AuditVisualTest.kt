package vn.uneti.ai05

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AuditVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun syntheticScreensInBothThemesAndLargeText() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("audit_capture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appearance = context.getSharedPreferences("appearance", 0)
        val original = appearance.getString("theme", "system")
        var screen by mutableIntStateOf(0)
        val now = System.currentTimeMillis()
        fun conversation(key: String, read: Boolean, warning: Boolean) = Conversation(
            key, "+1 202-555-0149", listOf(MessageItem(
                "visual:$key", "+1 202-555-0149",
                if (warning) "Your parcel needs attention. Check the payment request before opening any link."
                else "Mã xác thực của bạn là 123456. Không chia sẻ mã này.",
                now, false, read, "received", modelVersion = "contract-test",
                riskDecision = if (warning) "review" else "normal",
                riskReasons = if (warning) listOf("delivery_payment") else emptyList(),
            )), false, false, false,
        )
        val rows = listOf(conversation("unread", false, false), conversation("read", true, true))
        val controller = MessengerController.get(context)
        val folder = File(context.getExternalFilesDir(null), "audit-visual").apply { mkdirs() }
        try {
            appearance.edit().putString("theme", "light").commit()
            compose.setContent {
                LocTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                        Scaffold { padding ->
                            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                                when (screen) {
                                    0 -> InboxScreen(MessengerState(rows, false, mode = "default", isDefault = true), "", {}, "inbox", {}, true, {}, {}, {}, {}, {})
                                    1 -> ChatScreen(controller, rows[1].address, {}, rows[1], true, true, Modifier.fillMaxSize(), "", {}, {})
                                    else -> Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                        for (shape in listOf(CircleShape, RoundedCornerShape(24.dp))) {
                                            Surface(shape = shape, color = androidx.compose.ui.res.colorResource(R.color.loc_brand), modifier = Modifier.size(108.dp)) {
                                                androidx.compose.foundation.Image(painterResource(R.drawable.ic_loc_mark), "App icon")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (theme in listOf("light", "dark")) {
                appearance.edit().putString("theme", theme).commit()
                for (page in 0..2) {
                    compose.runOnIdle { screen = page }
                    compose.waitForIdle()
                    val image = compose.onRoot().captureToImage().asAndroidBitmap()
                    File(folder, "$theme-$page.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally {
            appearance.edit().putString("theme", original).commit()
        }
    }
}
