package com.therealityteam.aeropages.format.ppt

import com.therealityteam.aeropages.format.doc.CompoundFileReader
import com.therealityteam.aeropages.model.BoxFrame
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideSize
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun

/**
 * The legacy binary PowerPoint (.ppt, PowerPoint 97–2003 — and WPS Presentation's .dps and .dpt,
 * the same inside), read as the slides it shows ([MS-PPT], its drawings [MS-ODRAW]):
 *
 * - the current version of each record through the persist directory (`Current User` → the latest
 *   UserEditAtom → its PersistDirectoryAtom, then the older edits' for what they kept), so a file
 *   PowerPoint saved by appending its edits reads as it was last saved;
 * - the slide size (DocumentAtom, 576 units an inch), the fonts (FontCollection), the masters' text
 *   styles (TxMasterStyleAtom) and colour schemes, a slide's background;
 * - each slide's drawing (PPDrawing → OfficeArt shapes): the title and body placeholders as the
 *   slide's title and body at their place, other shapes and text boxes at theirs (Office's preset
 *   geometry by shape type, fill, outline, turn and flips, groups), pictures from the `Pictures`
 *   stream (PNG, JPEG, DIB, and EMF / WMF kept for saving);
 * - text with its paragraphs' levels, bullets and alignment and its runs' face, size, bold, italic,
 *   underline and colour (StyleTextPropAtom over the master's styles) — in the shape's text box, or,
 *   as PowerPoint keeps a placeholder's text, in the slide list (SlideListWithText);
 * - the speaker notes (each Notes' body, by the slide it names).
 *
 * A file whose structure cannot be followed falls back to its text alone, slide by slide.
 */
object PptReader {

    fun read(bytes: ByteArray): Presentation {
        val cfb = CompoundFileReader(bytes)
        // No document stream: not a presentation (a blank one could be saved over the file).
        val stream = cfb.readStream("PowerPoint Document") ?: cfb.readStream("PP40") ?: throw IllegalArgumentException("Not a PowerPoint file: no PowerPoint Document stream")
        val structured = runCatching { Reading(stream, cfb.readStream("Pictures"), cfb.readStream("Current User")).presentation() }.getOrNull()
        return (structured?.takeIf { it.slides.isNotEmpty() } ?: textOnly(stream)).also { it.props = com.therealityteam.aeropages.format.doc.OleSummary.read(cfb) }
    }

    // --- records -----------------------------------------------------------------------------

    internal const val DOCUMENT = 1000
    internal const val DOCUMENT_ATOM = 1001
    internal const val SLIDE = 1006
    internal const val SLIDE_ATOM = 1007
    internal const val NOTES = 1008
    internal const val NOTES_ATOM = 1009
    internal const val ENVIRONMENT = 1010
    internal const val SLIDE_PERSIST_ATOM = 1011
    internal const val MAIN_MASTER = 1016
    internal const val PP_DRAWING_GROUP = 1035
    internal const val PP_DRAWING = 1036
    internal const val COLOR_SCHEME_ATOM = 2032
    internal const val FONT_COLLECTION = 2005
    internal const val OE_PLACEHOLDER_ATOM = 3011
    internal const val OUTLINE_TEXT_REF_ATOM = 3998
    internal const val TEXT_HEADER_ATOM = 3999
    internal const val TEXT_CHARS_ATOM = 4000
    internal const val STYLE_TEXT_PROP_ATOM = 4001
    internal const val TEXT_BYTES_ATOM = 4008
    internal const val TX_MASTER_STYLE_ATOM = 4003
    internal const val FONT_ENTITY_ATOM = 4023
    internal const val SLIDE_LIST_WITH_TEXT = 4080
    internal const val USER_EDIT_ATOM = 4085
    internal const val PERSIST_DIRECTORY_ATOM = 6002
    internal const val EX_OBJ_LIST = 0x0409
    /** A link to a slide by its id in the file, while slides are being read. */
    private const val PPT_SLIDE = "#pptslide:"
    internal const val EX_HYPERLINK = 0x0FD7
    internal const val EX_HYPERLINK_ATOM = 0x0FD3
    internal const val CSTRING = 0x0FBA
    internal const val INTERACTIVE_INFO = 0x0FF2
    internal const val INTERACTIVE_INFO_ATOM = 0x0FF3
    internal const val TX_INTERACTIVE_INFO_ATOM = 0x0FDF

    // OfficeArt
    internal const val DGG_CONTAINER = 0xF000
    internal const val BSTORE_CONTAINER = 0xF001
    internal const val DG_CONTAINER = 0xF002
    internal const val SPGR_CONTAINER = 0xF003
    internal const val SP_CONTAINER = 0xF004
    internal const val FBSE = 0xF007
    internal const val FSPGR = 0xF009
    internal const val FSP = 0xF00A
    internal const val FOPT = 0xF00B
    internal const val CLIENT_TEXTBOX = 0xF00D
    internal const val CHILD_ANCHOR = 0xF00F
    internal const val CLIENT_ANCHOR = 0xF010
    internal const val CLIENT_DATA = 0xF011
    internal const val TERTIARY_FOPT = 0xF122

    /** A record: its type and instance, and where its content lies in its stream. */
    internal class Rec(val type: Int, val inst: Int, val ver: Int, val start: Int, val len: Int) {
        val end: Int get() = start + len
        val container: Boolean get() = ver == 0xF
    }

    internal fun recAt(b: ByteArray, off: Int, limit: Int = b.size): Rec? {
        if (off < 0 || off + 8 > limit) return null
        val vi = u16(b, off)
        val len = u32(b, off + 4)
        if (len < 0 || off + 8 + len > limit) return null
        return Rec(u16(b, off + 2), vi ushr 4, vi and 0xF, off + 8, len)
    }

    /** The records between [start] and [end], in order (stopping at the first that does not fit). */
    internal fun children(b: ByteArray, start: Int, end: Int): List<Rec> {
        val out = ArrayList<Rec>()
        var off = start
        while (off + 8 <= end) {
            val r = recAt(b, off, end) ?: break
            out += r
            off = r.end
        }
        return out
    }

    internal fun children(b: ByteArray, r: Rec): List<Rec> = children(b, r.start, r.end)

    internal fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    internal fun i16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt()
    internal fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    // --- the fallback: text alone --------------------------------------------------------------

    /** Each Slide container's text, its first line the title — for a file whose structure cannot be followed. */
    internal fun textOnly(stream: ByteArray): Presentation {
        val slides = ArrayList<MutableList<String>>()
        fun walk(start: Int, end: Int, inSlide: Boolean) {
            for (r in children(stream, start, end)) {
                when {
                    r.type == SLIDE -> { slides.add(ArrayList()); walk(r.start, r.end, true) }
                    // A master's, a notes page's or the handout's text is not a slide's.
                    r.type == MAIN_MASTER || r.type == NOTES || r.type == 4041 -> Unit
                    r.container -> walk(r.start, r.end, inSlide)
                    inSlide && r.type == TEXT_CHARS_ATOM -> addText(slides, String(stream, r.start, r.len, Charsets.UTF_16LE))
                    inSlide && r.type == TEXT_BYTES_ATOM -> addText(slides, String(stream, r.start, r.len, Charsets.ISO_8859_1))
                }
            }
        }
        walk(0, stream.size, false)
        val result = slides.map { texts -> texts.filter { it.isNotBlank() } }.filter { it.isNotEmpty() }.map { texts ->
            Slide(
                title = SlideBox(texts.first(), CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER),
                body = SlideBox(texts.drop(1).joinToString("\n"), CharacterFormat(fontSizePt = 22f)),
            )
        }
        return Presentation(result.ifEmpty { listOf(Slide()) }.toMutableList())
    }

    private fun addText(slides: ArrayList<MutableList<String>>, raw: String) {
        val clean = raw.replace('\r', '\n').replace('\u000B', '\n').replace("\u0000", "").trim()
        if (clean.isEmpty()) return
        (slides.lastOrNull() ?: ArrayList<String>().also { slides.add(it) }).add(clean)
    }

    // --- styles ------------------------------------------------------------------------------

    /** A paragraph's properties as a TextPFException gives them (null: not said). */
    internal class Pf(
        var bullet: Boolean? = null, var bulletChar: Char? = null, var align: Int? = null,
        var lineSpacing: Int? = null, var spaceBefore: Int? = null, var spaceAfter: Int? = null,
        var leftMargin: Int? = null, var indent: Int? = null,
    ) {
        /** These over [base]: what these leave unsaid, as it says. */
        fun over(base: Pf) = Pf(bullet ?: base.bullet, bulletChar ?: base.bulletChar, align ?: base.align, lineSpacing ?: base.lineSpacing,
            spaceBefore ?: base.spaceBefore, spaceAfter ?: base.spaceAfter, leftMargin ?: base.leftMargin, indent ?: base.indent)
    }

