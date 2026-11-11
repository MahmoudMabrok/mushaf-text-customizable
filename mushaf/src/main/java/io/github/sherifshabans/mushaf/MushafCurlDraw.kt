package io.github.sherifshabans.mushaf

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
    val adaptive = AdaptiveCurlMesh()
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

/** Growable triangle list shared by drawBitmap-style vertices and the CPU rasterizer. */
internal class AdaptiveCurlMesh {
    var n = 0
    var nTri = 0
    var xy = FloatArray(0)
    var uv = FloatArray(0)
    var light = FloatArray(0)
    var back = BooleanArray(0)
    var ox = FloatArray(0)
    var oy = FloatArray(0)
    var dist = FloatArray(0)
    var tris = IntArray(0)
    var packXy = FloatArray(0)
    var packUv = FloatArray(0)
    var packColor = IntArray(0)

    fun clear() {
        n = 0
        nTri = 0
    }

    fun vert(
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        lit: Float,
        isBack: Boolean,
        origX: Float,
        origY: Float,
        sampleDist: Float,
    ): Int {
        if (n + 1 > light.size) {
            val m = (light.size * 2).coerceAtLeast(4096)
            xy = xy.copyOf(m * 2)
            uv = uv.copyOf(m * 2)
            light = light.copyOf(m)
            back = back.copyOf(m)
            ox = ox.copyOf(m)
            oy = oy.copyOf(m)
            dist = dist.copyOf(m)
        }
        val i = n++
        xy[i * 2] = x
        xy[i * 2 + 1] = y
        uv[i * 2] = u
        uv[i * 2 + 1] = v
        light[i] = lit
        back[i] = isBack
        ox[i] = origX
        oy[i] = origY
        dist[i] = sampleDist
        return i
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (nTri + 3 > tris.size) tris = tris.copyOf((tris.size * 2).coerceAtLeast(8192))
        tris[nTri++] = a
        tris[nTri++] = b
        tris[nTri++] = c
    }
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
    val p = progress.coerceIn(0f, 1f)
    val lift = sin(PI * p).toFloat()
    val page = min(width, height)
    if (edge) {
        val travel = if (dragX.isNaN()) p * width else abs(dragX)
        val radius = max(page * (0.05f + 0.08f * lift), radiusPx * 0.55f).coerceAtLeast(8f)
        val cornerX = if (forward) 0f else width
        val fingerX = if (forward) cornerX + travel else cornerX - travel
        val y = fingerY.coerceIn(0f, height)
        return cylinderBetween(Offset(cornerX, y), Offset(fingerX, y), radius)
    }
    // Drag distance and the posed preview share one diagonal. At progress 0.5
    // the finger sits just past the page centre, so the crease covers about half.
    val along = if (dragX.isNaN()) {
        p * CYLINDER_TRAVEL
    } else {
        val travel = if (forward) dragX.coerceAtLeast(0f) else (-dragX).coerceAtLeast(0f)
        (travel / width.coerceAtLeast(1f)).coerceIn(0f, CYLINDER_TRAVEL)
    }
    val corner = Offset(if (forward) 0f else width, if (fromTop) 0f else height)
    val opposite = Offset(if (forward) width else 0f, if (fromTop) height else 0f)
    val finger = Offset(
        corner.x + (opposite.x - corner.x) * along,
        corner.y + (opposite.y - corner.y) * along + if (dragX.isNaN()) 0f else dragY * 0.35f,
    )
    val radius = max(page * (0.07f + 0.20f * lift), radiusPx * (0.85f + 0.6f * lift)).coerceAtLeast(12f)
    return cylinderBetween(corner, finger, radius)
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
    val mesh = scratch.adaptive
    buildAdaptiveMesh(mesh, front, back, versoIsDistinct, width, height, cylinder)
    if (mesh.nTri < 3) return
    if (software) {
        println("[mushaf] adaptive mesh verts=${mesh.n} tris=${mesh.nTri / 3} radius=${cylinder.radius}")
        rasterizeCurl(
            canvas, front, back, versoIsDistinct, mesh, cylinder,
            clipLeft, clipTop, clipRight, clipBottom,
        )
        return
    }
    val pad = cylinder.radius
    canvas.save()
    canvas.clipRect(clipLeft - pad, clipTop - pad, clipRight + pad, clipBottom + pad)
    drawShadowVerts(canvas, mesh, scratch.shadowPaint, cylinder, hole = true, ox = 0f, oy = 0f, scale = 1f)
    drawShadowVerts(
        canvas, mesh, scratch.shadowPaint, cylinder, hole = false,
        ox = cylinder.radius * 0.04f, oy = cylinder.radius * 0.09f, scale = 0.5f,
    )
    val paint = scratch.meshPaint
    paint.xfermode = null
    paint.shader = null
    if (versoIsDistinct && back !== front) {
        drawTextured(canvas, mesh, paint, front, wantBack = false)
        drawTextured(canvas, mesh, paint, back, wantBack = true)
    } else {
        drawTextured(canvas, mesh, paint, front, wantBack = null)
    }
    paint.shader = null
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    drawLightVerts(canvas, mesh, paint)
    paint.xfermode = null
    canvas.restore()
}

