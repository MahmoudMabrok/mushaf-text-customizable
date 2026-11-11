package io.github.sherifshabans.mushaf

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * تكشيرة أسطوانية. نقاط الصفحة تُلفّ على أسطوانة نصفها للأمام ونصفها للظهر،
 * فالنص ينضغط نحو اللفّة بدل أن يُقلب كمرآة مسطّحة. الشبكة (drawVertices)
 * هي المسار على كل الإصدارات وفي لقطات الاختبار. على API 33+ نحاول نفس
 * التحويل بـ AGSL؛ إن فشل نرجع للشبكة. الورقة تُلتقط مرة واحدة.
 */

/** Which implementation produced the last [drawRealisticCurl]. Tests read this. */
internal var curlDrawPath: String = "mesh"

/** Reused buffers so a curl frame does not allocate. */
internal class CurlDrawScratch {
    val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
        isFilterBitmap = true
        isDither = true
    }
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    val flapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
    }
    var verts = FloatArray(0)
    var texs = FloatArray(0)
    var backTexs = FloatArray(0)
    var colors = IntArray(0)
    var frontIdx = ShortArray(0)
    var backIdx = ShortArray(0)
    var shadowVerts = FloatArray(0)
    var shadowColors = IntArray(0)
    var shadowIdx = ShortArray(0)
    var shadeIdx = ShortArray(0)
    var light = FloatArray(0)
    private var frontBmp: Bitmap? = null
    private var frontShader: BitmapShader? = null
    private var backBmp: Bitmap? = null
    private var backShader: BitmapShader? = null

    fun shaderFor(bitmap: Bitmap, back: Boolean): BitmapShader {
        val cached = if (back) backBmp else frontBmp
        val shader = if (back) backShader else frontShader
        if (cached !== bitmap || shader == null) {
            val made = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (back) {
                backBmp = bitmap
                backShader = made
            } else {
                frontBmp = bitmap
                frontShader = made
            }
            return made
        }
        return shader
    }

    fun ensure(vertsN: Int, quads: Int) {
        val needIdx = quads * 6
        if (colors.size < vertsN) {
            verts = FloatArray(vertsN * 2)
            texs = FloatArray(vertsN * 2)
            backTexs = FloatArray(vertsN * 2)
            colors = IntArray(vertsN)
            shadowVerts = FloatArray(vertsN * 2)
            shadowColors = IntArray(vertsN)
            light = FloatArray(vertsN)
        }
        if (frontIdx.size < needIdx) {
            frontIdx = ShortArray(needIdx)
            backIdx = ShortArray(needIdx)
            shadowIdx = ShortArray(needIdx)
        }
        if (shadeIdx.size < needIdx * 2) shadeIdx = ShortArray(needIdx * 2)
    }
}

/**
 * Cylinder that takes the grabbed corner to [finger].
 * [dir] points from the finger back toward the original corner.
 * The front tangent sits at [pos]; paper distance along [dir] is what curls.
 */
internal data class CurlCylinder(
    val posX: Float,
    val posY: Float,
    val dirX: Float,
    val dirY: Float,
    val axisX: Float,
    val axisY: Float,
    val radius: Float,
)

internal data class CurlSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val back: Boolean,
    val theta: Float,
    val dist: Float,
)

/** Place a cylinder so [corner] lands on [finger] after the roll. */
internal fun cylinderBetween(corner: Offset, finger: Offset, radius: Float): CurlCylinder? {
    val dx = corner.x - finger.x
    val dy = corner.y - finger.y
    val d = hypot(dx, dy)
    if (d < 2f || radius < 1f) return null
    val dirX = dx / d
    val dirY = dy / d
    val distC = (d + PI.toFloat() * radius) * 0.5f
    return CurlCylinder(
        posX = corner.x - dirX * distC,
        posY = corner.y - dirY * distC,
        dirX = dirX,
        dirY = dirY,
        axisX = -dirY,
        axisY = dirX,
        radius = radius,
    )
}

