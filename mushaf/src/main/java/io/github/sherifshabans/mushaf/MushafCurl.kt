package io.github.sherifshabans.mushaf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/*
 * تقليب الورقة. الانزلاق على [androidx.compose.foundation.pager.HorizontalPager]،
 * والتكشيرة (زاوية أو حافة) والقلب الثلاثي والتلاشي والتغطية على محرّك واحد
 * بيتبع الإصبع. الهندسة دوال نقية عشان الاختبارات تقدر تثبّت الطرفين ومنتصف
 * التقليبة من غير جهاز.
 */

/**
 * How [MushafPager] turns a leaf.
 *
 * `Slide` and `Curl` are the 1.6.0 values. `Flip`, `Fade`, and `Cover` are
 * additive: a call that passes [Slide] or [Curl] keeps compiling. A `when`
 * with no `else` needs a branch for each new value.
 */
enum class PageTurn {
    /**
     * Right-to-left slide. `currentPageOffsetFraction` follows the finger.
     * The snap eases out (fast-out, slow-in) and a soft edge shade lifts the
     * moving sheet. On a two-page spread one flick advances a whole opening.
     */
    Slide,

    /**
     * A curl that follows the finger. A grab in the top or bottom band peels
     * that corner (dog-ear, back of the sheet, shadow on the page underneath);
     * a grab in the middle peels the fore-edge as a cylinder. Release eases
     * to the nearest settled page.
     *
     * `state.currentPageOffsetFraction` stays `0` during a curl — it is **not**
     * the curl progress. Callers that need the fraction stay on [Slide].
     */
    Curl,

    /**
     * A 3D turn around the spine. Going forward, the spine is the right edge
     * and the sheet swings shut over the next page, which lies flat underneath.
     * The sheet darkens as it turns. Rotation stops at 90° so the composable
     * never mirrors.
     *
     * `state.currentPageOffsetFraction` stays `0`.
     */
    Flip,

    /**
     * The leaving page fades and eases slightly back while the next page
     * fades in. Finger-driven; `currentPageOffsetFraction` stays `0`.
     */
    Fade,

    /**
     * The next page slides in from the fore-edge and covers the current one,
     * with a shadow on the leading edge and a dim on the page being covered.
     * `currentPageOffsetFraction` stays `0`.
     */
    Cover,
}

/**
 * Frozen pose for screenshot tests. Progress `0` is the settled page, `1` is
 * the next page fully revealed. [grabY] is `0` at the top edge and `1` at the
 * bottom; the middle band is an edge curl, the corners are a dog-ear.
 */
internal data class MushafTurnPreview(
    val progress: Float,
    val forward: Boolean = true,
    val grabY: Float = 0.9f,
)

internal val LocalMushafTurnPreview = staticCompositionLocalOf<MushafTurnPreview?> { null }

/**
 * One peel frame.
 *
 * @property boundaryX where the lifted sheet meets the flat page.
 * @property backLeft/backRight the strip showing the sheet's back face
 *   (zero width at the settled ends).
 * @property shadowLeft/shadowRight the gradient band cast on the revealed page.
 */
data class CurlSlice(
    val boundaryX: Float,
    val backLeft: Float,
    val backRight: Float,
    val shadowLeft: Float,
    val shadowRight: Float,
)

/**
 * Pure peel geometry.
 *
 * Forward (toward the next page — the RTL reader lifts the **left** fore-edge
 * to the right): the boundary travels left→right, the current page stays
 * clipped to `x >= boundaryX`, the back strip lies left of the boundary, and
 * the shadow falls on the revealed page further left. Backward mirrors all of
 * it. At `progress` 0 and 1 there is exactly one full page and **no strip**.
 */
