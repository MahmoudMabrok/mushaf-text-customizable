package io.github.sherifshabans.mushaf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/*
 * رسم التكشيرة. الورقة تُلتقط مرة واحدة. الجزء المفرود يُقصّ كما هو،
 * وظهرها انعكاس ملوّن، وشريط الأسطوانة ظلّ متدرّج (drawVertices)
 * مع لمعة على الشفة. يعمل من API 21 — لا RenderEffect ولا AGSL.
 * شبكة تعيد عيّنة الخط كانت تلطّخ رسم المصحف، فالظهر يبقى انعكاسًا حادًا.
 */

/** Reused buffers so a curl frame does not allocate. */
internal class CurlDrawScratch {
    val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val flapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val path = Path()
    val pathB = Path()
    val matrix = Matrix()
    val dst = MeshDst()
    var verts = FloatArray(0)
    var colors = IntArray(0)
    var indices = ShortArray(0)

    fun ensure(vertexCount: Int, indexCount: Int) {
        if (colors.size < vertexCount) {
            verts = FloatArray(vertexCount * 2)
            colors = IntArray(vertexCount)
        }
        if (indices.size < indexCount) indices = ShortArray(indexCount)
    }
}

internal class MeshDst {
    var x: Float = 0f
    var y: Float = 0f
    var color: Int = -1
}

/**
 * Reflect [p] across the line through [origin] in direction [dir] (must be a unit vector).
 */
internal fun curlMirrorPoint(p: Offset, origin: Offset, dir: Offset): Offset {
    val along = (p.x - origin.x) * dir.x + (p.y - origin.y) * dir.y
    val projX = origin.x + along * dir.x
    val projY = origin.y + along * dir.y
    return Offset(projX * 2f - p.x, projY * 2f - p.y)
}

/** Android matrix for [curlMirrorPoint]. Maps bitmap pixels onto the flipped flap. */
internal fun curlMirrorMatrix(origin: Offset, dir: Offset): Matrix {
    val dx = dir.x
    val dy = dir.y
    val dot = origin.x * dx + origin.y * dy
    val tx = 2f * origin.x - 2f * dot * dx
    val ty = 2f * origin.y - 2f * dot * dy
    return Matrix().apply {
        setValues(
            floatArrayOf(
                2f * dx * dx - 1f, 2f * dx * dy, tx,
                2f * dx * dy, 2f * dy * dy - 1f, ty,
                0f, 0f, 1f,
            )
        )
    }
}

internal fun curlBitmapHash(bitmap: Bitmap): Int {
    if (bitmap.width < 2 || bitmap.height < 2 || bitmap.isRecycled) return 0
    val src = if (bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        bitmap
    }
    var h = 17
    val xs = intArrayOf(1, src.width / 2, src.width - 2)
    val ys = intArrayOf(1, src.height / 2, src.height - 2)
    for (y in ys) {
        for (x in xs) {
            if (x in 0 until src.width && y in 0 until src.height) {
                h = h * 31 + src.getPixel(x, y)
            }
        }
    }
    if (src !== bitmap) src.recycle()
    return h
}

/**
 * Draw one curl frame onto [canvas]. The page underneath must already be there;
 * this paints the shadow, the flat sheet, the rolled cylinder, and the back.
 *
 * [dragX] / [dragY] are finger travel in pixels. Pass `NaN` for [dragX] to
 * derive the pose from [progress] alone (screenshot tests).
 *
 * [verso] is the other side of the leaf on a spread. When [uprightVerso] is
 * set it is pre-flipped so the text reads correctly once the leaf lands.
 * Otherwise the back is [front] mirrored and tinted.
 */
