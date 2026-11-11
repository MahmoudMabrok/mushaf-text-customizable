# Release notes

## 1.7.1 — realistic page curl

A patch on 1.7.0. `PageTurn.Curl` is redrawn; the public API is unchanged
(`PageTurn`, `curlSlice`, and `MushafPager`'s parameters). No tag is cut
from this commit.

### Curl

- **Cylinder, not a flat fold.** A corner drag keeps the crease on the diagonal
  from the grabbed corner. At the halfway point that crease crosses about half
  the page. The sheet is an adaptive mesh, denser on the cylinder, wrapped so
  the type and the frame compress into the roll. The back of the leaf is that
  page bent around the cylinder, only slightly warm, with a dark inner fold
  and a soft shadow on the page underneath. On API 33+ the same mapping is
  also an AGSL shader; otherwise the page is `drawVertices` with the same
  triangles. The page is snapshotted at 2×. An edge drag rolls the whole
  fore-edge. Robolectric's Skia mesh sampler does not return the bitmap's
  texels, so the screenshot tests rasterize this same mesh on the CPU with
  bilinear filtering.
- **One snapshot per gesture.** Each leaf is captured once (and again only if
  its pixels change). Later frames warp that bitmap, so the ayah layout is
  not redrawn at 60fps and the drag is not composed state.
- **Fling.** Release projects the finger's velocity with a decay and springs
  to the settled page or back, without bouncing past the end. A margin tap
  rolls the fore-edge with a small initial push.
- **Spreads.** The turning leaf curls across the gutter onto the facing page,
  carrying that page's verso, instead of crossfading the facing half. Flip
  still crossfades.

### Limits

- The curl is orthographic: the sheet does not shrink in perspective.
- On a single page the back is a tinted mirror of the front, not a second
  typeset page. On a spread the verso is the incoming page, pre-flipped so it
  reads upright as the leaf lands; a steep diagonal can still skew it.
- Shadows are gradient bands. A blur mask is unreliable on a hardware canvas.

### العربية — تكشيرة الورقة

ترقيع على ١٫٧٫٠. `PageTurn.Curl` أُعيد رسمها، والواجهة العامة كما هي. لا وسم
لهذا الالتزام.

- **أسطوانة لا طيّة مسطّحة.** ثنية الزاوية تبقى عمودية على سحب الإصبع ومثبّتة
  في الكعب. الورقة تلتف على أسطوانة: النسيج ينضغط عند الشفة، والظهر انعكاس
  ملوّن، وظل على الصفحة التي تحتها مع لمعة على الحرف. سحب الحافة يلفّ الطرف
  كله.
- **لقطة واحدة لكل إيماءة.** الإطار اللاحق يلوي الصورة، فتخطيط الآيات لا يُعاد
  رسمه كل إطار.
- **قذف.** الإفلات يقدّر سرعة الإصبع وينهي التقليبة أو يرجعها بنابض بلا ارتداد.
- **الازدواج.** الورقة تلتف عبر الكعب فوق الصفحة المقابلة بدل التلاشي.

## 1.7.0 — page turns

A minor bump. `MushafPager(pageTurn = …)` grows three motions and the two that
shipped in 1.6.0 are redrawn. The default is still `PageTurn.Slide`, and a call
that passes `Slide` or `Curl` keeps compiling.

### Turns

- **Curl.** `PageTurn.Curl` follows the finger. A grab in the top or bottom band
  peels that corner: the underside of the sheet and a soft shadow travel with the
  tip, and the next page lies underneath. A grab in the middle peels the whole
  fore-edge as a short cylinder with a highlight on the lip. Releasing eases to
  the nearest page (fast-out, slow-in). The drag itself is 1:1 — easing is only
  the settle.
- **Flip.** `PageTurn.Flip` rotates the sheet around the spine. Going forward the
  spine is the right edge, the next page stays flat underneath, and the turning
  sheet darkens as it closes. Rotation stops at 90° so the page never mirrors.
- **Fade.** `PageTurn.Fade` fades the leaving page back a little and fades the
  next one in, still driven by the finger.
- **Cover.** `PageTurn.Cover` pulls the next page in from the fore-edge over the
  current one, with a shadow on the leading edge and a dim on the page being
  covered.
- **Slide.** The right-to-left `HorizontalPager` snaps with the same fast-out,
  slow-in ease, and the moving sheet picks up a soft shade on both edges. On a
  wide spread, one flick advances a whole opening instead of landing on the same
  pair twice.
- **Direction.** A finger moving right reveals the next page, in every mode.
  Taps in the text column stay ayah taps. Taps in the frame margin turn the leaf.
- **Spread.** With `MushafSpread.Auto`, curl and flip turn the fore-edge leaf
  around the gutter. The facing page crossfades in over the second half of the
  turn, so the opening does not pop when the leaf settles. Fade and cover move
  the whole opening. `state.currentPage` is still one mushaf page. A finger turn
  settles on the right-hand page of the opening you land on (page 2 for the first
  opening, page 1 when you close it).
- **Offset.** On curl, flip, fade, and cover, `currentPageOffsetFraction` stays
  `0`. It is not the turn progress. Callers that need the fraction stay on
  `PageTurn.Slide`.
- **Sample.** The style preview (toolbar star) lists every turn, not only slide
  and curl.

### Compatibility

- Source-compatible for call sites that pass `pageTurn = PageTurn.Slide` or
  `PageTurn.Curl`. `Flip`, `Fade`, and `Cover` are new enum values, so a `when`
  without `else` needs a new branch. The library version is **1.7.0**.

### العربية — تقليب الصفحات

ترقية صغرى. `MushafPager(pageTurn = …)` زاد ثلاث حركات، والحركتان اللي نزلوا
في ١٫٦٫٠ اتعاد رسمهم. الافتراضي لسه `PageTurn.Slide`، والنداء اللي بيمرّر
`Slide` أو `Curl` بيتترجم زي ما هو.

#### التقليب

- **التكشيرة.** `PageTurn.Curl` بتتبع الإصبع. القبضة في الشريط العلوي أو السفلي
  بتقشّر الزاوية: ظهر الورقة وظل خفيف بيمشوا مع الطرف، والصفحة الجاية مفروشة
  تحت. القبضة من النص بتقشّر الحافة كلها كأسطوانة قصيرة فيها لمعة على الشفة.
  الإفلات بيهدي لأقرب صفحة. السحب نفسه واحد لواحد — التهدئة بس في الآخر.
- **القلب.** `PageTurn.Flip` بيدوّر الورقة حول الكعب. في التقدّم الكعب هو الحافة
  اليمين، والصفحة الجاية ثابتة تحتها، والورقة بتغمق وهي بتتقفل. الدوران بيقف
  عند ٩٠° فالصفحة ما بتنعكسش.
- **التلاشي.** `PageTurn.Fade` بيبهّت الصفحة الخارجة شوية وبيظهّر الجاية، برضه
  بإصبع القارئ.
- **التغطية.** `PageTurn.Cover` بيسحب الصفحة الجاية من حافة الكتاب فوق الحالية،
  بظل على الحرف وتعتيم على الصفحة المتغطية.
- **الانزلاق.** `HorizontalPager` من اليمين للشمال بيهدي بخروج سريع ودخول بطيء،
  والورقة المتحركة تاخد ظل خفيف على الحافتين. على الشاشة العريضة، سحبة واحدة
  بتنقل فتحة كاملة مش نفس الزوجتين مرتين.
- **الاتجاه.** الإصبع اللي بيتحرك يمين بيكشف الصفحة الجاية في كل وضع. لمسات
  عمود النص لمسات آيات. لمسات هامش الإطار بتقلب الورقة.
- **الازدواج.** مع `MushafSpread.Auto` التكشيرة والقلب بيقلّبوا ورقة الحافة حول
  الكعب. الصفحة المقابلة بتتبدّل في نص التقليبة، فالفتحة ما بتقفزش لما الورقة
  تهدى. التلاشي والتغطية بيحرّكوا الفتحة كلها. `state.currentPage` لسه صفحة
  واحدة. تقليبة الإصبع بتقف على الصفحة اليمين من الفتحة الجديدة (صفحة ٢ لأول
  فتحة، وصفحة ١ لما تتقفل).
- **الكسر.** في التكشيرة والقلب والتلاشي والتغطية،
  `currentPageOffsetFraction` بيفضل `0`. هو مش تقدّم التقليبة. اللي محتاج
  الكسر يفضل على `PageTurn.Slide`.
- **التطبيق التجريبي.** لوحة الطُرز (نجمة الشريط) بتعرض كل طرق التقليب.

#### التوافق

- متوافق مصدريًا للنداءات اللي بتمرّر `pageTurn = PageTurn.Slide` أو
  `PageTurn.Curl`. `Flip` و`Fade` و`Cover` قيم جديدة في التعداد، فـ`when` من
  غير `else` محتاج فرع جديد. إصدار المكتبة **1.7.0**.

## 1.6.0 — reader milestone

A minor bump. New reader features sit on the 1.5.0 decoration, and the new
parameters default so an undecorated page is unchanged. A page that already
draws the ornate frame does change pixels: tighter margins, a larger medallion
number, and shorter classic header panels.

### Page curl, spread, and division marks

- **Leaf turn.** `MushafPager(pageTurn = PageTurn.Curl)` peels one leaf: the
  fore-edge lifts, the back of the sheet shows along the peel, a soft shadow
  falls on the revealed page, and the current page is clipped above. The
  default stays `PageTurn.Slide` (the right-to-left `HorizontalPager`). Taps in
  the text column stay ayah taps; taps in the frame margin turn the leaf.
  During a peel, `state.currentPageOffsetFraction` stays `0` — it is **not**
  the peel progress. Callers that need the fraction stay on `PageTurn.Slide`.
- **Spread.** `spread = MushafSpread.Auto` shows the page and its pair side by
  side when the page area is at least 600 dp wide: the odd page on the right,
  its even pair on the left, like the open book. Page 1 shows the inside cover
  on its left, and the last spread (603, 604) is full. `state.currentPage`
  keeps addressing a single mushaf page. The pair is static for now — a leaf
  turn on a spread is a later task. The default is `MushafSpread.Single`.
- **Division corpus.** `MushafDivisions` ships the 240 Madinah quarters and the
  15 sajdah positions as `MushafMark` data. Quarter 106 is **15:49**, following
  the in-line `۞` in `hafs.tsv`, not 15:50.
- **Division chrome.** `divisionMarks = true` (default `false`) draws the margin
  medallions — juz, hizb, rubʿ, nisf, three-quarters, sajdah — aligned to the
  `۞` / `۩` line, and the printed running head inside the frame (surah on the
  right, juz on the left). `MushafPager` then suppresses its outer header and
  footer even when `showHeaders` is true, so the page number is not drawn
  twice. Preset: `MushafDecoration.Madinah` (Classic plus the marks).

### Printed looks and custom frames

- **Skins of the same Madinah pages.** `PageFrames.Cairo` / `.Diyanet` /
  `.IndoPak`, matching `SurahHeader`s, and `MushafDecoration.Cairo` /
  `.Diyanet` / `.IndoPak`. Page breaks, the font, and the star positions do
  not change. Each preset follows `MushafColors`, including a dark-paper
  palette. Indo-Pak draws a hairline under every verse line and uses eastern
  digits (`U+06F0`) on the surah-number label.
- **Host slots.** `PageFrame`, `PageFrameRequest`, `PageFrameGeometry`,
  `PageFrameContext`, `FittedLabel`, `LineSpan`, and `SurahHeader` /
  `SurahHeaderScope`. `MushafDecoration.frame` defaults to `PageFrames.Ornate`
  (the 1.5.0 frame). `surahHeader` defaults to `null`, which keeps the
  `SurahHeaderStyle` drawers; `SurahHeader.Custom` composes the host banner in
  the banner slot and clips it there. New looks live on `SurahHeader`, not on
  the `SurahHeaderStyle` enum, so existing `when`s keep compiling.
- **Sample.** An off-by-default preview sheet (presets, `PageTurn`, spread)
  behind a toolbar star. The opening screen stays the current reader.

### Frame margins, header panels, page number

- **Page number in the medallion.** Was a hardcoded `0.34 ×` the ayah font
  (about **6–9 sp** on a phone), so the digits sat small inside the rosette.
  `MushafDecoration.pageNumberScale` (default **0.72**, also
  `DEFAULT_PAGE_NUMBER_SCALE`) is the fraction of the inner disk the digit ink
  fills. With Android's Arabic sans digits that is about **23 sp** for a
  one-digit page and about **13 sp** for a three-digit page — larger, still
  inside the ring. Ayah text and surah-header geometry are unchanged. `1`
  fills the disk; values are clamped to `0.05..1`.
- **One page number.** Hosts that drew their own footer under `MushafPage`
  while `pageFrame` was on saw the number twice (footer + medallion). The
  medallion is the page number when the frame is on; hide any extra footer.
- **Margins, now fields on `MushafDecoration`.** `textGap` (default **8**, was
  5) leaves more room between the ayat and the ornate band.
  `frameOuterMargin` / `frameInnerMargin` / `frameTopMargin` /
  `frameBottomMargin` (defaults **5 / 2 / 1.5 / 14**, were 9 / 3 / 2 / 19) sit
  the frame closer to the page edge.
- **Classic header panels.** The «آياتها» caption is gone. The left panel is
  `ayahCountLabel` alone (for example `٧ آيات`). The right panel is one larger
  line, «مكية ١» / «مدنية ٢». Panel text is about `0.55 ×` the page font.

### Compatibility

- Source-compatible for Kotlin call sites: the new `MushafDecoration` fields
  and the new `MushafPager` parameters (`pageTurn`, `spread`) have defaults.
  The data-class constructor is the usual Kotlin binary break, the same story
  as `surahHeaderOverrides` and `pageNumberScale` in 1.5.0.
- `Surah.revelationType` stays required, as in 1.5.0. The Flutter package is
  unchanged.

### العربية — مرحلة القارئ

ترقية صغرى. ميزات القارئ الجديدة فوق تزيين ١٫٥٫٠، والقيم الافتراضية تُبقي
الصفحة بلا تزيين كما هي. الصفحة التي ترسم الإطار المزخرف أصلًا تتغيّر بكسلاتها:
هوامش أضيق، ورقم أكبر في الوردة، ولوحات ترويسة كلاسيكية أقصر.

#### تقليب الورقة، والازدواج، وعلامات الأقسام

- **تقليب الورقة.** `MushafPager(pageTurn = PageTurn.Curl)` يقشر ورقة واحدة:
  ترتفع الحافة، ويظهر ظهر الورقة على طول التكشيرة، ويقع ظلّ خفيف على الصفحة
  المكشوفة، وتُقصّ الصفحة الحالية فوقها. الافتراضي يبقى `PageTurn.Slide`
  (الانزلاق من اليمين إلى الشمال). اللمسات داخل عمود النص تبقى لمسات آيات،
  ولمسات هامش الإطار تقلب الورقة. أثناء التكشيرة يبقى
  `state.currentPageOffsetFraction` مساويًا `0` — هو **ليس** تقدّم التكشيرة.
  من يحتاج الكسر يبقى على `PageTurn.Slide`.
- **الازدواج.** `spread = MushafSpread.Auto` يعرض الصفحة وزوجتها جنبًا إلى جنب
  عندما يبلغ عرض مساحة الصفحة ٦٠٠dp على الأقل: الفردية يمينًا والزوجية شمالًا،
  كالكتاب المفتوح. الصفحة ١ شمالها داخل الكتاب، والازدواج الأخير (٦٠٣، ٦٠٤)
  ممتلئ. `state.currentPage` يظل يعنون صفحة واحدة. الزوج ثابت الآن — تقليب
  الورقة على ازدواج مهمة لاحقة. الافتراضي `MushafSpread.Single`.
- **جدول الأقسام.** `MushafDivisions` يضمّ أرباع المدينة الـ٢٤٠ ومواضع السجود
  الـ١٥ كبيانات `MushafMark`. الربع ١٠٦ هو **١٥:٤٩**، تبعًا لـ`۞` داخل
  `hafs.tsv`، لا ١٥:٥٠.
- **زينة الأقسام.** `divisionMarks = true` (الافتراضي `false`) يرسم وردات
  الهامش — جزء، حزب، ربع، نصف، ثلاثة أرباع، سجدة — محاذية لسطر `۞` / `۩`،
  ورأس الصفحة المطبوع داخل الإطار (السورة يمينًا والجزء شمالًا). عندها يخفي
  `MushafPager` ترويسته وتذييله الخارجيين حتى لو كان `showHeaders` صحيحًا، فلا
  يُرسم رقم الصفحة مرتين. الإعداد الجاهز: `MushafDecoration.Madinah` (الكلاسيك
  مع العلامات).

#### الطُرز المطبوعة والإطارات المخصّصة

- **مظاهر لصفحات المدينة نفسها.** `PageFrames.Cairo` / `.Diyanet` / `.IndoPak`
  مع `SurahHeader` المطابقة وإعدادات `MushafDecoration.Cairo` / `.Diyanet` /
  `.IndoPak`. كسور السطور والخط ومواضع النجوم لا تتغيّر. كل إعداد يتبع
  `MushafColors`، بما فيه لوحة الورق الداكن. إندو-باك يرسم خطًّا رفيعًا تحت كل
  سطر آيات ويستخدم الأرقام الشرقية (`U+06F0`) في رقم السورة.
- **خانات التطبيق.** `PageFrame` و`PageFrameRequest` و`PageFrameGeometry`
  و`PageFrameContext` و`FittedLabel` و`LineSpan` و`SurahHeader` /
  `SurahHeaderScope`. `MushafDecoration.frame` افتراضه `PageFrames.Ornate`
  (إطار ١٫٥٫٠). `surahHeader` افتراضه `null` فيُبقي رسّامي
  `SurahHeaderStyle`؛ و`SurahHeader.Custom` يضع ترويسة التطبيق في خانة الشريط
  ويقصّها عليها. الطُرز الجديدة على `SurahHeader` لا على تعداد
  `SurahHeaderStyle`، فتبقى فروع `when` الحالية قابلة للترجمة.
- **التطبيق التجريبي.** لوحة معاينة مطفأة افتراضيًا (الإعدادات و`PageTurn`
  والازدواج) خلف نجمة في شريط الأدوات. الشاشة الأولى تبقى القارئ الحالي.

#### هوامش الإطار، ولوحات الترويسة، ورقم الصفحة

- **رقم الصفحة في الوردة.** كان `0.34 ×` خط الآية ثابتًا (نحو **٦–٩sp** على
  الهاتف)، فتقعد الأرقام صغيرة داخل الوردة. `MushafDecoration.pageNumberScale`
  (الافتراضي **0.72**، وهو أيضًا `DEFAULT_PAGE_NUMBER_SCALE`) نسبة قرص الوردة
  الداخلي التي يملؤها حبر الرقم. بأرقام أندرويد العربية العادية يكون ذلك نحو
  **٢٣sp** لصفحة بخانة ونحو **١٣sp** لصفحة بثلاث خانات — أكبر، وما زال داخل
  الحلقة. نص الآيات وهندسة ترويسة السورة كما هما. `1` يملأ القرص، والقيم
  تُحصر في `0.05..1`.
- **رقم صفحة واحد.** التطبيق الذي رسم تذييله تحت `MushafPage` مع `pageFrame`
  كان يرى الرقم مرتين (التذييل + الوردة). الوردة هي رقم الصفحة حين يكون الإطار
  ظاهرًا؛ أخفِ أي تذييل زائد.
- **الهوامش صارت حقولًا على `MushafDecoration`.** `textGap` (الافتراضي **٨**،
  وكان ٥) يترك فراغًا أكبر بين الآيات والشريط المزخرف.
  `frameOuterMargin` / `frameInnerMargin` / `frameTopMargin` /
  `frameBottomMargin` (الافتراضيات **٥ / ٢ / ١٫٥ / ١٤**، وكانت ٩ / ٣ / ٢ / ١٩)
  تُقرّب الإطار من حافة الصفحة.
- **لوحات الترويسة الكلاسيكية.** سقط تعليق «آياتها». اللوحة اليسرى هي
  `ayahCountLabel` وحدها (مثل `٧ آيات`). اللوحة اليمنى سطر واحد أكبر: «مكية ١»
  / «مدنية ٢». حجم نص اللوحة نحو `0.55 ×` خط الصفحة.

#### التوافق

- متوافق مصدريًا لنداءات Kotlin: حقول `MushafDecoration` الجديدة ومعاملا
  `MushafPager` الجديدان (`pageTurn` و`spread`) لهما قيم افتراضية. منشئ
  الـdata class فيه كسر الثنائي المعتاد في Kotlin، كحال `surahHeaderOverrides`
  و`pageNumberScale` في ١٫٥٫٠.
- `Surah.revelationType` يبقى مطلوبًا كما في ١٫٥٫٠. حزمة Flutter بلا تغيير.

## 1.5.0 — decoration, stable

`MushafDecoration` now actually owns the page it decorates. Previously the frame was drawn *over*
a layout measured against the full canvas, so every line ran past the inner rule and under the
rope of beads, and the surah banner spilled off both edges.

### Page frame

- **The frame geometry is the single source of truth.** `MushafFrameGeometry` produces both the
  frame rect and the text area; the renderer draws from it and the layout is inset by `content*`.
  Lines can no longer cross the frame, and callers no longer need to add padding themselves.
  (The old hidden 28 dp `framePad` is gone — it double-padded and shrank the type for nothing.)
- `pageFrame` palette follows `MushafColors`: `MushafColors.Dark` gets a lifted gold and a dark
  field instead of a cream frame on a black page.
- The page-number medallion is now the printed eight-point rosette and carries the page number
  (it was an empty circle).
- Hit-testing follows the content origin, so taps land on the right word when a frame is on.

### Surah header

- `CLASSIC` is a full port of `SuraHeaderSpan.drawClassic`: curved cartouche, inner double line,
  ornamental band with diamonds, and two side panels joined to the cartouche by short rules.
- The **left panel** shows the ayah count with its Arabic noun — `٢٨٦ آية`, `٧ آيات`, `آيتان`
  (new public helper: `ayahCountLabel(count)`).
- Name, count and order are measured and fitted to their boxes, so nothing overflows the
  cartouche or the panels on any page size. Long names shrink instead of spilling.
- Header numbers are drawn in a plain sans face with `TextDirection.Rtl`. The KFGQPC font folds
  any digit run into an ayah rosette, so `٢٨٦` used to come out as two rosettes and the page
  number as one.
- `MODERN` and `PLAIN` follow the same width and colour rules.
- **Revelation type** (`RevelationType`, from QuranyApp's `quran.json`): the right panel shows
  مكية / مدنية with the surah number beneath it, and `MushafDecoration.surahHeaderOverrides`
  can force a revelation type, ayah count or name per surah.
- The surah number left the name cartouche: the cartouche carries the name alone, and the number
  sits in the right panel, the slot the printed header uses for the revelation type.

### Ayah frame

- `MushafDecoration.ayahFrame` was a no-op; it now draws a padded, rounded frame around
  highlighted, selected and playing ayahs. A multi-line ayah joins into one block (interior
  corners are square).

### Tests and docs

- `MushafFrameGeometryTest` — the content rect stays inside the inner rule for all 604 pages
  across several widths and densities; the Arabic ayah-count label pluralises.
- `MushafDecorationRenderTest` — pixel scan: no ink may appear in the frame band on pages with a
  top banner, a bottom banner, or three banners; `ayahFrame` must change the output.
- `MushafDecorationHarness` — renders every decoration combination to
  `mushaf/build/decoration-shots/` with no emulator.
- README rewritten as two complete versions (English and Arabic), with fresh screenshots for
  Classic, Modern, Plain, the ayah frame and night mode.

### Breaking changes

- `Surah` gained a required `revelationType` field, so the 114-entry table now carries
  مكية/مدنية. Code that only reads `Quran.surahs` is unaffected; code that constructs a `Surah`
  must pass it.
- `MushafDecoration` gained `surahHeaderOverrides` (defaults to empty, so existing call sites
  compile unchanged), and the public helpers `ayahCountLabel(count)` and `RevelationType` are new.
- `MushafDecoration`, `SurahHeaderStyle` and `MushafFrameColors` keep their shapes.

## 1.4.1

- Stop reading the alef that seats a tanween as a letter of madd.
- Drop the tafkhim switch, and give the extra alef its own colour.

## 1.4.0

- `MushafDecoration` introduced: page frame plus `CLASSIC` / `MODERN` / `PLAIN` surah headers.
