package io.github.sherifshabans.mushaf

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/**
 * Curl, flip, fade, and cover. Slide stays on [HorizontalPager].
 *
 * Drag distance maps 1:1 onto the sheet. Releasing eases with fast-out / slow-in
 * to the settled page or back. Only the draw phase reads the drag, so the page
 * composables are not rebuilt on every frame.
 *
 * On a spread, curl and flip turn the fore-edge leaf around the gutter and the
 * facing page crossfades in over the second half of the turn. Fade and cover
 * move the whole opening. A reciter jump replaces the in-flight target.
 */
@Composable
internal fun LeafPager(
    state: PagerState,
    mode: PageTurn,
    spread: Boolean,
    colors: MushafColors,
    decoration: MushafDecoration,
    pagePadding: PaddingValues,
    layoutDirection: LayoutDirection,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val preview = LocalMushafTurnPreview.current
    val scope = rememberCoroutineScope()
    val controller = remember(state) { TurnController(state.currentPage) }
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current

    // The hidden pager is what gives PagerState its layout pass. Without it
    // scrollToPage suspends forever. It draws nothing.
    HorizontalPager(
        state = state,
        modifier = Modifier.height(0.dp),
        beyondViewportPageCount = 0,
        userScrollEnabled = false,
    ) {}

    SideEffect {
        controller.currentPage = { state.currentPage }
        controller.pagerScroll = { page -> state.scrollToPage(page) }
    }

    DisposableEffect(controller) {
        onDispose { controller.job?.cancel() }
    }

    LaunchedEffect(state, controller) {
        snapshotFlow { state.currentPage }.collect { page ->
            if (preview != null) return@collect
            if (page == controller.settled || page == controller.landing) return@collect
            controller.beginProgrammatic(page, mode, scope)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .onSizeChanged {
                controller.viewWidth = it.width.toFloat().coerceAtLeast(1f)
                controller.viewHeight = it.height.toFloat().coerceAtLeast(1f)
            }
            .curlGestures(
                touchSlop = viewConfig.touchSlop,
                longPressTimeoutMs = viewConfig.longPressTimeoutMillis,
                marginTurn = { pos ->
                    val rect = marginContentRange(
                        decoration = decoration,
                        pagePadding = pagePadding,
                        layoutDirection = layoutDirection,
                        density = density,
                        widthPx = controller.viewWidth,
                        pageNumber = controller.settled + 1,
                    )
                    rect != null && pos.x !in rect
                },
                onDragStart = { forward, position ->
                    if (preview != null) return@curlGestures
                    controller.beginGesture(forward, position, spread, mode)
                },
                onDrag = { dx, dy ->
                    if (!controller.tracking) return@curlGestures
                    controller.motion.dragX += dx
                    controller.motion.dragY += dy
                    if (controller.leafForward && controller.motion.dragX < 0f) {
                        controller.motion.dragX = 0f
                    }
                    if (!controller.leafForward && controller.motion.dragX > 0f) {
                        controller.motion.dragX = 0f
                    }
                },
                onDragEnd = { velocityX ->
                    if (!controller.tracking) return@curlGestures
                    controller.tracking = false
                    if (controller.landing >= 0) return@curlGestures
                    controller.release(velocityX, mode, scope)
                },
                onTapMargin = { forward ->
                    if (preview != null) return@curlGestures
                    controller.beginTap(forward, spread, mode, scope)
                },
            )
    ) {
        val posed = preview
        val showTurn = posed != null || controller.active
        if (!showTurn) {
            Settled(controller.settled, spread, pageContent)
        } else {
            val from = if (posed != null) state.currentPage else controller.fromIndex
            val to = if (posed != null) {
                previewTarget(state.currentPage, posed.forward, spread)
            } else {
                controller.toIndex
            }
            val leafWise = if (posed != null) {
                spread && (mode == PageTurn.Curl || mode == PageTurn.Flip)
            } else {
                controller.leafWise
            }
            val edge = if (posed != null) grabIsEdge(posed.grabY) else controller.motion.edge
            val fromTop = if (posed != null) posed.grabY < 0.5f else controller.motion.fromTop
            val leafForward = when {
                posed != null && leafWise -> planSpreadTurn(from, to).leafForward
                posed != null -> posed.forward
                else -> controller.leafForward
            }
            if (leafWise) {
                AnimatedSpread(
                    plan = planSpreadTurn(from, to),
                    mode = mode,
                    edge = edge,
                    fromTop = fromTop,
                    posed = posed?.progress,
                    motion = controller.motion,
                    colors = colors,
                    pageContent = pageContent,
                )
            } else if (spread) {
                TurnStack(
                    under = { Settled(to, spread = true, pageContent) },
                    over = { Settled(from, spread = true, pageContent) },
                    leafForward = leafForward,
                    mode = mode,
                    edge = edge,
                    fromTop = fromTop,
                    posed = posed?.progress,
                    motion = controller.motion,
                    colors = colors,
                )
            } else {
                TurnStack(
                    under = { TurnPage(to, pageContent) },
                    over = { TurnPage(from, pageContent) },
                    leafForward = leafForward,
                    mode = mode,
                    edge = edge,
                    fromTop = fromTop,
                    posed = posed?.progress,
                    motion = controller.motion,
                    colors = colors,
                )
            }
        }
    }
}

private fun previewTarget(index: Int, forward: Boolean, spread: Boolean): Int {
    if (spread) return spreadLeafTarget(index, forward)
    return (index + if (forward) 1 else -1).coerceIn(0, Quran.PAGE_COUNT - 1)
}

@Composable
private fun Settled(
    index: Int,
    spread: Boolean,
    pageContent: @Composable (Int) -> Unit,
) {
    if (!spread) {
        TurnPage(index, pageContent)
        return
    }
    val (right, left) = spreadPair(index + 1)
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { TurnPage(right - 1, pageContent) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (left != null) TurnPage(left - 1, pageContent)
        }
    }
}

