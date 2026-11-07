package vn.uneti.ai05

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun LoadingSkeleton(modifier: Modifier = Modifier) {
    var pulse by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(LocMotion.PlaceholderPulseMillis)
        pulse = false
    }
    val opacity =
        if (pulse && ValueAnimator.areAnimatorsEnabled()) {
            val transition = rememberInfiniteTransition(label = "loading")
            transition.animateFloat(.45f, .85f, LocMotion.Placeholder, label = "placeholder")
        } else rememberUpdatedState(.65f)
    val label = stringResource(R.string.loading_content)
    Column(
        modifier
            .fillMaxWidth()
            .testTag("loading_skeleton")
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            }
            .graphicsLayer { alpha = opacity.value },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(44.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.fillMaxWidth(.55f)
                            .height(16.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .height(12.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                    Box(
                        Modifier.fillMaxWidth(.7f)
                            .height(12.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                }
            }
        }
    }
}