    /** A run's character properties as a TextCFException gives them (null: not said). */
    internal class Cf(
        var bold: Boolean? = null, var italic: Boolean? = null, var underline: Boolean? = null,
        var fontRef: Int? = null, var sizePt: Int? = null, var color: Long? = null, var colorIndex: Int? = null,
    ) {
        /** These over [base]; a colour of either kind (its own or the scheme's) stands for both. */
        fun over(base: Cf): Cf {
            val ownColor = color != null || colorIndex != null
            return Cf(bold ?: base.bold, italic ?: base.italic, underline ?: base.underline, fontRef ?: base.fontRef, sizePt ?: base.sizePt,
                if (ownColor) color else base.color, if (ownColor) colorIndex else base.colorIndex)
        }
    }

    /** Reads a TextPFException at [o]; returns it and where it ends. */
    internal fun readPf(b: ByteArray, o0: Int): Pair<Pf, Int> {
        var o = o0
        val m = u32(b, o); o += 4
        val pf = Pf()
        if (m and 0xF != 0) { val flags = u16(b, o); o += 2; if (m and 0x1 != 0) pf.bullet = flags and 0x1 != 0 }
        if (m and 0x80 != 0) { pf.bulletChar = u16(b, o).toChar(); o += 2 }
        if (m and 0x10 != 0) o += 2           // bulletFontRef
        if (m and 0x40 != 0) o += 2           // bulletSize
        if (m and 0x20 != 0) o += 4           // bulletColor
        if (m and 0x800 != 0) { pf.align = u16(b, o); o += 2 }
        if (m and 0x1000 != 0) { pf.lineSpacing = i16(b, o); o += 2 }
        if (m and 0x2000 != 0) { pf.spaceBefore = i16(b, o); o += 2 }
        if (m and 0x4000 != 0) { pf.spaceAfter = i16(b, o); o += 2 }
        if (m and 0x100 != 0) { pf.leftMargin = i16(b, o); o += 2 }
        if (m and 0x400 != 0) { pf.indent = i16(b, o); o += 2 }
        if (m and 0x8000 != 0) o += 2         // defaultTabSize
        if (m and 0x100000 != 0) { val n = u16(b, o); o += 2 + n * 4 }   // tabStops
        if (m and 0x10000 != 0) o += 2        // fontAlign
        if (m and 0xE0000 != 0) o += 2        // wrapFlags
        if (m and 0x200000 != 0) o += 2       // textDirection
        return pf to o
    }

    /** Reads a TextCFException at [o]; returns it and where it ends. */
    internal fun readCf(b: ByteArray, o0: Int): Pair<Cf, Int> {
        var o = o0
        val m = u32(b, o); o += 4
        val cf = Cf()
        if (m and 0x3EB7 != 0) {
            val style = u16(b, o); o += 2
            if (m and 0x1 != 0) cf.bold = style and 0x1 != 0
            if (m and 0x2 != 0) cf.italic = style and 0x2 != 0
            if (m and 0x4 != 0) cf.underline = style and 0x4 != 0
        }
        if (m and 0x10000 != 0) { cf.fontRef = u16(b, o); o += 2 }
        if (m and 0x200000 != 0) o += 2       // oldEAFontRef
        if (m and 0x400000 != 0) o += 2       // ansiFontRef
        if (m and 0x800000 != 0) o += 2       // symbolFontRef
        if (m and 0x20000 != 0) { cf.sizePt = u16(b, o); o += 2 }
        if (m and 0x40000 != 0) {
            val r = b[o].toInt() and 0xFF; val g = b[o + 1].toInt() and 0xFF; val bl = b[o + 2].toInt() and 0xFF; val idx = b[o + 3].toInt() and 0xFF
            if (idx == 0xFE) cf.color = 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or bl.toLong() else if (idx < 8) cf.colorIndex = idx
            o += 4
        }
        if (m and 0x80000 != 0) o += 2        // position
        if (m and 0x100000 != 0) o += 4       // pp10runid
        if (m and 0x1000000 != 0) o += 2      // newEAFontRef
        if (m and 0x2000000 != 0) o += 2      // csFontRef
        if (m and 0x4000000 != 0) o += 4      // pp11ext
        return cf to o
    }

    /** One paragraph run and one character run of a StyleTextPropAtom: how many characters each covers. */
    internal class StyleRuns(val paragraphs: List<Triple<Int, Int, Pf>>, val characters: List<Pair<Int, Cf>>)

    /** The paragraph and character runs of a StyleTextPropAtom over a text of [length] characters. */
    internal fun readStyle(b: ByteArray, r: Rec, length: Int): StyleRuns {
        val paras = ArrayList<Triple<Int, Int, Pf>>()
        val chars = ArrayList<Pair<Int, Cf>>()
        var o = r.start
        var covered = 0
        while (covered < length + 1 && o + 6 <= r.end) {
            val count = u32(b, o); val level = u16(b, o + 4); o += 6
            val (pf, next) = readPf(b, o); o = next
            paras += Triple(count, level, pf); covered += count
            if (count <= 0) break
        }
        covered = 0
        while (covered < length + 1 && o + 4 <= r.end) {
            val count = u32(b, o); o += 4
            val (cf, next) = readCf(b, o); o = next
            chars += count to cf; covered += count
            if (count <= 0) break
        }
        return StyleRuns(paras, chars)
    }

    /** A master's text style for a text type: each level's paragraph and character properties. */
    internal class MasterStyle(val levels: List<Pair<Pf, Cf>>) {
        /** This style over [base], level by level. */
        fun over(base: MasterStyle) = MasterStyle(List(maxOf(levels.size, base.levels.size)) { i ->
            val own = levels.getOrNull(i); val under = base.levels.getOrNull(i)
            when {
                own == null -> under!!
                under == null -> own
                else -> own.first.over(under.first) to own.second.over(under.second)
            }
        })
    }

    internal fun readMasterStyle(b: ByteArray, r: Rec): MasterStyle {
        val levels = ArrayList<Pair<Pf, Cf>>()
        var o = r.start
        val n = u16(b, o); o += 2
        for (k in 0 until n.coerceAtMost(5)) {
            if (r.inst >= 5) o += 2
            val (pf, a) = readPf(b, o)
            val (cf, c) = readCf(b, a)
            levels += pf to cf
            o = c
        }
        return MasterStyle(levels)
    }

    // --- the structured reading ----------------------------------------------------------------

    /** A text of a slide: its type (TextTypeEnum), its characters and its style record. */
    /** A text's type, characters, look and links ([links]: from, to and where, as its TxInteractiveInfoAtoms give them). */
    internal class TextBody(val type: Int, val text: String, val style: Rec?, val links: List<Triple<Int, Int, String>> = emptyList())

    private class Reading(val b: ByteArray, val pictures: ByteArray?, val currentUser: ByteArray?) {
        val persist = HashMap<Int, Int>()
        var docRef: Int? = null
        val fonts = HashMap<Int, String>()
        val blips = ArrayList<Rec?>()
        /** The document's hyperlinks by id ([MS-PPT] ExHyperlinkContainer): where each goes (its target) and its place in a file (location). */
        val hyperlinks = HashMap<Int, Pair<String, String>>()
        val look = OfficeArtLook()
        var defaultOpt: Map<Int, Int>
            get() = look.defaultOpt
            set(v) { look.defaultOpt = v }
        var slideW = 7680; var slideH = 5760
        /** Each master by its id: its text styles by text type, colour scheme, and its drawing as a design. */
        class Master(val styles: Map<Int, MasterStyle>, val scheme: LongArray?, val design: com.therealityteam.aeropages.model.SlideDesign)
        val masters = HashMap<Int, Master>()
        val designs = ArrayList<com.therealityteam.aeropages.model.SlideDesign>()
        /** The document's own text styles (its Environment's), for a text type a master gives none for ("other" text mostly). */
        val docStyles = HashMap<Int, MasterStyle>()

