package io.github.sherifshabans.mushaf

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** هندسة التكشيرة النقية — الطرفان بالضبط، والشريط جنب الحد. */
class MushafCurlSliceTest {

    private val w = 1000f

    @Test
    fun settledEndsHaveNoStripAndTheBoundaryOnTheCorrectEdge() {
        for ((progress, forward, expectedBoundary) in listOf(
            Triple(0f, true, 0f),
            Triple(1f, true, w),
            Triple(0f, false, w),
            Triple(1f, false, 0f),
        )) {
            val s = curlSlice(w, progress, forward, backWidthPx = 18f, shadowWidthPx = 24f)
            val where = "progress=$progress forward=$forward"
            assertEquals("$where boundary", expectedBoundary, s.boundaryX, 0f)
            // لا شريط ظهر عند الطرفين — ولا ورقة تخلّف ورقة صفحة واحدة.
            assertEquals("$where backLeft", s.boundaryX, s.backLeft, 0f)
            assertEquals("$where backRight", s.boundaryX, s.backRight, 0f)
        }
    }

    @Test
    fun halfForwardPutsTheBackStripLeftOfTheBoundary() {
        val s = curlSlice(w, 0.5f, forward = true, backWidthPx = 18f, shadowWidthPx = 24f)
        assertEquals(500f, s.boundaryX, 0f)
        assertEquals("back strip right edge on the boundary", 500f, s.backRight, 0f)
        assertEquals("back strip on the left of it", 482f, s.backLeft, 0f)
        assertEquals(500f, s.shadowRight, 0f)
        assertEquals(476f, s.shadowLeft, 0f)
    }

    @Test
    fun backwardPeelMirrorsForward() {
        val s = curlSlice(w, 0.5f, forward = false, backWidthPx = 18f, shadowWidthPx = 24f)
        assertEquals(500f, s.boundaryX, 0f)
        assertEquals("back strip right of the boundary", 518f, s.backRight, 0f)
        assertEquals(500f, s.backLeft, 0f)
        assertEquals(524f, s.shadowRight, 0f)
        assertEquals(500f, s.shadowLeft, 0f)
    }

    @Test
    fun progressIsClamped() {
        val over = curlSlice(w, 1.5f, forward = true, backWidthPx = 18f, shadowWidthPx = 24f)
        val at = curlSlice(w, 1f, forward = true, backWidthPx = 18f, shadowWidthPx = 24f)
        assertEquals(at.boundaryX, over.boundaryX, 0f)
        assertEquals(at.backLeft, over.backLeft, 0f)
    }
}

/** Dog-ear and edge geometry, plus where a spread leaf lands. */
class MushafTurnGeometryTest {

    private val w = 1000f
    private val h = 1800f

    @Test
    fun cornerAtRestIsTheWholePage() {
        val fold = cornerFoldAtProgress(w, h, progress = 0f)
        assertEquals(false, fold.curled)
        assertEquals(w * h, polygonArea(fold.flat), 1f)
        assertTrue(fold.back.isEmpty())
    }

    @Test
    fun cornerTipIsTheReflectionOfTheCorner() {
        val fold = cornerFoldAtProgress(w, h, progress = 0.5f)
        val reflected = fold.apex
        // The apex stored on the fold is the tip we asked for, and the peeled
        // corner's reflection must land on it.
        val corner = androidx.compose.ui.geometry.Offset(0f, h)
        val dx = reflected.x - corner.x
        val dy = reflected.y - corner.y
        assertTrue("tip should have left the corner", hypot(dx, dy) > 100f)
        assertTrue(fold.curled)
        assertTrue("back face is drawn", fold.back.size >= 3)
        assertTrue("the peeled corner is not still flat", !pointInPolygon(corner, fold.flat))
        assertTrue(
            "the far corner stays flat at mid-turn",
            pointInPolygon(androidx.compose.ui.geometry.Offset(w - 2f, 2f), fold.flat),
        )
    }

    @Test
    fun cornerProgressSamplesAreOrdered() {
        val areas = listOf(0.25f, 0.5f, 0.75f).map { p ->
            polygonArea(cornerFoldAtProgress(w, h, p).flat) / (w * h)
        }
        assertTrue("0.25 should keep most of the sheet, was ${areas[0]}", areas[0] > 0.45f)
        assertTrue("0.50 should be a real mid peel, was ${areas[1]}", areas[1] in 0.15f..0.8f)
        assertTrue("0.75 should leave a minority of the sheet, was ${areas[2]}", areas[2] < 0.55f)
        assertTrue("later progress peels more", areas[0] > areas[1] && areas[1] > areas[2])
    }

