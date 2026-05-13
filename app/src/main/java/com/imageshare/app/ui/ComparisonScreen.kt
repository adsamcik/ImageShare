@file:Suppress("MagicNumber", "LongMethod")

package com.imageshare.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.imageshare.app.R
import kotlin.math.roundToInt

private val Role.Companion.Slider: Role
    get() = Role.ValuePicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonScreen(
    before: Uri,
    after: Uri,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val a11y = stringResource(R.string.comparison_screen_a11y)
    var sliderFraction by remember { mutableFloatStateOf(0.5f) }
    var widthPx by remember { mutableStateOf(1) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale == 1f) Offset.Zero else offset + panChange
    }
    val handleDescription = stringResource(R.string.comparison_drag_handle_a11y)
    val splitState = stringResource(R.string.comparison_split_state, (sliderFraction * 100).roundToInt())

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("comparison-screen")
            .semantics { contentDescription = a11y },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.view_comparison)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("comparison-close"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.comparison_close),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .semantics { contentDescription = a11y },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(transformableState),
                    contentAlignment = Alignment.Center,
                ) {
                    val imageModifier = Modifier.fillMaxSize()
                    val beforeRequest = remember(before, context) {
                        ImageRequest.Builder(context)
                            .data(before)
                            .size(1080, 1080)
                            .precision(Precision.INEXACT)
                            .build()
                    }
                    val afterRequest = remember(after, context) {
                        ImageRequest.Builder(context)
                            .data(after)
                            .size(1080, 1080)
                            .precision(Precision.INEXACT)
                            .build()
                    }
                    AsyncImage(
                        model = beforeRequest,
                        contentDescription = stringResource(R.string.before_label),
                        contentScale = ContentScale.Fit,
                        modifier = imageModifier,
                    )
                    AsyncImage(
                        model = afterRequest,
                        contentDescription = stringResource(R.string.after_label),
                        contentScale = ContentScale.Fit,
                        modifier = imageModifier.drawWithContent {
                            clipRect(right = size.width * sliderFraction) {
                                this@drawWithContent.drawContent()
                            }
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset((widthPx * sliderFraction).roundToInt() - 22.dp.roundToPx(), 0)
                        }
                        .width(44.dp)
                        .fillMaxHeight()
                        .pointerInput(widthPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                sliderFraction = ((widthPx * sliderFraction + dragAmount.x) / widthPx).coerceIn(0f, 1f)
                            }
                        }
                        .semantics {
                            contentDescription = handleDescription
                            role = Role.Slider
                            stateDescription = splitState
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
    }
}