internal fun drawRealisticCurl(
    canvas: Canvas,
    front: Bitmap,
    width: Float,
    height: Float,
    progress: Float,
    forward: Boolean,
    edge: Boolean,
    fromTop: Boolean,
    radiusPx: Float,
    scratch: CurlDrawScratch,
    dragX: Float = Float.NaN,
    dragY: Float = 0f,
    fingerY: Float = height * if (fromTop) 0.14f else 0.86f,
    verso: Bitmap? = null,
    uprightVerso: Boolean = false,
    clipLeft: Float = 0f,
    clipTop: Float = 0f,
    clipRight: Float = width,
    clipBottom: Float = height,
) {
    if (width < 2f || height < 2f || front.width < 2 || front.height < 2) return
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.012f) {
        canvas.drawBitmap(front, 0f, 0f, scratch.flapPaint.apply { colorFilter = null; alpha = 255 })
        return
    }
    if (p >= 0.992f) return
    val lift = sin(PI * p).toFloat()
    val radius = (radiusPx * (0.72f + 0.28f * lift)).coerceAtLeast(6f)
    val travel = if (edge) width else CORNER_TRAVEL * width
    val dx = if (dragX.isNaN()) (if (forward) 1f else -1f) * p * travel else dragX
    val dy = if (dragX.isNaN()) 0f else dragY
    if (edge) {
        drawEdgeRoll(
            canvas, front, width, height, p, forward, radius, fingerY, scratch,
        )
    } else {
        drawCornerRoll(
            canvas = canvas,
            front = front,
            width = width,
            height = height,
            forward = forward,
            fromTop = fromTop,
            radius = radius,
            dragX = dx,
            dragY = dy,
            scratch = scratch,
            verso = verso,
            uprightVerso = uprightVerso,
            clipLeft = clipLeft,
            clipTop = clipTop,
            clipRight = clipRight,
            clipBottom = clipBottom,
            lift = lift,
        )
    }
}

private fun drawEdgeRoll(
    canvas: Canvas,
    front: Bitmap,
    width: Float,
    height: Float,
    progress: Float,
    forward: Boolean,
    radius: Float,
    fingerY: Float,
    scratch: CurlDrawScratch,
) {
    val boundary = if (forward) progress * width else (1f - progress) * width
    val bow = width * 0.032f * sin(PI * progress).toFloat() * if (forward) 1f else -1f
    val frontSign = if (forward) 1f else -1f
    val backW = radius * 1.65f
    val shadowW = radius * 3.4f
    fun creaseX(y: Float): Float {
        val t = (y / height).coerceIn(0f, 1f)
        val f = (fingerY / height).coerceIn(0.08f, 0.92f)
        val w = if (t <= f) t / f else (1f - t) / (1f - f)
        return boundary + bow * sin(w * PI.toFloat() * 0.5f).toFloat()
    }

    // Shadow on the revealed page, just beyond the back strip.
    drawEdgeBand(
        canvas, scratch, height,
        inner = { y -> creaseX(y) - frontSign * backW },
        outer = { y -> creaseX(y) - frontSign * (backW + shadowW) },
        innerColor = argb(150, 0f, 0f, 0f),
        outerColor = argb(0, 0f, 0f, 0f),
        rows = 24,
    )
    drawEdgeBand(
        canvas, scratch, height,
        inner = { y -> creaseX(y) - frontSign * (backW - 1f) },
        outer = { y -> creaseX(y) - frontSign * (backW + radius * 0.9f) },
        innerColor = argb(70, 0f, 0f, 0f),
        outerColor = argb(0, 0f, 0f, 0f),
        rows = 18,
    )

    // Flat front, up to the bowed crease. The roll sits on the peeled side.
    fillPath(scratch.path) { path ->
        path.moveTo(creaseX(0f), 0f)
        val steps = 28
        for (i in 1..steps) {
            val y = height * i / steps
            path.lineTo(creaseX(y), y)
        }
        if (forward) {
            path.lineTo(width, height)
            path.lineTo(width, 0f)
        } else {
            path.lineTo(0f, height)
            path.lineTo(0f, 0f)
        }
        path.close()
    }
    canvas.save()
    canvas.clipPath(scratch.path)
    scratch.flapPaint.colorFilter = null
    scratch.flapPaint.alpha = 255
    canvas.drawBitmap(front, 0f, 0f, scratch.flapPaint)
    canvas.restore()

    // Back of the sheet, mirrored across the crease and warm-tinted.
    fillPath(scratch.pathB) { path ->
        val steps = 28
        path.moveTo(creaseX(0f), 0f)
        for (i in 1..steps) {
            val y = height * i / steps.toFloat()
            path.lineTo(creaseX(y), y)
        }
        for (i in steps downTo 0) {
            val y = height * i / steps.toFloat()
            path.lineTo(creaseX(y) - frontSign * backW, y)
        }
        path.close()
    }
    canvas.save()
    canvas.clipPath(scratch.pathB)
    scratch.flapPaint.colorFilter = android.graphics.LightingColorFilter(0xFFCDB89A.toInt(), 0)
    scratch.flapPaint.alpha = 255
    canvas.save()
    canvas.translate(boundary, 0f)
    canvas.scale(-1f, 1f)
    canvas.translate(-boundary, 0f)
    canvas.drawBitmap(front, 0f, 0f, scratch.flapPaint)
    canvas.restore()
    scratch.flapPaint.colorFilter = null
    canvas.restore()

    // Shade across the roll: dark in the valley, clear at the outer edge.
    drawEdgeBand(
        canvas, scratch, height,
        inner = { y -> creaseX(y) },
        outer = { y -> creaseX(y) - frontSign * backW },
        innerColor = argb(110, 0.15f, 0.1f, 0.05f),
        outerColor = argb(0, 0f, 0f, 0f),
        rows = 24,
    )
    drawEdgeBand(
        canvas, scratch, height,
        inner = { y -> creaseX(y) },
        outer = { y -> creaseX(y) + frontSign * radius * 0.55f },
        innerColor = argb(70, 0f, 0f, 0f),
        outerColor = argb(0, 0f, 0f, 0f),
        rows = 24,
    )

    scratch.strokePaint.strokeCap = Paint.Cap.BUTT
    scratch.strokePaint.strokeWidth = 2.2f
    scratch.strokePaint.color = Color.WHITE
    scratch.strokePaint.alpha = (120 * sin(PI * progress)).toInt().coerceIn(0, 170)
    canvas.drawLine(creaseX(0f), 0f, creaseX(height), height, scratch.strokePaint)
}