@Composable
private fun AnimatedSpread(
    plan: SpreadTurnPlan,
    mode: PageTurn,
    edge: Boolean,
    fromTop: Boolean,
    posed: Float?,
    motion: TurnMotion,
    colors: MushafColors,
    pageContent: @Composable (Int) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        // RTL: the first child is the right-hand page.
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (plan.turnLeft) {
                FacingHalf(plan.staticPage, plan.incomingStaticPage, posed, motion, mode, edge, plan.leafForward, pageContent)
            } else {
                LeafHalf(plan, mode, edge, fromTop, posed, motion, colors, pageContent)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (plan.turnLeft) {
                LeafHalf(plan, mode, edge, fromTop, posed, motion, colors, pageContent)
            } else {
                FacingHalf(plan.staticPage, plan.incomingStaticPage, posed, motion, mode, edge, plan.leafForward, pageContent)
            }
        }
    }
}

@Composable
private fun LeafHalf(
    plan: SpreadTurnPlan,
    mode: PageTurn,
    edge: Boolean,
    fromTop: Boolean,
    posed: Float?,
    motion: TurnMotion,
    colors: MushafColors,
    pageContent: @Composable (Int) -> Unit,
) {
    TurnStack(
        under = { PageOrPaper(plan.underPage, colors, pageContent) },
        over = { PageOrPaper(plan.overPage, colors, pageContent) },
        leafForward = plan.leafForward,
        mode = mode,
        edge = edge,
        fromTop = fromTop,
        posed = posed,
        motion = motion,
        colors = colors,
    )
}