fun curlSlice(
    widthPx: Float,
    progress: Float,
    forward: Boolean,
    backWidthPx: Float,
    shadowWidthPx: Float,
): CurlSlice {
    val p = progress.coerceIn(0f, 1f)
    val settled = p <= 0f || p >= 1f
    val back = if (settled) 0f else backWidthPx.coerceAtLeast(0f)
    val shadow = shadowWidthPx.coerceAtLeast(0f)
    val boundary = if (forward) p * widthPx else (1f - p) * widthPx
    return if (forward) {
        CurlSlice(
            boundaryX = boundary,
            backLeft = boundary - back,
            backRight = boundary,
            shadowLeft = boundary - shadow,
            shadowRight = boundary,
        )
    } else {
        CurlSlice(
            boundaryX = boundary,
            backLeft = boundary,
            backRight = boundary + back,
            shadowLeft = boundary,
            shadowRight = boundary + shadow,
        )
    }
}

/**
 * How far the corner must travel, in page-widths, before the fold has left
 * the sheet. Finger travel stays 1:1; this only scales when a dog-ear counts
 * as finished, because the crease sits halfway from the corner to the tip.
 */
internal const val CORNER_TRAVEL = 2.8f

/** Grab band (fraction of height) that peels the whole edge instead of a corner. */
internal const val EDGE_GRAB_LO = 0.30f
internal const val EDGE_GRAB_HI = 0.70f

/** Release past this fraction of the leaf width finishes the turn. */
internal const val TURN_COMMIT = 0.32f

internal fun grabIsEdge(grabY: Float): Boolean = grabY in EDGE_GRAB_LO..EDGE_GRAB_HI

/** Rotation around Y, in degrees. Never past ±90, so the sheet cannot mirror. */
internal fun flipDegrees(progress: Float, forward: Boolean): Float {
    val mag = progress.coerceIn(0f, 1f) * 90f
    return if (forward) -mag else mag
}

/**
 * Where a finger-drag of one leaf lands. A spread turns one leaf: forward from
 * page 1 opens pages 1–2, and every later turn moves a whole opening (the odd
 * page stays on the right).
 */
internal fun spreadLeafTarget(pageIndex: Int, forward: Boolean): Int {
    val last = Quran.PAGE_COUNT - 1
    val page = pageIndex + 1
    if (forward) {
        if (page <= 1) return 1.coerceAtMost(last)
        val left = if (page % 2 == 0) page else page + 1
        if (left >= Quran.PAGE_COUNT) return pageIndex
        return (left).coerceIn(0, last)
    }
    val right = when {
        page <= 1 -> 1
        page % 2 == 1 -> page
        else -> page - 1
    }
    if (right <= 1) return 0
    if (right == 3) return 1
    return (right - 2 - 1).coerceIn(0, last)
}

/**
 * Which half of an open spread moves, and which pages sit under it.
 * Pages are 1-based; null is the inside cover (blank paper).
 */
internal data class SpreadTurnPlan(
    val turnLeft: Boolean,
    val leafForward: Boolean,
    val overPage: Int?,
    val underPage: Int?,
    val staticPage: Int?,
    val incomingStaticPage: Int?,
)

internal fun planSpreadTurn(fromIndex: Int, toIndex: Int): SpreadTurnPlan {
    val fromPage = fromIndex + 1
    val forward = toIndex > fromIndex
    if (!forward && toIndex == 0 && fromPage <= 2) {
        return SpreadTurnPlan(
            turnLeft = true,
            leafForward = false,
            overPage = 2,
            underPage = null,
            staticPage = 1,
            incomingStaticPage = 1,
        )
    }
    if (forward && fromPage == 1) {
        return SpreadTurnPlan(
            turnLeft = true,
            leafForward = true,
            overPage = null,
            underPage = 2,
            staticPage = 1,
            incomingStaticPage = 1,
        )
    }
    if (forward) {
        val left = if (fromPage % 2 == 0) fromPage else fromPage + 1
        val right = left - 1
        return SpreadTurnPlan(
            turnLeft = true,
            leafForward = true,
            overPage = left.takeIf { it in 1..Quran.PAGE_COUNT },
            underPage = (left + 2).takeIf { it in 1..Quran.PAGE_COUNT },
            staticPage = right.takeIf { it in 1..Quran.PAGE_COUNT },
            incomingStaticPage = (right + 2).takeIf { it in 1..Quran.PAGE_COUNT },
        )
    }
    val right = if (fromPage % 2 == 1) fromPage else fromPage - 1
    val left = right + 1
    return SpreadTurnPlan(
        turnLeft = false,
        leafForward = false,
        overPage = right.takeIf { it in 1..Quran.PAGE_COUNT },
        underPage = (right - 2).takeIf { it in 1..Quran.PAGE_COUNT },
        staticPage = left.takeIf { it in 1..Quran.PAGE_COUNT },
        incomingStaticPage = (left - 2).takeIf { it in 1..Quran.PAGE_COUNT },
    )
}