        fun presentation(): Presentation {
            readPersistDirectory()
            val docOff = docRef?.let { persist[it] } ?: firstTopLevel(DOCUMENT) ?: error("no document")
            val doc = recAt(b, docOff)?.takeIf { it.type == DOCUMENT } ?: error("no document container")
            val lists = HashMap<Int, List<Pair<Rec, List<TextBody>>>>()
            for (r in children(b, doc)) when (r.type) {
                DOCUMENT_ATOM -> { slideW = u32(b, r.start); slideH = u32(b, r.start + 4) }
                ENVIRONMENT -> for (e in children(b, r)) when (e.type) {
                    FONT_COLLECTION -> for (f in children(b, e)) if (f.type == FONT_ENTITY_ATOM && f.len >= 64) {
                        fonts[f.inst] = String(b, f.start, 64, Charsets.UTF_16LE).substringBefore('\u0000').trim()
                    }
                    TX_MASTER_STYLE_ATOM -> runCatching { readMasterStyle(b, e) }.getOrNull()?.let { docStyles[e.inst] = it }
                }
                PP_DRAWING_GROUP -> children(b, r).firstOrNull { it.type == DGG_CONTAINER }?.let { dgg ->
                    for (c in children(b, dgg)) when (c.type) {
                        BSTORE_CONTAINER -> children(b, c).forEach { blips += it.takeIf { e -> e.type == FBSE } }
                        FOPT -> defaultOpt = Opt.read(b, c).props
                    }
                }
                SLIDE_LIST_WITH_TEXT -> lists[r.inst] = slideList(r)
                EX_OBJ_LIST -> for (h in children(b, r)) if (h.type == EX_HYPERLINK) {
                    val parts = children(b, h)
                    val id = parts.firstOrNull { it.type == EX_HYPERLINK_ATOM && it.len >= 4 }?.let { u32(b, it.start) } ?: continue
                    fun text(inst: Int) = parts.firstOrNull { it.type == CSTRING && it.inst == inst }?.let { String(b, it.start, it.len, Charsets.UTF_16LE).substringBefore('\u0000').trim() }.orEmpty()
                    hyperlinks[id] = text(1) to text(3)
                }
            }
            // Masters by their ids (a slide names its own), each drawn as a design.
            lists[1]?.forEach { (atom, _) ->
                val id = u32(b, atom.start + 12)
                persist[u32(b, atom.start)]?.let { off ->
                    recAt(b, off)?.takeIf { it.type == MAIN_MASTER }?.let { r -> runCatching { master(r, "Master ${masters.size + 1}") }.getOrNull()?.let { masters[id] = it; designs += it.design } }
                }
            }
            // Title masters (PowerPoint 97's: a slide record of its own, on its main master) — what title slides show.
            lists[1]?.forEach { (atom, _) ->
                val id = u32(b, atom.start + 12)
                persist[u32(b, atom.start)]?.let { off ->
                    recAt(b, off)?.takeIf { it.type == SLIDE }?.let { r -> runCatching { titleMaster(r, "Title Master ${designs.size + 1}") }.getOrNull()?.let { masters[id] = it; designs += it.design } }
                }
            }
            val slides = ArrayList<Slide>()
            val slideIds = HashMap<Int, Slide>()
            for ((atom, texts) in lists[0].orEmpty()) {
                val off = persist[u32(b, atom.start)] ?: continue
                val rec = recAt(b, off)?.takeIf { it.type == SLIDE } ?: continue
                val slide = runCatching { slide(rec, texts) }.getOrNull() ?: continue
                slides += slide
                slideIds[u32(b, atom.start + 12)] = slide
            }
            // The speaker notes, each to the slide its NotesAtom names.
            for ((atom, texts) in lists[2].orEmpty()) {
                val off = persist[u32(b, atom.start)] ?: continue
                val rec = recAt(b, off)?.takeIf { it.type == NOTES } ?: continue
                runCatching { notes(rec, texts) }.getOrNull()?.let { (slideId, text) -> slideIds[slideId]?.notes = text }
            }
            // A design template with masters and no slides (WPS's and PowerPoint's .pot / .dpt designs): one blank slide
            // on its title master (else its master), as PowerPoint and LibreOffice open it.
            if (slides.isEmpty() && designs.isNotEmpty()) {
                val first = Slide(layout = SlideLayout.TITLE)
                first.title.paragraph.runs.clear(); first.title.paragraph.runs.add(TextRun("", CharacterFormat()))
                first.body.paragraph.runs.clear(); first.body.paragraph.runs.add(TextRun("", CharacterFormat()))
                first.design = (designs.firstOrNull { it.name.startsWith("Title Master") } ?: designs.first()).name
                slides += first
            }
            val p = Presentation(slides, slideSize = SlideSize.of(slideW / 8f, slideH / 8f))
            // Actions that go to a slide name it by its id in the file: now every slide is read, by ours.
            com.therealityteam.aeropages.model.SlideLinks.remap(p) { link ->
                if (link.startsWith(PPT_SLIDE)) slideIds[link.removePrefix(PPT_SLIDE).toIntOrNull() ?: -1]?.let(com.therealityteam.aeropages.model.SlideLinks::toSlide) else link
            }
            // The designs the slides draw on (a master that draws nothing but a colour is that colour).
            p.designs.addAll(designs.filter { d -> slides.any { it.design == d.name } })
            return p
        }

        /** The persist directory: every persist id's offset in its latest version. */
        fun readPersistDirectory() {
            var edit: Int? = currentUser?.takeIf { it.size >= 20 && u32(it, 12) == 0xE391C05F.toInt() }?.let { u32(it, 16) } ?: lastUserEdit()
            val seen = HashSet<Int>()
            while (edit != null && seen.add(edit)) {
                val r = recAt(b, edit)?.takeIf { it.type == USER_EDIT_ATOM && it.len >= 20 } ?: break
                if (docRef == null) docRef = u32(b, r.start + 16)
                recAt(b, u32(b, r.start + 12))?.takeIf { it.type == PERSIST_DIRECTORY_ATOM }?.let { dir ->
                    var o = dir.start
                    while (o + 4 <= dir.end) {
                        val v = u32(b, o); o += 4
                        val id = v and 0xFFFFF; val n = v ushr 20
                        for (k in 0 until n) { if (o + 4 > dir.end) break; persist.putIfAbsent(id + k, u32(b, o)); o += 4 }
                    }
                }
                edit = u32(b, r.start + 8).takeIf { it > 0 }
            }
        }

        /** The last UserEditAtom at the top level, for a file without a usable Current User stream. */
        fun lastUserEdit(): Int? = children(b, 0, b.size).lastOrNull { it.type == USER_EDIT_ATOM }?.let { it.start - 8 }

        fun firstTopLevel(type: Int): Int? = children(b, 0, b.size).firstOrNull { it.type == type }?.let { it.start - 8 }

        /** A SlideListWithText: each SlidePersistAtom with the texts that follow it. */
        fun slideList(r: Rec): List<Pair<Rec, List<TextBody>>> {
            val out = ArrayList<Pair<Rec, MutableList<TextBody>>>()
            var type = -1; var text: String? = null; var style: Rec? = null
            val links = TextLinks()
            fun flush() { if (text != null && out.isNotEmpty()) out.last().second += TextBody(type, text!!, style, links.taken()); text = null; style = null; links.clear() }
            for (c in children(b, r)) when (c.type) {
                SLIDE_PERSIST_ATOM -> { flush(); out += c to ArrayList() }
                TEXT_HEADER_ATOM -> { flush(); type = u32(b, c.start) }
                TEXT_CHARS_ATOM -> text = String(b, c.start, c.len, Charsets.UTF_16LE)
                TEXT_BYTES_ATOM -> text = String(b, c.start, c.len, Charsets.ISO_8859_1)
                STYLE_TEXT_PROP_ATOM -> style = c
                INTERACTIVE_INFO, TX_INTERACTIVE_INFO_ATOM -> links.take(c)
            }
            flush()
            return out
        }

        /**
         * A master: its text styles and colour scheme, and its drawing as a design — its background (a colour,
         * or a picture fill), the pictures and shapes on it that are not placeholders.
         */
        fun master(r: Rec, name: String): Master {
            val styles = HashMap<Int, MasterStyle>()
            var scheme: LongArray? = null
            val kids = children(b, r)
            for (c in kids) when (c.type) {
                TX_MASTER_STYLE_ATOM -> runCatching { readMasterStyle(b, c) }.getOrNull()?.let { styles[c.inst] = it }
                COLOR_SCHEME_ATOM -> if (c.inst == 1) scheme = scheme(c)
            }
            // The centre title's style gives what differs from the title's; the centre, half and quarter body's from the body's.
            for (inst in 5..8) styles[inst]?.let { own -> styles[if (inst == 6) 0 else 1]?.let { base -> styles[inst] = own.over(base) } }
            val design = com.therealityteam.aeropages.model.SlideDesign(name)
            val m = Master(styles, scheme, design)
            kids.firstOrNull { it.type == PP_DRAWING }?.let { drawing ->
                val dg = children(b, drawing).firstOrNull { it.type == DG_CONTAINER } ?: return@let
                val ctx = SlideCtx(Slide(), m, scheme, emptyList(), design)
                for (c in children(b, dg)) when (c.type) {
                    SPGR_CONTAINER -> group(c, ctx, null, null)
                    SP_CONTAINER -> backgroundShape(c, scheme)?.let { bg -> design.background = bg.color; design.backgroundShade = bg.shade; design.picture = bg.picture }
                }
            }
            return m
        }

        /**
         * A title master: its own shapes and background over its main master's (SlideAtom's masterIdRef), the
         * main master's shapes too unless it hides them (fMasterObjects), its colours unless its own (fMasterScheme).
         */
        fun titleMaster(r: Rec, name: String): Master? {
            val kids = children(b, r)
            val atom = kids.firstOrNull { it.type == SLIDE_ATOM } ?: return null
            val main = masters[u32(b, atom.start + 12)] ?: masters.values.firstOrNull() ?: return null
            val flags = if (atom.len >= 22) u16(b, atom.start + 20) else 0x7
            val scheme = kids.firstOrNull { it.type == COLOR_SCHEME_ATOM && it.inst == 1 }?.let { scheme(it) }?.takeIf { flags and 0x2 == 0 } ?: main.scheme
            val design = com.therealityteam.aeropages.model.SlideDesign(name)
            design.background = main.design.background; design.backgroundShade = main.design.backgroundShade; design.picture = main.design.picture
            if (flags and 0x1 != 0) design.items.addAll(main.design.items)
            val m = Master(main.styles, scheme, design)
            kids.firstOrNull { it.type == PP_DRAWING }?.let { drawing ->
                val dg = children(b, drawing).firstOrNull { it.type == DG_CONTAINER } ?: return@let
                val ctx = SlideCtx(Slide(), m, scheme, emptyList(), design)
                for (c in children(b, dg)) when (c.type) {
                    SPGR_CONTAINER -> group(c, ctx, null, null)
                    SP_CONTAINER -> if (flags and 0x4 == 0) backgroundShape(c, scheme)?.let { bg -> design.background = bg.color; design.backgroundShade = bg.shade; design.picture = bg.picture }
                }
            }
            return m
        }