@Composable
private fun FacingHalf(
    staticPage: Int?,
    incomingPage: Int?,
    posed: Float?,
    motion: TurnMotion,
    mode: PageTurn,
    edge: Boolean,
    leafForward: Boolean,
    pageContent: @Composable (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (staticPage != null) TurnPage(staticPage - 1, pageContent)
        if (incomingPage != null && incomingPage != staticPage) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = poseFraction(posed, motion, leafForward, edge, mode, size.width)
                        alpha = ((p - 0.42f) / 0.58f).coerceIn(0f, 1f)
                    }
            ) {
                TurnPage(incomingPage - 1, pageContent)
            }
        }
    }
}

@Composable
private fun PageOrPaper(
    page: Int?,
    colors: MushafColors,
    pageContent: @Composable (Int) -> Unit,
) {
    if (page != null && page in 1..Quran.PAGE_COUNT) {
        TurnPage(page - 1, pageContent)
    } else {
        Box(Modifier.fillMaxSize().background(colors.paper))
    }
}

@Composable
private fun TurnPage(
    index: Int,
    pageContent: @Composable (Int) -> Unit,
) {
    SideEffect { MushafCurlProbe.composedPages.add(index) }
    pageContent(index)
}

@Composable
private fun TurnStack(
    under: @Composable () -> Unit,
    over: @Composable () -> Unit,
    leafForward: Boolean,
    mode: PageTurn,
    edge: Boolean,
    fromTop: Boolean,
    posed: Float?,
    motion: TurnMotion,
    colors: MushafColors,
) {
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.fillMaxSize().background(colors.paper)) { under() }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    paintUnderlay(mode, edge, fromTop, leafForward, posed, motion, colors)
                }
        )
        Box(
            Modifier
                .fillMaxSize()
                .leafTransform(mode, leafForward, edge, posed, motion)
                .drawWithContent {
                    paintOver(mode, edge, fromTop, leafForward, posed, motion, colors.paper)
                }
        ) {
            over()
        }
    }
}

private fun Modifier.leafTransform(
    mode: PageTurn,
    leafForward: Boolean,
    edge: Boolean,
    posed: Float?,
    motion: TurnMotion,
): Modifier = when (mode) {
    PageTurn.Flip -> graphicsLayer {
        val p = poseFraction(posed, motion, leafForward, edge, mode, size.width)
        rotationY = flipDegrees(p, leafForward)
        transformOrigin = TransformOrigin(if (leafForward) 1f else 0f, 0.5f)
        cameraDistance = 32.dp.toPx()
        alpha = if (p >= 0.985f) 0f else 1f
    }
    PageTurn.Fade -> graphicsLayer {
        val p = poseFraction(posed, motion, leafForward, edge, mode, size.width)
        alpha = 1f - p
        val scale = 1f - 0.045f * p
        scaleX = scale
        scaleY = scale
        compositingStrategy = CompositingStrategy.Offscreen
    }
    PageTurn.Cover -> graphicsLayer {
        val p = poseFraction(posed, motion, leafForward, edge, mode, size.width)
        translationX = if (leafForward) (p - 1f) * size.width else (1f - p) * size.width
    }
    else -> this
}

private fun DrawScope.paintUnderlay(
    mode: PageTurn,
    edge: Boolean,
    fromTop: Boolean,
    leafForward: Boolean,
    posed: Float?,
    motion: TurnMotion,
    colors: MushafColors,
) {
    val p = poseFraction(posed, motion, leafForward, edge, mode, size.width)
    when (mode) {
        PageTurn.Flip -> {
            val alpha = 0.45f * sin(Math.PI * p).toFloat()
            if (alpha > 0.01f) {
                drawRect(
                    brush = if (leafForward) {
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = alpha)),
                            startX = size.width * 0.2f,
                            endX = size.width,
                        )
                    } else {
                        Brush.horizontalGradient(
                            colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
                            startX = 0f,
                            endX = size.width * 0.8f,
                        )
                    }
                )
            }
        }
        PageTurn.Cover -> {
            if (p > 0.01f) drawRect(Color.Black.copy(alpha = 0.30f * p))
        }
        else -> Unit
    }
}