/**
 * Wrap [px],[py] onto [c]. Flat in front of the tangent, a half-cylinder
 * through the roll, then the back settles down onto the page. Neighbouring
 * page points stay neighbours, so the glyphs compress instead of smearing.
 */
internal fun sampleCylinder(px: Float, py: Float, c: CurlCylinder): CurlSample {
    val relX = px - c.posX
    val relY = py - c.posY
    val dist = relX * c.dirX + relY * c.dirY
    val axisC = relX * c.axisX + relY * c.axisY
    if (dist <= 0f) {
        return CurlSample(px, py, 0f, back = false, theta = 0f, dist = dist)
    }
    val r = c.radius
    val arc = PI.toFloat() * r
    val along: Float
    val z: Float
    val theta: Float
    val back: Boolean
    if (dist < arc) {
        theta = dist / r
        along = r * kotlin.math.sin(theta)
        z = r * (1f - kotlin.math.cos(theta))
        back = theta > PI.toFloat() * 0.5f
    } else {
        val extra = dist - arc
        theta = PI.toFloat()
        along = -extra
        z = (2f * r) * exp(-extra / (r * 0.55f))
        back = true
    }
    return CurlSample(
        x = c.posX + c.dirX * along + c.axisX * axisC,
        y = c.posY + c.dirY * along + c.axisY * axisC,
        z = z,
        back = back,
        theta = theta,
        dist = dist,
    )
}

/**
 * Reflect [p] across the line through [origin] in direction [dir] (unit vector).
 */
internal fun curlMirrorPoint(p: Offset, origin: Offset, dir: Offset): Offset {
    val along = (p.x - origin.x) * dir.x + (p.y - origin.y) * dir.y
    val projX = origin.x + along * dir.x
    val projY = origin.y + along * dir.y
    return Offset(projX * 2f - p.x, projY * 2f - p.y)
}

/** Android matrix for [curlMirrorPoint]. */
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
 * Draw one curl frame. The page underneath is already on [canvas].
 * [dragX] / [dragY] are finger travel. Pass `NaN` for [dragX] to derive the
 * pose from [progress]. Screenshots use the mesh; API 33+ tries the same
 * mapping as an AGSL shader and falls back if the runtime rejects it.
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
    renderer: String = "auto",
) {
    if (width < 2f || height < 2f || front.width < 2 || front.height < 2) return
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.012f) {
        scratch.flapPaint.colorFilter = null
        scratch.flapPaint.alpha = 255
        canvas.drawBitmap(front, 0f, 0f, scratch.flapPaint)
        curlDrawPath = "flat"
        return
    }
    if (p >= 0.992f) {
        curlDrawPath = "gone"
        return
    }
    val cylinder = cylinderForPose(
        width, height, p, forward, edge, fromTop, radiusPx, dragX, dragY, fingerY,
    )
    if (cylinder == null) {
        scratch.flapPaint.colorFilter = null
        scratch.flapPaint.alpha = 255
        canvas.drawBitmap(front, 0f, 0f, scratch.flapPaint)
        curlDrawPath = "flat"
        return
    }
    val backBitmap = if (uprightVerso && verso != null) verso else front
    if (renderer == "auto" && Build.VERSION.SDK_INT >= 33 &&
        invokeAgsl(canvas, front, backBitmap, uprightVerso && verso != null, width, height, cylinder, clipLeft, clipTop, clipRight, clipBottom)
    ) {
        curlDrawPath = "agsl"
        return
    }
    val software = renderer == "software"
    drawCurlMesh(
        canvas, front, backBitmap, uprightVerso && verso != null,
        width, height, cylinder, scratch, clipLeft, clipTop, clipRight, clipBottom,
        software = software,
    )
    curlDrawPath = if (software) "software-mesh" else "mesh"
}