        /** A background: its colour and the gradient from it, or a picture stretched over the slide. */
        inner class Background(val color: Long?, val shade: com.therealityteam.aeropages.model.BackgroundShade?, val picture: SlideImage?)

        /** The background shape's fill: its colour (opaque) and, shaded, its gradient; or (a picture fill) the picture; null for another shape. */
        fun backgroundShape(sp: Rec, scheme: LongArray?): Background? {
            val k = children(b, sp)
            val fsp = k.firstOrNull { it.type == FSP } ?: return null
            if (u32(b, fsp.start + 4) and 0x400 == 0) return null
            val opt = Opt.merge(k.firstOrNull { it.type == FOPT }?.let { Opt.read(b, it) }, k.firstOrNull { it.type == TERTIARY_FOPT }?.let { Opt.read(b, it) })
            val type = opt.props[0x0180] ?: 0
            if ((type == 2 || type == 3) && opt.props[0x0186] != null) {
                picture(opt.props.getValue(0x0186), Opt(emptyMap(), emptySet()), floatArrayOf(0f, 0f, 1f, 1f), 0f, false, false)?.let { return Background(null, null, it) }
            }
            val fill = fillOf(opt.props, scheme) ?: return Background(null, null, null)
            // Shaded as a shape is: its first colour and the gradient on from it.
            val probe = SlideShape(ShapeType.RECT, fill = fill)
            gradient(probe, fill, opt.props, scheme)
            val first = (probe.fill ?: fill) or 0xFF000000L
            val shade = probe.fillGradient?.let { com.therealityteam.aeropages.model.BackgroundShade(it or 0xFF000000L, probe.gradientAngle, probe.gradientKind, probe.gradientCx, probe.gradientCy) }
            return Background(first, shade, null)
        }

        fun scheme(c: Rec): LongArray? = if (c.len < 32) null else LongArray(8) { k ->
            0xFF000000L or ((b[c.start + k * 4].toLong() and 0xFF) shl 16) or ((b[c.start + k * 4 + 1].toLong() and 0xFF) shl 8) or (b[c.start + k * 4 + 2].toLong() and 0xFF)
        }

        // --- a slide -------------------------------------------------------------------------

        fun slide(r: Rec, listTexts: List<TextBody>): Slide {
            val kids = children(b, r)
            val atom = kids.firstOrNull { it.type == SLIDE_ATOM }
            val geom = atom?.let { u32(b, it.start) } ?: 1
            val master = atom?.let { masters[u32(b, it.start + 12)] } ?: masters.values.firstOrNull()
            val flags = atom?.takeIf { it.len >= 22 }?.let { u16(b, it.start + 20) } ?: 0x7
            val scheme = kids.firstOrNull { it.type == COLOR_SCHEME_ATOM && it.inst == 1 }?.let { scheme(it) }?.takeIf { flags and 0x2 == 0 } ?: master?.scheme
            val slide = Slide(layout = when (geom) { 0 -> SlideLayout.TITLE; 16 -> SlideLayout.BLANK; 7 -> SlideLayout.BLANK; else -> SlideLayout.TITLE_CONTENT })
            // Its master's design behind it: its pictures and shapes too unless the slide hides them.
            master?.let { slide.design = it.design.name; slide.designObjects = flags and 0x1 != 0 }
            slide.title.paragraph.runs.clear(); slide.title.paragraph.runs.add(TextRun("", CharacterFormat()))
            slide.body.paragraph.runs.clear(); slide.body.paragraph.runs.add(TextRun("", CharacterFormat()))
            val ctx = SlideCtx(slide, master, scheme, listTexts)
            kids.firstOrNull { it.type == PP_DRAWING }?.let { drawing ->
                val dg = children(b, drawing).firstOrNull { it.type == DG_CONTAINER }
                if (dg != null) for (c in children(b, dg)) when (c.type) {
                    SPGR_CONTAINER -> group(c, ctx, null, null)
                    // The slide's own background (outside the shapes' group), unless it follows its master's.
                    SP_CONTAINER -> if (flags and 0x4 == 0) backgroundShape(c, scheme)?.let { bg ->
                        val picture = bg.picture
                        slide.background = bg.color; slide.backgroundShade = bg.shade
                        // A picture of its own: a design of its own, drawing it under the master's pictures and shapes.
                        picture?.let { pic ->
                            val own = com.therealityteam.aeropages.model.SlideDesign("${master?.design?.name ?: "Slide"} · ${designs.size + 1}", picture = pic)
                            if (slide.designObjects) master?.design?.items?.let { own.items.addAll(it) }
                            designs += own
                            slide.design = own.name; slide.designObjects = true
                        }
                    }
                }
            }
            // Placeholder text the slide list holds but no shape showed (a title with no placeholder shape …).
            if (slide.title.text.isEmpty()) listTexts.firstOrNull { it.type == 0 || it.type == 6 }?.takeIf { it !in ctx.used }?.let { fillBox(slide.title, it.text, it.style, it.type, ctx, it.links) }
            if (slide.body.text.isEmpty()) listTexts.firstOrNull { it.type in BODY_TYPES }?.takeIf { it !in ctx.used }?.let { fillBox(slide.body, it.text, it.style, it.type, ctx, it.links) }
            return slide
        }

        /** Where shapes go: a slide's, or — reading a master — its design's ([design]: pictures and shapes, no placeholders). */
        inner class SlideCtx(val slide: Slide, val master: Master?, val scheme: LongArray?, val listTexts: List<TextBody>,
                             val design: com.therealityteam.aeropages.model.SlideDesign? = null) {
            val used = HashSet<TextBody>()
            var groups = 0
        }

        /** A group: its shapes (the first, the group's own, gives its coordinate space), mapped into its parent's. */
        fun group(r: Rec, ctx: SlideCtx, outer: Mapping?, groupId: Int?) {
            val parts = children(b, r)
            var mapping = outer
            var id = groupId
            parts.forEachIndexed { i, c ->
                when {
                    i == 0 && c.type == SP_CONTAINER -> {
                        // The group's own shape: its coordinate space (FSPGR) and where it sits.
                        val k = children(b, c)
                        val fspgr = k.firstOrNull { it.type == FSPGR }
                        val fsp = k.firstOrNull { it.type == FSP }
                        val patriarch = fsp != null && u32(b, fsp.start + 4) and 0x4 != 0
                        if (!patriarch && fspgr != null && fspgr.len >= 16) {
                            val anchor = anchorOf(k, outer)
                            val space = intArrayOf(u32(b, fspgr.start), u32(b, fspgr.start + 4), u32(b, fspgr.start + 8), u32(b, fspgr.start + 12))
                            if (anchor != null) mapping = Mapping(space, anchor)
                            if (id == null) id = ++ctx.groups
                        }
                    }
                    c.type == SPGR_CONTAINER -> group(c, ctx, mapping, id)
                    c.type == SP_CONTAINER -> runCatching { shape(c, ctx, mapping, id) }
                }
            }
        }

        /** A group's coordinate space ([space]: left, top, right, bottom) and where it lies on the slide ([to]: the same, in slide units). */
        inner class Mapping(val space: IntArray, val to: FloatArray) {
            fun map(l: Int, t: Int, rr: Int, bb: Int): FloatArray {
                val sw = (space[2] - space[0]).takeIf { it != 0 } ?: 1; val sh = (space[3] - space[1]).takeIf { it != 0 } ?: 1
                val kx = (to[2] - to[0]) / sw; val ky = (to[3] - to[1]) / sh
                return floatArrayOf(to[0] + (l - space[0]) * kx, to[1] + (t - space[1]) * ky, to[0] + (rr - space[0]) * kx, to[1] + (bb - space[1]) * ky)
            }
        }

        /** A shape's place in slide units (left, top, right, bottom): its client anchor, or its child anchor in its group's space. */
        fun anchorOf(k: List<Rec>, mapping: Mapping?): FloatArray? {
            k.firstOrNull { it.type == CHILD_ANCHOR && it.len >= 16 }?.let { a ->
                val l = u32(b, a.start); val t = u32(b, a.start + 4); val rr = u32(b, a.start + 8); val bb = u32(b, a.start + 12)
                return mapping?.map(l, t, rr, bb) ?: floatArrayOf(l.toFloat(), t.toFloat(), rr.toFloat(), bb.toFloat())
            }
            k.firstOrNull { it.type == CLIENT_ANCHOR }?.let { a ->
                return when {
                    a.len >= 16 -> floatArrayOf(u32(b, a.start + 4).toFloat(), u32(b, a.start).toFloat(), u32(b, a.start + 8).toFloat(), u32(b, a.start + 12).toFloat())
                    a.len >= 8 -> floatArrayOf(i16(b, a.start + 2).toFloat(), i16(b, a.start).toFloat(), i16(b, a.start + 4).toFloat(), i16(b, a.start + 6).toFloat())
                    else -> null
                }
            }
            return null
        }