private fun ContentDrawScope.paintOver(
    mode: PageTurn,
    edge: Boolean,
    fromTop: Boolean,
    leafForward: Boolean,
    posed: Float?,
    motion: TurnMotion,
    paper: Color,
) {
    val p = poseFraction(posed, motion, leafForward, edge, mode, size.width)
    when (mode) {
        PageTurn.Curl -> {
            if (p >= 0.995f) return
            if (edge) {
                val curl = edgeCurl(size.width, p, leafForward, 22.dp.toPx(), 36.dp.toPx())
                if (curl.settled && p <= 0f) {
                    drawRect(paper)
                    drawContent()
                    return
                }
                clipPath(edgeClip(curl, leafForward)) {
                    drawRect(paper)
                    this@paintOver.drawContent()
                }
                drawEdgeCurl(p, leafForward, paper)
            } else {
                val dragX = dragX(posed, motion, leafForward, edge, mode)
                val dragY = if (posed != null) 0f else motion.dragY
                val apex = cornerApex(size.width, size.height, dragX, dragY, leafForward, fromTop)
                val fold = cornerFold(size.width, size.height, apex, leafForward, fromTop)
                if (!fold.curled) {
                    drawRect(paper)
                    drawContent()
                } else if (fold.flat.size >= 3) {
                    clipPath(polygonPath(fold.flat)) {
                        drawRect(paper)
                        this@paintOver.drawContent()
                    }
                    drawCornerCurl(fold, paper)
                }
            }
        }
        PageTurn.Flip -> {
            drawRect(paper)
            drawContent()
            drawRect(Color.Black.copy(alpha = 0.45f * p))
        }
        PageTurn.Cover -> {
            drawRect(paper)
            drawContent()
            val band = 26.dp.toPx()
            val alpha = 0.38f * sin(Math.PI * p).toFloat()
            if (alpha > 0.01f) {
                if (leafForward) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = alpha)),
                            startX = size.width - band,
                            endX = size.width,
                        ),
                        topLeft = Offset(size.width - band, 0f),
                        size = Size(band, size.height),
                    )
                } else {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
                            startX = 0f,
                            endX = band,
                        ),
                        size = Size(band, size.height),
                    )
                }
            }
        }
        else -> {
            drawRect(paper)
            drawContent()
        }
    }
}

private fun DrawScope.drawEdgeCurl(progress: Float, forward: Boolean, paper: Color) {
    val curl = edgeCurl(size.width, progress, forward, 22.dp.toPx(), 40.dp.toPx())
    if (curl.settled || curl.back <= 0.5f) return
    val curve = creaseCurve(curl.boundary, curl.bow, forward, size.height)
    val shade = 0.32f * sin(Math.PI * progress.coerceIn(0f, 1f)).toFloat()
    drawPath(
        curve,
        color = Color.Black.copy(alpha = shade),
        style = Stroke(width = curl.back + curl.shadow, cap = StrokeCap.Butt),
    )
    val start = if (forward) curl.boundary - curl.back else curl.boundary
    val end = if (forward) curl.boundary else curl.boundary + curl.back
    drawPath(
        curve,
        brush = Brush.horizontalGradient(
            0f to curlTone(paper, if (forward) 0.08f else 0.92f),
            0.42f to curlTone(paper, 0.4f),
            1f to curlTone(paper, if (forward) 0.92f else 0.08f),
            startX = start,
            endX = end,
        ),
        style = Stroke(width = curl.back, cap = StrokeCap.Butt),
    )
    drawPath(
        curve,
        color = Color.White.copy(alpha = 0.28f * sin(Math.PI * progress).toFloat()),
        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Butt),
    )
}