internal fun cylinderForPose(
    width: Float,
    height: Float,
    progress: Float,
    forward: Boolean,
    edge: Boolean,
    fromTop: Boolean,
    radiusPx: Float,
    dragX: Float,
    dragY: Float,
    fingerY: Float,
): CurlCylinder? {
    val lift = sin(PI * progress.coerceIn(0f, 1f)).toFloat()
    val base = radiusPx * (0.78f + 0.22f * lift)
    if (edge) {
        val travel = if (dragX.isNaN()) {
            progress.coerceIn(0f, 1f) * width
        } else {
            abs(dragX)
        }
        val radius = min(base, max(travel * 0.45f, 8f))
        val cornerX = if (forward) 0f else width
        val fingerX = if (forward) cornerX + travel else cornerX - travel
        val y = fingerY.coerceIn(0f, height)
        return cylinderBetween(Offset(cornerX, y), Offset(fingerX, y), radius)
    }
    val travel = if (dragX.isNaN()) {
        (if (forward) 1f else -1f) * progress.coerceIn(0f, 1f) * CYLINDER_TRAVEL * width
    } else {
        dragX
    }
    val dy = if (dragX.isNaN()) 0f else dragY
    val apex = cornerApex(width, height, travel, dy, forward, fromTop)
    val corner = Offset(if (forward) 0f else width, if (fromTop) 0f else height)
    val reach = hypot(apex.x - corner.x, apex.y - corner.y)
    val radius = min(base, max(reach * 0.16f, 8f))
    return cylinderBetween(corner, apex, radius)
}