    @Test
    fun finishedCornerLeavesNoSheet() {
        val fold = cornerFoldAtProgress(w, h, progress = 1f)
        assertTrue("flat area ${polygonArea(fold.flat)}", polygonArea(fold.flat) < 1f)
    }

    @Test
    fun creaseStaysPerpendicularToTheDrag() {
        val fold = cornerFoldAtProgress(w, h, 0.45f, forward = true, fromTop = false)
        val dragX = fold.apex.x - 0f
        val dragY = fold.apex.y - h
        val cx = fold.creaseB.x - fold.creaseA.x
        val cy = fold.creaseB.y - fold.creaseA.y
        val dot = dragX * cx + dragY * cy
        val scale = hypot(dragX, dragY) * hypot(cx, cy)
        assertTrue("crease drifted off the drag, dot/scale=${dot / scale}", abs(dot) <= scale * 0.02f)
    }

    @Test
    fun midTurnKeepsTheSpineOnTheSheet() {
        val fold = cornerFoldAtProgress(w, h, 0.35f, forward = true, fromTop = false)
        assertTrue(
            "spine left the sheet",
            pointInPolygon(androidx.compose.ui.geometry.Offset(w - 2f, h * 0.5f), fold.flat),
        )
    }

    @Test
    fun cylinderCarriesTheCornerToTheFinger() {
        val corner = androidx.compose.ui.geometry.Offset(0f, 1800f)
        val finger = androidx.compose.ui.geometry.Offset(900f, 1500f)
        val cylinder = cylinderBetween(corner, finger, radius = 80f)
        assertNotNull(cylinder)
        val landed = sampleCylinder(corner.x, corner.y, cylinder!!)
        assertEquals(finger.x, landed.x, 1.5f)
        assertEquals(finger.y, landed.y, 1.5f)
        assertTrue(landed.back)
        val spine = sampleCylinder(1000f, 400f, cylinder)
        assertEquals(1000f, spine.x, 0.5f)
        assertEquals(400f, spine.y, 0.5f)
        assertTrue(!spine.back)
    }

    @Test
    fun cylinderCompressesPaperTowardTheRidge() {
        val corner = androidx.compose.ui.geometry.Offset(0f, 1800f)
        val finger = androidx.compose.ui.geometry.Offset(1100f, 1400f)
        val cylinder = cylinderBetween(corner, finger, radius = 90f)!!
        val dir = androidx.compose.ui.geometry.Offset(cylinder.dirX, cylinder.dirY)
        val origin = androidx.compose.ui.geometry.Offset(cylinder.posX, cylinder.posY)
        fun pageAt(dist: Float) = androidx.compose.ui.geometry.Offset(
            origin.x + dir.x * dist,
            origin.y + dir.y * dist,
        )
        val a = pageAt(10f)
        val b = pageAt(10f + 40f)
        val mid = pageAt(cylinder.radius * 1.2f)
        val mid2 = pageAt(cylinder.radius * 1.2f + 40f)
        val flat = sampleCylinder(a.x, a.y, cylinder)
        val flat2 = sampleCylinder(b.x, b.y, cylinder)
        val curl = sampleCylinder(mid.x, mid.y, cylinder)
        val curl2 = sampleCylinder(mid2.x, mid2.y, cylinder)
        val flatGap = hypot(flat.x - flat2.x, flat.y - flat2.y)
        val curlGap = hypot(curl.x - curl2.x, curl.y - curl2.y)
        assertTrue("flat gap $flatGap", flatGap > 30f)
        assertTrue("curl gap $curlGap should be tighter than flat $flatGap", curlGap < flatGap * 0.75f)
    }