        fun shape(r: Rec, ctx: SlideCtx, mapping: Mapping?, groupId: Int?) {
            val k = children(b, r)
            val fsp = k.firstOrNull { it.type == FSP } ?: return
            val type = fsp.inst
            val flags = u32(b, fsp.start + 4)
            if (flags and 0x8 != 0 || flags and 0x400 != 0) return       // deleted, the background
            val opt = Opt.merge(k.firstOrNull { it.type == FOPT }?.let { Opt.read(b, it) }, k.firstOrNull { it.type == TERTIARY_FOPT }?.let { Opt.read(b, it) })
            val rotation = opt.props[0x0004]?.let { it / 65536f } ?: 0f
            val box = anchorOf(k, mapping)?.let { unturned(it, rotation) } ?: return
            val frame = floatArrayOf(box[0] / slideW, box[1] / slideH, (box[2] - box[0]) / slideW, (box[3] - box[1]) / slideH)
            val placeholder = k.firstOrNull { it.type == CLIENT_DATA }?.let { cd -> children(b, cd).firstOrNull { it.type == OE_PLACEHOLDER_ATOM } }?.let { b[it.start + 4].toInt() and 0xFF }
            val text = k.firstOrNull { it.type == CLIENT_TEXTBOX }?.let { textOf(it, ctx) }
            val flipH = flags and 0x40 != 0; val flipV = flags and 0x80 != 0
            val (click, hover) = actionsOf(k)
            // Date, slide number, footer and header: the master's, not the slide's own; a master's placeholders: its layout's.
            if (placeholder in CHROME_PLACEHOLDERS) return
            if (ctx.design != null && placeholder != null) return
            val textType = text?.type
            val isTitle = placeholder in TITLE_PLACEHOLDERS || (placeholder != null && (textType == 0 || textType == 6))
            val isBody = !isTitle && (placeholder in BODY_PLACEHOLDERS || (placeholder != null && textType in BODY_TYPES))
            // A picture: its blip from the store.
            val pib = opt.props[0x0104]
            if (pib != null && (type == 75 || opt.bid.contains(0x0104)) && text == null) {
                picture(pib, opt, frame, rotation, flipH, flipV)?.let {
                    it.action = click; it.hoverAction = hover
                    if (ctx.design != null) ctx.design.items += com.therealityteam.aeropages.model.DesignPicture(it) else ctx.slide.images += it
                }
                return
            }
            when {
                isTitle && ctx.slide.title.text.isEmpty() -> {
                    text?.let { fillBox(ctx.slide.title, it.text, it.style, it.type, ctx, it.links) }
                    ctx.slide.title.frame = boxFrame(frame)
                }
                isBody && ctx.slide.body.text.isEmpty() -> {
                    text?.let { fillBox(ctx.slide.body, it.text, it.style, it.type, ctx, it.links) }
                    ctx.slide.body.frame = boxFrame(frame)
                }
                placeholder != null && text == null -> Unit           // an empty placeholder: nothing on the slide
                // WordArt: its text drawn in its fill, as a text box.
                type in 136..175 && opt.complex[0x00C0] != null -> wordArt(ctx, opt, frame, box, rotation, flipH, flipV, groupId)
                else -> {
                    val preset = PRESETS[type]
                    val line = type == 20 || type in 32..40
                    val adjust = msoAdjust(type, opt.props, box[2] - box[0], box[3] - box[1])
                    // A shape of its own geometry (a freeform; anything without a preset): drawn from its vertices,
                    // or, with none, nothing but its text.
                    val ownGeometry = type == 0 || type == 0xFFF
                    val own = if (ownGeometry) MsoGeometry.freeform(opt.props, opt.complex, (box[2] - box[0]) * 1587.5, (box[3] - box[1]) * 1587.5) else null
                    if (ownGeometry && own == null && text == null) return
                    // A preset the app draws as its own; any other worked out from its DrawingML definition
                    // (576 units an inch: 1587.5 EMU a unit), a rectangle when it has none.
                    // (Office's binary trapezoid is DrawingML's upside down: its long side at the top.)
                    // Its adjust values as DrawingML's: a preset the app draws as its own becomes the preset outline when they are not its own.
                    val path = own ?: if (line || preset == null || (preset in OWN_PRESETS && adjust == null)) null
                        else com.therealityteam.aeropages.format.pptx.PresetGeometries.freeform(preset, com.therealityteam.aeropages.format.pptx.PresetGeometries.avLstOf(adjust),
                            (box[2] - box[0]) * 1587.5, (box[3] - box[1]) * 1587.5)
                            ?.let { if (type == 8) it.mapped { x, y -> x to 1f - y } else it }
                    val shapeType = when {
                        line -> ShapeType.LINE
                        own != null -> ShapeType.FREEFORM
                        type == 202 || ownGeometry || (type == 1 && text != null && fillOf(opt.props, ctx.scheme) == null && lineOf(opt.props, ctx.scheme) == null) -> ShapeType.TEXTBOX
                        path != null -> ShapeType.FREEFORM
                        preset == "roundRect" -> ShapeType.ROUNDED
                        preset == "ellipse" -> ShapeType.ELLIPSE
                        preset == "triangle" -> ShapeType.TRIANGLE
                        preset == "star5" -> ShapeType.STAR
                        preset == "rightArrow" -> ShapeType.ARROW
                        else -> ShapeType.RECT
                    }
                    val sh = SlideShape(shapeType, xPct = frame[0], yPct = frame[1], wPct = frame[2].coerceAtLeast(0f), hPct = frame[3].coerceAtLeast(0f))
                    if (shapeType == ShapeType.FREEFORM && own == null && type != 8) { sh.path = path; sh.preset = preset; sh.presetAdjust = adjust }
                    else if (shapeType == ShapeType.FREEFORM) sh.path = path
                    sh.action = click; sh.hoverAction = hover
                    addRect(ctx, sh, text, opt, rotation, flipH, flipV, groupId, outlined = !ownGeometry || own != null)
                }
            }
        }

        /**
         * A shape's click and mouse-over actions ([MS-PPT] InteractiveInfoContainer in its client data, instance 0 and 1):
         * a jump in the show, a link to a slide (by its id in the file, resolved once every slide is read) or to an address.
         * Running a program or a macro, an OLE verb, a sound or a custom show are not kept.
         */
        fun actionsOf(k: List<Rec>): Pair<String?, String?> {
            val data = k.firstOrNull { it.type == CLIENT_DATA } ?: return null to null
            var click: String? = null; var hover: String? = null
            for (info in children(b, data)) if (info.type == INTERACTIVE_INFO) {
                val atom = children(b, info).firstOrNull { it.type == INTERACTIVE_INFO_ATOM && it.len >= 13 } ?: continue
                val link = actionOf(atom)
                if (info.inst == 1) hover = link else click = link
            }
            return click to hover
        }

        /** The place one InteractiveInfoAtom goes: by its action (3 = a jump, 4 = a hyperlink) and, for a hyperlink, its kind. */
        fun actionOf(atom: Rec): String? {
            val action = b[atom.start + 8].toInt() and 0xFF
            val jump = b[atom.start + 10].toInt() and 0xFF
            val kind = b[atom.start + 12].toInt() and 0xFF
            fun jumpOf(j: Int) = when (j) {
                1 -> com.therealityteam.aeropages.model.SlideLinks.NEXT
                2, 5 -> com.therealityteam.aeropages.model.SlideLinks.PREVIOUS
                3 -> com.therealityteam.aeropages.model.SlideLinks.FIRST
                4 -> com.therealityteam.aeropages.model.SlideLinks.LAST
                6 -> com.therealityteam.aeropages.model.SlideLinks.END
                else -> null
            }
            return when (action) {
                3 -> jumpOf(jump)
                4 -> when (kind) {
                    0 -> jumpOf(1); 1 -> jumpOf(2); 2 -> jumpOf(3); 3 -> jumpOf(4)
                    else -> {
                        val (target, location) = hyperlinks[u32(b, atom.start + 4)] ?: return null
                        when {
                            // A slide of this presentation: "slide id,slide number,title".
                            kind == 7 || (target.isEmpty() && location.isNotEmpty()) ->
                                location.substringBefore(',').trim().toIntOrNull()?.let { PPT_SLIDE + it }
                            target.isNotEmpty() -> if (location.isNotEmpty() && kind != 8) "$target#$location" else target
                            else -> null
                        }
                    }
                }
                else -> null
            }
        }

