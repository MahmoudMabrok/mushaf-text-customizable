package io.github.sherifshabans.mushaf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders the cylindrical curl at fixed progress values and a before/after
 * grid against the 1.7.0 fold. Bitmaps are written for visual review:
 *
 *     ./gradlew :mushaf:testDebugUnitTest --tests '*MushafCurlFrames*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class MushafCurlFrames {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("/opt/cursor/artifacts").apply { mkdirs() }

    @Test
    fun mirrorMatrixMatchesTheAlgebra() {
        val origin = androidx.compose.ui.geometry.Offset(30f, 8f)
        val dir = androidx.compose.ui.geometry.Offset(0f, 1f)
        val point = androidx.compose.ui.geometry.Offset(10f, 40f)
        val expected = curlMirrorPoint(point, origin, dir)
        val pts = floatArrayOf(point.x, point.y)
        curlMirrorMatrix(origin, dir).mapPoints(pts)
        assertEquals(expected.x, pts[0], 0.05f)
        assertEquals(expected.y, pts[1], 0.05f)
    }

    @Test
    fun curlSamplesFromCornerAndEdge() {
        val pages = renderPages(2, 3)
        val front = pages.getValue(2)
        val under = pages.getValue(3)
        assertTrue("page bitmap is empty", front.width > 200 && front.height > 400)
        val density = rule.activity.resources.displayMetrics.density
        val progress = listOf(0.15f, 0.35f, 0.5f, 0.75f, 0.9f)
        val cells = ArrayList<Bitmap>(20)
        val labels = ArrayList<String>(20)

        for (edge in listOf(false, true)) {
            val kind = if (edge) "edge" else "corner"
            for (p in progress) {
                val legacy = renderCurl(front, under, p, edge, density, modern = false)
                val modern = renderCurl(front, under, p, edge, density, modern = true)
                val name = "curl-$kind-${label(p)}.png"
                writePng(modern, File(outDir, name))
                cells += legacy
                cells += modern
                labels += "$kind ${label(p)} 1.7.0"
                labels += "$kind ${label(p)} 1.7.1"
                assertTrue("$name is tiny", File(outDir, name).length() > 8_000)
            }
        }

        val cornerMid = renderCurl(front, under, 0.5f, edge = false, density, modern = true)
        val edgeMid = renderCurl(front, under, 0.5f, edge = true, density, modern = true)
        val legacyMid = renderCurl(front, under, 0.5f, edge = false, density, modern = false)
        assertTrue(
            "corner and edge curls look the same",
            meanAbsDiff(cornerMid, edgeMid) > 6f,
        )
        assertTrue(
            "1.7.1 corner curl matches the 1.7.0 fold",
            meanAbsDiff(cornerMid, legacyMid) > 4f,
        )
        assertSpineStillFront(front, edgeMid)
        val edgeEarly = renderCurl(front, under, 0.15f, edge = true, density, modern = true)
        val edgeLate = renderCurl(front, under, 0.9f, edge = true, density, modern = true)
        assertTrue(
            "edge curl did not travel between 0.15 and 0.9",
            meanAbsDiff(edgeEarly, edgeLate) > 8f,
        )
        assertTrue(
            "cylinder has no light-to-dark roll",
            lipContrast(edgeMid) > 18,
        )

        writePng(grid(cells, labels, columns = 2), File(outDir, "curl-before-after.png"))
        assertTrue(File(outDir, "curl-before-after.png").length() > 8_000)
    }

    @Test
    fun cylinderCornerOnAPageAndASpread() {
        val pages = renderPages(2, 3, 4)
        val front = pages.getValue(2)
        val under = pages.getValue(3)
        val facing = pages.getValue(4)
        val density = rule.activity.resources.displayMetrics.density
        println("[mushaf] screenshots use the CPU rasterizer of the cylinder mesh; Robolectric drawBitmapMesh returns garbage texels")
        for (p in listOf(0.25f, 0.5f, 0.75f)) {
            val single = renderCurl(front, under, p, edge = false, density, modern = true)
            writePng(single, File(outDir, "curl-mesh-single-${label(p)}.png"))
            assertTrue(File(outDir, "curl-mesh-single-${label(p)}.png").length() > 8_000)
            val spread = renderSpread(front, facing, under, p, density)
            writePng(spread, File(outDir, "curl-mesh-spread-${label(p)}.png"))
            assertTrue(File(outDir, "curl-mesh-spread-${label(p)}.png").length() > 8_000)
        }
        val ours = renderCurl(front, under, 0.5f, edge = false, density, modern = true)
        writePng(sideBySide(ours), File(outDir, "curl-mesh-vs-reference.png"))
        assertEquals("software-mesh", curlDrawPath)
    }

    private fun renderSpread(
        curlPage: Bitmap,
        facing: Bitmap,
        under: Bitmap,
        progress: Float,
        density: Float,
    ): Bitmap {
        val w = curlPage.width
        val h = curlPage.height
        val out = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(245, 236, 214))
        canvas.drawBitmap(under, 0f, 0f, null)
        canvas.drawBitmap(facing, w.toFloat(), 0f, null)
        drawRealisticCurl(
            canvas = canvas,
            front = curlPage,
            width = w.toFloat(),
            height = h.toFloat(),
            progress = progress,
            forward = true,
            edge = false,
            fromTop = false,
            radiusPx = 52f * density,
            scratch = CurlDrawScratch(),
            fingerY = h * 0.9f,
            clipRight = w * 2f,
            renderer = "software",
        )
        return out
    }

    private fun sideBySide(ours: Bitmap): Bitmap {
        val refFile = listOf(
            File("/workspace/curl-ref/reference.jpg"),
            File("/opt/cursor/artifacts/curl-reference.jpg"),
        ).firstOrNull { it.isFile }
        val ref = refFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val h = 900
        val leftW = (ours.width * (h / ours.height.toFloat())).toInt().coerceAtLeast(1)
        val right = ref?.let {
            val s = h / it.height.toFloat()
            Bitmap.createScaledBitmap(it, (it.width * s).toInt().coerceAtLeast(1), h, true)
        }
        val rightW = right?.width ?: leftW
        val grid = Bitmap.createBitmap(leftW + rightW + 28, h + 52, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grid)
        canvas.drawColor(Color.rgb(24, 24, 24))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
        }
        canvas.drawText("mesh 0.50", 12f, 30f, text)
        canvas.drawBitmap(Bitmap.createScaledBitmap(ours, leftW, h, true), 8f, 42f, null)
        if (right != null) {
            canvas.drawText("reference", leftW + 20f, 30f, text)
            canvas.drawBitmap(right, leftW + 16f, 42f, null)
        } else {
            canvas.drawText("reference file missing", leftW + 20f, 80f, text)
        }
        return grid
    }

    private fun renderPages(vararg pages: Int): Map<Int, Bitmap> {
        val shown = mutableStateOf(pages.first())
        rule.setContent {
            val page = shown.value
            Box(Modifier.fillMaxSize().background(MushafColors.Light.paper)) {
                MushafPage(
                    ayahs = TestData.ayahs.filter { it.page == page },
                    modifier = Modifier.padding(8.dp),
                    colors = MushafColors.Light,
                    decoration = MushafDecoration.Madinah,
                )
            }
        }
        return pages.associateWith { page ->
            rule.runOnUiThread { shown.value = page }
            rule.waitForIdle()
            capture()
        }
    }

    private fun capture(): Bitmap {
        val view = rule.activity.findViewById<View>(android.R.id.content)
        rule.waitForIdle()
        assertTrue("content view has no size", view.width > 50 && view.height > 50)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun renderCurl(
        front: Bitmap,
        under: Bitmap,
        progress: Float,
        edge: Boolean,
        density: Float,
        modern: Boolean,
    ): Bitmap {
        val out = Bitmap.createBitmap(front.width, front.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(under, 0f, 0f, null)
        if (modern) {
            drawRealisticCurl(
                canvas = canvas,
                front = front,
                width = front.width.toFloat(),
                height = front.height.toFloat(),
                progress = progress,
                forward = true,
                edge = edge,
                fromTop = false,
                radiusPx = (if (edge) 36f else 52f) * density,
                scratch = CurlDrawScratch(),
                fingerY = if (edge) front.height * 0.5f else front.height * 0.9f,
                renderer = "software",
            )
        } else {
            drawLegacyCurl(canvas, front, progress, edge, density)
        }
        return out
    }

    /** The 1.7.0 geometric fold, drawn on the same page bitmap. */
    private fun drawLegacyCurl(
        canvas: Canvas,
        front: Bitmap,
        progress: Float,
        edge: Boolean,
        density: Float,
    ) {
        val w = front.width.toFloat()
        val h = front.height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (edge) {
            val curl = edgeCurl(w, progress, forward = true, maxBack = 22f * density, maxShadow = 36f * density)
            val clip = Path().apply {
                moveTo(curl.boundary, 0f)
                quadTo(curl.boundary + curl.bow, h * 0.5f, curl.boundary, h)
                lineTo(w, h)
                lineTo(w, 0f)
                close()
            }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(front, 0f, 0f, paint)
            canvas.restore()
            if (curl.settled || curl.back <= 0.5f) return
            val curve = Path().apply {
                moveTo(curl.boundary, 0f)
                quadTo(curl.boundary + curl.bow, h * 0.5f, curl.boundary, h)
            }
            val shade = (0.32f * sin(Math.PI * progress)).toFloat()
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.BUTT
                strokeWidth = curl.back + curl.shadow
                color = Color.argb((shade * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
            }
            canvas.drawPath(curve, stroke)
            stroke.strokeWidth = curl.back
            stroke.shader = LinearGradient(
                curl.boundary - curl.back, 0f, curl.boundary, 0f,
                intArrayOf(0xFFE7D7C4.toInt(), 0xFFB7A28C.toInt(), 0xFF6E5A48.toInt()),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(curve, stroke)
            stroke.shader = null
            stroke.color = Color.WHITE
            stroke.alpha = (0.28f * sin(Math.PI * progress) * 255f).toInt().coerceIn(0, 255)
            stroke.strokeWidth = 1.5f * density
            canvas.drawPath(curve, stroke)
        } else {
            val fold = cornerFoldAtProgress(w, h, progress, forward = true, fromTop = false)
            if (!fold.curled || fold.flat.size < 3) {
                canvas.drawBitmap(front, 0f, 0f, paint)
                return
            }
            canvas.save()
            canvas.clipPath(polygon(fold.flat))
            canvas.drawBitmap(front, 0f, 0f, paint)
            canvas.restore()
            if (fold.back.size < 3) return
            val n = fold.revealedNormal
            val shadowW = 28f * density
            val shadow = Path().apply {
                moveTo(fold.creaseA.x, fold.creaseA.y)
                lineTo(fold.creaseB.x, fold.creaseB.y)
                lineTo(fold.creaseB.x + n.x * shadowW, fold.creaseB.y + n.y * shadowW)
                lineTo(fold.creaseA.x + n.x * shadowW, fold.creaseA.y + n.y * shadowW)
                close()
            }
            val midX = (fold.creaseA.x + fold.creaseB.x) * 0.5f
            val midY = (fold.creaseA.y + fold.creaseB.y) * 0.5f
            canvas.drawPath(
                shadow,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        midX, midY, midX + n.x * shadowW, midY + n.y * shadowW,
                        intArrayOf(0x61000000, 0x00000000),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                },
            )
            canvas.drawPath(
                polygon(fold.back),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        midX, midY, fold.apex.x, fold.apex.y,
                        intArrayOf(0xFF5C4A3A.toInt(), 0xFFB7A28C.toInt(), 0xFFE7D7C4.toInt()),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                },
            )
            canvas.drawLine(
                fold.creaseA.x, fold.creaseA.y, fold.creaseB.x, fold.creaseB.y,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(102, 0, 0, 0)
                    strokeWidth = 2f
                },
            )
        }
    }

    private fun assertSpineStillFront(front: Bitmap, curled: Bitmap) {
        // A patch on the spine, not one glyph pixel: bilinear mesh samples shift a hair.
        var sum = 0
        var n = 0
        val x0 = front.width - 70
        val y0 = 40
        for (y in y0 until y0 + 36 step 4) {
            for (x in x0 until x0 + 40 step 4) {
                sum += pixelDelta(front.getPixel(x, y), curled.getPixel(x, y))
                n++
            }
        }
        val mean = sum / n.toFloat()
        assertTrue("spine moved or was covered, mean delta=$mean", mean < 40f)
    }

    /** Luminance spread along the mid-height lip. A flat stroke stays narrow. */
    private fun lipContrast(curled: Bitmap): Int {
        val y = curled.height / 2
        var min = 255
        var max = 0
        val x0 = (curled.width * 0.42f).toInt()
        val x1 = (curled.width * 0.62f).toInt()
        for (x in x0 until x1) {
            val c = curled.getPixel(x, y)
            val lum = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
            if (lum < min) min = lum
            if (lum > max) max = lum
        }
        return max - min
    }

    private fun grid(cells: List<Bitmap>, labels: List<String>, columns: Int): Bitmap {
        val cellW = 180
        val cellH = 300
        val rows = (cells.size + columns - 1) / columns
        val labelH = 28
        val grid = Bitmap.createBitmap(columns * cellW, rows * (cellH + labelH), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grid)
        canvas.drawColor(Color.rgb(32, 32, 32))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
        }
        cells.forEachIndexed { i, cell ->
            val col = i % columns
            val row = i / columns
            val left = col * cellW
            val top = row * (cellH + labelH)
            canvas.drawText(labels[i], left + 8f, top + 20f, text)
            val scaled = Bitmap.createScaledBitmap(cell, cellW - 8, cellH - 8, true)
            canvas.drawBitmap(scaled, left + 4f, top + labelH.toFloat(), null)
            scaled.recycle()
        }
        return grid
    }

    private fun meanAbsDiff(a: Bitmap, b: Bitmap): Float {
        var sum = 0.0
        var n = 0
        val stepX = (a.width / 40).coerceAtLeast(1)
        val stepY = (a.height / 40).coerceAtLeast(1)
        var y = 0
        while (y < a.height && y < b.height) {
            var x = 0
            while (x < a.width && x < b.width) {
                sum += pixelDelta(a.getPixel(x, y), b.getPixel(x, y))
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    private fun pixelDelta(a: Int, b: Int): Int {
        return abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))
    }

    private fun polygon(points: List<androidx.compose.ui.geometry.Offset>): Path {
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        path.close()
        return path
    }

    private fun label(progress: Float) = "%03d".format((progress * 100).roundToInt())

    private fun writePng(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("[mushaf] ${file.absolutePath} ${bitmap.width}x${bitmap.height} ${file.length()}")
    }
}