/**
 * Coarse page grid, split again wherever the cylinder bends the sheet.
 * Device drawVertices and the CPU rasterizer both consume this triangle list.
 */
private fun buildAdaptiveMesh(
    mesh: AdaptiveCurlMesh,
    front: Bitmap,
    back: Bitmap,
    versoIsDistinct: Boolean,
    width: Float,
    height: Float,
    cylinder: CurlCylinder,
) {
    mesh.clear()
    val cols = 40
    val rows = 56
    val camZ = max(width, height) * 2.15f
    val camX = width * 0.5f
    val camY = height * 0.46f
    val arc = PI.toFloat() * cylinder.radius
    fun project(x: Float, y: Float, z: Float): Pair<Float, Float> {
        val f = camZ / (camZ - z).coerceAtLeast(1f)
        return (camX + (x - camX) * f) to (camY + (y - camY) * f)
    }
    fun emit(x0: Float, y0: Float, x1: Float, y1: Float, depth: Int) {
        val s00 = sampleCylinder(x0, y0, cylinder)
        val s10 = sampleCylinder(x1, y0, cylinder)
        val s01 = sampleCylinder(x0, y1, cylinder)
        val s11 = sampleCylinder(x1, y1, cylinder)
        val p00 = project(s00.x, s00.y, s00.z)
        val p10 = project(s10.x, s10.y, s10.z)
        val p01 = project(s01.x, s01.y, s01.z)
        val p11 = project(s11.x, s11.y, s11.z)
        val maxEdge = maxOf(
            hypot(p00.first - p10.first, p00.second - p10.second),
            hypot(p00.first - p01.first, p00.second - p01.second),
            hypot(p10.first - p11.first, p10.second - p11.second),
            hypot(p01.first - p11.first, p01.second - p11.second),
        )
        val dMin = minOf(s00.dist, s10.dist, s01.dist, s11.dist)
        val dMax = maxOf(s00.dist, s10.dist, s01.dist, s11.dist)
        val onRoll = dMax > -8f && dMin < arc + 10f
        val mixed = (s00.back || s10.back || s01.back || s11.back) &&
            (!s00.back || !s10.back || !s01.back || !s11.back)
        val split = (depth < 2 && onRoll && maxEdge > 3.4f) ||
            (mixed && depth < 3 && maxEdge > 1.8f)
        if (split) {
            val mx = (x0 + x1) * 0.5f
            val my = (y0 + y1) * 0.5f
            emit(x0, y0, mx, my, depth + 1)
            emit(mx, y0, x1, my, depth + 1)
            emit(x0, my, mx, y1, depth + 1)
            emit(mx, my, x1, y1, depth + 1)
            return
        }
        if (maxEdge > max(width, height) * 0.45f || !maxEdge.isFinite()) return
        fun add(px: Float, py: Float, s: CurlSample, q: Pair<Float, Float>): Int {
            val useBack = s.back && versoIsDistinct
            val bmpW = if (useBack) back.width else front.width
            val bmpH = if (useBack) back.height else front.height
            val u = (if (useBack) width - px else px) * bmpW / width
            val v = py * bmpH / height
            return mesh.vert(q.first, q.second, u, v, curlLightFactor(s), s.back, px, py, s.dist)
        }
        val i00 = add(x0, y0, s00, p00)
        val i10 = add(x1, y0, s10, p10)
        val i01 = add(x0, y1, s01, p01)
        val i11 = add(x1, y1, s11, p11)
        mesh.tri(i00, i10, i01)
        mesh.tri(i10, i11, i01)
    }
    val bottom = sampleCylinder(width * 0.5f, height, cylinder).dist
    val top = sampleCylinder(width * 0.5f, 0f, cylinder).dist
    val right = sampleCylinder(width, height * 0.5f, cylinder).dist
    val left = sampleCylinder(0f, height * 0.5f, cylinder).dist
    val rowsFrom = if (bottom >= top) rows - 1 else 0
    val rowStep = if (bottom >= top) -1 else 1
    val colsFrom = if (left >= right) 0 else cols - 1
    val colStep = if (left >= right) 1 else -1
    var row = rowsFrom
    val rowStop = if (rowStep > 0) rows else -1
    while (row != rowStop) {
        val y0 = height * row / rows
        val y1 = height * (row + 1) / rows
        var col = colsFrom
        val colStop = if (colStep > 0) cols else -1
        while (col != colStop) {
            val x0 = width * col / cols
            val x1 = width * (col + 1) / cols
            emit(x0, y0, x1, y1, 0)
            col += colStep
        }
        row += rowStep
    }
}