/** Cylindrical fore-edge peel. Settled ends have no strip and no bow. */
internal data class EdgeCurl(
    val boundary: Float,
    val back: Float,
    val shadow: Float,
    val bow: Float,
    val settled: Boolean,
)

internal fun edgeCurl(
    width: Float,
    progress: Float,
    forward: Boolean,
    maxBack: Float,
    maxShadow: Float,
): EdgeCurl {
    val p = progress.coerceIn(0f, 1f)
    val settled = p <= 0.004f || p >= 0.996f
    val lift = if (settled) 0f else sin(Math.PI * p).toFloat()
    val back = if (settled) 0f else maxBack * (0.42f + 0.58f * lift)
    val shadow = if (settled) 0f else maxShadow * (0.45f + 0.55f * lift)
    val bow = if (settled) 0f else width * 0.04f * lift
    val boundary = if (forward) p * width else (1f - p) * width
    return EdgeCurl(boundary, back, shadow, bow, settled)
}

/**
 * Dog-ear fold. [flat] is the part of the current page still down; [back] is
 * the underside of the peeled region, clipped to the page.
 */
internal data class CornerFold(
    val curled: Boolean,
    val flat: List<Offset>,
    val back: List<Offset>,
    val creaseA: Offset,
    val creaseB: Offset,
    val revealedNormal: Offset,
    val apex: Offset,
)

internal fun cornerFoldAtProgress(
    width: Float,
    height: Float,
    progress: Float,
    forward: Boolean = true,
    fromTop: Boolean = false,
): CornerFold {
    val travel = (if (forward) 1f else -1f) * progress.coerceIn(0f, 1f) * CORNER_TRAVEL * width
    val apex = cornerApex(width, height, travel, 0f, forward, fromTop)
    return cornerFold(width, height, apex, forward, fromTop)
}

/**
 * The curled corner. Horizontal finger travel is 1:1 on X. A purely sideways
 * drag still lifts the corner off the edge — paper will not fold flat along
 * its own edge — and any inward drag lifts it further.
 */
internal fun cornerApex(
    width: Float,
    height: Float,
    travelX: Float,
    travelY: Float,
    forward: Boolean,
    fromTop: Boolean,
): Offset {
    val signed = if (forward) travelX.coerceAtLeast(0f) else travelX.coerceAtMost(0f)
    val inward = if (fromTop) travelY.coerceAtLeast(0f) else (-travelY).coerceAtLeast(0f)
    val minLift = abs(signed) * 0.24f
    val lift = max(minLift, inward).coerceAtMost(height * 1.35f)
    val x = (if (forward) 0f else width) + signed
    val y = if (fromTop) lift else height - lift
    return Offset(x, y)
}

