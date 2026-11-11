package io.github.sherifshabans.mushaf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Posed frames of [PageTurn.Curl]. Drawing is oleksandrbalan's pagecurl,
 * captured here by Robolectric's native Skia. Paparazzi is not used.
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
    fun curlAtQuarterHalfAndThreeQuarters() {
        val pose = mutableStateOf(MushafTurnPreview(0.02f))
        val spread = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalMushafTurnPreview provides pose.value) {
                val pager = rememberMushafPagerState(initialPage = 2)
                Box(Modifier.fillMaxSize().background(MushafColors.Light.paper)) {
                    MushafPageCurl(
                        state = pager,
                        colors = MushafColors.Light,
                        spread = spread.value,
                        modifier = Modifier.fillMaxSize(),
                        pageContent = { index ->
                            MushafPage(
                                ayahs = TestData.ayahs.filter { it.page == index + 1 },
                                modifier = Modifier.padding(8.dp),
                                colors = MushafColors.Light,
                                decoration = MushafDecoration.Madinah,
                            )
                        },
                    )
                }
            }
        }
        println("[mushaf] curl frames are Robolectric native Skia of io.github.oleksandrbalan:pagecurl")
        for (p in listOf(0.25f, 0.5f, 0.75f)) {
            rule.runOnUiThread { pose.value = MushafTurnPreview(p) }
            rule.waitForIdle()
            val single = capture()
            writePng(single, File(outDir, "curl-mesh-single-${label(p)}.png"))
            assertTrue(File(outDir, "curl-mesh-single-${label(p)}.png").length() > 8_000)
            if (p == 0.5f) writePng(sideBySide(single), File(outDir, "curl-mesh-vs-reference.png"))
        }
        rule.runOnUiThread { spread.value = true }
        for (p in listOf(0.25f, 0.5f, 0.75f)) {
            rule.runOnUiThread { pose.value = MushafTurnPreview(p) }
            rule.waitForIdle()
            writePng(capture(), File(outDir, "curl-mesh-spread-${label(p)}.png"))
            assertTrue(File(outDir, "curl-mesh-spread-${label(p)}.png").length() > 8_000)
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

    private fun sideBySide(ours: Bitmap): Bitmap {
        val refFile = listOf(
            File("/workspace/curl-ref/reference.jpg"),
            File("/opt/cursor/artifacts/curl-reference.jpg"),
            File("/home/ubuntu/.cursor/projects/workspace/uploads/reference_cf71.jpg"),
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
        canvas.drawText("pagecurl 0.50", 12f, 30f, text)
        canvas.drawBitmap(Bitmap.createScaledBitmap(ours, leftW, h, true), 8f, 42f, null)
        if (right != null) {
            canvas.drawText("reference", leftW + 20f, 30f, text)
            canvas.drawBitmap(right, leftW + 16f, 42f, null)
        } else {
            canvas.drawText("reference file missing", leftW + 20f, 80f, text)
        }
        return grid
    }

    private fun label(progress: Float): String = "%03d".format((progress * 100).toInt())

    private fun writePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