private fun drawTextured(
    canvas: Canvas,
    mesh: AdaptiveCurlMesh,
    paint: Paint,
    bitmap: Bitmap,
    wantBack: Boolean?,
) {
    val n = packPage(mesh, wantBack)
    if (n < 3) return
    paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    paint.isFilterBitmap = true
    canvas.drawVertices(
        Canvas.VertexMode.TRIANGLES,
        n,
        mesh.packXy, 0,
        mesh.packUv, 0,
        null, 0,
        null, 0, 0,
        paint,
    )
}

private fun drawLightVerts(canvas: Canvas, mesh: AdaptiveCurlMesh, paint: Paint) {
    val count = mesh.nTri
    ensurePack(mesh, count)
    var o = 0
    var i = 0
    while (i < count) {
        val a = mesh.tris[i]
        val b = mesh.tris[i + 1]
        val c = mesh.tris[i + 2]
        i += 3
        o = putVert(mesh, o, a, light = true, hole = false, dx = 0f, dy = 0f, alphaScale = 1f, radius = 1f)
        o = putVert(mesh, o, b, light = true, hole = false, dx = 0f, dy = 0f, alphaScale = 1f, radius = 1f)
        o = putVert(mesh, o, c, light = true, hole = false, dx = 0f, dy = 0f, alphaScale = 1f, radius = 1f)
    }
    if (o < 3) return
    canvas.drawVertices(
        Canvas.VertexMode.TRIANGLES, o, mesh.packXy, 0, null, 0, mesh.packColor, 0, null, 0, 0, paint,
    )
}