internal fun cornerFold(
    width: Float,
    height: Float,
    apex: Offset,
    forward: Boolean,
    fromTop: Boolean,
): CornerFold {
    val corner = Offset(
        x = if (forward) 0f else width,
        y = if (fromTop) 0f else height,
    )
    val full = pageRect(width, height)
    val none = CornerFold(
        curled = false,
        flat = full,
        back = emptyList(),
        creaseA = corner,
        creaseB = corner,
        revealedNormal = Offset.Zero,
        apex = corner,
    )
    if (width < 1f || height < 1f) return none
    val vx = apex.x - corner.x
    val vy = apex.y - corner.y
    val len = hypot(vx, vy)
    if (len < 2f) return none

    val mid = Offset((corner.x + apex.x) * 0.5f, (corner.y + apex.y) * 0.5f)
    val dx = -vy / len
    val dy = vx / len
    val reach = max(width, height) * 8f
    val lineA = Offset(mid.x - dx * reach, mid.y - dy * reach)
    val lineB = Offset(mid.x + dx * reach, mid.y + dy * reach)
    val cornerSide = cross(lineA, lineB, corner)
    if (abs(cornerSide) < 0.5f) return none

    val flat = clipHalf(full, lineA, lineB) { cross(lineA, lineB, it) * cornerSide <= 0f }
    val peeled = clipHalf(full, lineA, lineB) { cross(lineA, lineB, it) * cornerSide >= 0f }
    if (peeled.size < 3) return none

    val back = clipToRect(peeled.map { reflect(it, mid, dx, dy) }, width, height)
    val onCrease = peeled.filter { abs(cross(lineA, lineB, it)) <= 2f }
    val creaseA = onCrease.getOrElse(0) { mid }
    val creaseB = onCrease.getOrElse(1) { onCrease.getOrElse(0) { mid } }
    val nLen = hypot(corner.x - mid.x, corner.y - mid.y).coerceAtLeast(1f)
    val curled = flat.size < 3 || polygonArea(flat) < width * height * 0.985f
    return CornerFold(
        curled = curled,
        flat = if (flat.size >= 3) flat else emptyList(),
        back = if (back.size >= 3) back else emptyList(),
        creaseA = creaseA,
        creaseB = creaseB,
        revealedNormal = Offset((corner.x - mid.x) / nLen, (corner.y - mid.y) / nLen),
        apex = apex,
    )
}

internal fun polygonArea(poly: List<Offset>): Float {
    if (poly.size < 3) return 0f
    var sum = 0f
    var prev = poly.last()
    for (cur in poly) {
        sum += prev.x * cur.y - cur.x * prev.y
        prev = cur
    }
    return abs(sum) * 0.5f
}

internal fun pointInPolygon(p: Offset, poly: List<Offset>): Boolean {
    if (poly.size < 3) return false
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        val dy = b.y - a.y
        if ((a.y > p.y) != (b.y > p.y) && dy != 0f) {
            val xCross = (b.x - a.x) * (p.y - a.y) / dy + a.x
            if (p.x < xCross) inside = !inside
        }
        j = i
    }
    return inside
}

private fun pageRect(width: Float, height: Float) = listOf(
    Offset(0f, 0f),
    Offset(width, 0f),
    Offset(width, height),
    Offset(0f, height),
)