private fun drawCurlMesh(
    canvas: Canvas,
    front: Bitmap,
    back: Bitmap,
    versoIsDistinct: Boolean,
    width: Float,
    height: Float,
    cylinder: CurlCylinder,
    scratch: CurlDrawScratch,
    clipLeft: Float,
    clipTop: Float,
    clipRight: Float,
    clipBottom: Float,
    software: Boolean = false,
) {
    val cols = 80
    val rows = 112
    val stride = cols + 1
    val vertsN = stride * (rows + 1)
    scratch.ensure(vertsN, cols * rows)
    val sx = front.width / width
    val sy = front.height / height
    val backSx = back.width / width
    val backSy = back.height / height
    val camZ = max(width, height) * 2.15f
    val camX = width * 0.5f
    val camY = height * 0.46f

    fun project(x: Float, y: Float, z: Float): Pair<Float, Float> {
        val f = camZ / (camZ - z).coerceAtLeast(1f)
        return (camX + (x - camX) * f) to (camY + (y - camY) * f)
    }

    val faceBack = BooleanArray(vertsN)
    for (row in 0..rows) {
        val py = height * row / rows
        for (col in 0..cols) {
            val px = width * col / cols
            val s = sampleCylinder(px, py, cylinder)
            val (qx, qy) = project(s.x, s.y, s.z)
            val i = row * stride + col
            val o = i * 2
            scratch.verts[o] = qx
            scratch.verts[o + 1] = qy
            scratch.texs[o] = px * sx
            scratch.texs[o + 1] = py * sy
            scratch.backTexs[o] = if (versoIsDistinct) (width - px) * backSx else px * sx
            scratch.backTexs[o + 1] = py * if (versoIsDistinct) backSy else sy
            scratch.colors[i] = curlShadeOverlay(s)
            scratch.light[i] = curlLightFactor(s)
            faceBack[i] = s.back
            // Shadow sits on the page the leaf lifted off, darkest by the roll.
            val shade = if (s.dist > 1f) {
                val near = exp(-s.dist / (cylinder.radius * 1.15f))
                val lift = (s.z / (cylinder.radius * 2f)).coerceIn(0f, 1f)
                ((120f * near + 70f * lift) * (0.55f + 0.45f * sin(PI * (s.theta / PI.toFloat()).coerceIn(0f, 1f)).toFloat()))
                    .toInt().coerceIn(0, 170)
            } else {
                0
            }
            val (sx0, sy0) = project(px + 2.5f, py + 7f, 0f)
            scratch.shadowVerts[o] = sx0
            scratch.shadowVerts[o + 1] = sy0
            scratch.shadowColors[i] = Color.argb(shade, 0, 0, 0)
        }
    }

    var frontCount = 0
    var backCount = 0
    var shadowCount = 0
    val limit = max(width, height) * 0.08f
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val i00 = row * stride + col
            val i10 = i00 + 1
            val i01 = i00 + stride
            val i11 = i01 + 1
            if (!quadSane(scratch.verts, i00, i10, i01, i11, limit)) continue
            val anyBack = faceBack[i00] || faceBack[i10] || faceBack[i01] || faceBack[i11]
            val anyFront = !faceBack[i00] || !faceBack[i10] || !faceBack[i01] || !faceBack[i11]
            if (anyFront) frontCount = writeQuad(scratch.frontIdx, frontCount, i00, i10, i01, i11)
            if (anyBack) backCount = writeQuad(scratch.backIdx, backCount, i00, i10, i01, i11)
            if (scratch.shadowColors[i00] > 0 || scratch.shadowColors[i11] > 0) {
                shadowCount = writeQuad(scratch.shadowIdx, shadowCount, i00, i10, i01, i11)
            }
        }
    }

    if (software) {
        // Robolectric's Skia paints drawBitmapMesh and drawVertices a flat green.
        // Rasterize these vertices on the CPU; the device path below is drawBitmapMesh.
        rasterizeCurl(
            canvas, front, scratch.verts, scratch.light, cols, rows, stride,
            width, height, clipLeft, clipTop, clipRight, clipBottom,
        )
        return
    }
    canvas.save()
    canvas.clipRect(clipLeft - cylinder.radius, clipTop - cylinder.radius, clipRight + cylinder.radius, clipBottom + cylinder.radius)
    if (shadowCount > 0) {
        scratch.shadowPaint.shader = null
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            vertsN,
            scratch.shadowVerts, 0,
            null, 0,
            scratch.shadowColors, 0,
            scratch.shadowIdx, 0,
            shadowCount,
            scratch.shadowPaint,
        )
    }
    val paint = scratch.meshPaint
    paint.shader = null
    canvas.drawBitmapMesh(front, cols, rows, scratch.verts, 0, null, 0, paint)
    scratch.shadowPaint.shader = null
    val shadeCount = frontCount + backCount
    if (shadeCount > 0 && shadeCount <= scratch.shadeIdx.size) {
        System.arraycopy(scratch.frontIdx, 0, scratch.shadeIdx, 0, frontCount)
        System.arraycopy(scratch.backIdx, 0, scratch.shadeIdx, frontCount, backCount)
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            vertsN,
            scratch.verts, 0,
            null, 0,
            scratch.colors, 0,
            scratch.shadeIdx, 0,
            shadeCount,
            scratch.shadowPaint,
        )
    }
    canvas.restore()
}

/** Translucent ink laid over the warped page: dark in the fold, clear on the ridge. */
private fun curlShadeOverlay(s: CurlSample): Int {
    if (!s.back && s.dist <= 0f) {
        val band = 56f
        val t = if (s.dist > -band) (s.dist + band) / band else 0f
        return Color.argb((t * 70f).toInt(), 20, 16, 10)
    }
    val theta = s.theta
    val ridge = exp(-((theta - PI.toFloat() * 0.5f) * (theta - PI.toFloat() * 0.5f)) / 0.045f)
    val nz = abs(cos(theta))
    val valley = (1f - nz) * if (s.back) 0.55f else 0.72f
    val alpha = ((valley * (1f - ridge) + if (s.back) 0.28f else 0.08f) * 220f).toInt().coerceIn(0, 210)
    val highlight = (ridge * if (s.back) 40f else 150f).toInt().coerceIn(0, 180)
    return if (highlight > alpha) {
        Color.argb(highlight, 255, 250, 240)
    } else {
        Color.argb(alpha, 28, 20, 12)
    }
}