private fun drawShadowVerts(
    canvas: Canvas,
    mesh: AdaptiveCurlMesh,
    paint: Paint,
    cylinder: CurlCylinder,
    hole: Boolean,
    ox: Float,
    oy: Float,
    scale: Float,
) {
    paint.shader = null
    paint.xfermode = null
    val count = mesh.nTri
    ensurePack(mesh, count)
    var o = 0
    var i = 0
    while (i < count) {
        val a = mesh.tris[i]
        val b = mesh.tris[i + 1]
        val c = mesh.tris[i + 2]
        i += 3
        val peak = maxOf(mesh.dist[a], mesh.dist[b], mesh.dist[c])
        if (peak <= 0.5f) continue
        o = putVert(mesh, o, a, light = false, hole = hole, dx = ox, dy = oy, alphaScale = scale, radius = cylinder.radius)
        o = putVert(mesh, o, b, light = false, hole = hole, dx = ox, dy = oy, alphaScale = scale, radius = cylinder.radius)
        o = putVert(mesh, o, c, light = false, hole = hole, dx = ox, dy = oy, alphaScale = scale, radius = cylinder.radius)
    }
    if (o < 3) return
    canvas.drawVertices(
        Canvas.VertexMode.TRIANGLES, o, mesh.packXy, 0, null, 0, mesh.packColor, 0, null, 0, 0, paint,
    )
}

/** @return vertices written. [wantBack] null keeps every triangle. */
private fun packPage(mesh: AdaptiveCurlMesh, wantBack: Boolean?): Int {
    val count = mesh.nTri
    ensurePack(mesh, count)
    var o = 0
    var i = 0
    while (i < count) {
        val a = mesh.tris[i]
        val b = mesh.tris[i + 1]
        val c = mesh.tris[i + 2]
        i += 3
        if (wantBack != null) {
            val votes = (if (mesh.back[a]) 1 else 0) + (if (mesh.back[b]) 1 else 0) + (if (mesh.back[c]) 1 else 0)
            if ((votes >= 2) != wantBack) continue
        }
        o = putVert(mesh, o, a, light = false, hole = false, dx = 0f, dy = 0f, alphaScale = 1f, radius = 1f)
        o = putVert(mesh, o, b, light = false, hole = false, dx = 0f, dy = 0f, alphaScale = 1f, radius = 1f)
        o = putVert(mesh, o, c, light = false, hole = false, dx = 0f, dy = 0f, alphaScale = 1f, radius = 1f)
    }
    return o
}

private fun ensurePack(mesh: AdaptiveCurlMesh, verts: Int) {
    if (mesh.packXy.size < verts * 2) {
        val n = (verts * 2).coerceAtLeast(mesh.packXy.size * 2).coerceAtLeast(1024)
        mesh.packXy = FloatArray(n)
        mesh.packUv = FloatArray(n)
        mesh.packColor = IntArray(n / 2)
    }
}

private fun putVert(
    mesh: AdaptiveCurlMesh,
    o: Int,
    v: Int,
    light: Boolean,
    hole: Boolean,
    dx: Float,
    dy: Float,
    alphaScale: Float,
    radius: Float,
): Int {
    val x = if (hole) mesh.ox[v] else mesh.xy[v * 2]
    val y = if (hole) mesh.oy[v] else mesh.xy[v * 2 + 1]
    mesh.packXy[o * 2] = x + dx
    mesh.packXy[o * 2 + 1] = y + dy
    mesh.packUv[o * 2] = mesh.uv[v * 2]
    mesh.packUv[o * 2 + 1] = mesh.uv[v * 2 + 1]
    mesh.packColor[o] = if (light) {
        val lit = mesh.light[v]
        if (mesh.back[v]) argb(255, lit * 0.99f, lit * 0.975f, lit * 0.94f) else argb(255, lit, lit, lit)
    } else if (radius > 2f) {
        val a = (shadowAlpha(mesh.dist[v], radius) * alphaScale).toInt().coerceIn(0, 220)
        Color.argb(a, 0, 0, 0)
    } else {
        Color.WHITE
    }
    return o + 1
}

