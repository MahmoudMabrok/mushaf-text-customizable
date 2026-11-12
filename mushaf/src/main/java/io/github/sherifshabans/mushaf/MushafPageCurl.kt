@file:OptIn(eu.wewox.pagecurl.ExperimentalPageCurlApi::class)

package io.github.sherifshabans.mushaf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.rememberPageCurlConfig
import androidx.compose.animation.core.Animatable
import eu.wewox.pagecurl.page.Edge
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.PageCurlState
import eu.wewox.pagecurl.page.rememberPageCurlState

/*
 * PageTurn.Curl is oleksandrbalan's pagecurl (Apache-2.0):
 * https://github.com/oleksandrbalan/pagecurl
 *
 * The library peels its right edge for a forward turn. Drag and tap zones are
 * swapped so a rightward swipe, and a tap on the left margin, advance the
 * mushaf — the same direction as the RTL pager.
 */

/**
 * Curl the mushaf with [PageCurl]. [state] stays the public pager state:
 * a drag or tap moves it, and [PagerState.scrollToPage] replaces an in-flight turn.
 */
@Composable
internal fun MushafPageCurl(
    state: PagerState,
    colors: MushafColors,
    spread: Boolean,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val preview = LocalMushafTurnPreview.current
    val curlState = rememberPageCurlState(initialCurrent = state.currentPage)
    var lockedPage by remember { mutableIntStateOf(state.currentPage) }
    var lockUntil by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state, curlState) {
        snapshotFlow { state.currentPage }.collect { page ->
            if (page == curlState.current) return@collect
            lockedPage = page
            lockUntil = System.currentTimeMillis() + 700L
            curlState.snapTo(page)
        }
    }
    LaunchedEffect(state, curlState) {
        snapshotFlow { curlState.current }.collect { page ->
            if (System.currentTimeMillis() < lockUntil) {
                if (page != lockedPage) curlState.snapTo(lockedPage)
                return@collect
            }
            if (page != state.currentPage && !state.isScrollInProgress) {
                lockedPage = page
                state.scrollToPage(page)
            }
        }
    }
    if (preview != null) {
        LaunchedEffect(preview.progress, preview.forward) {
            repeat(10) {
                posePageCurl(curlState, preview.progress, preview.forward)
                if (kotlin.math.abs(curlState.progress) > 0.02f) return@LaunchedEffect
                kotlinx.coroutines.delay(40)
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        // PagerState.scrollToPage needs a pager in the composition.
        HorizontalPager(
            state = state,
            modifier = Modifier.height(0.dp),
            beyondViewportPageCount = 0,
            userScrollEnabled = false,
        ) {}
        if (!spread) {
            ActivePageCurl(curlState, colors, pageContent, Modifier.fillMaxSize())
        } else {
            // pagecurl turns one rectangle. The facing page stays a single
            // opaque sheet; only the active page is inside PageCurl.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val page = curlState.current + 1
                val (right, left) = spreadPair(page)
                val curlOnRight = page == right
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(colors.paper)) {
                        if (curlOnRight) {
                            ActivePageCurl(curlState, colors, pageContent)
                        } else {
                            pageContent(right - 1)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().background(colors.paper)) {
                        if (!curlOnRight && left != null) {
                            ActivePageCurl(curlState, colors, pageContent)
                        } else if (left != null) {
                            pageContent(left - 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivePageCurl(
    state: PageCurlState,
    colors: MushafColors,
    pageContent: @Composable (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    PageCurl(
        count = Quran.PAGE_COUNT,
        state = state,
        config = rememberMushafPageCurlConfig(colors),
        modifier = modifier,
    ) { index ->
        PaperPage(colors, index, pageContent)
    }
}

/** Opaque paper first, then the ayahs, so a curl cannot show through the sheet. */
@Composable
private fun PaperPage(
    colors: MushafColors,
    index: Int,
    pageContent: @Composable (Int) -> Unit,
) {
    SideEffect { MushafCurlProbe.composedPages.add(index) }
    Box(Modifier.fillMaxSize().background(colors.paper)) {
        pageContent(index)
    }
}

/** Shadow, opaque-paper back, and drag zones tuned toward the reference curl. */
@Composable
internal fun rememberMushafPageCurlConfig(colors: MushafColors): PageCurlConfig =
    rememberPageCurlConfig(
        backPageColor = colors.paper,
        backPageContentAlpha = 0.1f,
        shadowColor = colors.ink,
        shadowAlpha = 0.55f,
        shadowRadius = 36.dp,
        shadowOffset = androidx.compose.ui.unit.DpOffset(10.dp, 16.dp),
        dragInteraction = PageCurlConfig.StartEndDragInteraction(
            pointerBehavior = PageCurlConfig.DragInteraction.PointerBehavior.PageEdge,
            // Rightward swipe (starts on the left) turns forward.
            forward = PageCurlConfig.StartEndDragInteraction.Config(
                start = Rect(0f, 0f, 0.5f, 1f),
                end = Rect(0.5f, 0f, 1f, 1f),
            ),
            backward = PageCurlConfig.StartEndDragInteraction.Config(
                start = Rect(0.5f, 0f, 1f, 1f),
                end = Rect(0f, 0f, 0.5f, 1f),
            ),
        ),
        tapInteraction = PageCurlConfig.TargetTapInteraction(
            forward = PageCurlConfig.TargetTapInteraction.Config(Rect(0f, 0f, 0.1f, 1f)),
            backward = PageCurlConfig.TargetTapInteraction.Config(Rect(0.9f, 0f, 1f, 1f)),
        ),
        onCustomTap = { size, offset ->
            val x = offset.x / size.width.toFloat().coerceAtLeast(1f)
            x in 0.1f..0.9f
        },
    )

/**
 * Hold a curl without finishing the turn. pagecurl keeps the edge on an
 * internal animatable, so a posed frame sets that edge directly.
 */
internal suspend fun posePageCurl(
    state: PageCurlState,
    progress: Float,
    forward: Boolean,
) {
    val internal = PageCurlState::class.java
        .getMethod("getInternalState\$pagecurl_release")
        .invoke(state) ?: return
    val right = internal.javaClass.getMethod("getRightEdge").invoke(internal) as Edge
    val width = right.top.x
    val height = right.bottom.y
    if (width < 2f || height < 2f) return
    @Suppress("UNCHECKED_CAST")
    val forwardEdge = internal.javaClass.getMethod("getForward").invoke(internal) as Animatable<Edge, *>
    forwardEdge.snapTo(mushafCurlEdge(width, height, progress, forward))
}

/**
 * A diagonal crease. [progress] `0.5` runs from near the top centre to the
 * bottom corner, so about half the page is on the curled side.
 */
internal fun mushafCurlEdge(
    width: Float,
    height: Float,
    progress: Float,
    forward: Boolean,
): Edge {
    val p = progress.coerceIn(0.04f, 0.98f)
    val topX = width * (1f - p * 0.35f)
    val bottomX = (width * (1f - p * 1.65f)).coerceAtLeast(0f)
    val edge = Edge(Offset(topX, 0f), Offset(bottomX, height))
    if (forward) return edge
    return Edge(Offset(width - edge.top.x, 0f), Offset(width - edge.bottom.x, height))
}
