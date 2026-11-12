package io.github.sherifshabans.mushaf

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Curl, flip, fade, and cover. Slide stays on [HorizontalPager].
 *
 * Drag distance maps 1:1 onto the sheet. Releasing carries the finger's velocity
 * into a spring that finishes or cancels the turn. Only the draw phase reads
 * the drag, and a curl snapshots each page once per gesture, so the page
 * composables are not rebuilt on every frame.
 *
 * On a spread, curl rolls the fore-edge leaf across the gutter (the facing page
 * is the back of that leaf). Flip still crossfades the facing page over the
 * second half of the turn. Fade and cover move the whole opening. A reciter
 * jump replaces the in-flight target.
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
                onDragEnd = { velocityX, velocityY ->
                    if (!controller.tracking) return@curlGestures
                    controller.tracking = false
                    if (controller.landing >= 0) return@curlGestures
                    controller.release(velocityX, velocityY, mode, scope)
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
    val full = leafWidth
    return (travel / full.coerceAtLeast(1f)).coerceIn(0f, 1f)
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
        // A margin tap rolls the whole fore-edge. The spring gets a small
        // push so the page is moving on the first frames.
        motion.grabY = 0.5f
        motion.edge = true
        motion.fromTop = false
        leafForward = if (leafWise) planSpreadTurn(settled, target).leafForward else forward
        motion.dragX = 0f
        motion.dragY = 0f
        active = true
        release(velocityX = 0f, velocityY = 0f, mode = mode, scope = scope, forceCommit = true)
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
        motion.edge = true
        motion.fromTop = false
        motion.grabY = 0.5f
        leafForward = page > settled
        tracking = false
        active = true
        animateRelease(commit = true, mode = mode, scope = scope, velocityX = 0f, velocityY = 0f) {
            settle(page)
        }
    }

    fun release(
        velocityX: Float,
        velocityY: Float,
        mode: PageTurn,
        scope: kotlinx.coroutines.CoroutineScope,
        forceCommit: Boolean = false,
    ) {
        val leafW = leafWidth()
        val travel = if (leafForward) motion.dragX.coerceAtLeast(0f) else (-motion.dragX).coerceAtLeast(0f)
        val pushed = if (forceCommit && abs(velocityX) < 80f) {
            (if (leafForward) 1f else -1f) * leafW * 1.15f
        } else {
            velocityX
        }
        val signed = if (leafForward) pushed else -pushed
        val projected = exponentialDecay<Float>(frictionMultiplier = 2.2f)
            .calculateTargetValue(initialValue = travel, initialVelocity = signed)
        val flickForward = signed > 1400f
        val flickBack = signed < -1400f
        val commit = when {
            forceCommit -> true
            flickForward -> true
            flickBack && travel < leafW * 0.55f -> false
            projected >= leafW * TURN_COMMIT -> true
            travel / leafW.coerceAtLeast(1f) >= TURN_COMMIT -> true
            else -> false
        }
        if (commit) landing = toIndex
        animateRelease(commit, mode, scope, pushed, velocityY) {
            if (commit) settle(toIndex) else retreat()
        }
    }

    private fun animateRelease(
        commit: Boolean,
        mode: PageTurn,
        scope: kotlinx.coroutines.CoroutineScope,
        velocityX: Float,
        velocityY: Float,
        onEnd: suspend () -> Unit,
    ) {
        job?.cancel()
        val leafW = leafWidth()
        val sign = if (leafForward) 1f else -1f
        val full = leafW
        val targetX = if (commit) sign * full else 0f
        val targetY = 0f
        val startX = motion.dragX
        val startY = motion.dragY
        val dx = targetX - startX
        val dy = targetY - startY
        val dist = hypot(dx, dy)
        val along = velocityX * dx + velocityY * dy
        val speed = if (dist < 1f) 0f else (along / (dist * dist)).coerceIn(-14f, 14f)
        job = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                initialVelocity = speed,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = 640f,
                    visibilityThreshold = 0.0015f,
                ),
            ) { u, _ ->
                val t = u.coerceIn(0f, 1f)
                motion.dragX = startX + dx * t
                motion.dragY = startY + dy * t
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
