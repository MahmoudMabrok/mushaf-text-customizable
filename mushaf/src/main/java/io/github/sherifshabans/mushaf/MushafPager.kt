package io.github.sherifshabans.mushaf

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
/**
 * A pager state over the 604 pages. [initialPage] is the **mushaf page number** (1‥604);
 * `state.currentPage + 1` is the page on screen.
 */
@Composable
fun rememberMushafPagerState(initialPage: Int = 1): PagerState =
    rememberPagerState(initialPage = (initialPage - 1).coerceIn(0, Quran.PAGE_COUNT - 1)) {
        Quran.PAGE_COUNT
    }

/** عرض منطقة الصفحة (dp) اللي بعده تتحطّ صفحتين جنب بعض في [MushafSpread.Auto]. */
private val SPREAD_MIN_WIDTH = 600.dp

/** صفحات واحدة لكل شاشة أو صفحتين (ازدواجية) في الشاشات العريضة. */
enum class MushafSpread {
    /** Always one page per screen. */
    Single,

    /**
     * A spread when the page area is at least [SPREAD_MIN_WIDTH] wide: the odd
     * page on the right, its even pair on the left, like the open book. Page 1
     * shows the inside cover on its left. Curl and flip turn one leaf around
     * the gutter; slide, fade, and cover move the whole opening.
     */
    Auto,
}

/**
 * ازدواجية الصفحة: الزوجية شمالًا (يسار الكتاب) والفردية يمينًا، وأول صفحة
 * شمالها داخل الكتاب (فارغ). ٦٠٤ صفحات = ٣٠٢ ازدواجية.
 */
internal fun spreadPair(page: Int): Pair<Int, Int?> {
    if (page == 1) return 1 to null
    val left = if (page % 2 == 0) page else page + 1
    return (left - 1) to left
}

/**
 * The whole mushaf, one page per screen, turning right-to-left like a printed copy
 * (the next page comes in from the left) whatever the app's locale.
 *
 * Each page gets a small header (surah · juz) and footer (page number) unless
 * [showHeaders] is false or the decoration's frame owns that chrome. Every other
 * parameter is passed through to [MushafPage].
 *
 * @param pageTurn how a leaf turns. [PageTurn.Slide] (default) is the
 *   right-to-left [HorizontalPager], and `currentPageOffsetFraction` follows
 *   the finger. [PageTurn.Curl], [PageTurn.Flip], [PageTurn.Fade], and
 *   [PageTurn.Cover] are driven inside the pager; during those turns
 *   `currentPageOffsetFraction` stays `0`.
 * @param spread [MushafSpread.Single] (default) one page per screen;
 *   [MushafSpread.Auto] shows the page and its pair side by side on wide
 *   screens. `state.currentPage` keeps addressing a single mushaf page.
 */