private fun quadSane(verts: FloatArray, a: Int, b: Int, c: Int, d: Int, limit: Float): Boolean {
    fun dist(i: Int, j: Int): Float {
        val dx = verts[i * 2] - verts[j * 2]
        val dy = verts[i * 2 + 1] - verts[j * 2 + 1]
        return hypot(dx, dy)
    }
    val m = max(max(dist(a, b), dist(a, c)), max(dist(b, d), dist(c, d)))
    return m < limit && m.isFinite()
}

private fun writeQuad(dst: ShortArray, at: Int, i00: Int, i10: Int, i01: Int, i11: Int): Int {
    dst[at] = i00.toShort()
    dst[at + 1] = i10.toShort()
    dst[at + 2] = i01.toShort()
    dst[at + 3] = i10.toShort()
    dst[at + 4] = i11.toShort()
    dst[at + 5] = i01.toShort()
    return at + 6
}

/**
 * Load the API 33 shader class only on devices that have [RuntimeShader].
 * Referencing that class directly would fail verification on API 21.
 */
private fun invokeAgsl(
    canvas: Canvas,
    front: Bitmap,
    back: Bitmap,
    versoIsDistinct: Boolean,
    width: Float,
    height: Float,
    cylinder: CurlCylinder,
    clipLeft: Float,
    clipTop: Float,
    clipRight: Float,
    clipBottom: Float,
): Boolean {
    return try {
        val cls = Class.forName("io.github.sherifshabans.mushaf.MushafCurlAgsl")
        val instance = cls.getField("INSTANCE").get(null)
        val method = cls.declaredMethods.first { it.name == "draw" }
        method.invoke(
            instance,
            canvas, front, back, versoIsDistinct, width, height, cylinder,
            clipLeft, clipTop, clipRight, clipBottom,
        ) as Boolean
    } catch (_: Throwable) {
        false
    }
}

/** 1 = the page colour, lower = the inner fold. */
private fun curlLightFactor(s: CurlSample): Float {
    if (!s.back && s.dist <= 0f) {
        val band = 56f
        val t = if (s.dist > -band) (s.dist + band) / band else 0f
        return 1f - 0.18f * t
    }
    val theta = s.theta
    val ridge = exp(-((theta - PI.toFloat() * 0.5f) * (theta - PI.toFloat() * 0.5f)) / 0.04f)
    val nz = abs(cos(theta))
    val base = if (s.back) 0.62f + 0.2f * nz else 0.38f + 0.62f * nz
    val spec = ridge * if (s.back) 0.12f else 0.42f
    return (base + spec).coerceIn(0.22f, 1.15f)
}

/**
 * CPU rasterizer of the cylinder mesh. Same vertices as [Canvas.drawBitmapMesh],
 * bilinear samples, used for screenshots because Robolectric's mesh sampler
 * does not return the bitmap's texels.
 */
