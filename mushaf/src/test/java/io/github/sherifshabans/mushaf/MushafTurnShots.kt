package io.github.sherifshabans.mushaf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Mid-turn frames for curl, flip, and slide. Progress is pinned at 0.25, 0.5,
 * and 0.75 — curl and flip through [MushafTurnPreview], slide by scrolling the
 * real pager — and the bitmaps are written for the release notes.
 *
 *     ./gradlew :mushaf:testDebugUnitTest --tests '*MushafTurnShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h720dp-xhdpi")
class MushafTurnShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("/opt/cursor/artifacts").apply { mkdirs() }

    @Test
    fun curlAtQuarterHalfAndThreeQuarters() {
        capturePosed(PageTurn.Curl, "curl")
    }

    @Test
    fun flipAtQuarterHalfAndThreeQuarters() {
        capturePosed(PageTurn.Flip, "flip")
    }

    @Test
    fun slideAtQuarterHalfAndThreeQuarters() {
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        rule.setContent {
            scope = rememberCoroutineScope()
            pager = rememberMushafPagerState(initialPage = 2)
            MushafPager(
                modifier = Modifier.fillMaxSize(),
                state = pager,
                decoration = MushafDecoration.Madinah,
                pageTurn = PageTurn.Slide,
                pagePadding = PaddingValues(0.dp),
            )
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { pager.layoutInfo.pageSize > 0 }
        // RTL pager: a negative scroll reveals the next (higher) page.
        val sign = forwardSign(pager, scope)
        for (progress in listOf(0.25f, 0.5f, 0.75f)) {
            poseSlide(pager, scope, progress, sign)
            save(File(outDir, "turn-slide-${label(progress)}.png"))
        }
    }

    private fun capturePosed(mode: PageTurn, name: String) {
        var progress by mutableStateOf(0.5f)
        rule.setContent {
            val pose = remember(progress) { MushafTurnPreview(progress = progress, grabY = 0.9f) }
            CompositionLocalProvider(LocalMushafTurnPreview provides pose) {
                MushafPager(
                    modifier = Modifier.fillMaxSize(),
                    state = rememberMushafPagerState(initialPage = 2),
                    decoration = MushafDecoration.Madinah,
                    pageTurn = mode,
                    pagePadding = PaddingValues(0.dp),
                )
            }
        }
        for (value in listOf(0.25f, 0.5f, 0.75f)) {
            rule.runOnUiThread { progress = value }
            rule.waitForIdle()
            save(File(outDir, "turn-$name-${label(value)}.png"))
        }
    }

    /**
     * Which scrollBy sign moves toward the next page.
     *
     * In this right-to-left pager a positive offset shifts the current page
     * toward the start (the right) and brings the next page in from the left.
     */
    private fun forwardSign(pager: PagerState, scope: CoroutineScope): Float {
        val start = pager.currentPage
        poseSlide(pager, scope, 0.4f, sign = 1f)
        val forward = pager.currentPage > start || pager.currentPageOffsetFraction > 0.05f
        poseSlide(pager, scope, 0f, sign = 1f)
        return if (forward) 1f else -1f
    }

    private fun poseSlide(pager: PagerState, scope: CoroutineScope, progress: Float, sign: Float) {
        val page = pager.layoutInfo.pageSize
        scope.launch {
            pager.scrollToPage(1)
            if (progress > 0f) {
                pager.scroll { scrollBy(sign * page * progress) }
            }
        }
        rule.waitForIdle()
        println(
            "[mushaf] slide progress=$progress sign=$sign " +
                "page=${pager.currentPage} fraction=${pager.currentPageOffsetFraction}"
        )
    }

    private fun label(progress: Float) = "%03d".format((progress * 100).toInt())

    private fun save(file: File) {
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("[mushaf] ${file.absolutePath} ${bitmap.width}x${bitmap.height} ${file.length()}")
        assertTrue("${file.name} was empty", file.length() > 20_000)
    }
}