@Composable
fun MushafPager(
    modifier: Modifier = Modifier,
    state: PagerState = rememberMushafPagerState(),
    tajweed: Boolean = false,
    naturalMadd: Boolean = false,
    colors: MushafColors = MushafColors.Light,
    decoration: MushafDecoration = MushafDecoration.None,
    showHeaders: Boolean = true,
    pageTurn: PageTurn = PageTurn.Slide,
    spread: MushafSpread = MushafSpread.Single,
    selectedAyahIds: Set<Int> = emptySet(),
    highlightedAyahs: Map<Int, Color> = emptyMap(),
    playingAyahIds: Set<Int> = emptySet(),
    onAyahClick: ((Ayah) -> Unit)? = null,
    onAyahLongClick: ((Ayah) -> Unit)? = null,
    onTajweedClick: ((TajweedHit) -> Unit)? = null,
    maxFontSize: Float = DEFAULT_MAX_FONT_SIZE,
    pagePadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
) {
    val outerDirection = LocalLayoutDirection.current
    // الإطار اللي بيملك رأس الصفحة ورقمها بيلغي الأطراف الخارجية — مهما كان
    // `showHeaders` — عشان الرقم ما يظهرش مرتين.
    val frameOwnsChrome = decoration.pageFrame && decoration.divisionMarks
    val singleContent: @Composable (Int) -> Unit = { index ->
        CompositionLocalProvider(LocalLayoutDirection provides outerDirection) {
            val page = index + 1
            val context = LocalContext.current
            val ayahs by produceState<List<Ayah>?>(null, page) {
                value = Quran.load(context).filter { it.page == page }
            }
            Column(Modifier.fillMaxSize().padding(pagePadding)) {
                if (showHeaders && !frameOwnsChrome) PageHeader(ayahs, colors)
                MushafPage(
                    ayahs = ayahs ?: emptyList(),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    tajweed = tajweed,
                    naturalMadd = naturalMadd,
                    colors = colors,
                    decoration = decoration,
                    selectedAyahIds = selectedAyahIds,
                    highlightedAyahs = highlightedAyahs,
                    playingAyahIds = playingAyahIds,
                    onAyahClick = onAyahClick,
                    onAyahLongClick = onAyahLongClick,
                    onTajweedClick = onTajweedClick,
                    maxFontSize = maxFontSize
                )
                if (showHeaders && !frameOwnsChrome) PageFooter(page, colors)
            }
        }
    }
    // الصفحة الجاية تيجي من الشمال زي المصحف المطبوع، مهما كانت لغة التطبيق.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(modifier.background(colors.paper)) {
            val spreadActive = spread == MushafSpread.Auto && maxWidth >= SPREAD_MIN_WIDTH
            val pageContent: @Composable (Int) -> Unit = { index ->
                if (!spreadActive) {
                    singleContent(index)
                } else {
                    // الزوجية: الفردية يمينًا (أول صف في RTL) والزوجية شمالًا،
                    // وأول صفحة شمالها داخل الكتاب الفاضي.
                    val (right, left) = spreadPair(index + 1)
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            singleContent(right - 1)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (left != null) singleContent(left - 1)
                        }
                    }
                }
            }
            when (pageTurn) {
                PageTurn.Slide -> {
                    val fling = PagerDefaults.flingBehavior(
                        state = state,
                        pagerSnapDistance = if (spreadActive) {
                            object : PagerSnapDistance {
                                override fun calculateTargetPage(
                                    startPage: Int,
                                    suggestedTargetPage: Int,
                                    velocity: Float,
                                    pageSize: Int,
                                    pageSpacing: Int,
                                ): Int = if (suggestedTargetPage == startPage) {
                                    startPage
                                } else {
                                    spreadLeafTarget(
                                        startPage,
                                        forward = suggestedTargetPage > startPage,
                                    )
                                }
                            }
                        } else {
                            PagerSnapDistance.atMost(1)
                        },
                        snapAnimationSpec = tween(
                            durationMillis = 420,
                            easing = FastOutSlowInEasing,
                        ),
                        snapPositionalThreshold = 0.3f,
                    )
                    HorizontalPager(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = if (spreadActive) 2 else 1,
                        flingBehavior = fling,
                        key = { it },
                    ) { index ->
                        Box(
                            Modifier.fillMaxSize().drawWithContent {
                                drawContent()
                                val offset =
                                    (state.currentPage - index) + state.currentPageOffsetFraction
                                val travel = kotlin.math.abs(offset).coerceIn(0f, 1f)
                                if (travel > 0.02f) {
                                    val alpha = 0.28f * travel
                                    val band = 32.dp.toPx()
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                Color.Black.copy(alpha = alpha),
                                                Color.Transparent,
                                            ),
                                            startX = 0f,
                                            endX = band,
                                        ),
                                        size = Size(band, size.height),
                                    )
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color.Black.copy(alpha = alpha),
                                            ),
                                            startX = size.width - band,
                                            endX = size.width,
                                        ),
                                        topLeft = Offset(size.width - band, 0f),
                                        size = Size(band, size.height),
                                    )
                                }
                            }
                        ) { pageContent(index) }
                    }
                }

                PageTurn.Curl -> MushafPageCurl(
                    state = state,
                    colors = colors,
                    spread = spreadActive,
                    modifier = Modifier.fillMaxSize(),
                    pageContent = singleContent,
                )

                PageTurn.Flip, PageTurn.Fade, PageTurn.Cover -> LeafPager(
                    state = state,
                    mode = pageTurn,
                    spread = spreadActive,
                    colors = colors,
                    decoration = decoration,
                    pagePadding = pagePadding,
                    layoutDirection = outerDirection,
                    modifier = Modifier.fillMaxSize(),
                    pageContent = singleContent,
                )
            }
        }
    }
}


/**
 * خط رأس الصفحة وتذييلها.
 *
 * الأرقام (الجزء والصفحة) **لازم** تتكتب بخط غير خط المصحف: خط المصحف بيحوّل أي
 * رَنّ أرقام عربية لوردة آية (رابطة `rlig`)، فرقم الصفحة كان بيطلع جوّه وردة.
 * اتشاف على الموبايل مش في الـharness، لأن الـharness بيصوّر `MushafPage` لوحدها.
 */
private fun chromeStyle(colors: MushafColors, font: FontFamily = FontFamily.Default) =
    TextStyle(fontFamily = font, fontSize = 15.sp, color = colors.marker)

@Composable
private fun PageHeader(ayahs: List<Ayah>?, colors: MushafColors) {
    val first = ayahs?.firstOrNull()
    // السورة يمين والجزء شمال — زي رأس الصفحة المطبوعة.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BasicText(
                text = first?.let { "سُورَةُ " + Quran.surah(it.surah).nameArabic } ?: "",
                style = chromeStyle(colors, MushafFont)
            )
            BasicText(
                text = first?.let { "الجزء " + toArabicDigits(it.juz) } ?: "",
                style = chromeStyle(colors)
            )
        }
    }
}

@Composable
private fun PageFooter(page: Int, colors: MushafColors) {
    Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
        BasicText(text = toArabicDigits(page), style = chromeStyle(colors))
    }
}

/**
 * The colour key for the tajweed rules — a wrapping row of swatch + name.
 *
 * @param english show [TajweedRule.englishName] instead of the Arabic name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TajweedLegend(
    modifier: Modifier = Modifier,
    colors: MushafColors = MushafColors.Light,
    includeNaturalMadd: Boolean = false,
    english: Boolean = false,
    textColor: Color = colors.ink
) {
    val rules = TajweedRule.entries.filter { includeNaturalMadd || !it.isNaturalMadd }
    CompositionLocalProvider(
        LocalLayoutDirection provides if (english) LayoutDirection.Ltr else LayoutDirection.Rtl
    ) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rules.forEach { rule ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(colors.tajweedColor(rule))
                    )
                    BasicText(
                        text = if (english) rule.englishName else rule.label,
                        modifier = Modifier.padding(horizontal = 5.dp),
                        style = TextStyle(fontSize = 13.sp, color = textColor)
                    )
                }
            }
        }
    }
}