private fun DrawScope.drawCornerCurl(fold: CornerFold, paper: Color) {
    if (fold.back.size < 3) return
    val span = hypot2(fold.creaseA, fold.creaseB).coerceAtLeast(1f)
    val shadowW = (28.dp.toPx()).coerceAtMost(span)
    val n = fold.revealedNormal
    val a2 = Offset(fold.creaseA.x + n.x * shadowW, fold.creaseA.y + n.y * shadowW)
    val b2 = Offset(fold.creaseB.x + n.x * shadowW, fold.creaseB.y + n.y * shadowW)
    val shadow = Path().apply {
        moveTo(fold.creaseA.x, fold.creaseA.y)
        lineTo(fold.creaseB.x, fold.creaseB.y)
        lineTo(b2.x, b2.y)
        lineTo(a2.x, a2.y)
        close()
    }
    val midCrease = Offset(
        (fold.creaseA.x + fold.creaseB.x) * 0.5f,
        (fold.creaseA.y + fold.creaseB.y) * 0.5f,
    )
    val midOuter = Offset((a2.x + b2.x) * 0.5f, (a2.y + b2.y) * 0.5f)
    drawPath(
        shadow,
        brush = Brush.linearGradient(
            colors = listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent),
            start = midCrease,
            end = midOuter,
        ),
    )
    drawPath(
        polygonPath(fold.back),
        brush = Brush.linearGradient(
            colors = listOf(curlTone(paper, 0.9f), curlTone(paper, 0.38f), curlTone(paper, 0.1f)),
            start = midCrease,
            end = fold.apex,
        ),
    )
    drawLine(
        color = Color.Black.copy(alpha = 0.4f),
        start = fold.creaseA,
        end = fold.creaseB,
        strokeWidth = 2f,
    )
    val lipA = Offset(fold.creaseA.x + n.x * 2f, fold.creaseA.y + n.y * 2f)
    val lipB = Offset(fold.creaseB.x + n.x * 2f, fold.creaseB.y + n.y * 2f)
    drawLine(
        color = Color.White.copy(alpha = 0.3f),
        start = lipA,
        end = lipB,
        strokeWidth = 1.5f,
    )
}

private fun DrawScope.edgeClip(curl: EdgeCurl, forward: Boolean): Path {
    val path = Path()
    val b = curl.boundary
    val bow = curl.bow
    val h = size.height
    val w = size.width
    if (forward) {
        path.moveTo(b, 0f)
        path.quadraticBezierTo(b + bow, h * 0.5f, b, h)
        path.lineTo(w, h)
        path.lineTo(w, 0f)
    } else {
        path.moveTo(b, 0f)
        path.quadraticBezierTo(b - bow, h * 0.5f, b, h)
        path.lineTo(0f, h)
        path.lineTo(0f, 0f)
    }
    path.close()
    return path
}

private fun creaseCurve(boundary: Float, bow: Float, forward: Boolean, height: Float): Path {
    val path = Path()
    path.moveTo(boundary, 0f)
    val controlX = if (forward) boundary + bow else boundary - bow
    path.quadraticBezierTo(controlX, height * 0.5f, boundary, height)
    return path
}

private fun polygonPath(points: List<Offset>): Path {
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    path.close()
    return path
}

private fun hypot2(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.hypot(dx, dy)
}

private fun curlTone(paper: Color, shade: Float): Color {
    val lightPaper = paper.luminance() > 0.45f
    val highlight = if (lightPaper) lerp(paper, Color.White, 0.28f) else lerp(paper, Color.White, 0.14f)
    val deep = if (lightPaper) lerp(paper, Color.Black, 0.20f) else lerp(paper, Color.Black, 0.32f)
    return lerp(highlight, deep, shade.coerceIn(0f, 1f))
}

private fun poseFraction(
    posed: Float?,
    motion: TurnMotion,
    leafForward: Boolean,
    edge: Boolean,
    mode: PageTurn,
    leafWidth: Float,
): Float {
    if (posed != null) return posed.coerceIn(0f, 1f)
    val travel = if (leafForward) motion.dragX.coerceAtLeast(0f) else (-motion.dragX).coerceAtLeast(0f)
    val full = if (mode == PageTurn.Curl && !edge) leafWidth * CORNER_TRAVEL else leafWidth
    return (travel / full.coerceAtLeast(1f)).coerceIn(0f, 1f)
}