        /** [sh] with its look, text, turn and group, onto the slide ([outlined]: it has an outline to draw). */
        fun addRect(ctx: SlideCtx, sh: SlideShape, text: TextBody?, opt: Opt, rotation: Float, flipH: Boolean, flipV: Boolean, groupId: Int?, outlined: Boolean = true) {
            val line = sh.type == ShapeType.LINE
            sh.fill = if (line || sh.type == ShapeType.TEXTBOX) null else fillOf(opt.props, ctx.scheme)
            sh.border = if (outlined) lineOf(opt.props, ctx.scheme) else null
            // A text box with no text, no fill and no outline shows nothing (LibreOffice writes an empty placeholder so).
            if (sh.type == ShapeType.TEXTBOX && sh.border == null && text?.text.isNullOrBlank()) return
            sh.fill?.let { gradient(sh, it, opt.props, ctx.scheme) }
            opt.props[0x01CB]?.let { if (it > 0) sh.borderWidthPt = (it / 12700f).coerceIn(0.25f, 12f) }
            sh.dash = look.dashOf(opt.props)
            sh.rotationDeg = ((rotation % 360f) + 360f) % 360f
            sh.flipH = flipH; sh.flipV = flipV
            sh.groupId = groupId
            sh.noWrap = opt.props[0x0085] == 2
            sh.anchor = when (opt.props[0x0087]) { 1, 4, 7 -> ShapeAnchor.CENTER; 2, 5, 8 -> ShapeAnchor.BOTTOM; 0, 3, 6, 9 -> ShapeAnchor.TOP; else -> if (sh.type == ShapeType.TEXTBOX) ShapeAnchor.TOP else ShapeAnchor.CENTER }
            if (text != null) fillBox(sh.box, text.text, text.style, text.type, ctx, text.links)
            textFlow(sh, opt.props[0x0088] ?: 0)
            if (ctx.design != null) ctx.design.items += com.therealityteam.aeropages.model.DesignShape(sh) else ctx.slide.shapes += sh
        }

        /**
         * Text running down (txflTextFlow top to bottom: 1, 3, 5) or up (2) a shape that looks the same turned
         * a quarter (a rectangle, a text box, an ellipse): the box turned so, its width and height changing places
         * about its centre — the text reads down or up the box as Office draws it.
         */
        fun textFlow(sh: SlideShape, flow: Int) {
            val turn = when (flow) { 1, 3, 5 -> 90f; 2 -> 270f; else -> return }
            sh.turnText(turn, slideW.toFloat() / slideH)
        }

        /**
         * A shape's own box from its anchor: turned a quarter either way (45°–135°, 225°–315°), Office
         * keeps the box it covers turned, so its width and height change places about its centre.
         */
        fun unturned(a: FloatArray, rotation: Float): FloatArray {
            val r = ((rotation % 360f) + 360f) % 360f
            if (!(r >= 45f && r < 135f || r >= 225f && r < 315f)) return a
            val cx = (a[0] + a[2]) / 2f; val cy = (a[1] + a[3]) / 2f; val hw = (a[3] - a[1]) / 2f; val hh = (a[2] - a[0]) / 2f
            return floatArrayOf(cx - hw, cy - hh, cx + hw, cy + hh)
        }

        /**
         * A preset's adjust values ([MS-ODRAW]: in its 21600-unit geometry, the shape's own default when
         * not given) as DrawingML's (`name=val n|…`, in 100000ths, of the shape's shorter side where
         * DrawingML measures so) for a shape [w] × [h]; null when DrawingML's defaults draw it the same.
         */
        fun msoAdjust(type: Int, props: Map<Int, Int>, w: Float, h: Float): String? {
            if (w <= 0f || h <= 0f) return null
            val ss = minOf(w, h).toDouble()
            fun a(k: Int, default: Int) = (props[0x0147 + k] ?: default).toDouble()
            fun ofWidth(v: Double) = v / 21600 * w / ss * 100000      // a length across, of the shorter side
            fun ofHeight(v: Double) = v / 21600 * h / ss * 100000
            fun plain(v: Double) = v / 21600 * 100000
            val values: List<Pair<String, Double>> = when (type) {
                2 -> listOf("adj" to plain(a(0, 3600)))                                                     // round rectangle: the corner
                5 -> listOf("adj" to plain(a(0, 10800)))                                                    // triangle: the apex across
                7, 9 -> listOf("adj" to ofWidth(a(0, 5400)))                                                // parallelogram, hexagon: the slant
                8 -> listOf("adj" to ofWidth(a(0, 5400)))                                                   // trapezoid: the narrow side's inset
                10 -> listOf("adj" to plain(a(0, 6326)))                                                    // octagon: the corners cut
                11, 16, 23 -> listOf("adj" to plain(a(0, 5400)))                                            // plus, cube, donut
                21 -> listOf("adj" to plain(a(0, 3600)))                                                    // plaque
                84 -> listOf("adj" to plain(a(0, 2700)))                                                    // bevel
                184 -> listOf("adj" to plain(a(0, 10800)))                                                  // moon
                15, 55 -> listOf("adj" to ofWidth(21600 - a(0, 16200)))                                     // home plate, chevron: the point
                13 -> listOf("adj1" to plain(21600 - 2 * a(1, 5400)), "adj2" to ofWidth(21600 - a(0, 16200)))   // arrows: the shaft, the head
                66 -> listOf("adj1" to plain(21600 - 2 * a(1, 5400)), "adj2" to ofWidth(a(0, 5400)))
                68 -> listOf("adj1" to plain(21600 - 2 * a(1, 5400)), "adj2" to ofHeight(a(0, 5400)))
                67 -> listOf("adj1" to plain(21600 - 2 * a(1, 5400)), "adj2" to ofHeight(21600 - a(0, 16200)))
                69 -> listOf("adj1" to plain(21600 - 2 * a(1, 5400)), "adj2" to ofWidth(a(0, 4320)))
                17, 61, 62, 63, 106 -> listOf("adj1" to (a(0, 1350) / 21600 - 0.5) * 100000, "adj2" to (a(1, 25920) / 21600 - 0.5) * 100000)  // callouts: the tail's tip
                else -> return null
            }
            val preset = PRESETS[type] ?: return null
            val defaults = com.therealityteam.aeropages.format.pptx.PresetGeometries.defaults(preset)
            val given = values.map { (n, v) -> n to Math.round(v).toInt() }
            if (given.all { (n, v) -> defaults[n]?.let { d -> kotlin.math.abs(d - v) <= 1 } == true }) return null
            return given.joinToString("|") { (n, v) -> "$n=val $v" }
        }

        /** WordArt (geometry text): its text, face, size and look, in its fill colour (its outline's when unfilled), centred in its box. */
        fun wordArt(ctx: SlideCtx, opt: Opt, frame: FloatArray, box: FloatArray, rotation: Float, flipH: Boolean, flipV: Boolean, groupId: Int?) {
            val text = opt.complex[0x00C0]?.let { String(it, Charsets.UTF_16LE) }?.substringBefore('\u0000')?.replace("\r\n", "\n")?.replace('\r', '\n')?.trimEnd()
            if (text.isNullOrEmpty()) return
            val face = opt.complex[0x00C5]?.let { String(it, Charsets.UTF_16LE) }?.substringBefore('\u0000')?.trim()?.takeIf { it.isNotEmpty() }
            val bools = opt.props[0x00FF] ?: 0
            fun flag(bit: Int) = bools and (1 shl (bit + 16)) != 0 && bools and (1 shl bit) != 0
            val lines = text.split('\n')
            val boxHeightPt = (box[3] - box[1]) / 8f
            val given = opt.props[0x00C3]?.let { it / 65536f } ?: 36f
            // Stretched or fitted to its box (as WordArt mostly is): its letters as tall as the box lets each line be.
            val sizePt = if (flag(10) || flag(9) || flag(8)) (boxHeightPt / lines.size).coerceIn(4f, 400f) else given
            val color = fillOf(opt.props, ctx.scheme) ?: lineOf(opt.props, ctx.scheme) ?: 0xFF000000L
            val format = CharacterFormat(bold = flag(5), italic = flag(4), underline = flag(3), fontSizePt = sizePt, fontFamily = face, color = color)
            // WordArt's box hugs its letters; a text box keeps margins (7.2 pt a side, 3.6 above and below): the box grows
            // about its centre to hold the text with them.
            val textWidthPt = lines.maxOf { l -> l.sumOf { c -> if (c.code >= 0x2E80) 1.0 else 0.6 } }.toFloat() * sizePt
            val wPt = maxOf((box[2] - box[0]) / 8f, textWidthPt + 14.4f + sizePt * 0.2f)
            val hPt = maxOf(boxHeightPt, lines.size * sizePt * 1.2f + 7.2f)
            val wPct = wPt * 8f / slideW; val hPct = hPt * 8f / slideH
            val sh = SlideShape(ShapeType.TEXTBOX, SlideBox(text, format, when (opt.props[0x00C2]) { 2 -> TextAlign.LEFT; 3 -> TextAlign.RIGHT; else -> TextAlign.CENTER }),
                xPct = frame[0] + frame[2] / 2f - wPct / 2f, yPct = frame[1] + frame[3] / 2f - hPct / 2f, wPct = wPct, hPct = hPct, fill = null, border = null)
            sh.rotationDeg = ((rotation % 360f) + 360f) % 360f
            sh.flipH = flipH; sh.flipV = flipV
            sh.groupId = groupId
            sh.anchor = ShapeAnchor.CENTER
            if (ctx.design != null) ctx.design.items += com.therealityteam.aeropages.model.DesignShape(sh) else ctx.slide.shapes += sh
        }