private fun drawCornerRoll(
    canvas: Canvas,
    front: Bitmap,
    width: Float,
    height: Float,
    forward: Boolean,
    fromTop: Boolean,
    radius: Float,
    dragX: Float,
    dragY: Float,
    scratch: CurlDrawScratch,
    verso: Bitmap?,
    uprightVerso: Boolean,
    clipLeft: Float,
    clipTop: Float,
    clipRight: Float,
    clipBottom: Float,
    lift: Float,
) {
    val apex = cornerApex(width, height, dragX, dragY, forward, fromTop)
    val fold = cornerFold(
        width, height, apex, forward, fromTop,
        clipLeft, clipTop, clipRight, clipBottom,
    )
    val corner = Offset(
        if (forward) 0f else width,
        if (fromTop) 0f else height,
    )
    val segment = creaseSegment(corner, apex, clipLeft, clipTop, clipRight, clipBottom)
    if (segment == null) {
        // The fold has left the sheet, or it has not started.
        if (polygonArea(fold.flat) > width * height * 0.04f) {
            scratch.flapPaint.colorFilter = null
            scratch.flapPaint.alpha = 255
            canvas.drawBitmap(front, 0f, 0f, scratch.flapPaint)
        }
        return
    }
    val creaseA = segment.first
    val creaseB = segment.second
    val ax = creaseB.x - creaseA.x
    val ay = creaseB.y - creaseA.y
    val len = hypot(ax, ay).coerceAtLeast(1f)
    val tx = ax / len
    val ty = ay / len
    val nx = fold.revealedNormal.x
    val ny = fold.revealedNormal.y
    val origin = Offset(
        (creaseA.x + creaseB.x) * 0.5f,
        (creaseA.y + creaseB.y) * 0.5f,
    )
    val dir = Offset(tx, ty)
    val inset = radius * 0.1f

    // Shadow cast on the revealed page (+normal).
    drawShadowBand(canvas, scratch, creaseA, creaseB, nx, ny, radius * 3.1f, (140 * (0.55f + 0.45f * lift)).toInt())
    drawShadowBand(canvas, scratch, creaseA, creaseB, nx, ny, radius * 1.05f, (70 * lift).toInt().coerceAtLeast(24))

    // Contact shadow of the flap, shifted toward the revealed side.
    if (fold.back.size >= 3) {
        fillPath(scratch.path, fold.back)
        canvas.save()
        canvas.translate(nx * 8f + 1.5f, ny * 8f + 5f)
        scratch.fillPaint.color = Color.argb(48, 0, 0, 0)
        canvas.drawPath(scratch.path, scratch.fillPaint)
        canvas.restore()
    }

    // Flat front, stopped short of the crease so the roll is not double-drawn.
    val frontPoly = halfPlanePolygon(
        0f, 0f, width, height,
        origin - Offset(nx, ny) * inset,
        dir,
        keepPositiveNormal = false,
        normal = Offset(nx, ny),
    )
    if (frontPoly.size >= 3) {
        fillPath(scratch.path, frontPoly)
        canvas.save()
        canvas.clipPath(scratch.path)
        scratch.flapPaint.colorFilter = null
        scratch.flapPaint.alpha = 255
        canvas.drawBitmap(front, 0f, 0f, scratch.flapPaint)
        canvas.restore()
    }

    // Back of the page: mirrored front, or the spread's verso flipped upright.
    val flapPoly = if (fold.back.size >= 3) {
        polygonOutsideBand(fold.back, origin, Offset(nx, ny), inset)
    } else {
        emptyList()
    }
    if (flapPoly.size >= 3) {
        fillPath(scratch.path, flapPoly)
        canvas.save()
        canvas.clipPath(scratch.path)
        val backBitmap = if (uprightVerso && verso != null) verso else front
        scratch.matrix.set(curlMirrorMatrix(origin, dir))
        if (uprightVerso && verso != null) {
            val flip = Matrix()
            flip.setScale(-1f, 1f, width / 2f, height / 2f)
            flip.postConcat(scratch.matrix)
            scratch.matrix.set(flip)
        }
        scratch.flapPaint.shader = null
        scratch.flapPaint.colorFilter = android.graphics.LightingColorFilter(0xFFCDB89A.toInt(), 0x00000000)
        scratch.flapPaint.alpha = 255
        canvas.concat(scratch.matrix)
        canvas.drawBitmap(backBitmap, 0f, 0f, scratch.flapPaint)
        scratch.flapPaint.colorFilter = null
        canvas.restore()
    }

    // Cylindrical shade on the back of the roll, darkest at the lip.
    drawShadowBand(
        canvas, scratch, creaseA, creaseB, -nx, -ny,
        radius * 0.9f,
        (96 * lift).toInt().coerceAtLeast(36),
    )

    scratch.strokePaint.strokeCap = Paint.Cap.ROUND
    scratch.strokePaint.color = Color.WHITE
    scratch.strokePaint.strokeWidth = 2.4f
    scratch.strokePaint.alpha = (100 * lift).toInt().coerceIn(28, 140)
    canvas.drawLine(creaseA.x, creaseA.y, creaseB.x, creaseB.y, scratch.strokePaint)
}