    @Test
    fun meshEdgesStayShortAtMidCurl() {
        val w = 720f
        val h = 1280f
        val cyl = cylinderForPose(
            w, h, 0.5f, forward = true, edge = false, fromTop = false,
            radiusPx = 72f, dragX = Float.NaN, dragY = 0f, fingerY = h * 0.9f,
        )
        assertNotNull(cyl)
        val cols = 42
        val rows = 60
        var maxEdge = 0f
        fun at(col: Int, row: Int): CurlSample {
            val px = w * col / cols
            val py = h * row / rows
            return sampleCylinder(px, py, cyl!!)
        }
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val a = at(col, row)
                val b = at(col + 1, row)
                val c = at(col, row + 1)
                maxEdge = maxOf(maxEdge, hypot(a.x - b.x, a.y - b.y), hypot(a.x - c.x, a.y - c.y))
            }
        }
        println("[mushaf] max mesh edge at 0.5 = $maxEdge radius=${cyl!!.radius}")
        val corner = sampleCylinder(0f, h, cyl)
        val mid = sampleCylinder(w * 0.5f, h * 0.5f, cyl)
        println("[mushaf] corner -> (${corner.x}, ${corner.y}, z=${corner.z}) mid -> (${mid.x}, ${mid.y}, z=${mid.z}, back=${mid.back})")
        assertTrue("mesh edge $maxEdge is a smear", maxEdge < 80f)
        val crease = hypot(cyl.posX - 0f, cyl.posY - h)
        val half = hypot(w * 0.5f, h * 0.5f)
        assertTrue("crease $crease should sit near the page centre $half", abs(crease - half) < half * 0.2f)
        assertTrue("radius ${cyl.radius} is still a thin roll", cyl.radius > w * 0.18f)
    }

    @Test
    fun mirrorAcrossAVerticalLineFlipsXOnly() {
        val mirrored = curlMirrorPoint(
            androidx.compose.ui.geometry.Offset(10f, 40f),
            androidx.compose.ui.geometry.Offset(30f, 0f),
            androidx.compose.ui.geometry.Offset(0f, 1f),
        )
        assertEquals(50f, mirrored.x, 0.01f)
        assertEquals(40f, mirrored.y, 0.01f)
    }

    @Test
    fun backwardCornerMirrorsForward() {
        val forward = cornerFoldAtProgress(w, h, 0.4f, forward = true, fromTop = false)
        val backward = cornerFoldAtProgress(w, h, 0.4f, forward = false, fromTop = false)
        assertEquals(polygonArea(forward.flat), polygonArea(backward.flat), 2f)
        val fwdCorner = androidx.compose.ui.geometry.Offset(1f, h - 1f)
        val backCorner = androidx.compose.ui.geometry.Offset(w - 1f, h - 1f)
        assertTrue(!pointInPolygon(fwdCorner, forward.flat))
        assertTrue(!pointInPolygon(backCorner, backward.flat))
    }

    @Test
    fun edgeCurlSettledEndsHaveNoStrip() {
        for (p in listOf(0f, 1f)) {
            val e = edgeCurl(w, p, forward = true, maxBack = 40f, maxShadow = 60f)
            assertTrue(e.settled)
            assertEquals(0f, e.back, 0f)
            assertEquals(0f, e.bow, 0f)
        }
    }

    @Test
    fun edgeCurlAtHalfSitsOnTheFinger() {
        val e = edgeCurl(w, 0.5f, forward = true, maxBack = 40f, maxShadow = 60f)
        assertEquals(500f, e.boundary, 0.5f)
        assertTrue(e.back > 0f)
        assertTrue(e.bow > 0f)
        assertTrue(!e.settled)
    }

    @Test
    fun flipNeverMirrors() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val forward = flipDegrees(p, forward = true)
            val backward = flipDegrees(p, forward = false)
            assertTrue(abs(forward) <= 90f)
            assertTrue(abs(backward) <= 90f)
            assertEquals(-p * 90f, forward, 0.01f)
            assertEquals(p * 90f, backward, 0.01f)
        }
    }

    @Test
    fun spreadLeafTargets() {
        assertEquals("page 1 opens onto page 2", 1, spreadLeafTarget(0, forward = true))
        assertEquals("pages 1–2 turn onto 3", 2, spreadLeafTarget(1, forward = true))
        assertEquals(50, spreadLeafTarget(49, forward = true)) // page 50 → page 51
        assertEquals(50, spreadLeafTarget(48, forward = true)) // page 49 → page 51
        assertEquals(603, spreadLeafTarget(603, forward = true)) // already the last opening
        assertEquals(0, spreadLeafTarget(1, forward = false)) // pages 1–2 close to page 1
        assertEquals(1, spreadLeafTarget(2, forward = false)) // pages 3–4 back to page 2
        assertEquals(48, spreadLeafTarget(50, forward = false)) // page 51 back to page 49
    }

    @Test
    fun spreadPlanTurnsTheForeEdgeLeaf() {
        val forward = planSpreadTurn(fromIndex = 49, toIndex = 50)
        assertTrue(forward.turnLeft)
        assertTrue(forward.leafForward)
        assertEquals(50, forward.overPage)
        assertEquals(52, forward.underPage)
        assertEquals(49, forward.staticPage)
        assertEquals(51, forward.incomingStaticPage)

        val backward = planSpreadTurn(fromIndex = 50, toIndex = 48)
        assertTrue(!backward.turnLeft)
        assertTrue(!backward.leafForward)
        assertEquals(51, backward.overPage)
        assertEquals(49, backward.underPage)
        assertEquals(52, backward.staticPage)
        assertEquals(50, backward.incomingStaticPage)
    }
}