private fun cross(a: Offset, b: Offset, p: Offset): Float =
    (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

private fun reflect(p: Offset, mid: Offset, dx: Float, dy: Float): Offset {
    val along = (p.x - mid.x) * dx + (p.y - mid.y) * dy
    val projX = mid.x + along * dx
    val projY = mid.y + along * dy
    return Offset(projX * 2f - p.x, projY * 2f - p.y)
}

private fun clipHalf(
    subject: List<Offset>,
    lineA: Offset,
    lineB: Offset,
    inside: (Offset) -> Boolean,
): List<Offset> {
    if (subject.isEmpty()) return emptyList()
    val out = ArrayList<Offset>(subject.size + 2)
    var prev = subject.last()
    var prevIn = inside(prev)
    for (cur in subject) {
        val curIn = inside(cur)
        if (curIn) {
            if (!prevIn) out.add(segmentHit(prev, cur, lineA, lineB))
            out.add(cur)
        } else if (prevIn) {
            out.add(segmentHit(prev, cur, lineA, lineB))
        }
        prev = cur
        prevIn = curIn
    }
    return out
}

private fun segmentHit(p: Offset, q: Offset, a: Offset, b: Offset): Offset {
    val sp = cross(a, b, p)
    val sq = cross(a, b, q)
    val den = sp - sq
    if (abs(den) < 1e-3f) return Offset((p.x + q.x) * 0.5f, (p.y + q.y) * 0.5f)
    val t = (sp / den).coerceIn(0f, 1f)
    return Offset(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
}

private fun clipToRect(poly: List<Offset>, width: Float, height: Float): List<Offset> {
    var p = poly
    p = clipAxis(p, vertical = true, bound = 0f, keepGreater = true)
    p = clipAxis(p, vertical = true, bound = width, keepGreater = false)
    p = clipAxis(p, vertical = false, bound = 0f, keepGreater = true)
    p = clipAxis(p, vertical = false, bound = height, keepGreater = false)
    return p
}

private fun clipAxis(
    subject: List<Offset>,
    vertical: Boolean,
    bound: Float,
    keepGreater: Boolean,
): List<Offset> {
    if (subject.isEmpty()) return emptyList()
    fun coord(o: Offset) = if (vertical) o.x else o.y
    fun inside(o: Offset): Boolean {
        val c = coord(o)
        return if (keepGreater) c >= bound - 0.01f else c <= bound + 0.01f
    }
    fun hit(p: Offset, q: Offset): Offset {
        val cp = coord(p)
        val cq = coord(q)
        val den = cp - cq
        val t = if (abs(den) < 1e-3f) 0f else ((cp - bound) / den).coerceIn(0f, 1f)
        return Offset(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
    }
    val out = ArrayList<Offset>(subject.size + 2)
    var prev = subject.last()
    var prevIn = inside(prev)
    for (cur in subject) {
        val curIn = inside(cur)
        if (curIn) {
            if (!prevIn) out.add(hit(prev, cur))
            out.add(cur)
        } else if (prevIn) {
            out.add(hit(prev, cur))
        }
        prev = cur
        prevIn = curIn
    }
    return out
}

/**
 * Test hook: the page indexes a non-slide turn composed. Slide mode never
 * touches it, and a turn only composes the settled page and its neighbour.
 */
internal object MushafCurlProbe {
    val composedPages: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    fun reset() {
        composedPages.clear()
    }
}

/**
 * Turn gestures on the page stack.
 *
 * Runs on [PointerEventPass.Initial] so the container sees events before
 * [MushafPage]: it consumes only horizontal drags past touch slop and taps in
 * the frame margins. A tap inside the text column is left untouched and stays
 * an ayah tap or long-press. Vertical movement past slop cancels.
 *
 * [onDragEnd] receives horizontal velocity in pixels per second (positive when
 * the finger was moving right) so the release can carry a flick.
 */
internal fun Modifier.curlGestures(
    touchSlop: Float,
    longPressTimeoutMs: Long,
    marginTurn: (Offset) -> Boolean,
    onDragStart: (forward: Boolean, position: Offset) -> Unit,
    onDrag: (deltaX: Float, deltaY: Float) -> Unit,
    onDragEnd: (velocityX: Float) -> Unit,
    onTapMargin: (forward: Boolean) -> Unit,
): Modifier = pointerInput(touchSlop, longPressTimeoutMs) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val pointerId = down.id
        var dragging = false
        var cancelled = false
        var totalDx = 0f
        var totalDy = 0f
        var lastX = down.position.x
        var lastY = down.position.y
        var lastTime = down.uptimeMillis
        var velocityX = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
                if (!dragging && !cancelled &&
                    change.uptimeMillis - down.uptimeMillis < longPressTimeoutMs &&
                    abs(totalDx) < touchSlop && abs(totalDy) < touchSlop
                ) {
                    if (marginTurn(change.position)) {
                        change.consume()
                        onTapMargin(change.position.x < size.width / 2f)
                    }
                }
                break
            }
            val dx = change.position.x - lastX
            val dy = change.position.y - lastY
            if (dragging) {
                change.consume()
                val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                val instant = dx / dt * 1000f
                velocityX = velocityX * 0.65f + instant * 0.35f
                onDrag(dx, dy)
                lastX = change.position.x
                lastY = change.position.y
                lastTime = change.uptimeMillis
            } else {
                totalDx = change.position.x - down.position.x
                totalDy = change.position.y - down.position.y
                when {
                    abs(totalDx) > touchSlop && abs(totalDx) > abs(totalDy) -> {
                        dragging = true
                        change.consume()
                        onDragStart(totalDx > 0f, down.position)
                        onDrag(dx, dy)
                        lastX = change.position.x
                        lastY = change.position.y
                        lastTime = change.uptimeMillis
                    }
                    abs(totalDy) > touchSlop -> cancelled = true
                }
            }
        }
        if (dragging) onDragEnd(velocityX)
    }
}