private fun drawShadowBand(
    canvas: Canvas,
    scratch: CurlDrawScratch,
    a: Offset,
    b: Offset,
    nx: Float,
    ny: Float,
    reach: Float,
    alpha: Int,
) {
    if (alpha <= 0) return
    val cols = 1
    val rows = 20
    renderColorMesh(canvas, scratch, cols, rows) { col, row, dst ->
        val u = col / cols.toFloat()
        val t = row / rows.toFloat()
        val x = a.x + (b.x - a.x) * t
        val y = a.y + (b.y - a.y) * t
        dst.x = x + nx * reach * u
        dst.y = y + ny * reach * u
        val fade = (1f - u) * (1f - u)
        dst.color = Color.argb((alpha * fade).toInt().coerceIn(0, 255), 0, 0, 0)
    }
}

private fun drawEdgeBand(
    canvas: Canvas,
    scratch: CurlDrawScratch,
    height: Float,
    inner: (Float) -> Float,
    outer: (Float) -> Float,
    innerColor: Int,
    outerColor: Int,
    rows: Int,
) {
    val cols = 1
    renderColorMesh(canvas, scratch, cols, rows) { col, row, dst ->
        val u = col / cols.toFloat()
        val y = height * row / rows
        val x0 = inner(y)
        val x1 = outer(y)
        dst.x = x0 + (x1 - x0) * u
        dst.y = y
        dst.color = if (u < 0.5f) innerColor else outerColor
    }
}