private fun shadowAlpha(dist: Float, radius: Float): Int {
    if (dist <= 0.4f) return 0
    val tight = exp(-dist / (radius * 0.58f))
    val soft = exp(-dist / (radius * 1.65f))
    return (220f * tight + 90f * soft).toInt().coerceIn(0, 230)
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

/** 1 = the page colour. The back stays nearly opaque; the fold goes dark. */
private fun curlLightFactor(s: CurlSample): Float {
    if (!s.back && s.dist <= 0f) {
        val band = 90f
        val t = if (s.dist > -band) (s.dist + band) / band else 0f
        return 1f - 0.42f * t * t
    }
    val theta = s.theta
    val nz = abs(cos(theta))
    if (s.back) {
        val fold = (1f - nz).coerceIn(0f, 1f)
        return (0.98f - 0.34f * fold * fold).coerceIn(0.62f, 0.99f)
    }
    val ridge = exp(-((theta - PI.toFloat() * 0.5f) * (theta - PI.toFloat() * 0.5f)) / 0.055f)
    return (0.22f + 0.78f * nz + 0.08f * ridge).coerceIn(0.18f, 1f)
}

/**
 * CPU rasterizer of [AdaptiveCurlMesh]. Same triangles, UVs and shadow
 * placement as the drawVertices path. Bilinear samples, with coverage on
 * the silhouette so the frame does not stair-step.
 */
private fun rasterizeCurl(
    canvas: Canvas,
    front: Bitmap,
    back: Bitmap,
    versoIsDistinct: Boolean,
    mesh: AdaptiveCurlMesh,
    cylinder: CurlCylinder,
    clipLeft: Float,
    clipTop: Float,
    clipRight: Float,
    clipBottom: Float,
) {
    val srcF = IntArray(front.width * front.height)
    front.getPixels(srcF, 0, front.width, 0, 0, front.width, front.height)
    val srcB = if (versoIsDistinct && back !== front) {
        IntArray(back.width * back.height).also {
            back.getPixels(it, 0, back.width, 0, 0, back.width, back.height)
        }
    } else {
        srcF
    }
    val pad = cylinder.radius * 0.35f
    val left = kotlin.math.floor((clipLeft - pad).toDouble()).toInt()
    val top = kotlin.math.floor((clipTop - pad).toDouble()).toInt()
    val right = kotlin.math.ceil((clipRight + pad).toDouble()).toInt().coerceAtLeast(left + 1)
    val bottom = kotlin.math.ceil((clipBottom + pad).toDouble()).toInt().coerceAtLeast(top + 1)
    // Two samples per pixel, then a box filter, so the silhouette and the
    // gold frame are not a one-pixel staircase. The mesh is the same one
    // drawVertices uses.
    val ss = 2
    val bw = (right - left) * ss
    val bh = (bottom - top) * ss
    val pixels = IntArray(bw * bh)
    fun over(dst: Int, r: Int, g: Int, b: Int, a: Float): Int {
        if (a >= 0.985f) return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        if (a <= 0.01f) return dst
        val da = ((dst ushr 24) and 0xFF) / 255f
        val inv = 1f - a
        val oa = a + da * inv
        if (oa <= 0.001f) return 0
        val dr = (dst shr 16) and 0xFF
        val dg = (dst shr 8) and 0xFF
        val db = dst and 0xFF
        val rr = ((r * a + dr * da * inv) / oa).toInt().coerceIn(0, 255)
        val gg = ((g * a + dg * da * inv) / oa).toInt().coerceIn(0, 255)
        val bb = ((b * a + db * da * inv) / oa).toInt().coerceIn(0, 255)
        return ((oa * 255f).toInt().coerceIn(0, 255) shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
    fun raster(
        i0: Int,
        i1: Int,
        i2: Int,
        hole: Boolean,
        shadow: Boolean,
        alphaScale: Float,
        shiftX: Float,
        shiftY: Float,
    ) {
        val x0: Float
        val y0: Float
        val x1: Float
        val y1: Float
        val x2: Float
        val y2: Float
        if (hole) {
            x0 = mesh.ox[i0] + shiftX; y0 = mesh.oy[i0] + shiftY
            x1 = mesh.ox[i1] + shiftX; y1 = mesh.oy[i1] + shiftY
            x2 = mesh.ox[i2] + shiftX; y2 = mesh.oy[i2] + shiftY
        } else {
            x0 = mesh.xy[i0 * 2] + shiftX; y0 = mesh.xy[i0 * 2 + 1] + shiftY
            x1 = mesh.xy[i1 * 2] + shiftX; y1 = mesh.xy[i1 * 2 + 1] + shiftY
            x2 = mesh.xy[i2 * 2] + shiftX; y2 = mesh.xy[i2 * 2 + 1] + shiftY
        }
        val denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (abs(denom) < 0.2f) return
        val minX = max(0, kotlin.math.floor(((min(x0, min(x1, x2)) - left) * ss).toDouble()).toInt() - 1)
        val maxX = min(bw - 1, kotlin.math.ceil(((max(x0, max(x1, x2)) - left) * ss).toDouble()).toInt() + 1)
        val minY = max(0, kotlin.math.floor(((min(y0, min(y1, y2)) - top) * ss).toDouble()).toInt() - 1)
        val maxY = min(bh - 1, kotlin.math.ceil(((max(y0, max(y1, y2)) - top) * ss).toDouble()).toInt() + 1)
        if (minX > maxX || minY > maxY) return
        val votes = (if (mesh.back[i0]) 1 else 0) + (if (mesh.back[i1]) 1 else 0) + (if (mesh.back[i2]) 1 else 0)
        val isBack = votes >= 2
        val bitmap = if (isBack && versoIsDistinct) back else front
        val src = if (isBack && versoIsDistinct) srcB else srcF
        val u0 = mesh.uv[i0 * 2]; val v0 = mesh.uv[i0 * 2 + 1]
        val u1 = mesh.uv[i1 * 2]; val v1 = mesh.uv[i1 * 2 + 1]
        val u2 = mesh.uv[i2 * 2]; val v2 = mesh.uv[i2 * 2 + 1]
        val l0 = mesh.light[i0]; val l1 = mesh.light[i1]; val l2 = mesh.light[i2]
        val a0 = shadowAlpha(mesh.dist[i0], cylinder.radius) * alphaScale
        val a1 = shadowAlpha(mesh.dist[i1], cylinder.radius) * alphaScale
        val a2 = shadowAlpha(mesh.dist[i2], cylinder.radius) * alphaScale
        var y = minY
        while (y <= maxY) {
            var x = minX
            val py = top + (y + 0.5f) / ss
            while (x <= maxX) {
                val px = left + (x + 0.5f) / ss
                val w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom
                val w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom
                val w2 = 1f - w0 - w1
                val minW = minOf(w0, w1, w2)
                if (minW >= -0.25f) {
                    val inside = minW >= -0.05f
                    val cover = if (inside) 1f else ((minW + 0.25f) / 0.2f).coerceIn(0f, 1f)
                    if (cover > 0.04f) {
                        val idx = y * bw + x
                        val occupied = (pixels[idx] ushr 24) > 240
                        if (!inside && occupied) {
                            // leave an opaque page pixel alone
                        } else if (shadow) {
                            val sa = ((w0 * a0 + w1 * a1 + w2 * a2).coerceIn(0f, 220f)) / 255f * cover
                            pixels[idx] = over(pixels[idx], 0, 0, 0, sa)
                        } else {
                            val u = (w0 * u0 + w1 * u1 + w2 * u2).coerceIn(0f, bitmap.width - 1.001f)
                            val v = (w0 * v0 + w1 * v1 + w2 * v2).coerceIn(0f, bitmap.height - 1.001f)
                            val lit = (w0 * l0 + w1 * l1 + w2 * l2).coerceIn(0.15f, 1f)
                            val warm = isBack
                            val lr = if (warm) lit * 0.99f else lit
                            val lg = if (warm) lit * 0.975f else lit
                            val lb = if (warm) lit * 0.94f else lit
                            val ix = u.toInt()
                            val iy = v.toInt()
                            val fx = u - ix
                            val fy = v - iy
                            val row1 = min(iy + 1, bitmap.height - 1)
                            val col1 = min(ix + 1, bitmap.width - 1)
                            val i00 = src[iy * bitmap.width + ix]
                            val i10 = src[iy * bitmap.width + col1]
                            val i01 = src[row1 * bitmap.width + ix]
                            val i11 = src[row1 * bitmap.width + col1]
                            val r = (sample(i00, i10, i01, i11, fx, fy, 16) * lr).toInt().coerceIn(0, 255)
                            val g = (sample(i00, i10, i01, i11, fx, fy, 8) * lg).toInt().coerceIn(0, 255)
                            val b = (sample(i00, i10, i01, i11, fx, fy, 0) * lb).toInt().coerceIn(0, 255)
                            pixels[idx] = over(pixels[idx], r, g, b, cover)
                        }
                    }
                }
                x++
            }
            y++
        }
    }
    var i = 0
    while (i < mesh.nTri) {
        val a = mesh.tris[i]
        val b = mesh.tris[i + 1]
        val c = mesh.tris[i + 2]
        i += 3
        val peak = maxOf(mesh.dist[a], mesh.dist[b], mesh.dist[c])
        if (peak > 0.5f) {
            raster(a, b, c, hole = true, shadow = true, alphaScale = 1f, shiftX = 0f, shiftY = 0f)
            raster(
                a, b, c, hole = false, shadow = true, alphaScale = 0.55f,
                shiftX = cylinder.radius * 0.045f, shiftY = cylinder.radius * 0.1f,
            )
        }
    }
    i = 0
    while (i < mesh.nTri) {
        raster(
            mesh.tris[i], mesh.tris[i + 1], mesh.tris[i + 2],
            hole = false, shadow = false, alphaScale = 1f, shiftX = 0f, shiftY = 0f,
        )
        i += 3
    }
    val ow = right - left
    val oh = bottom - top
    val down = IntArray(ow * oh)
    var y = 0
    while (y < oh) {
        var x = 0
        while (x < ow) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var sy = 0
            while (sy < ss) {
                var sx = 0
                val row = (y * ss + sy) * bw + x * ss
                while (sx < ss) {
                    val p = pixels[row + sx]
                    val pa = (p ushr 24) and 0xFF
                    a += pa
                    r += ((p shr 16) and 0xFF) * pa
                    g += ((p shr 8) and 0xFF) * pa
                    b += (p and 0xFF) * pa
                    sx++
                }
                sy++
            }
            if (a > 0) {
                val aa = a / (ss * ss)
                down[y * ow + x] = (aa shl 24) or ((r / a) shl 16) or ((g / a) shl 8) or (b / a)
            }
            x++
        }
        y++
    }
    val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
    out.setPixels(down, 0, ow, 0, 0, ow, oh)
    canvas.drawBitmap(out, left.toFloat(), top.toFloat(), null)
    out.recycle()
}

private fun sample(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float, shift: Int): Float {
    fun ch(c: Int) = ((c shr shift) and 0xFF).toFloat()
    val top = ch(c00) + (ch(c10) - ch(c00)) * fx
    val bot = ch(c01) + (ch(c11) - ch(c01)) * fx
    return top + (bot - top) * fy
}

private fun argb(a: Int, r: Float, g: Float, b: Float): Int {
    return (a.coerceIn(0, 255) shl 24) or
        ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
        ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
        (b.coerceIn(0f, 1f) * 255f).toInt()
}