private fun hypot(x: Float, y: Float) = kotlin.math.hypot(x, y)

/**
 * تقليب الورقة داخل الـpager: السحب يودّي للصفحة الجاية، ولمسة الهامش تقلّب،
 * والقفزة الخارجية تستبدل الوجهة، والصفحات الغريبة مش بتتراكّب.
 *
 * ملاحظة اتجاه: الـpager RTL — السحب **يمينًا** هو التقدّم للصفحة الجاية
 * (اللي بتيجي من الشمال)، زي ما سحب الـHorizontalPager بيتصرّف.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class MushafCurlPagerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var pagerState: PagerState
    private lateinit var scope: CoroutineScope

    private var tappedAyah: Ayah? = null

    private fun content(pageTurn: PageTurn, decoration: MushafDecoration = MushafDecoration.None) {
        rule.setContent {
            scope = rememberCoroutineScope()
            pagerState = rememberMushafPagerState(initialPage = 1)
            Box(Modifier.size(411.dp, 830.dp).background(MushafColors.Light.paper)) {
                MushafPager(
                    modifier = Modifier.size(411.dp, 830.dp),
                    state = pagerState,
                    colors = MushafColors.Light,
                    decoration = decoration,
                    pageTurn = pageTurn,
                    onAyahClick = { tappedAyah = it },
                    pagePadding = PaddingValues(0.dp),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun slideModeNeverComposesCurlPages() {
        MushafCurlProbe.reset()
        content(PageTurn.Slide)
        assertTrue(
            "curl content composed in Slide mode",
            MushafCurlProbe.composedPages.isEmpty(),
        )
    }

    @Test
    fun draggingRightSettlesOnTheNextPage() {
        content(PageTurn.Curl)
        assertEquals(0, pagerState.currentPage)
        rule.onRoot().performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals("RTL forward drag must land on page 2", 1, pagerState.currentPage)
    }

    @Test
    fun tapOnAWordStillInvokesOnAyahClick() {
        content(PageTurn.Curl)
        rule.onRoot().performTouchInput {
            down(Offset(center.x, center.y))
            up()
        }
        rule.waitForIdle()
        assertNotNull("tap inside the text column was stolen by the curl", tappedAyah)
    }

    @Test
    fun aMarginTapTurnsTheLeaf() {
        // الهامش موجود مع الإطار بس — برّة عمود النص.
        content(PageTurn.Curl, decoration = MushafDecoration.Classic)
        // يسار الإطار (برّة عمود النص) = تقليب للأمام.
        rule.onRoot().performTouchInput {
            down(Offset(4f, center.y))
            up()
        }
        rule.waitForIdle()
        assertEquals("left margin tap must turn forward", 1, pagerState.currentPage)
    }

    @Test
    fun aScrollJumpDuringAPeelReplacesTheTarget() {
        MushafCurlProbe.reset()
        content(PageTurn.Curl)
        rule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(400f, 0f))
            // قفزة خارجية أثناء التكشيرة — لازم تحلّ محلّ الوجهة الحالية.
            scope.launch { pagerState.scrollToPage(9) }
            up()
        }
        rule.waitForIdle()
        assertEquals("settled page must be the jump target", 9, pagerState.currentPage)
        val composed = MushafCurlProbe.composedPages
        assertTrue("target page never composed", 9 in composed)
        for (unrelated in listOf(4, 5, 6, 7)) {
            assertTrue("unrelated page $unrelated was composed", unrelated !in composed)
        }
    }
}