private inline fun renderColorMesh(
    canvas: Canvas,
    scratch: CurlDrawScratch,
    cols: Int,
    rows: Int,
    sample: (col: Int, row: Int, dst: MeshDst) -> Unit,
) {
    val vertsN = (cols + 1) * (rows + 1)
    val indexN = cols * rows * 6
    scratch.ensure(vertsN, indexN)
    val dst = scratch.dst
    var slot = 0
    for (row in 0..rows) {
        for (col in 0..cols) {
            sample(col, row, dst)
            val o = slot * 2
            scratch.verts[o] = dst.x
            scratch.verts[o + 1] = dst.y
            scratch.colors[slot] = dst.color
            slot++
        }
    }
    writeGridIndices(scratch, cols, rows)
    val paint = scratch.meshPaint
    paint.shader = null
    paint.alpha = 255
    canvas.drawVertices(
        Canvas.VertexMode.TRIANGLES,
        vertsN,
        scratch.verts, 0,
        null, 0,
        scratch.colors, 0,
        scratch.indices, 0,
        indexN,
        paint,
    )
}

private fun writeGridIndices(scratch: CurlDrawScratch, cols: Int, rows: Int) {
    var k = 0
    val stride = cols + 1
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val i = row * stride + col
            scratch.indices[k++] = i.toShort()
            scratch.indices[k++] = (i + 1).toShort()
            scratch.indices[k++] = (i + stride).toShort()
            scratch.indices[k++] = (i + 1).toShort()
            scratch.indices[k++] = (i + stride + 1).toShort()
            scratch.indices[k++] = (i + stride).toShort()
        }
    }
}

/** Clip the perpendicular bisector of [corner]→[apex] to the page rectangle. */
private fun creaseSegment(
    corner: Offset,
    apex: Offset,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): Pair<Offset, Offset>? {
    val vx = apex.x - corner.x
    val vy = apex.y - corner.y
    val len = hypot(vx, vy)
    if (len < 2f) return null
    val mid = Offset((corner.x + apex.x) * 0.5f, (corner.y + apex.y) * 0.5f)
    val tx = -vy / len
    val ty = vx / len
    val hits = ArrayList<Offset>(4)
    fun consider(x: Float, y: Float) {
        if (x < left - 1.5f || x > right + 1.5f || y < top - 1.5f || y > bottom + 1.5f) return
        val p = Offset(x.coerceIn(left, right), y.coerceIn(top, bottom))
        if (hits.any { hypot(it.x - p.x, it.y - p.y) < 2f }) return
        hits.add(p)
    }
    if (kotlin.math.abs(tx) > 1e-4f) {
        consider(left, mid.y + (left - mid.x) * ty / tx)
        consider(right, mid.y + (right - mid.x) * ty / tx)
    }
    if (kotlin.math.abs(ty) > 1e-4f) {
        consider(mid.x + (top - mid.y) * tx / ty, top)
        consider(mid.x + (bottom - mid.y) * tx / ty, bottom)
    }
    if (hits.size < 2) return null
    var bestI = 0
    var bestJ = 1
    var best = -1f
    for (i in hits.indices) {
        for (j in i + 1 until hits.size) {
            val d = hypot(hits[i].x - hits[j].x, hits[i].y - hits[j].y)
            if (d > best) {
                best = d
                bestI = i
                bestJ = j
            }
        }
    }
    if (best < 8f) return null
    return hits[bestI] to hits[bestJ]
}