private fun DrawScope.dragX(
    posed: Float?,
    motion: TurnMotion,
    leafForward: Boolean,
    edge: Boolean,
    mode: PageTurn,
): Float {
    if (posed == null) return motion.dragX
    val full = if (mode == PageTurn.Curl && !edge) size.width * CORNER_TRAVEL else size.width
    return (if (leafForward) 1f else -1f) * posed.coerceIn(0f, 1f) * full
}

private fun marginContentRange(
    decoration: MushafDecoration,
    pagePadding: PaddingValues,
    layoutDirection: LayoutDirection,
    density: androidx.compose.ui.unit.Density,
    widthPx: Float,
    pageNumber: Int,
): ClosedFloatingPointRange<Float>? {
    if (!decoration.pageFrame || widthPx <= 1f) return null
    val leftPad = with(density) { pagePadding.calculateLeftPadding(layoutDirection).toPx() }
    val rightPad = with(density) { pagePadding.calculateRightPadding(layoutDirection).toPx() }
    val g = decoration.frame.geometry(
        PageFrameRequest(
            widthPx = (widthPx - leftPad - rightPad).coerceAtLeast(1f),
            heightPx = 1f,
            pageNumber = pageNumber,
            density = density.density,
            outerMargin = decoration.frameOuterMargin,
            innerMargin = decoration.frameInnerMargin,
            topMargin = decoration.frameTopMargin,
            bottomMargin = decoration.frameBottomMargin,
            textGap = decoration.textGap,
            divisionMarks = decoration.divisionMarks,
        )
    )
    return (leftPad + g.contentLeft)..(leftPad + g.contentLeft + g.contentWidth)
}

private class TurnMotion {
    var dragX by mutableFloatStateOf(0f)
    var dragY by mutableFloatStateOf(0f)
    var edge: Boolean = true
    var fromTop: Boolean = false
    var grabY: Float = 0.5f
}

private class TurnController(initialPage: Int) {
    var settled by mutableIntStateOf(initialPage)
    var active by mutableStateOf(false)
    var fromIndex by mutableIntStateOf(initialPage)
    var toIndex by mutableIntStateOf(initialPage)
    var leafWise by mutableStateOf(false)
    var leafForward: Boolean = true
    var tracking: Boolean = false
    var landing: Int = -1
    var viewWidth by mutableFloatStateOf(1f)
    var viewHeight by mutableFloatStateOf(1f)
    val motion = TurnMotion()
    var job: Job? = null

    fun beginGesture(
        forward: Boolean,
        position: Offset,
        spread: Boolean,
        mode: PageTurn,
    ) {
        job?.cancel()
        val target = if (spread) {
            spreadLeafTarget(settled, forward)
        } else {
            (settled + if (forward) 1 else -1).coerceIn(0, Quran.PAGE_COUNT - 1)
        }
        if (target == settled) {
            tracking = false
            return
        }
        tracking = true
        landing = -1
        leafWise = spread && (mode == PageTurn.Curl || mode == PageTurn.Flip)
        fromIndex = settled
        toIndex = target
        val grabY = (position.y / viewHeight.coerceAtLeast(1f)).coerceIn(0f, 1f)
        motion.grabY = grabY
        motion.edge = grabIsEdge(grabY)
        motion.fromTop = grabY < 0.5f
        leafForward = if (leafWise) planSpreadTurn(settled, target).leafForward else forward
        motion.dragX = 0f
        motion.dragY = 0f
        active = true
    }