        fun boxFrame(f: FloatArray): BoxFrame? =
            if (f[2] <= 0f || f[3] <= 0f) null else BoxFrame(f[0].coerceIn(0f, 1f), f[1].coerceIn(0f, 1f), f[2].coerceIn(0.02f, 1f), f[3].coerceIn(0.02f, 1f))

        /**
         * The links in a text as its records give them: each a mouse-click InteractiveInfoContainer (where it goes)
         * followed by a TxInteractiveInfoAtom (the characters it covers).
         */
        inner class TextLinks {
            private var pending: String? = null
            private val found = ArrayList<Triple<Int, Int, String>>()
            fun take(c: Rec) {
                if (c.type == INTERACTIVE_INFO) {
                    pending = if (c.inst == 0) children(b, c).firstOrNull { it.type == INTERACTIVE_INFO_ATOM && it.len >= 13 }?.let(::actionOf) else null
                } else if (c.len >= 8) {
                    pending?.let { found += Triple(u32(b, c.start), u32(b, c.start + 4), it) }
                    pending = null
                }
            }
            fun taken(): List<Triple<Int, Int, String>> = found.toList()
            fun clear() { pending = null; found.clear() }
        }

        /** A text box's text: its own atoms, or (an OutlineTextRefAtom) the slide list's text it points to. */
        fun textOf(r: Rec, ctx: SlideCtx): TextBody? {
            var type = 4; var text: String? = null; var style: Rec? = null
            val links = TextLinks()
            for (c in children(b, r)) when (c.type) {
                TEXT_HEADER_ATOM -> type = u32(b, c.start)
                TEXT_CHARS_ATOM -> text = String(b, c.start, c.len, Charsets.UTF_16LE)
                TEXT_BYTES_ATOM -> text = String(b, c.start, c.len, Charsets.ISO_8859_1)
                STYLE_TEXT_PROP_ATOM -> style = c
                OUTLINE_TEXT_REF_ATOM -> ctx.listTexts.getOrNull(u32(b, c.start))?.let { ctx.used += it; return it }
                INTERACTIVE_INFO, TX_INTERACTIVE_INFO_ATOM -> links.take(c)
            }
            return text?.let { TextBody(type, it, style, links.taken()) }
        }

        /** Fills [box] with [text]: its paragraphs (levels as leading tabs, line breaks kept), its runs' look, its bullets and alignment. */
        fun fillBox(box: SlideBox, text: String, style: Rec?, textType: Int, ctx: SlideCtx, links: List<Triple<Int, Int, String>> = emptyList()) {
            val clean = text.replace("\u0000", "")
            val runsStyle = style?.let { runCatching { readStyle(b, it, clean.length) }.getOrNull() }
            val masterStyle = masterType(textType).let { t -> ctx.master?.styles?.get(t) ?: docStyles[t] }
            // Paragraphs and their levels.
            val paragraphs = clean.split('\r')
            val levels = IntArray(paragraphs.size)
            val pfs = arrayOfNulls<Pf>(paragraphs.size)
            run {
                var at = 0; var p = 0
                for ((count, level, pf) in runsStyle?.paragraphs.orEmpty()) {
                    val end = at + count
                    while (p < paragraphs.size) {
                        val start = paragraphs.take(p).sumOf { it.length + 1 }
                        if (start >= end) break
                        // A level as its own field says, or — an unbulleted line indented as a whole, as LibreOffice
                        // writes a text box's levels — by its indent in half inches (288 units).
                        val margin = pf.leftMargin
                        levels[p] = if (level == 0 && pf.bullet != true && margin != null && margin > 0 && pf.indent == margin) Math.round(margin / 288f).coerceIn(0, 4)
                            else level.coerceIn(0, 4)
                        pfs[p] = pf
                        p++
                    }
                    at = end
                }
            }
            // Characters: each character's run.
            val charCf = arrayOfNulls<Cf>(clean.length)
            run {
                var at = 0
                for ((count, cf) in runsStyle?.characters.orEmpty()) {
                    for (i in at until minOf(clean.length, at + count)) charCf[i] = cf
                    at += count
                }
            }
            val runs = ArrayList<TextRun>()
            var pos = 0
            paragraphs.forEachIndexed { pi, ptext ->
                val level = levels[pi]
                val lv = masterStyle?.levels?.getOrNull(level) ?: masterStyle?.levels?.firstOrNull()
                fun formatAt(i: Int): CharacterFormat {
                    val cf = charCf.getOrNull(i)
                    val mcf = lv?.second
                    val f = CharacterFormat(
                        bold = cf?.bold ?: mcf?.bold ?: false,
                        italic = cf?.italic ?: mcf?.italic ?: false,
                        underline = cf?.underline ?: mcf?.underline ?: false,
                        fontSizePt = (cf?.sizePt ?: mcf?.sizePt)?.toFloat() ?: defaultSize(textType),
                        fontFamily = (cf?.fontRef ?: mcf?.fontRef)?.let { fonts[it] },
                        color = cf?.color ?: cf?.colorIndex?.let { ctx.scheme?.getOrNull(it) } ?: mcf?.color ?: mcf?.colorIndex?.let { ctx.scheme?.getOrNull(it) },
                    )
                    // A link the text's records put on this character.
                    links.firstOrNull { (from, to, _) -> i in from until to }?.let { f.link = it.third }
                    return f
                }
                fun append(s: String, f: CharacterFormat) {
                    val last = runs.lastOrNull()
                    if (last != null && last.format == f && last.text != "\n") runs[runs.lastIndex] = TextRun(last.text + s, f) else runs += TextRun(s, f)
                }
                if (pi > 0) runs += TextRun("\n", runs.lastOrNull()?.format?.copyOf() ?: formatAt(pos))
                if (level > 0) append("\t".repeat(level), formatAt(pos))
                ptext.forEachIndexed { i, ch ->
                    val c = when (ch) { '\u000B' -> com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK; else -> ch }
                    append(c.toString(), formatAt(pos + i))
                }
                pos += ptext.length + 1
            }
            if (runs.isEmpty()) runs += TextRun("", CharacterFormat(fontSizePt = defaultSize(textType)))
            box.paragraph.runs.clear(); box.paragraph.runs.addAll(runs)
            // Alignment and bullets: the first paragraph's (its own, else its master's).
            val firstPf = pfs.firstOrNull()
            val masterPf = masterStyle?.levels?.firstOrNull()?.first
            box.paragraph.format.align = when (firstPf?.align ?: masterPf?.align) { 1 -> TextAlign.CENTER; 2 -> TextAlign.RIGHT; 3, 4 -> TextAlign.JUSTIFY; 0 -> TextAlign.LEFT; else -> box.paragraph.format.align }
            val bulleted = pfs.any { it?.bullet == true } || (pfs.none { it?.bullet == false } && masterPf?.bullet == true && textType in BODY_TYPES)
            box.listStyle = if (bulleted && textType != 0 && textType != 6) SlideListStyle.BULLET else SlideListStyle.NONE
        }

        /** The master text style a text type takes: title (0), body (1), notes (2), other (4), centre body and title (5, 6), half and quarter body (7, 8). */
        fun masterType(textType: Int): Int = if (textType in 0..8 && textType != 3) textType else 4

        /** A text's size when neither its runs nor its master say: PowerPoint's title, body and other text sizes. */
        fun defaultSize(textType: Int): Float = when (textType) { 0, 6 -> 44f; 1, 5, 7, 8 -> 32f; 2 -> 12f; else -> 18f }

        // --- pictures ---------------------------------------------------------------------------

        fun picture(pib: Int, opt: Opt, frame: FloatArray, rotation: Float, flipH: Boolean, flipV: Boolean): SlideImage? {
            val fbse = blips.getOrNull(pib - 1) ?: return null
            val pics = pictures ?: return null
            if (fbse.len < 36) return null
            val foDelay = u32(b, fbse.start + 28)
            val blip = recAt(pics, foDelay) ?: return null
            val (data, mime) = blipData(pics, blip) ?: return null
            val (pw, ph) = com.therealityteam.aeropages.format.ImageSize.of(data) ?: ((frame[2] * slideW / 8f) to (frame[3] * slideH / 8f))
            val img = SlideImage(data, mime, pw, ph, frame[0], frame[1], frame[2].coerceAtLeast(0.01f), frame[3].coerceAtLeast(0.01f))
            img.rotationDeg = ((rotation % 360f) + 360f) % 360f
            img.flipH = flipH; img.flipV = flipV
            // The crop, as fractions of the picture (16.16).
            // (16.16: a half comes as 32767 / 65536; to a thousandth.)
            fun frac(id: Int) = Math.round((opt.props[id] ?: 0) / 65.536f) / 1000f
            img.cropT = frac(0x0100).coerceIn(0f, 0.95f); img.cropB = frac(0x0101).coerceIn(0f, 0.95f)
            img.cropL = frac(0x0102).coerceIn(0f, 0.95f); img.cropR = frac(0x0103).coerceIn(0f, 0.95f)
            return img
        }

        fun blipData(p: ByteArray, r: Rec): Pair<ByteArray, String>? = OfficeArtBlips.blipData(p, r)