private fun halfPlanePolygon(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    linePoint: Offset,
    lineDir: Offset,
    keepPositiveNormal: Boolean,
    normal: Offset,
): List<Offset> {
    val rect = listOf(
        Offset(left, top),
        Offset(right, top),
        Offset(right, bottom),
        Offset(left, bottom),
    )
    val reach = hypot(right - left, bottom - top) * 4f
    val a = Offset(linePoint.x - lineDir.x * reach, linePoint.y - lineDir.y * reach)
    val b = Offset(linePoint.x + lineDir.x * reach, linePoint.y + lineDir.y * reach)
    fun side(p: Offset): Float {
        val s = (p.x - linePoint.x) * normal.x + (p.y - linePoint.y) * normal.y
        return if (keepPositiveNormal) s else -s
    }
    if (rect.isEmpty()) return emptyList()
    val out = ArrayList<Offset>(6)
    var prev = rect.last()
    var prevIn = side(prev) >= -0.5f
    for (cur in rect) {
        val curIn = side(cur) >= -0.5f
        if (curIn) {
            if (!prevIn) out.add(segmentHit(prev, cur, a, b))
            out.add(cur)
        } else if (prevIn) {
            out.add(segmentHit(prev, cur, a, b))
        }
        prev = cur
        prevIn = curIn
    }
    return out
}

private fun segmentHit(p: Offset, q: Offset, a: Offset, b: Offset): Offset {
    fun cross(a0: Offset, b0: Offset, p0: Offset) =
        (b0.x - a0.x) * (p0.y - a0.y) - (b0.y - a0.y) * (p0.x - a0.x)
    val sp = cross(a, b, p)
    val sq = cross(a, b, q)
    val den = sp - sq
    if (kotlin.math.abs(den) < 1e-3f) return Offset((p.x + q.x) * 0.5f, (p.y + q.y) * 0.5f)
    val t = (sp / den).coerceIn(0f, 1f)
    return Offset(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
}

/** Drop the part of [poly] that lies closer to the crease than [band]. */
private fun polygonOutsideBand(
    poly: List<Offset>,
    origin: Offset,
    normal: Offset,
    band: Float,
): List<Offset> {
    if (poly.size < 3) return emptyList()
    fun signed(p: Offset) = (p.x - origin.x) * normal.x + (p.y - origin.y) * normal.y
    // Flap lives on the negative side of the normal. Keep signed <= -band.
    val limit = -band
    val out = ArrayList<Offset>(poly.size + 2)
    var prev = poly.last()
    var prevIn = signed(prev) <= limit + 0.5f
    for (cur in poly) {
        val curIn = signed(cur) <= limit + 0.5f
        if (curIn) {
            if (!prevIn) out.add(lerpToSigned(prev, cur, origin, normal, limit))
            out.add(cur)
        } else if (prevIn) {
            out.add(lerpToSigned(prev, cur, origin, normal, limit))
        }
        prev = cur
        prevIn = curIn
    }
    return out
}

private fun lerpToSigned(
    p: Offset,
    q: Offset,
    origin: Offset,
    normal: Offset,
    limit: Float,
): Offset {
    fun s(o: Offset) = (o.x - origin.x) * normal.x + (o.y - origin.y) * normal.y
    val sp = s(p)
    val sq = s(q)
    val den = sp - sq
    val t = if (kotlin.math.abs(den) < 1e-3f) 0f else ((sp - limit) / den).coerceIn(0f, 1f)
    return Offset(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
}

private fun fillPath(path: Path, points: List<Offset>) {
    path.rewind()
    if (points.size < 3) return
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    path.close()
}

private inline fun fillPath(path: Path, build: (Path) -> Unit) {
    path.rewind()
    build(path)
}

private fun argb(a: Int, r: Float, g: Float, b: Float): Int {
    return (a.coerceIn(0, 255) shl 24) or
        ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
        ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
        (b.coerceIn(0f, 1f) * 255f).toInt()
}

