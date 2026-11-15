package com.therealityteam.aeropages

import com.therealityteam.aeropages.TestCfb.le16
import com.therealityteam.aeropages.TestCfb.le32
import com.therealityteam.aeropages.format.ppt.PptReader
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.GradientKind
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideLevels
import com.therealityteam.aeropages.model.SlideListStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The binary PowerPoint reader on decks built record by record ([MS-PPT], [MS-ODRAW]): the persist
 * directory and an appended edit, the master's styles, colours and drawing, placeholders and their
 * text from the slide list, shapes, groups, pictures, backgrounds, notes — and the text-only reading
 * of a file whose structure cannot be followed.
 */
class PptReaderTest {

    // --- records ---------------------------------------------------------------------------------

    private fun rec(type: Int, body: ByteArray, inst: Int = 0, ver: Int = 0) = le16((inst shl 4) or ver) + le16(type) + le32(body.size) + body
    private fun box(type: Int, vararg kids: ByteArray, inst: Int = 0) = rec(type, kids.fold(ByteArray(0)) { a, k -> a + k }, inst, 0xF)
    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, k -> a + k }
    private fun chars(s: String) = rec(4000, s.toByteArray(Charsets.UTF_16LE))
    private fun header(type: Int) = rec(3999, le32(type))
    private fun anchor(left: Int, top: Int, right: Int, bottom: Int) = rec(0xF010, le16(top) + le16(left) + le16(right) + le16(bottom))
    private fun fsp(type: Int, id: Int, flags: Int) = rec(0xF00A, le32(id) + le32(flags), inst = type, ver = 2)
    private fun opt(vararg props: Pair<Int, Int>) = rec(0xF00B, cat(*props.map { (id, v) -> le16(id) + le32(v) }.toTypedArray()), inst = props.size, ver = 3)
    private fun placeholder(id: Int) = box(0xF011, rec(3011, le32(0) + byteArrayOf(id.toByte(), 0) + le16(0)))
    private fun textRef(index: Int) = box(0xF00D, rec(3998, le32(index)))
    /** An RGB colour (0xRRGGBB) as an OfficeArtCOLORREF keeps it (0x00BBGGRR). */
    private fun ref(rgb: Int) = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)

    /** A TextCFException: [bold], [size] and [colorIndex] when given. */
    private fun cf(bold: Boolean? = null, size: Int? = null, fontRef: Int? = null, colorIndex: Int? = null, rgb: Int? = null): ByteArray {
        var mask = 0
        if (bold != null) mask = mask or 0x1
        if (fontRef != null) mask = mask or 0x10000
        if (size != null) mask = mask or 0x20000
        if (colorIndex != null || rgb != null) mask = mask or 0x40000
        var out = le32(mask)
        if (bold != null) out += le16(if (bold) 1 else 0)
        if (fontRef != null) out += le16(fontRef)
        if (size != null) out += le16(size)
        if (colorIndex != null) out += byteArrayOf(0, 0, 0, colorIndex.toByte())
        if (rgb != null) out += byteArrayOf((rgb shr 16).toByte(), (rgb shr 8).toByte(), rgb.toByte(), 0xFE.toByte())
        return out
    }

    /** A TextPFException: [bullet] and [align] when given. */
    private fun pf(bullet: Boolean? = null, align: Int? = null): ByteArray {
        var mask = 0
        if (bullet != null) mask = mask or 0x1
        if (align != null) mask = mask or 0x800
        var out = le32(mask)
        if (bullet != null) out += le16(if (bullet) 1 else 0)
        if (align != null) out += le16(align)
        return out
    }

    /** A StyleTextPropAtom: paragraph runs (characters, level, PF) then character runs (characters, CF). */
    private fun style(paras: List<Triple<Int, Int, ByteArray>>, runs: List<Pair<Int, ByteArray>>) =
        rec(4001, cat(*paras.map { (n, level, p) -> le32(n) + le16(level) + p }.toTypedArray(), *runs.map { (n, c) -> le32(n) + c }.toTypedArray()))

    private fun colorScheme(vararg rgb: Int) = rec(2032, cat(*rgb.map { byteArrayOf((it shr 16).toByte(), (it shr 8).toByte(), it.toByte(), 0) }.toTypedArray()), inst = 1)

    /** The patriarch group's own shape, first in a drawing's group. */
    private fun patriarch() = box(0xF004, rec(0xF009, le32(0) + le32(0) + le32(0) + le32(0), ver = 1), fsp(0, 1024, 0x5))

    private fun drawing(vararg shapes: ByteArray, background: ByteArray? = null) =
        box(1036, box(0xF002, rec(0xF008, le32(shapes.size + 1) + le32(1024 + shapes.size)), box(0xF003, patriarch(), *shapes), background ?: ByteArray(0)))

    private fun slideAtom(geom: Int, masterId: Int, flags: Int) = rec(1007, le32(geom) + ByteArray(8) + le32(masterId) + le32(0) + le16(flags) + le16(0), ver = 2)
    private fun persistAtom(ref: Int, slideId: Int) = rec(1011, le32(ref) + le32(0) + le32(0) + le32(slideId) + le32(0))

    private fun fontEntity(index: Int, name: String) = rec(4023, name.toByteArray(Charsets.UTF_16LE).copyOf(64) + ByteArray(4), inst = index)

    /** A one-pixel PNG, as a blip in the `Pictures` stream (one UID, its tag). */
    private val png: ByteArray = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==")

    private class Stream {
        val out = ByteArrayOutputStream()
        val size get() = out.size()
        fun add(b: ByteArray): Int { val at = out.size(); out.write(b); return at }
    }

    private fun persistDirectory(entries: Map<Int, Int>): ByteArray =
        rec(6002, cat(*entries.toSortedMap().map { (id, off) -> le32(id or (1 shl 20)) + le32(off) }.toTypedArray()))

    private fun userEdit(lastEdit: Int, persistDir: Int, docRef: Int) =
        rec(4085, le32(256) + le16(0) + byteArrayOf(0, 3) + le32(lastEdit) + le32(persistDir) + le32(docRef) + le32(10) + le16(1) + le16(0))

    private fun currentUser(edit: Int) =
        rec(4086, le32(20) + le32(0xE391C05F.toInt()) + le32(edit) + le16(0) + le16(0x03F4) + byteArrayOf(3, 0) + le16(0) + le32(8))

    private val masterId = 0x80000001.toInt()

    /**
     * A deck of two slides and their master, saved, then edited once (the first slide's drawing
     * replaced by an appended version that adds a shape): the file as PowerPoint leaves it.
     */
    private fun deck(): ByteArray {
        val s = Stream()
        // The master: title 44 pt in scheme colour 3, body bulleted 32 pt and 28 pt a level down, Arial; a pale
        // background and a red band across the top.
        val titleStyle = rec(4003, le16(1) + pf(align = 1) + cf(size = 44, fontRef = 0, colorIndex = 3), inst = 0)
        val bodyStyle = rec(4003, le16(2) + pf(bullet = true, align = 0) + cf(size = 32, fontRef = 0) + pf(bullet = true) + cf(size = 28), inst = 1)
        val otherStyle = rec(4003, le16(1) + pf(bullet = false) + cf(size = 18), inst = 4)
        val centerTitle = rec(4003, le16(1) + le16(0) + pf() + cf(size = 54), inst = 6)
        val band = box(0xF004, fsp(1, 2050, 0xA00), opt(0x0181 to ref(0xFF0000)), anchor(0, 0, 5760, 300))
        val masterBackground = box(0xF004, fsp(1, 2049, 0xC00), opt(0x0181 to ref(0xEAF2FB)))
        val master = s.add(box(1016, slideAtom(1, 0, 0), titleStyle, bodyStyle, otherStyle, centerTitle,
            colorScheme(0xFFFFFF, 0x000000, 0x808080, 0x1F4E79, 0xDDDDDD, 0x4472C4, 0xED7D31, 0xA5A5A5), drawing(band, background = masterBackground)))
        // Slide 1, as first saved: its title and body placeholders, their text in the slide list.
        val title = box(0xF004, fsp(1, 1025, 0xA20), anchor(500, 400, 5260, 1100), placeholder(0x0D), textRef(0))
        val body = box(0xF004, fsp(1, 1026, 0xA20), anchor(500, 1300, 5260, 4000), placeholder(0x0E), textRef(1))
        val slide1Old = s.add(box(1006, slideAtom(1, masterId, 0x7), drawing(title, body)))
        // Slide 2: its own green background, a text box of single-byte text, and a picture cropped a tenth from the left.
        val textBox = box(0xF004, fsp(202, 2051, 0xA00), opt(0x01BF to 0x100000, 0x01FF to 0x80000), anchor(600, 600, 3000, 1000),
            box(0xF00D, header(4), rec(4008, "Plain words".toByteArray(Charsets.ISO_8859_1))))
        val picture = box(0xF004, fsp(75, 2052, 0xA00), opt(0x4104 to 1, 0x0102 to 6554), anchor(3000, 1500, 5000, 3500))
        val ownBackground = box(0xF004, fsp(1, 2053, 0xC00), opt(0x0181 to ref(0x00AA00)))
        val slide2 = s.add(box(1006, slideAtom(16, masterId, 0x2), drawing(textBox, picture, background = ownBackground)))
        // The notes of slide 1.
        val notesText = box(0xF004, fsp(1, 3073, 0xA20), anchor(500, 4000, 5260, 4300), placeholder(0x0C), box(0xF00D, header(2), chars("Say hello first")))
        val notes = s.add(box(1008, rec(1009, le32(256) + le16(0) + le16(0), ver = 1), drawing(notesText)))
        // The document: 10 × 7.5 inches, two fonts, the picture's store entry, and the slide lists.
        val fbse = rec(0xF007, byteArrayOf(6, 6) + ByteArray(16) + le16(0xFF) + le32(png.size + 25) + le32(1) + le32(0) + ByteArray(4), inst = 6, ver = 2)
        val slideTexts = box(4080,
            persistAtom(3, 256), header(0), chars("Quarterly review"),
            header(1), chars("First point\rSecond point"),
            style(listOf(Triple(12, 0, pf()), Triple(13, 1, pf())), listOf(5 to cf(bold = true), 20 to cf())),
            persistAtom(4, 257), inst = 0)
        val document = s.add(box(1000,
            rec(1001, le32(5760) + le32(4320) + le32(4320) + le32(5760) + ByteArray(8) + le32(0) + le32(0) + le16(1) + le16(0) + ByteArray(4), ver = 1),
            box(1010, box(2005, fontEntity(0, "Arial"), fontEntity(1, "SimSun"))),
            box(1035, box(0xF000, box(0xF001, fbse, inst = 1), opt(0x0181 to 0xFFFFFF, 0x01C0 to 0x000000))),
            box(4080, persistAtom(2, masterId), inst = 1),
            slideTexts,
            box(4080, persistAtom(5, 256), inst = 2)))
        val dir1 = s.add(persistDirectory(mapOf(1 to document, 2 to master, 3 to slide1Old, 4 to slide2, 5 to notes)))
        val edit1 = s.add(userEdit(0, dir1, 1))
        // The appended edit: slide 1 again, now with a turned, flipped ellipse of its own text, a group and a line.
        val ellipse = box(0xF004, fsp(3, 1027, 0xA40), opt(0x0004 to (30 shl 16), 0x0181 to ref(0x3366FF), 0x01C0 to ref(0x003399), 0x01CB to 25400),
            anchor(3500, 2500, 4500, 3300), box(0xF00D, header(4), chars("Hi"), style(listOf(Triple(3, 0, pf())), listOf(3 to cf(size = 20, rgb = 0x112233)))))
        val groupShape = box(0xF004, rec(0xF009, le32(0) + le32(0) + le32(1000) + le32(1000), ver = 1), fsp(0, 1028, 0x201), anchor(1000, 3000, 2000, 4000))
        val inGroup = box(0xF004, fsp(1, 1029, 0xA02), opt(0x0181 to ref(0x00FF00)), rec(0xF00F, le32(500) + le32(0) + le32(1000) + le32(500)))
        val group = box(0xF003, groupShape, inGroup)
        val line = box(0xF004, fsp(20, 1030, 0xA00), opt(0x01C0 to ref(0x0000FF)), anchor(100, 4200, 5600, 4200))
        val gradient = box(0xF004, fsp(1, 1031, 0xA00), opt(0x0180 to 7, 0x0181 to ref(0xFF0000), 0x0183 to ref(0x0000FF), 0x018C to 100), anchor(4600, 3500, 5600, 4100))
        val slide1New = s.add(box(1006, slideAtom(1, masterId, 0x7), drawing(title, body, ellipse, group, line, gradient)))
        val dir2 = s.add(persistDirectory(mapOf(3 to slide1New)))
        val edit2 = s.add(userEdit(edit1, dir2, 1))
        val pictures = rec(0xF01E, ByteArray(16) + byteArrayOf(0xFF.toByte()) + png, inst = 0x6E0)
        return TestCfb.build(mapOf("PowerPoint Document" to s.out.toByteArray(), "Current User" to currentUser(edit2), "Pictures" to pictures))
    }

    @Test fun slidesComeInTheirLatestVersion() {
        val p = PptReader.read(deck())
        assertEquals(2, p.slides.size)
        assertEquals(720f, p.slideSize.widthPt, 0.01f); assertEquals(540f, p.slideSize.heightPt, 0.01f)
        // The appended edit's slide 1 (its ellipse, the shape in its group, a line and a shaded box), not the first saved one.
        assertEquals(4, p.slides[0].shapes.size)
    }

    @Test fun placeholdersTakeTheSlideListTextAndTheMastersStyles() {
        val s = PptReader.read(deck()).slides[0]
        assertEquals("Quarterly review", s.title.text)
        val t = s.title.paragraph.runs.first().format
        assertEquals(44f, t.fontSizePt); assertEquals("Arial", t.fontFamily); assertEquals(0xFF1F4E79, t.color)
        assertEquals(com.therealityteam.aeropages.model.TextAlign.CENTER, s.title.paragraph.format.align)
        assertEquals(500f / 5760f, s.title.frame!!.xPct, 1e-4f); assertEquals(400f / 4320f, s.title.frame!!.yPct, 1e-4f)
        // The second paragraph a level down (a tab), the first word bold, bullets from the master's body style.
        assertEquals("First point\n\tSecond point", s.body.text)
        assertEquals(SlideListStyle.BULLET, s.body.listStyle)
        val runs = s.body.paragraph.runs
        assertTrue(runs.first { it.text.startsWith("First") }.format.bold)
        assertFalse(runs.first { it.text.contains("point") && !it.text.startsWith("First") }.format.bold)
        assertEquals(28f, runs.last().format.fontSizePt)
        assertEquals(32f, runs.first().format.fontSizePt)
    }

    @Test fun shapesKeepTheirLookTurnAndText() {
        val shapes = PptReader.read(deck()).slides[0].shapes
        val e = shapes.first { it.type == ShapeType.ELLIPSE }
        assertEquals(0xFF3366FF, e.fill); assertEquals(0xFF003399, e.border); assertEquals(2f, e.borderWidthPt, 0.01f)
        assertEquals(30f, e.rotationDeg, 0.01f); assertTrue(e.flipH); assertFalse(e.flipV)
        assertEquals("Hi", e.box.text)
        assertEquals(20f, e.box.paragraph.runs.first().format.fontSizePt); assertEquals(0xFF112233, e.box.paragraph.runs.first().format.color)
        // The group's child, from its own space (0..1000) to where the group sits (1000..2000 across, 3000..4000 down).
        val child = shapes.first { it.fill == 0xFF00FF00 }
        assertEquals(1500f / 5760f, child.xPct, 1e-4f); assertEquals(3000f / 4320f, child.yPct, 1e-4f)
        assertEquals(500f / 5760f, child.wPct, 1e-4f); assertEquals(500f / 4320f, child.hPct, 1e-4f)
        assertNotNull(child.groupId)
        val line = shapes.first { it.type == ShapeType.LINE }
        assertEquals(0xFF0000FF, line.border); assertNull(line.fill)
        // A shade from red to blue, its focus at the end: blue first.
        val g = shapes.first { it.fillGradient != null }
        assertEquals(0xFF0000FF, g.fill); assertEquals(0xFFFF0000, g.fillGradient); assertEquals(GradientKind.LINEAR, g.gradientKind)
    }

    @Test fun theMasterIsTheSlidesDesign() {
        val p = PptReader.read(deck())
        val design = p.designs.single()
        assertEquals(0xFFEAF2FB, design.background)
        val band = design.items.filterIsInstance<DesignShape>().single().shape
        assertEquals(0xFFFF0000, band.fill); assertEquals(1f, band.wPct, 1e-4f)
        assertEquals(design.name, p.slides[0].design); assertTrue(p.slides[0].designObjects)
        assertNull(p.slides[0].background)
    }

    @Test fun aSlideWithItsOwnBackgroundAndAPicture() {
        val s = PptReader.read(deck()).slides[1]
        assertEquals(0xFF00AA00, s.background)
        assertEquals(com.therealityteam.aeropages.model.SlideLayout.BLANK, s.layout)
        // Its master's band hidden (objects flag clear), its background its own.
        assertFalse(s.designObjects)
        val box = s.shapes.single()
        assertEquals(ShapeType.TEXTBOX, box.type); assertEquals("Plain words", box.box.text)
        assertEquals(18f, box.box.paragraph.runs.first().format.fontSizePt)
        val img = s.images.single()
        assertEquals("image/png", img.mime); assertTrue(img.data.contentEquals(png))
        assertEquals(0.1f, img.cropL, 0.001f); assertEquals(0f, img.cropR, 0.001f)
        assertEquals(3000f / 5760f, img.xPct, 1e-4f)
    }

    @Test fun notesGoToTheSlideTheyName() {
        val p = PptReader.read(deck())
        assertEquals("Say hello first", p.slides[0].notes)
        assertEquals("", p.slides[1].notes)
    }

    @Test fun lineBreaksWithinAParagraphStay() {
        // A vertical tab is a line break within its paragraph, kept as the app's own.
        val s = Stream()
        val title = box(0xF004, fsp(1, 1025, 0xA20), anchor(500, 400, 5260, 1100), placeholder(0x0D), box(0xF00D, header(0), chars("One\u000Btwo")))
        val slide = s.add(box(1006, slideAtom(1, 0, 0x7), drawing(title)))
        val doc = s.add(box(1000, rec(1001, le32(5760) + le32(4320) + ByteArray(32), ver = 1), box(4080, persistAtom(2, 256), inst = 0)))
        val dir = s.add(persistDirectory(mapOf(1 to doc, 2 to slide)))
        val edit = s.add(userEdit(0, dir, 1))
        val p = PptReader.read(TestCfb.build(mapOf("PowerPoint Document" to s.out.toByteArray(), "Current User" to currentUser(edit))))
        assertEquals("One${SlideLevels.LINE_BREAK}two", p.slides.single().title.text)
    }

    @Test fun aFileWhoseStructureCannotBeFollowedReadsAsText() {
        // Slide containers with text but no document, persist directory or edit: the text, slide by slide.
        val stream = cat(
            box(1006, box(0xF00D, header(0), chars("Lost title"), header(1), chars("a line\rnext line"))),
            box(1016, box(0xF00D, chars("Master text, not a slide's"))),
            box(1006, box(0xF00D, rec(4008, "Second".toByteArray(Charsets.ISO_8859_1)))),
        )
        val p = PptReader.read(TestCfb.build(mapOf("PowerPoint Document" to stream)))
        assertEquals(listOf("Lost title", "Second"), p.slides.map { it.title.text })
        assertEquals("a line\nnext line", p.slides[0].body.text)
    }

    /** An OfficeArtFOPT with complex properties after the simple ones ([headerless]: an array's size given without its 6-byte header, as some writers give it). */
    private fun optc(simple: List<Pair<Int, Int>>, complex: List<Pair<Int, ByteArray>>, headerless: Boolean = false): ByteArray {
        val entries = simple.map { (id, v) -> le16(id) + le32(v) } + complex.map { (id, d) -> le16(id or 0x8000) + le32(if (headerless && id in 0x0145..0x0146) d.size - 6 else d.size) }
        return rec(0xF00B, cat(*entries.toTypedArray(), *complex.map { it.second }.toTypedArray()), inst = simple.size + complex.size, ver = 3)
    }

    private fun msoArray(size: Int, elements: List<ByteArray>) = le16(elements.size) + le16(elements.size) + le16(size) + cat(*elements.toTypedArray())

    /** A deck of one slide (no master) drawing [shapes]. */
    private fun oneSlide(vararg shapes: ByteArray): com.therealityteam.aeropages.model.Slide {
        val s = Stream()
        val slide = s.add(box(1006, slideAtom(16, 0, 0x7), drawing(*shapes)))
        val doc = s.add(box(1000, rec(1001, le32(5760) + le32(4320) + ByteArray(32), ver = 1), box(4080, persistAtom(2, 256), inst = 0)))
        val dir = s.add(persistDirectory(mapOf(1 to doc, 2 to slide)))
        val edit = s.add(userEdit(0, dir, 1))
        return PptReader.read(TestCfb.build(mapOf("PowerPoint Document" to s.out.toByteArray(), "Current User" to currentUser(edit)))).slides.single()
    }

    @Test fun aFreeformIsDrawnFromItsVertices() {
        // A triangle in a 100-unit space, its arrays' sizes given without their headers.
        val vertices = msoArray(8, listOf(le32(50) + le32(0), le32(100) + le32(100), le32(0) + le32(100)))
        val segments = msoArray(2, listOf(le16(0x4000), le16(0x0002), le16(0x6001), le16(0x8000)))
        val shape = box(0xF004, fsp(0, 1025, 0xA00), optc(listOf(0x0142 to 100, 0x0143 to 100, 0x0181 to ref(0xFF0000)), listOf(0x0145 to vertices, 0x0146 to segments), headerless = true),
            anchor(576, 576, 1152, 1152))
        // A shape of its own geometry with none, holding text: the text alone.
        val bare = box(0xF004, fsp(0, 1026, 0xA00), anchor(2000, 576, 3000, 1152), box(0xF00D, header(4), chars("Only words")))
        val s = oneSlide(shape, bare)
        val f = s.shapes.first { it.type == ShapeType.FREEFORM }
        assertNull(f.preset)
        assertEquals(0xFFFF0000, f.fill)
        val ops = f.path!!.parts.single().ops
        assertEquals(com.therealityteam.aeropages.model.Freeform.Move(0.5f, 0f), ops[0])
        assertEquals(com.therealityteam.aeropages.model.Freeform.Close, ops.last())
        val t = s.shapes.first { it.type == ShapeType.TEXTBOX }
        assertEquals("Only words", t.box.text); assertNull(t.border); assertNull(t.fill)
    }

    @Test fun adjustValuesBecomeDrawingMLs() {
        // A rounded rectangle at its roundest (10800 of 21600: half the shorter side), a rectangular callout with
        // Office's default tail (at 1350, 25920: DrawingML's are elsewhere), a plain round rectangle, and a trapezoid.
        val round = box(0xF004, fsp(2, 1025, 0xA00), opt(0x0147 to 10800), anchor(0, 0, 1000, 1000))
        val callout = box(0xF004, fsp(61, 1026, 0xA00), anchor(1000, 0, 2000, 1000))
        val plain = box(0xF004, fsp(2, 1027, 0xA00), anchor(2000, 0, 3000, 1000))
        val trapezoid = box(0xF004, fsp(8, 1028, 0xA00), anchor(3000, 0, 4000, 1000))
        val shapes = oneSlide(round, callout, plain, trapezoid).shapes
        assertEquals(ShapeType.FREEFORM, shapes[0].type)
        assertEquals("roundRect", shapes[0].preset); assertEquals("adj=val 50000", shapes[0].presetAdjust)
        assertEquals("wedgeRectCallout", shapes[1].preset); assertEquals("adj1=val -43750|adj2=val 70000", shapes[1].presetAdjust)
        assertEquals(ShapeType.ROUNDED, shapes[2].type)
        // The trapezoid upside down from DrawingML's: its long side at the top.
        val t = shapes[3].path!!.parts.first().ops
        assertEquals(com.therealityteam.aeropages.model.Freeform.Move(0f, 0f), t[0])
        assertEquals(1f, (t[1] as com.therealityteam.aeropages.model.Freeform.Line).y, 1e-4f)
    }

    @Test fun flagsAreMergedBitByBit() {
        // The shape says it is not filled (use bit and flag clear); its tertiary properties set other flags only.
        val tertiary = rec(0xF122, le16(0x01BF) + le32(0x600000), inst = 1, ver = 3)
        val unfilled = box(0xF004, fsp(1, 1025, 0xA00), opt(0x01BF to 0x180008, 0x0181 to ref(0xFF0000)), tertiary, anchor(0, 0, 1000, 1000),
            box(0xF00D, header(4), chars("Words")))
        val sh = oneSlide(unfilled).shapes.single()
        assertNull(sh.fill)
    }

    @Test fun seeThroughFillsAndColoursOfTheShapesOwn() {
        // 25% opaque red, its gradient's second colour its fill colour darkened by half (a system colour, 0xF0) and clear.
        val glass = box(0xF004, fsp(3, 1025, 0xA00), opt(0x0180 to 7, 0x0181 to ref(0xFF8080), 0x0182 to 0x4000,
            0x0183 to (0x10000000 or (0x80 shl 16) or (0x01 shl 8) or 0xF0), 0x0184 to 0), anchor(0, 0, 1000, 1000))
        val sh = oneSlide(glass).shapes.single()
        assertEquals(0x40FF8080L, sh.fill)
        assertEquals(0x00800000L or 0x4040L, sh.fillGradient)
    }

    @Test fun otherTextTakesTheDocumentsStyleWhenTheMasterHasNone() {
        // The document's own "other" style (its Environment's TxMasterStyleAtom, type 4): 18 pt; the master has no type 4 style.
        val s = Stream()
        val master = s.add(box(1016, slideAtom(1, 0, 0), rec(4003, le16(1) + pf() + cf(size = 32), inst = 1)))
        val text = box(0xF004, fsp(202, 1025, 0xA00), anchor(500, 500, 3000, 1000), box(0xF00D, header(4), chars("Other words")))
        val slide = s.add(box(1006, slideAtom(16, masterId, 0x7), drawing(text)))
        val doc = s.add(box(1000, rec(1001, le32(5760) + le32(4320) + ByteArray(32), ver = 1),
            box(1010, rec(4003, le16(1) + pf() + cf(size = 18), inst = 4)),
            box(4080, persistAtom(2, masterId), inst = 1), box(4080, persistAtom(3, 256), inst = 0)))
        val dir = s.add(persistDirectory(mapOf(1 to doc, 2 to master, 3 to slide)))
        val edit = s.add(userEdit(0, dir, 1))
        val p = PptReader.read(TestCfb.build(mapOf("PowerPoint Document" to s.out.toByteArray(), "Current User" to currentUser(edit))))
        assertEquals(18f, p.slides.single().shapes.single().box.paragraph.runs.first().format.fontSizePt)
    }

    @Test fun aShadedBackgroundIsTheSlidesGradient() {
        // The slide's own background (flags: master objects and scheme, not its background): blue shading to white
        // from the top (angle 0 runs down; the focus at its end, the second colour first).
        val s = Stream()
        val background = box(0xF004, fsp(1, 1025, 0xC00), opt(0x0180 to 7, 0x0181 to ref(0x0070C0), 0x0183 to ref(0xFFFFFF), 0x018C to 0))
        val slide = s.add(box(1006, slideAtom(16, 0, 0x3), drawing(background = background)))
        val doc = s.add(box(1000, rec(1001, le32(5760) + le32(4320) + ByteArray(32), ver = 1), box(4080, persistAtom(2, 256), inst = 0)))
        val dir = s.add(persistDirectory(mapOf(1 to doc, 2 to slide)))
        val edit = s.add(userEdit(0, dir, 1))
        val p = PptReader.read(TestCfb.build(mapOf("PowerPoint Document" to s.out.toByteArray(), "Current User" to currentUser(edit))))
        val sl = p.slides.single()
        assertEquals(0xFF0070C0, sl.background)
        val shade = sl.backgroundShade!!
        assertEquals(0xFFFFFFFF, shade.second); assertEquals(GradientKind.LINEAR, shade.kind); assertEquals(270f, shade.angleDeg, 0.01f)
    }

    @Test fun wordArtIsItsTextInItsFill() {
        val words = "WordArt\u0000".toByteArray(Charsets.UTF_16LE)
        val face = "Georgia\u0000".toByteArray(Charsets.UTF_16LE)
        // Bold, stretched to its box (bits 5 and 10 with their use bits), filled green.
        val art = box(0xF004, fsp(136, 1025, 0xA00),
            optc(listOf(0x00C3 to (36 shl 16), 0x00FF to 0x04200420, 0x0181 to ref(0x00AA00)), listOf(0x00C0 to words, 0x00C5 to face)),
            anchor(576, 576, 2880, 1152))
        val sh = oneSlide(art).shapes.single()
        assertEquals(ShapeType.TEXTBOX, sh.type); assertEquals("WordArt", sh.box.text)
        assertNull(sh.fill); assertNull(sh.border)
        val f = sh.box.paragraph.runs.first().format
        assertEquals("Georgia", f.fontFamily); assertTrue(f.bold); assertEquals(0xFF00AA00, f.color)
        // As tall as its box (576 units = 72 pt) lets one line be; the box widened about its centre to hold it.
        assertEquals(72f, f.fontSizePt!!, 0.01f)
        assertEquals((576 + 2880) / 2f / 5760f, sh.xPct + sh.wPct / 2f, 1e-4f)
        assertTrue(sh.wPct > 2304f / 5760f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCompoundFileWithoutAPresentationIsNotOne() {
        PptReader.read(TestCfb.build(mapOf("WordDocument" to ByteArray(100))))
    }

    /** A shape's InteractiveInfoContainer (instance 0: a click, 1: the pointer over it) with its atom. */
    private fun interactive(inst: Int, action: Int, jump: Int = 0, kind: Int = 0xFF, link: Int = 0) =
        box(0x0FF2, rec(0x0FF3, le32(0) + le32(link) + byteArrayOf(action.toByte(), 0, jump.toByte(), 0, kind.toByte(), 0, 0, 0)), inst = inst)

    private fun hyperlink(id: Int, target: String, location: String) = box(0x0FD7, rec(0x0FD3, le32(id)),
        rec(0x0FBA, target.toByteArray(Charsets.UTF_16LE), inst = 1), rec(0x0FBA, location.toByteArray(Charsets.UTF_16LE), inst = 3))

    @Test fun clicksAndPointingAtShapesTakeTheirActions() {
        val s = Stream()
        // Slide 1: a rectangle going on with a click and to the third slide when pointed at, a rectangle linked to an
        // address, and PowerPoint's Home button going to the first slide.
        val next = box(0xF004, fsp(1, 1025, 0xA00), anchor(500, 500, 1500, 1000), box(0xF011, interactive(0, 3, jump = 1), interactive(1, 4, kind = 7, link = 1)))
        val web = box(0xF004, fsp(1, 1026, 0xA00), anchor(2000, 500, 3000, 1000), box(0xF011, interactive(0, 4, kind = 8, link = 2)))
        val home = box(0xF004, fsp(190, 1027, 0xA00), anchor(3500, 500, 4500, 1000), box(0xF011, interactive(0, 3, jump = 3)))
        // A text box whose "site" goes to the address and "slide three" to the third slide.
        val words = box(0xF004, fsp(202, 1028, 0xA00), anchor(500, 2000, 4500, 2500), box(0xF00D, header(4), chars("See the site or slide three"),
            interactive(0, 4, kind = 8, link = 2), rec(0x0FDF, le32(8) + le32(12)), interactive(0, 4, kind = 7, link = 1), rec(0x0FDF, le32(16) + le32(27))))
        val slide1 = s.add(box(1006, slideAtom(16, 0, 0x7), drawing(next, web, home, words)))
        // Slide 2: back (last viewed), slide 3 plain.
        val back = box(0xF004, fsp(1, 2049, 0xA00), anchor(500, 500, 1500, 1000), box(0xF011, interactive(0, 3, jump = 5)))
        val slide2 = s.add(box(1006, slideAtom(16, 0, 0x7), drawing(back)))
        val slide3 = s.add(box(1006, slideAtom(16, 0, 0x7), drawing()))
        val doc = s.add(box(1000, rec(1001, le32(5760) + le32(4320) + ByteArray(32), ver = 1),
            box(0x0409, rec(0x040A, le32(2)), hyperlink(1, "", "258,3,Three"), hyperlink(2, "https://example.com/", "")),
            box(4080, persistAtom(2, 256), persistAtom(3, 257), persistAtom(4, 258), inst = 0)))
        val dir = s.add(persistDirectory(mapOf(1 to doc, 2 to slide1, 3 to slide2, 4 to slide3)))
        val edit = s.add(userEdit(0, dir, 1))
        val p = PptReader.read(TestCfb.build(mapOf("PowerPoint Document" to s.out.toByteArray(), "Current User" to currentUser(edit))))
        val shapes = p.slides[0].shapes
        assertEquals(com.therealityteam.aeropages.model.SlideLinks.NEXT, shapes[0].action)
        assertEquals(2, com.therealityteam.aeropages.model.SlideLinks.indexIn(p, shapes[0].hoverAction))
        assertEquals("https://example.com/", shapes[1].action); assertNull(shapes[1].hoverAction)
        assertEquals("actionButtonHome", shapes[2].preset)
        assertEquals(com.therealityteam.aeropages.model.SlideLinks.FIRST, shapes[2].action)
        assertEquals(com.therealityteam.aeropages.model.SlideLinks.PREVIOUS, p.slides[1].shapes.single().action)
        val runs = shapes[3].box.paragraph.runs.map { it.text to it.format.link?.let { l -> com.therealityteam.aeropages.model.SlideLinks.indexIn(p, l)?.let { i -> "slide $i" } ?: l } }
        assertEquals(listOf("See the " to null, "site" to "https://example.com/", " or " to null, "slide three" to "slide 2"), runs)
    }

    /** With `-Daero.pptActions=<file>`: a .ppt LibreOffice wrote from ActionSettingsTest's click-actions.pptx keeps its clicks. */
    @Test fun libreOfficesClickActionsAreRead() {
        val f = System.getProperty("aero.pptActions")?.let { java.io.File(it) }?.takeIf { it.isFile } ?: return
        val p = PptReader.read(f.readBytes())
        val acts = p.slides[0].shapes.map { com.therealityteam.aeropages.model.SlideLinks.indexIn(p, it.action)?.let { i -> "slide $i" } ?: it.action }
        assertEquals(listOf("#next", "slide 2", "https://example.com/", "#end"), acts)
        assertEquals(com.therealityteam.aeropages.model.SlideLinks.FIRST, p.slides[0].images.single().action)
        assertEquals(com.therealityteam.aeropages.model.SlideLinks.PREVIOUS, p.slides[1].shapes.single().action)
    }

    /** With `-Daero.pptLinks=<file>`: every link in the text of a .ppt (LibreOffice's, from the slide-links sample), printed and checked. */
    @Test fun libreOfficesTextLinksAreRead() {
        val f = System.getProperty("aero.pptLinks")?.let { java.io.File(it) }?.takeIf { it.isFile } ?: return
        val p = PptReader.read(f.readBytes())
        val links = p.slides.flatMapIndexed { i, s -> (listOf(s.title, s.body) + s.shapes.map { it.box }).flatMap { b -> b.paragraph.runs.filter { it.format.link != null }.map { r ->
            "$i ${r.text.trim()} -> " + (com.therealityteam.aeropages.model.SlideLinks.indexIn(p, r.format.link)?.let { "slide $it" } ?: r.format.link) } } }
        assertTrue(links.toString(), links.contains("0 Part A — the light -> slide 1"))
        assertTrue(links.toString(), links.contains("0 Part B — the shade -> slide 2"))
        assertTrue(links.toString(), links.contains("0 example.com -> https://example.com/"))
    }
}