        // --- notes ---------------------------------------------------------------------------------

        /** A notes page's slide id and its body text. */
        fun notes(r: Rec, listTexts: List<TextBody>): Pair<Int, String>? {
            val kids = children(b, r)
            val slideId = kids.firstOrNull { it.type == NOTES_ATOM }?.let { u32(b, it.start) } ?: return null
            var text: String? = null
            kids.firstOrNull { it.type == PP_DRAWING }?.let { drawing -> findNotesText(drawing)?.let { text = it } }
            if (text.isNullOrBlank()) text = listTexts.firstOrNull { it.type == 2 }?.text
            return text?.replace('\r', '\n')?.replace('\u000B', '\n')?.replace("\u0000", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { slideId to it }
        }

        fun findNotesText(r: Rec): String? {
            for (c in children(b, r)) {
                if (c.type == CLIENT_TEXTBOX) {
                    var type = -1; var text: String? = null
                    for (t in children(b, c)) when (t.type) {
                        TEXT_HEADER_ATOM -> type = u32(b, t.start)
                        TEXT_CHARS_ATOM -> text = String(b, t.start, t.len, Charsets.UTF_16LE)
                        TEXT_BYTES_ATOM -> text = String(b, t.start, t.len, Charsets.ISO_8859_1)
                    }
                    if (type == 2 && !text.isNullOrBlank()) return text
                } else if (c.container) findNotesText(c)?.let { return it }
            }
            return null
        }

        // --- colours --------------------------------------------------------------------------------

        // The shapes' colours, fills and outlines (OfficeArtLook, shared with the .doc reader), over this drawing's defaults.
        fun colorOf(v: Int, scheme: LongArray?, props: Map<Int, Int>? = null) = look.colorOf(v, scheme, props)
        fun flagOf(props: Map<Int, Int>, id: Int, bit: Int) = look.flagOf(props, id, bit)
        fun fillOf(props: Map<Int, Int>, scheme: LongArray?) = look.fillOf(props, scheme)
        fun withOpacity(c: Long, fixed: Int?) = look.withOpacity(c, fixed)
        fun gradient(sh: SlideShape, fill: Long, props: Map<Int, Int>, scheme: LongArray?) = look.gradient(sh, fill, props, scheme)
        fun lineOf(props: Map<Int, Int>, scheme: LongArray?) = look.lineOf(props, scheme)
    }

    /** A shape's properties (OfficeArtFOPT): each simple one's value, which hold a blip id, the complex ones' data. */
    internal class Opt(val props: Map<Int, Int>, val bid: Set<Int>, val complex: Map<Int, ByteArray> = emptyMap()) {
        companion object {
            fun read(b: ByteArray, r: Rec): Opt {
                val props = HashMap<Int, Int>(); val bid = HashSet<Int>()
                val sizes = ArrayList<Pair<Int, Int>>()
                var o = r.start
                for (k in 0 until r.inst) {
                    if (o + 6 > r.end) break
                    val opid = u16(b, o); val value = u32(b, o + 2); o += 6
                    val id = opid and 0x3FFF
                    props[id] = value
                    if (opid and 0x4000 != 0) bid += id
                    if (opid and 0x8000 != 0) sizes += id to value
                }
                // The complex properties' data, in their order after the simple ones.
                val complex = HashMap<Int, ByteArray>()
                for ((id, size) in sizes) {
                    var n = size
                    // An array whose size leaves out its header (as some writers give it): known by its count of elements.
                    if (id in MsoGeometry.ARRAYS && o + 6 <= r.end) {
                        val each = u16(b, o + 4).let { if (it == 0xFFF0) 4 else it }
                        if (u16(b, o) * each == size) n = size + 6
                    }
                    if (n < 0 || o + n > r.end) break
                    complex[id] = b.copyOfRange(o, o + n)
                    o += n
                }
                return Opt(props, bid, complex)
            }

            /**
             * [c]'s properties over [a]'s; a set of flags (a property numbered 0x…3F, 0x…7F …) bit by bit, each
             * flag [c] says (its use bit set) over [a]'s.
             */
            fun merge(a: Opt?, c: Opt?): Opt = when {
                a == null -> c ?: Opt(emptyMap(), emptySet())
                c == null -> a
                else -> {
                    val props = HashMap(a.props)
                    for ((id, v) in c.props) {
                        val under = props[id]
                        props[id] = if (under != null && id and 0x3F == 0x3F) {
                            val said = (v ushr 16) and 0xFFFF
                            val mask = said or (said shl 16)
                            (under and mask.inv()) or (v and mask)
                        } else v
                    }
                    Opt(props, a.bid + c.bid, a.complex + c.complex)
                }
            }
        }
    }

    private val TITLE_PLACEHOLDERS = setOf(0x0D, 0x0F, 0x11, 0x01, 0x03)
    private val BODY_PLACEHOLDERS = setOf(0x0E, 0x10, 0x12, 0x13, 0x02, 0x04)
    private val CHROME_PLACEHOLDERS = setOf(0x07, 0x08, 0x09, 0x0A)
    private val BODY_TYPES = setOf(1, 5, 7, 8)
    private val OWN_PRESETS = setOf("rect", "roundRect", "ellipse", "triangle", "star5", "rightArrow")

    /**
     * Office's shape types (MSOSPT, [MS-ODRAW] 2.4.24) as DrawingML's presets, for those the app draws
     * (the line callouts, whose leader lines Office places by adjust values, stay boxes). A balloon is
     * a rounded callout, a seal a 16-point star.
     */
    private val PRESETS = mapOf(
        1 to "rect", 2 to "roundRect", 3 to "ellipse", 4 to "diamond", 5 to "triangle", 6 to "rtTriangle", 7 to "parallelogram",
        8 to "trapezoid", 9 to "hexagon", 10 to "octagon", 11 to "plus", 12 to "star5", 13 to "rightArrow", 15 to "homePlate", 16 to "cube",
        17 to "wedgeRoundRectCallout", 18 to "star16", 19 to "arc", 21 to "plaque", 22 to "can", 23 to "donut",
        55 to "chevron", 56 to "pentagon", 57 to "noSmoking", 58 to "star8", 59 to "star16", 60 to "star32",
        61 to "wedgeRectCallout", 62 to "wedgeRoundRectCallout", 63 to "wedgeEllipseCallout", 64 to "wave", 65 to "foldedCorner",
        66 to "leftArrow", 67 to "downArrow", 68 to "upArrow", 69 to "leftRightArrow", 70 to "upDownArrow",
        71 to "irregularSeal1", 72 to "irregularSeal2", 73 to "lightningBolt", 74 to "heart", 76 to "quadArrow",
        77 to "leftArrowCallout", 78 to "rightArrowCallout", 79 to "upArrowCallout", 80 to "downArrowCallout",
        81 to "leftRightArrowCallout", 82 to "upDownArrowCallout", 83 to "quadArrowCallout", 84 to "bevel",
        85 to "leftBracket", 86 to "rightBracket", 87 to "leftBrace", 88 to "rightBrace", 89 to "leftUpArrow", 90 to "bentUpArrow",
        91 to "bentArrow", 92 to "star24", 93 to "stripedRightArrow", 94 to "notchedRightArrow", 95 to "blockArc", 96 to "smileyFace",
        99 to "circularArrow", 101 to "uturnArrow", 102 to "curvedRightArrow", 103 to "curvedLeftArrow", 104 to "curvedUpArrow",
        105 to "curvedDownArrow", 106 to "cloudCallout",
        109 to "flowChartProcess", 110 to "flowChartDecision", 111 to "flowChartInputOutput", 112 to "flowChartPredefinedProcess",
        113 to "flowChartInternalStorage", 114 to "flowChartDocument", 115 to "flowChartMultidocument", 116 to "flowChartTerminator",
        117 to "flowChartPreparation", 118 to "flowChartManualInput", 119 to "flowChartManualOperation", 120 to "flowChartConnector",
        121 to "flowChartPunchedCard", 122 to "flowChartPunchedTape", 123 to "flowChartSummingJunction", 124 to "flowChartOr",
        125 to "flowChartCollate", 126 to "flowChartSort", 127 to "flowChartExtract", 128 to "flowChartMerge",
        129 to "flowChartOfflineStorage", 130 to "flowChartOnlineStorage", 131 to "flowChartMagneticTape", 132 to "flowChartMagneticDisk",
        133 to "flowChartMagneticDrum", 134 to "flowChartDisplay", 135 to "flowChartDelay",
        176 to "flowChartAlternateProcess", 177 to "flowChartOffpageConnector", 182 to "leftRightUpArrow", 183 to "sun", 184 to "moon",
        185 to "bracketPair", 186 to "bracePair", 187 to "star4", 188 to "doubleWave", 189 to "actionButtonBlank",
        190 to "actionButtonHome", 191 to "actionButtonHelp", 192 to "actionButtonInformation",
        193 to "actionButtonForwardNext", 194 to "actionButtonBackPrevious", 195 to "actionButtonEnd", 196 to "actionButtonBeginning",
        197 to "actionButtonReturn", 198 to "actionButtonDocument", 199 to "actionButtonSound", 200 to "actionButtonMovie",
    )
}