private fun rasterizeCurl(
    canvas: Canvas,
    bitmap: Bitmap,
    verts: FloatArray,
    light: FloatArray,
    cols: Int,
    rows: Int,
    stride: Int,
    width: Float,
    height: Float,
    clipLeft: Float,
    clipTop: Float,
    clipRight: Float,
    clipBottom: Float,
) {
    val src = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(src, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val left = kotlin.math.floor(clipLeft.toDouble()).toInt()
    val top = kotlin.math.floor(clipTop.toDouble()).toInt()
    val right = kotlin.math.ceil(clipRight.toDouble()).toInt().coerceAtLeast(left + 1)
    val bottom = kotlin.math.ceil(clipBottom.toDouble()).toInt().coerceAtLeast(top + 1)
    val bw = right - left
    val bh = bottom - top
    val pixels = IntArray(bw * bh)
    val sx = bitmap.width / width
    val sy = bitmap.height / height
    fun drawTri(i0: Int, i1: Int, i2: Int) {
        val x0 = verts[i0 * 2]; val y0 = verts[i0 * 2 + 1]
        val x1 = verts[i1 * 2]; val y1 = verts[i1 * 2 + 1]
        val x2 = verts[i2 * 2]; val y2 = verts[i2 * 2 + 1]
        val denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (abs(denom) < 0.25f) return
        val minX = max(left, kotlin.math.floor(min(x0, min(x1, x2)).toDouble()).toInt())
        val maxX = min(right - 1, kotlin.math.ceil(max(x0, max(x1, x2)).toDouble()).toInt())
        val minY = max(top, kotlin.math.floor(min(y0, min(y1, y2)).toDouble()).toInt())
        val maxY = min(bottom - 1, kotlin.math.ceil(max(y0, max(y1, y2)).toDouble()).toInt())
        val col0 = i0 % stride
        val row0 = i0 / stride
        val col1 = i1 % stride
        val row1 = i1 / stride
        val col2 = i2 % stride
        val row2 = i2 / stride
        val u0 = width * col0 / cols * sx
        val v0 = height * row0 / rows * sy
        val u1 = width * col1 / cols * sx
        val v1 = height * row1 / rows * sy
        val u2 = width * col2 / cols * sx
        val v2 = height * row2 / rows * sy
        val l0 = light[i0]; val l1 = light[i1]; val l2 = light[i2]
        var y = minY
        while (y <= maxY) {
            var x = minX
            val py = y + 0.5f
            while (x <= maxX) {
                val px = x + 0.5f
                val w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom
                val w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom
                val w2 = 1f - w0 - w1
                if (w0 >= -0.02f && w1 >= -0.02f && w2 >= -0.02f) {
                    val u = (w0 * u0 + w1 * u1 + w2 * u2).coerceIn(0f, bitmap.width - 1.001f)
                    val v = (w0 * v0 + w1 * v1 + w2 * v2).coerceIn(0f, bitmap.height - 1.001f)
                    val lx = (w0 * l0 + w1 * l1 + w2 * l2).coerceIn(0.2f, 1.2f)
                    val ix = u.toInt()
                    val iy = v.toInt()
                    val fx = u - ix
                    val fy = v - iy
                    val i00 = src[iy * bitmap.width + ix]
                    val i10 = src[iy * bitmap.width + min(ix + 1, bitmap.width - 1)]
                    val i01 = src[min(iy + 1, bitmap.height - 1) * bitmap.width + ix]
                    val i11 = src[min(iy + 1, bitmap.height - 1) * bitmap.width + min(ix + 1, bitmap.width - 1)]
                    val r = blend(i00, i10, i01, i11, fx, fy, 16, lx)
                    val g = blend(i00, i10, i01, i11, fx, fy, 8, lx)
                    val b = blend(i00, i10, i01, i11, fx, fy, 0, lx)
                    pixels[(y - top) * bw + (x - left)] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                x++
            }
            y++
        }
    }
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val i00 = row * stride + col
            val i10 = i00 + 1
            val i01 = i00 + stride
            val i11 = i01 + 1
            drawTri(i00, i10, i01)
            drawTri(i10, i11, i01)
        }
    }
    val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, bw, 0, 0, bw, bh)
    canvas.drawBitmap(out, left.toFloat(), top.toFloat(), null)
    out.recycle()
}

private fun blend(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float, shift: Int, light: Float): Int {
    fun ch(c: Int) = (c shr shift) and 0xFF
    val top = ch(c00) + (ch(c10) - ch(c00)) * fx
    val bot = ch(c01) + (ch(c11) - ch(c01)) * fx
    return ((top + (bot - top) * fy) * light).toInt().coerceIn(0, 255)
}

private fun argb(a: Int, r: Float, g: Float, b: Float): Int {
    return (a.coerceIn(0, 255) shl 24) or
        ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
        ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
        (b.coerceIn(0f, 1f) * 255f).toInt()
}