    fun beginTap(
        forward: Boolean,
        spread: Boolean,
        mode: PageTurn,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        job?.cancel()
        val target = if (spread) {
            spreadLeafTarget(settled, forward)
        } else {
            (settled + if (forward) 1 else -1).coerceIn(0, Quran.PAGE_COUNT - 1)
        }
        if (target == settled) return
        tracking = false
        landing = -1
        leafWise = spread && (mode == PageTurn.Curl || mode == PageTurn.Flip)
        fromIndex = settled
        toIndex = target
        motion.grabY = 0.92f
        motion.edge = false
        motion.fromTop = false
        leafForward = if (leafWise) planSpreadTurn(settled, target).leafForward else forward
        motion.dragX = 0f
        motion.dragY = 0f
        active = true
        release(velocityX = 0f, mode = mode, scope = scope, forceCommit = true)
    }

    fun beginProgrammatic(
        page: Int,
        mode: PageTurn,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        landing = page
        fromIndex = settled
        toIndex = page
        leafWise = false
        motion.edge = false
        motion.fromTop = false
        motion.grabY = 0.92f
        leafForward = page > settled
        tracking = false
        active = true
        animateRelease(commit = true, mode = mode, scope = scope) {
            settle(page)
        }
    }

    fun release(
        velocityX: Float,
        mode: PageTurn,
        scope: kotlinx.coroutines.CoroutineScope,
        forceCommit: Boolean = false,
    ) {
        val leafW = leafWidth()
        val travel = if (leafForward) motion.dragX.coerceAtLeast(0f) else (-motion.dragX).coerceAtLeast(0f)
        val finger = travel / leafW.coerceAtLeast(1f)
        val flickForward = if (leafForward) velocityX > 1600f else velocityX < -1600f
        val flickBack = if (leafForward) velocityX < -1600f else velocityX > 1600f
        val commit = when {
            forceCommit -> true
            flickForward -> true
            flickBack && finger < 0.55f -> false
            finger >= TURN_COMMIT -> true
            else -> false
        }
        if (commit) landing = toIndex
        animateRelease(commit, mode, scope) {
            if (commit) settle(toIndex) else retreat()
        }
    }

    private fun animateRelease(
        commit: Boolean,
        mode: PageTurn,
        scope: kotlinx.coroutines.CoroutineScope,
        onEnd: suspend () -> Unit,
    ) {
        job?.cancel()
        val leafW = leafWidth()
        val sign = if (leafForward) 1f else -1f
        val full = if (mode == PageTurn.Curl && !motion.edge) leafW * CORNER_TRAVEL else leafW
        val targetX = if (commit) sign * full else 0f
        val targetY = 0f
        val startX = motion.dragX
        val startY = motion.dragY
        val remain = abs(targetX - startX) / viewWidth.coerceAtLeast(1f)
        val ms = (240 + 400 * remain.coerceIn(0.2f, 1f)).toInt().coerceIn(260, 680)
        job = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = ms, easing = FastOutSlowInEasing),
            ) { t, _ ->
                motion.dragX = startX + (targetX - startX) * t
                motion.dragY = startY + (targetY - startY) * t
            }
            onEnd()
        }
    }

    private suspend fun settle(target: Int) {
        val current = currentPage()
        val landed = when (current) {
            settled, target -> target
            else -> current
        }
        settled = landed
        active = false
        tracking = false
        fromIndex = landed
        toIndex = landed
        motion.dragX = 0f
        motion.dragY = 0f
        pagerScroll?.invoke(landed)
        landing = -1
    }

    private fun retreat() {
        active = false
        tracking = false
        toIndex = fromIndex
        motion.dragX = 0f
        motion.dragY = 0f
        landing = -1
    }

    /** Assigned each composition so a settle can read and scroll the pager. */
    var currentPage: () -> Int = { settled }
    var pagerScroll: (suspend (Int) -> Unit)? = null

    private fun leafWidth(): Float {
        val full = viewWidth.coerceAtLeast(1f)
        return if (leafWise) full * 0.5f else full
    }
}
