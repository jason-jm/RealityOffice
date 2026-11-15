package com.therealityteam.aeropages.format.doc

import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.DocFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.PAGE_BREAK
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.StyleSheet
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import java.io.InputStream

/**
 * Reader for legacy binary Word documents (`.doc`, the Word 97-2003 format).
 *
 * A `.doc` is a CFB container ([CompoundFileReader]) holding a "WordDocument"
 * stream (with the FIB) and a table stream ("0Table"/"1Table") that contains the
 * piece table (CLX). We follow [MS-DOC]:
 *
 *  1. Parse the FIB to find ccpText, fcClx/lcbClx and which table stream to use.
 *  2. Parse the CLX piece table: each piece points at a range of the
 *     WordDocument stream, stored either as compressed CP-1252 bytes or as
 *     raw UTF-16LE.
 *  3. Reconstruct the main-document text and split it into paragraphs.
 *
 * Formatting is recovered best-effort from the formatted-disk-page tables: character runs
 * (bold / italic / underline / strike / size / colour / font) from the CHPX FKPs and paragraph
 * alignment plus the built-in heading styles from the PAPX FKPs ([MS-DOC] 2.8.x, 2.6.1). A
 * damaged table only costs the formatting, never the text. Saving is always done as .docx.
 */
object DocReader {

    fun read(input: InputStream): Document {
        val bytes = input.readBytes()
        val cfb = CompoundFileReader(bytes)
        val wordDoc = cfb.readStream("WordDocument")
            ?: throw IllegalArgumentException("Not a valid .doc: missing WordDocument stream")

        val fib = Fib(wordDoc)
        val tableName = if (fib.useTable1) "1Table" else "0Table"
        val table = cfb.readStream(tableName) ?: cfb.readStream("0Table") ?: cfb.readStream("1Table")

        val pieces = if (table != null && fib.lcbClx > 0 && fib.fcClx >= 0 && fib.fcClx + fib.lcbClx <= table.size) parseClx(table, fib.fcClx, fib.lcbClx) else emptyList()
        // Every story's characters: the main text, then the footnotes', headers', comments', endnotes' and text boxes'.
        val text = extractText(wordDoc, pieces, fib, if (pieces.isEmpty()) fib.ccpText else fib.ccpAll)
        // Without a piece table the text is the contiguous fcMin..fcMac range: one compressed piece.
        val effective = pieces.ifEmpty { listOf(Piece(0, text.length, fib.fcMin * 2, true)) }
        val data = cfb.readStream("Data")
        val fmt = if (table != null) runCatching { readFormatting(wordDoc, table, fib, effective, text.length, data) }.getOrNull() else null
        val stories = Stories(fib)
        val notes = table?.let { runCatching { readNotes(table, fib, text, fmt, stories) }.getOrNull() } ?: Notes(emptyMap(), emptyList(), emptyList())
        val doc = Document(mutableListOf())
        // The main text's inline pictures, from the Data stream.
        val picture = data?.let { d -> { at: Int -> runCatching { inlinePicture(d, at) }.getOrNull() } }
        val comments = table?.let { t -> runCatching { readComments(t, fib, text, fmt, stories) }.getOrNull() }.orEmpty()
        // The bookmarks, and each thread's range as its comment's marks (the ranges kept with the text as bookmarks are).
        val bookmarks = (table?.let { runCatching { readBookmarks(it, fib) }.getOrNull() }.orEmpty()).let { b ->
            if (comments.none { it.range != null && it.comment.parentId == null }) b
            else HashMap<Int, MutableList<Pair<String, Boolean>>>().also { all ->
                b.forEach { (cp, l) -> all[cp] = l.toMutableList() }
                comments.filter { it.comment.parentId == null }.forEach { dc ->
                    val (a, z) = dc.range ?: return@forEach
                    val name = com.therealityteam.aeropages.model.Bookmarks.commentMark(dc.comment.id)
                    all.getOrPut(z) { ArrayList() }.add(name to true)
                    all.getOrPut(a) { ArrayList() }.add(0, name to false)
                }
            }
        }
        // Where sections end (their last character, a 0x0C): there a 0x0C is the section's end, elsewhere a page break.
        val sectionEnds = table?.let { t -> runCatching { plc(t, fib.fcPlcfSed, fib.lcbPlcfSed, 12)?.let { sed -> (0 until sed.n - 1).map { sed.cps[it + 1] - 1 }.toSet() } }.getOrNull() }.orEmpty()
        val main = paragraphs(text, fmt, 0, minOf(fib.ccpText, text.length).coerceAtLeast(if (pieces.isEmpty()) text.length else 0), notes.markers, picture = picture,
            bookmarks = bookmarks, sectionEnds = sectionEnds)
        if (table != null) {
            runCatching { readSections(wordDoc, table, fib, doc, main) }
            runCatching { anchorFloating(wordDoc, table, fib, text, fmt, stories, main) }
        }
        doc.blocks.addAll(blocksOf(main))
        if (doc.blocks.isEmpty()) doc.addParagraph(Paragraph.of(""))
        // Each comment on the paragraph its range starts in (else its reference's), a reply on its thread's.
        comments.forEach { dc ->
            val cp = dc.range?.first ?: dc.ref
            dc.comment.targetId = (main.lastOrNull { it.start <= cp } ?: main.firstOrNull())?.paragraph?.id ?: doc.paragraphs().first().id
            doc.comments.add(dc.comment)
        }
        doc.comments.forEach { c -> c.parentId?.let { pid -> doc.comments.firstOrNull { it.id == pid }?.let { c.targetId = it.targetId } } }
        doc.footnotes.addAll(notes.footnotes.map { com.therealityteam.aeropages.model.Footnote(it) })
        doc.endnotes.addAll(notes.endnotes.map { com.therealityteam.aeropages.model.Endnote(it) })
        doc.sourceFormat = DocFormat.DOC
        // The paragraph styles its paragraphs use, as the document's style sheet has them.
        fmt?.let { f -> runCatching { styleSheet(f, doc) } }
        // The lists its paragraphs use.
        fmt?.lists?.values?.forEach { inst -> doc.lists.putIfAbsent(inst.listId, inst.def) }
        doc.lists.keys.retainAll(doc.locations().map { it.paragraph.format.listId }.toSet())
        if (table != null) {
            runCatching { readHeaders(table, fib, text, fmt, stories, doc) }
            // The document properties: the default tab stops' distance (Word's half inch unless set) and facing pages.
            if (fib.fcDop >= 0 && fib.lcbDop >= 12 && fib.fcDop + 12 <= table.size) {
                le16(table, fib.fcDop + 10).takeIf { it in 20..14400 }?.let { doc.defaultTabPt = it / 20f }
                if ((table[fib.fcDop].toInt() and 1) != 0 && (doc.evenHeader != null || doc.evenFooter != null)) doc.differentOddEven = true
                // Track Changes on (DopBase's fRevMarking: byte 5's high bit).
                if ((table[fib.fcDop + 5].toInt() and 0x80) != 0) doc.trackRevisions = true
            }
        }
        doc.props = OleSummary.read(cfb)
        return doc
    }

    /** The slice of the File Information Block we need for text extraction. */
    private class Fib(stream: ByteArray) {
        val complex: Boolean
        val useTable1: Boolean
        val ccpText: Int
        val ccpFtn: Int; val ccpHdd: Int; val ccpMcr: Int; val ccpAtn: Int; val ccpEdn: Int; val ccpTxbx: Int; val ccpHdrTxbx: Int
        /** All the stories' characters together (the piece table covers them all). */
        val ccpAll: Int get() = ccpText + ccpFtn + ccpHdd + ccpMcr + ccpAtn + ccpEdn + ccpTxbx + ccpHdrTxbx
        val fcClx: Int
        val lcbClx: Int
        val fcMin: Int
        val fcMac: Int

        init {
            val flags = le16(stream, 0x0A)
            complex = (flags and 0x0004) != 0
            useTable1 = (flags and 0x0200) != 0

            fcMin = le32(stream, 0x18)
            fcMac = le32(stream, 0x1C)

            // Walk the variable-length FIB sections to locate fibRgLw and fibRgFcLcb.
            var pos = 32
            val csw = le16(stream, pos); pos += 2
            pos += csw * 2                 // skip fibRgW
            val cslw = le16(stream, pos); pos += 2
            val rgLwStart = pos
            pos += cslw * 4                // skip fibRgLw
            pos += 2                       // skip cbRgFcLcb

            // ccpText is the 4th 32-bit value of fibRgLw97 (index 3), the other stories' lengths after it.
            ccpText = le32(stream, rgLwStart + 3 * 4)
            ccpFtn = le32(stream, rgLwStart + 4 * 4).coerceAtLeast(0)
            ccpHdd = le32(stream, rgLwStart + 5 * 4).coerceAtLeast(0)
            ccpMcr = le32(stream, rgLwStart + 6 * 4).coerceAtLeast(0)
            ccpAtn = le32(stream, rgLwStart + 7 * 4).coerceAtLeast(0)
            ccpEdn = le32(stream, rgLwStart + 8 * 4).coerceAtLeast(0)
            ccpTxbx = le32(stream, rgLwStart + 9 * 4).coerceAtLeast(0)
            ccpHdrTxbx = le32(stream, rgLwStart + 10 * 4).coerceAtLeast(0)

            // fcClx / lcbClx sit at fixed offsets within the WordDocument stream
            // for the Word 97+ FIB (well-known constants from [MS-DOC]).
            fcClx = le32(stream, 0x01A2)
            lcbClx = le32(stream, 0x01A6)
        }

        // Formatting tables (FibRgFcLcb97 fields 24-31): character / paragraph bin tables and the font names.
        val fcPlcfBteChpx = le32(stream, 0x00FA); val lcbPlcfBteChpx = le32(stream, 0x00FE)
        val fcPlcfBtePapx = le32(stream, 0x0102); val lcbPlcfBtePapx = le32(stream, 0x0106)
        val fcSttbfFfn = le32(stream, 0x0112); val lcbSttbfFfn = le32(stream, 0x0116)
        // The document properties (FibRgFcLcb97 field 31).
        val fcDop = le32(stream, 0x0192); val lcbDop = le32(stream, 0x0196)
        // The other stories' tables (FibRgFcLcb97 fields 2, 3, 6, 11, 40, 46, 47, 56).
        val fcPlcffndRef = le32(stream, 0x00AA); val lcbPlcffndRef = le32(stream, 0x00AE)
        val fcPlcffndTxt = le32(stream, 0x00B2); val lcbPlcffndTxt = le32(stream, 0x00B6)
        val fcPlcfSed = le32(stream, 0x00CA); val lcbPlcfSed = le32(stream, 0x00CE)
        val fcPlcfHdd = le32(stream, 0x00F2); val lcbPlcfHdd = le32(stream, 0x00F6)
        val fcPlcSpaMom = le32(stream, 0x01DA); val lcbPlcSpaMom = le32(stream, 0x01DE)
        val fcPlcfendRef = le32(stream, 0x020A); val lcbPlcfendRef = le32(stream, 0x020E)
        val fcPlcfendTxt = le32(stream, 0x0212); val lcbPlcfendTxt = le32(stream, 0x0216)
        val fcPlcftxbxTxt = le32(stream, 0x025A); val lcbPlcftxbxTxt = le32(stream, 0x025E)
        // The drawings (FibRgFcLcb97 field 50): OfficeArtContent.
        val fcDggInfo = le32(stream, 0x022A); val lcbDggInfo = le32(stream, 0x022E)
        // The bookmarks (FibRgFcLcb97 fields 21-23): their names, where each starts, where each ends.
        val fcSttbfBkmk = le32(stream, 0x0142); val lcbSttbfBkmk = le32(stream, 0x0146)
        val fcPlcfBkf = le32(stream, 0x014A); val lcbPlcfBkf = le32(stream, 0x014E)
        val fcPlcfBkl = le32(stream, 0x0152); val lcbPlcfBkl = le32(stream, 0x0156)
        // The style sheet and the lists (FibRgFcLcb97 fields 1, 73, 74).
        val fcStshf = le32(stream, 0x00A2); val lcbStshf = le32(stream, 0x00A6)
        val fcPlfLst = le32(stream, 0x02E2); val lcbPlfLst = le32(stream, 0x02E6)
        val fcPlfLfo = le32(stream, 0x02EA); val lcbPlfLfo = le32(stream, 0x02EE)
        // The revision authors (FibRgFcLcb97 field 51): SttbfRMark, which the revision marks' ibst index.
        val fcSttbfRMark = le32(stream, 0x0232); val lcbSttbfRMark = le32(stream, 0x0236)
        // The comments (FibRgFcLcb97 fields 4, 5, 36, 37, 42, 43): references and their ATRDs, texts, authors, range bookmarks.
        val fcPlcfandRef = le32(stream, 0x00BA); val lcbPlcfandRef = le32(stream, 0x00BE)
        val fcPlcfandTxt = le32(stream, 0x00C2); val lcbPlcfandTxt = le32(stream, 0x00C6)
        val fcGrpXstAtnOwners = le32(stream, 0x01BA); val lcbGrpXstAtnOwners = le32(stream, 0x01BE)
        val fcSttbfAtnBkmk = le32(stream, 0x01C2); val lcbSttbfAtnBkmk = le32(stream, 0x01C6)
        val fcPlcfAtnBkf = le32(stream, 0x01EA); val lcbPlcfAtnBkf = le32(stream, 0x01EE)
        val fcPlcfAtnBkl = le32(stream, 0x01F2); val lcbPlcfAtnBkl = le32(stream, 0x01F6)
        // Word 2002's comment dates and threads (FibRgFcLcb2002's fcAtrdExtra, the FIB's 128th pair) when the FIB is that long.
        private val pairs = le16(stream, 0x0098)
        val fcAtrdExtra = if (pairs > 127 && stream.size >= 0x049A) le32(stream, 0x0492) else -1
        val lcbAtrdExtra = if (pairs > 127 && stream.size >= 0x049A) le32(stream, 0x0496) else 0
    }

    /**
     * The main text's bookmarks, by the CP each end stands at (name, and whether it is the end): the
     * names from SttbfBkmk, each one's start from PlcfBkf, whose FBKF (ibkl) says which PlcfBkl entry
     * is its end. At one CP the ends of bookmarks begun before it come first, then starts, then the
     * ends of points (a bookmark starting and ending there).
     */
    private fun readBookmarks(table: ByteArray, fib: Fib): Map<Int, List<Pair<String, Boolean>>> {
        val names = sttb(table, fib.fcSttbfBkmk, fib.lcbSttbfBkmk)
        val starts = plc(table, fib.fcPlcfBkf, fib.lcbPlcfBkf, 4) ?: return emptyMap()
        val ends = plc(table, fib.fcPlcfBkl, fib.lcbPlcfBkl, 0) ?: return emptyMap()
        class Bk(val name: String, val start: Int, val end: Int)
        val all = (0 until starts.n).mapNotNull { i ->
            val name = names.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val ibkl = le16(table, starts.dataAt + i * 4)
            val end = ends.cps.getOrNull(ibkl)?.takeIf { ibkl < ends.n } ?: return@mapNotNull null
            val start = starts.cps[i]
            if (start < 0 || start > fib.ccpText || end < start) null else Bk(name, start, end.coerceAtMost(fib.ccpText))
        }
        val out = HashMap<Int, MutableList<Pair<String, Boolean>>>()
        all.filter { it.end > it.start }.forEach { out.getOrPut(it.end) { ArrayList() }.add(it.name to true) }
        all.forEach { out.getOrPut(it.start) { ArrayList() }.add(it.name to false) }
        all.filter { it.end == it.start }.forEach { out.getOrPut(it.end) { ArrayList() }.add(it.name to true) }
        return out
    }

    /** An STTB's strings (Unicode when it starts with fExtend 0xFFFF, else 8-bit); empty when it does not fit. */
    private fun sttb(table: ByteArray, fc: Int, lcb: Int): List<String> = sttbEntries(table, fc, lcb).map { it.first }

    /** An STTB's entries: each string and its extra data (cbExtra bytes after it). */
    private fun sttbEntries(table: ByteArray, fc: Int, lcb: Int): List<Pair<String, ByteArray>> {
        if (fc < 0 || lcb < 4 || fc + lcb > table.size) return emptyList()
        val end = fc + lcb
        val extended = le16(table, fc) == 0xFFFF
        var pos = if (extended) fc + 2 else fc
        val count = le16(table, pos); pos += 2
        val cbExtra = le16(table, pos); pos += 2
        val out = ArrayList<Pair<String, ByteArray>>(count)
        repeat(count) {
            val str: String
            if (extended) {
                if (pos + 2 > end) return out
                val cch = le16(table, pos); pos += 2
                if (pos + cch * 2 > end) return out
                str = String(table, pos, cch * 2, Charsets.UTF_16LE); pos += cch * 2
            } else {
                if (pos + 1 > end) return out
                val cch = table[pos].toInt() and 0xFF; pos += 1
                if (pos + cch > end) return out
                str = String(table, pos, cch, Charsets.ISO_8859_1); pos += cch
            }
            if (pos + cbExtra > end) return out
            out.add(str to table.copyOfRange(pos, pos + cbExtra))
            pos += cbExtra
        }
        return out
    }

    /**
     * A comment read ([MS-DOC] 2.3.4): its comment, the CP of its reference in the main text, and its range's start and
     * end CPs when it has one.
     */
    private class DocComment(val comment: com.therealityteam.aeropages.model.Comment, val ref: Int, val range: Pair<Int, Int>?)

    /**
     * The comments: each reference's ATRD (PlcfandRef: its author — an index into GrpXstAtnOwners — and the tag of its
     * range), its text in the comments' story (PlcfandTxt), its range (SttbfAtnBkmk's ATNBE tags with PlcfAtnBkf /
     * PlcfAtnBkl), and Word 2002's date and thread (AtrdExtra's ATRDPost10s).
     */
    private fun readComments(table: ByteArray, fib: Fib, text: String, fmt: Formatting?, s: Stories): List<DocComment> {
        val refs = plc(table, fib.fcPlcfandRef, fib.lcbPlcfandRef, 30) ?: return emptyList()
        val txts = plc(table, fib.fcPlcfandTxt, fib.lcbPlcfandTxt, 0) ?: return emptyList()
        val owners = ArrayList<String>()
        run {
            var pos = fib.fcGrpXstAtnOwners
            val end = fib.fcGrpXstAtnOwners + fib.lcbGrpXstAtnOwners
            while (pos >= 0 && pos + 2 <= end && end <= table.size) {
                val cch = le16(table, pos); pos += 2
                if (pos + cch * 2 > end) break
                owners.add(String(table, pos, cch * 2, Charsets.UTF_16LE)); pos += cch * 2
            }
        }
        // Ranges by tag: the i-th SttbfAtnBkmk entry's ATNBE (bmc, lTag, lTagOld) is the i-th PlcfAtnBkf start, whose FBKF names its end.
        val ranges = HashMap<Int, Pair<Int, Int>>()
        runCatching {
            val tags = sttbEntries(table, fib.fcSttbfAtnBkmk, fib.lcbSttbfAtnBkmk)
            val bkf = plc(table, fib.fcPlcfAtnBkf, fib.lcbPlcfAtnBkf, 4)
            val bkl = plc(table, fib.fcPlcfAtnBkl, fib.lcbPlcfAtnBkl, 0)
            if (bkf != null && bkl != null) tags.forEachIndexed { i, (_, extra) ->
                if (extra.size < 6 || i >= bkf.n) return@forEachIndexed
                val tag = le32(extra, 2)
                val ibkl = le16(table, bkf.dataAt + i * 4)
                val end = bkl.cps.getOrNull(ibkl)?.takeIf { ibkl < bkl.n } ?: return@forEachIndexed
                ranges[tag] = bkf.cps[i] to end
            }
        }
        // Word 2002's ATRDPost10s, one per comment: its date, its depth and its parent's offset from it.
        val extra = fib.fcAtrdExtra.takeIf { fib.lcbAtrdExtra >= 18 * refs.n && it >= 0 && it + 18 * refs.n <= table.size }
        val out = ArrayList<DocComment>()
        for (k in 0 until refs.n) {
            if (k + 1 >= txts.cps.size) break
            val at = refs.dataAt + k * 30
            val initials = le16(table, at).coerceIn(0, 9).let { n -> String(table, at + 2, n * 2, Charsets.UTF_16LE) }
            val author = owners.getOrNull(le16(table, at + 20))?.takeIf { it.isNotBlank() } ?: initials
            val tag = le32(table, at + 26)
            val body = paragraphs(text, fmt, s.atn + txts.cps[k], s.atn + txts.cps[k + 1]).joinToString("\n") { it.paragraph.plainText() }.trim()
            val date = extra?.let { dttm(le32(table, it + k * 18)) }
            val c = com.therealityteam.aeropages.model.Comment(author, body, -1L, dateMs = date ?: System.currentTimeMillis())
            out.add(DocComment(c, refs.cps[k], ranges[tag]?.takeIf { tag != -1 }))
        }
        // Threads: a comment of depth > 0 answers the one its parent offset points at (the thread's range is the thread's).
        if (extra != null) out.forEachIndexed { k, dc ->
            val depth = le32(table, extra + k * 18 + 6)
            val parent = k + le32(table, extra + k * 18 + 10)
            if (depth > 0 && parent != k && parent in out.indices) out[parent].comment.let { p -> dc.comment.parentId = p.parentId ?: p.id }
        }
        return out
    }

    /**
     * A shape of the main document's drawing: its type (MSOSPT), properties (its FOPT over its tertiary
     * one) and their complex data, and its FSP flags.
     */
    private class DrawnShape(val type: Int, val props: Map<Int, Int>, val flags: Int = 0, val complex: Map<Int, ByteArray> = emptyMap()) {
        /** Mirrored (FSP's fFlipH / fFlipV), a half turn (rotation, 16.16 degrees) being both. */
        private val halfTurn: Boolean get() = props[0x0004]?.let { Math.round((((it / 65536f) % 360f) + 360f) % 360f) == 180 } == true
        val flipH: Boolean get() = (flags and 0x40 != 0) != halfTurn
        val flipV: Boolean get() = (flags and 0x80 != 0) != halfTurn

        /** Behind the document's text: the group shape booleans' fBehindDocument (its use bit set). */
        val behindDocument: Boolean get() = props[0x03BF]?.let { it and 0x200000 != 0 && it and 0x20 != 0 } == true
    }

    /** The main document's drawing: its shapes by their id, how their properties look, the drawing group's pictures (its BStore's FBSEs). */
    private class Drawing(val shapes: Map<Int, DrawnShape>, val look: com.therealityteam.aeropages.format.ppt.OfficeArtLook, val blips: List<com.therealityteam.aeropages.format.ppt.PptReader.Rec>)

    /**
     * The main document's drawing: the OfficeArtContent at fcDggInfo — the drawing group (with its
     * store of pictures), then each drawing, the main document's (dgglbl 0) holding the shapes, in
     * groups as deep as they go ([MS-DOC] 2.11.1, [MS-ODRAW]). The drawing group's own properties are
     * the defaults Word gives shapes the user adds, not what the shapes in the file inherit (LibreOffice
     * reads them so too): what a shape leaves unsaid takes [MS-ODRAW]'s defaults.
     */
    private fun readDrawing(table: ByteArray, fib: Fib): Drawing? {
        val fc = fib.fcDggInfo; val end = fc + fib.lcbDggInfo
        if (fc < 0 || fib.lcbDggInfo < 8 || end > table.size) return null
        val pr = com.therealityteam.aeropages.format.ppt.PptReader
        val dgg = pr.recAt(table, fc, end)?.takeIf { it.type == pr.DGG_CONTAINER } ?: return null
        val look = com.therealityteam.aeropages.format.ppt.OfficeArtLook()
        val shapes = HashMap<Int, DrawnShape>()
        fun walk(r: com.therealityteam.aeropages.format.ppt.PptReader.Rec) {
            for (c in pr.children(table, r)) when (c.type) {
                pr.SPGR_CONTAINER, pr.DG_CONTAINER -> walk(c)
                pr.SP_CONTAINER -> {
                    val kids = pr.children(table, c)
                    val fsp = kids.firstOrNull { it.type == pr.FSP && it.len >= 4 } ?: continue
                    val opt = com.therealityteam.aeropages.format.ppt.PptReader.Opt.merge(kids.firstOrNull { it.type == pr.TERTIARY_FOPT }?.let { com.therealityteam.aeropages.format.ppt.PptReader.Opt.read(table, it) },
                        kids.firstOrNull { it.type == pr.FOPT }?.let { com.therealityteam.aeropages.format.ppt.PptReader.Opt.read(table, it) })
                    shapes[pr.u32(table, fsp.start)] = DrawnShape(fsp.inst, opt.props, if (fsp.len >= 8) pr.u32(table, fsp.start + 4) else 0, opt.complex)
                }
            }
        }
        val blips = pr.children(table, dgg).firstOrNull { it.type == pr.BSTORE_CONTAINER }?.let { store -> pr.children(table, store).filter { it.type == pr.FBSE } }.orEmpty()
        // Each drawing: a byte naming whose it is (0 the main document's, 1 the headers'), then its container.
        var at = dgg.end
        while (at + 9 <= end) {
            val label = table[at].toInt() and 0xFF
            val dg = pr.recAt(table, at + 1, end) ?: break
            if (label == 0 && dg.type == pr.DG_CONTAINER) walk(dg)
            at = dg.end
        }
        return Drawing(shapes, look, blips)
    }

    /**
     * The picture an FBSE stands for ([MS-ODRAW] 2.2.32): the BLIP it holds after its name, or — none
     * there — the one at its foDelay in [delay] (a .doc's store keeps them in the WordDocument stream).
     */
    private fun blipOf(b: ByteArray, fbse: com.therealityteam.aeropages.format.ppt.PptReader.Rec, delay: ByteArray?): Pair<ByteArray, String>? {
        if (fbse.len < 36) return null
        val pr = com.therealityteam.aeropages.format.ppt.PptReader
        val held = fbse.start + 36 + (b[fbse.start + 33].toInt() and 0xFF)
        pr.recAt(b, held, fbse.end)?.let { return com.therealityteam.aeropages.format.ppt.OfficeArtBlips.blipData(b, it) }
        val d = delay ?: return null
        return pr.recAt(d, le32(b, fbse.start + 28))?.let { com.therealityteam.aeropages.format.ppt.OfficeArtBlips.blipData(d, it) }
    }

    /**
     * An inline picture: the PICFAndOfficeArtData at [fc] of the Data stream ([MS-DOC] 2.9.192–2.9.193).
     * Its PICF gives the picture's size — dxaGoal × dyaGoal twips less the crop, scaled by mx × my
     * thousandths — and the crop, in twips of the unscaled picture; after it (and a linked picture's
     * file name, MM_SHAPEFILE) the shape, then the FBSEs its pib counts among, each holding its BLIP.
     * A crop only the shape gives (fractions, 16.16) applies when the PICF has none.
     */
    private fun inlinePicture(data: ByteArray, fc: Int): com.therealityteam.aeropages.model.ImageBlock? {
        if (fc < 0 || fc + 68 > data.size || le16(data, fc + 4) != 0x44) return null
        val end = minOf(fc + le32(data, fc), data.size)
        if (end < fc + 68) return null
        val pr = com.therealityteam.aeropages.format.ppt.PptReader
        var at = fc + 68
        if (le16(data, fc + 6) == 0x66) at += 1 + (data[at].toInt() and 0xFF)
        val sp = pr.recAt(data, at, end)?.takeIf { it.type == pr.SP_CONTAINER } ?: return null
        val props = pr.children(data, sp).firstOrNull { it.type == pr.FOPT }?.let { com.therealityteam.aeropages.format.ppt.PptReader.Opt.read(data, it).props }.orEmpty()
        val fbses = pr.children(data, sp.end, end).filter { it.type == pr.FBSE }
        val fbse = props[0x0104]?.let { fbses.getOrNull(it - 1) } ?: fbses.firstOrNull() ?: return null
        val (bytes, mime) = blipOf(data, fbse, null) ?: return null
        val goalW = le16(data, fc + 28); val goalH = le16(data, fc + 30)
        val mx = le16(data, fc + 32).takeIf { it > 0 } ?: 1000; val my = le16(data, fc + 34).takeIf { it > 0 } ?: 1000
        val crop = IntArray(4) { le16(data, fc + 36 + it * 2).toShort().toInt().coerceAtLeast(0) }   // left, top, right, bottom
        val img = com.therealityteam.aeropages.model.ImageBlock(bytes, mime, 0f, 0f)
        if (goalW > 0 && goalH > 0) {
            img.widthPt = (goalW - crop[0] - crop[2]).coerceAtLeast(goalW / 20) * mx / 1000f / 20f
            img.heightPt = (goalH - crop[1] - crop[3]).coerceAtLeast(goalH / 20) * my / 1000f / 20f
            img.cropL = crop[0].toFloat() / goalW; img.cropT = crop[1].toFloat() / goalH
            img.cropR = crop[2].toFloat() / goalW; img.cropB = crop[3].toFloat() / goalH
        } else {
            // No size given: the picture's own, a pixel a point (at 96 dpi, three quarters of one).
            val (pw, ph) = com.therealityteam.aeropages.format.ImageSize.of(bytes) ?: (300f to 200f)
            img.widthPt = pw * 0.75f; img.heightPt = ph * 0.75f
        }
        if (!img.cropped) cropOf(img, props)
        return img.takeIf { it.widthPt > 0f && it.heightPt > 0f }
    }

    /** A shape's crop ([MS-ODRAW] cropFromTop, …Bottom, …Left, …Right: 16.16 fractions of the picture) on [img]. */
    private fun cropOf(img: com.therealityteam.aeropages.model.ImageBlock, props: Map<Int, Int>) {
        fun frac(id: Int) = ((props[id] ?: 0) / 65536f).coerceIn(0f, 0.95f)
        img.cropT = frac(0x0100); img.cropB = frac(0x0101); img.cropL = frac(0x0102); img.cropR = frac(0x0103)
    }

    /** Where each story starts in the document's characters ([MS-DOC] 2.3.1). */
    private class Stories(fib: Fib) {
        val ftn = fib.ccpText
        val hdd = ftn + fib.ccpFtn
        val atn = hdd + fib.ccpHdd + fib.ccpMcr
        val edn = atn + fib.ccpAtn
        val txbx = edn + fib.ccpEdn
    }

    /** A PLC's CPs ([n]+1) and the start of its [n] data elements, each [cbData] bytes; null when it does not fit. */
    private class Plc(val cps: IntArray, val dataAt: Int, val cbData: Int) { val n get() = cps.size - 1 }

    private fun plc(table: ByteArray, fc: Int, lcb: Int, cbData: Int): Plc? {
        if (fc < 0 || lcb < 8 || fc + lcb > table.size) return null
        val n = (lcb - 4) / (4 + cbData)
        if (n < 1) return null
        return Plc(IntArray(n + 1) { le32(table, fc + it * 4) }, fc + (n + 1) * 4, cbData)
    }

    private fun extractText(wordDoc: ByteArray, pieces: List<Piece>, fib: Fib, length: Int): String {
        // Prefer the piece table when we have a usable CLX; otherwise fall back to
        // the contiguous fcMin..fcMac range (older / simple documents).
        if (pieces.isNotEmpty()) return reconstruct(wordDoc, pieces, length)
        if (!fib.complex && fib.fcMac > fib.fcMin && fib.fcMac <= wordDoc.size) {
            // Heuristic: try 8-bit then fall back to treating as UTF-16LE.
            val len = fib.fcMac - fib.fcMin
            return String(wordDoc, fib.fcMin, len, Charsets.ISO_8859_1)
        }
        return ""
    }

    private class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean)

    /** Parse the CLX (which may have leading Prc blocks before the Pcdt). */
    private fun parseClx(table: ByteArray, start: Int, len: Int): List<Piece> {
        var pos = start
        val end = start + len
        while (pos < end) {
            when (table[pos].toInt() and 0xFF) {
                0x01 -> { // Prc: skip cbGrpprl bytes
                    val cb = le16(table, pos + 1)
                    pos += 3 + cb
                }
                0x02 -> { // Pcdt: the piece table
                    val lcb = le32(table, pos + 1)
                    val plcStart = pos + 5
                    return parsePlcPcd(table, plcStart, lcb)
                }
                else -> return emptyList()
            }
        }
        return emptyList()
    }

    /** PlcPcd: (n+1) CP values followed by n 8-byte piece descriptors. */
    private fun parsePlcPcd(table: ByteArray, start: Int, lcb: Int): List<Piece> {
        if (lcb < 4) return emptyList()
        val n = (lcb - 4) / (4 + 8)
        val cps = IntArray(n + 1) { le32(table, start + it * 4) }
        val pcdBase = start + (n + 1) * 4
        val pieces = ArrayList<Piece>(n)
        for (i in 0 until n) {
            val off = pcdBase + i * 8
            val fcCompressed = le32(table, off + 2)
            val compressed = (fcCompressed and 0x40000000.toInt()) != 0
            val fc = fcCompressed and 0x3FFFFFFF
            pieces.add(Piece(cps[i], cps[i + 1], fc, compressed))
        }
        return pieces
    }

    private fun reconstruct(wordDoc: ByteArray, pieces: List<Piece>, ccpText: Int): String {
        val sb = StringBuilder()
        for (p in pieces) {
            if (sb.length >= ccpText) break
            val count = p.cpEnd - p.cpStart
            if (count <= 0) continue
            if (p.compressed) {
                val base = p.fc / 2
                for (i in 0 until count) {
                    val idx = base + i
                    if (idx in wordDoc.indices) sb.append(cp1252(wordDoc[idx].toInt() and 0xFF))
                }
            } else {
                val base = p.fc
                for (i in 0 until count) {
                    val idx = base + i * 2
                    if (idx + 1 < wordDoc.size) {
                        val c = (wordDoc[idx].toInt() and 0xFF) or ((wordDoc[idx + 1].toInt() and 0xFF) shl 8)
                        sb.append(c.toChar())
                    }
                }
            }
        }
        if (sb.length > ccpText && ccpText > 0) sb.setLength(ccpText)
        return sb.toString()
    }

    /** A compressed piece's byte as the character it stands for: CP-1252, which differs from Latin-1 in 0x80–0x9F (curly quotes, dashes, €…). */
    private fun cp1252(b: Int): Char = if (b in 0x80..0x9F) CP1252_HIGH[b - 0x80] else b.toChar()

    private const val CP1252_HIGH = "\u20AC\u0081\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u008D\u017D\u008F\u0090\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u009D\u017E\u0178"

    // --- formatting (CHPX / PAPX formatted disk pages) ---------------------------

    /**
     * Per-character run format ids and per-character paragraph property ids, plus the tables they index;
     * [pics]: the run formats that place a picture (sprmCPicLocation, not form field data), by id, its
     * offset in the Data stream.
     */
    private class Formatting(
        val charFmt: IntArray, val chars: List<CharacterFormat>, val paraFmt: IntArray, val paras: List<ParaProps>, val pics: Map<Int, Int>,
        /** The list instances by 1-based index, and the paragraph styles' lists (see DocLists). */
        val lists: Map<Int, DocLists.Instance> = emptyMap(), val styleLists: Map<Int, DocLists.StyleList> = emptyMap(),
        /** The style sheet (STSH), each character format's own sprms (by [chars] index) and the font table they name. */
        val styles: Map<Int, DocStyles.Std> = emptyMap(), val charSprms: List<ByteArray?> = emptyList(), val fonts: List<String> = emptyList(),
        /** The revision authors (SttbfRMark) the runs' revision marks name. */
        val authors: List<String> = emptyList(),
    ) {
        private val styleChars = HashMap<Int, CharacterFormat>()
        private val runFormats = HashMap<Long, CharacterFormat>()

        /** What a paragraph of style [istd] sets its text in: the style chain's character sprms over Word's defaults (10 pt). */
        fun styleChar(istd: Int): CharacterFormat = styleChars.getOrPut(istd) {
            val f = CharacterFormat(fontSizePt = 10f)
            for (st in DocStyles.chain(styles, istd)) st.chpx?.let { applyChpx(f, it, f.copyOf(), fonts, authors) }
            f
        }

        /**
         * Run [id]'s format in a paragraph of style [istd]: its own sprms over the style's — a toggle (bold, italic …)
         * of "the same as" or "the opposite of the style" taken against it. Without a style sheet, the run's own format.
         */
        fun runFormat(id: Int, istd: Int): CharacterFormat {
            if (styles.isEmpty()) return chars.getOrNull(id)?.copyOf() ?: CharacterFormat()
            return runFormats.getOrPut((id.toLong() shl 20) or istd.toLong()) {
                val base = styleChar(istd)
                val f = base.copyOf()
                charSprms.getOrNull(id)?.let { applyChpx(f, it, base, fonts, authors) }
                f
            }.copyOf()
        }
    }
    /**
     * A PAPX's alignment, style and indents: left, right and first line (signed, hanging negative) in
     * twips, or — the `Dxc` sprms, non-zero — in hundredths of a character (字符).
     */
    private class ParaProps(var align: TextAlign?, val istd: Int) {
        var left: Int? = null; var right: Int? = null; var first: Int? = null
        var leftChars = 0; var rightChars = 0; var firstChars = 0
        /** Tab stops the PAPX sets (sprmPChgTabsPapx; the ones it deletes only undo its style's, which are not read). */
        val tabs = ArrayList<com.therealityteam.aeropages.model.TabStop>()
        /** In a table (sprmPFInTable, sprmPItap), how deep (1: an outer table), and whether it ends a row (sprmPFTtp) with its [row]'s definition. */
        var inTable = false; var itap = 0; var ttp = false
        var row: RowDef? = null
        /** Its list instance (sprmPIlfo, 1-based; 0: none, even if its style has one) and level (sprmPIlvl). */
        var ilfo: Int? = null; var ilvl: Int? = null
        /** Space before and after in twips (sprmPDyaBefore / sprmPDyaAfter, "auto" being 14 pt), line spacing as lines (sprmPDyaLine). */
        var before: Int? = null; var after: Int? = null; var line: Float? = null
        /** An exact or least line height in points (LSPD not a multiple), instead of [line]. */
        var lineHeight: Float? = null; var lineExact = true
        /** Page break before (sprmPFPageBreakBefore), keep lines together (sprmPFKeep), keep with next (sprmPFKeepFollow), widow control (sprmPFWidowControl). */
        var pageBreakBefore = false; var keepTogether = false; var keepNext: Boolean? = null; var widowControl: Boolean? = null
        /** Keeps to the document grid (sprmPFUsePgsuSettings; on unless it says otherwise). */
        var snap = true
        /** Its borders (sprmPBrcTop80… / sprmPBrcTop…): each side's line (null: none) and space in points; and its shading (sprmPShd80 / sprmPShd). */
        val sides = HashMap<String, Pair<com.therealityteam.aeropages.model.CellBorder?, Float>>()
        var shading: Long? = null
        /** Its lines not numbered (sprmPFNoLineNumb). */
        var noLineNumbers = false
        /** A drop cap's frame (sprmPDcs: the kind in its low 3 bits — 1 dropped, 2 in the margin — and the lines above), and its distance from the text (sprmPDxaFromText, twips). */
        var dcs = 0
        var fromText = 0

        /** The drop cap this paragraph's frame holds, or null. */
        fun dropCap(): com.therealityteam.aeropages.model.DropCap? {
            val kind = dcs and 7
            if (kind != 1 && kind != 2) return null
            return com.therealityteam.aeropages.model.DropCap(inMargin = kind == 2, lines = ((dcs shr 3) and 0x1F).coerceIn(1, com.therealityteam.aeropages.model.DropCap.MAX_LINES),
                distancePt = (fromText / 20f).coerceIn(0f, 72f))
        }

        /** A side's space; a side without a line keeps Word's distance ([dflt]) for when one is set. */
        private fun space(side: String, dflt: Float): Float = sides[side]?.takeIf { it.first != null }?.second ?: dflt

        /** The borders its sides make, or null for none. */
        fun borders(): com.therealityteam.aeropages.model.ParagraphBorders? = com.therealityteam.aeropages.model.ParagraphBorders(
            top = sides["top"]?.first, bottom = sides["bottom"]?.first, left = sides["left"]?.first, right = sides["right"]?.first, between = sides["between"]?.first,
            spaceTop = space("top", 1f), spaceBottom = space("bottom", 1f), spaceLeft = space("left", 4f), spaceRight = space("right", 4f),
        ).takeIf { !it.none }
    }

    /**
     * A table row as its terminating paragraph defines it (the TAP's sprms): its cells' boundaries in
     * twips (sprmTDefTable's rgdxaCenter), each cell's horizontal and vertical merge and whether it has
     * borders (its TC80), its shading (sprmTDefTableShd80 / sprmTDefTableShd), a header row
     * (sprmTTableHeader), the table's borders (sprmTTableBorders80 / sprmTTableBorders).
     */
    private class RowDef {
        var bounds = IntArray(0)
        var horz = IntArray(0); var vert = IntArray(0)
        var cellBorders = false
        /** Each cell's own borders (its TC80's): the sides it sets — top, left, bottom, right — and their lines (null: nil, none). */
        var cellSides: List<Map<String, com.therealityteam.aeropages.model.CellBorder?>> = emptyList()
        /** The table's borders (sprmTTableBorders80 / sprmTTableBorders): top, left, bottom, right, inside ones. */
        var tableSides: Map<String, com.therealityteam.aeropages.model.CellBorder?>? = null
        val shades = HashMap<Int, Long>()
        var header = false
        var tableBorders: Boolean? = null
        /** Every cell's four borders explicitly none (TC80 BRC80s all 0xFFFFFFFF): they override the table's own borders. */
        var cellsNil = false
        /** A positioned table's frames (sprmTPc) and place across and down (sprmTDxaAbs / sprmTDyaAbs: XAS / YAS); null when not set. */
        var pc: Int? = null; var dxaAbs: Int? = null; var dyaAbs: Int? = null
        val positioned: Boolean get() = pc != null || dxaAbs != null || dyaAbs != null
        /** Where the table stands across its column (sprmTJc: 0 left, 1 centre, 2 right) and the row's height (sprmTDyaRowHeight: > 0 at least, < 0 exactly, twips). */
        var jc = 0; var rowHeight = 0
    }

    private fun readFormatting(wordDoc: ByteArray, table: ByteArray, fib: Fib, pieces: List<Piece>, textLen: Int, data: ByteArray? = null): Formatting? {
        if (textLen <= 0) return null
        val fonts = runCatching { parseFontNames(table, fib.fcSttbfFfn, fib.lcbSttbfFfn) }.getOrDefault(emptyList())
        val authors = runCatching { sttb(table, fib.fcSttbfRMark, fib.lcbSttbfRMark) }.getOrDefault(emptyList())
        val charFmt = IntArray(textLen); val chars = ArrayList<CharacterFormat>().apply { add(CharacterFormat()) }
        val paraFmt = IntArray(textLen); val paras = ArrayList<ParaProps>().apply { add(ParaProps(null, 0)) }
        val pics = HashMap<Int, Int>()
        val styles = runCatching { DocStyles.read(table, fib.fcStshf, fib.lcbStshf) }.getOrDefault(emptyMap())
        val charSprms = ArrayList<ByteArray?>().apply { add(null) }
        // Character runs: every CHPX FKP page the bin table points at.
        forEachFkp(wordDoc, table, fib.fcPlcfBteChpx, fib.lcbPlcfBteChpx) { page ->
            val crun = page[511].toInt() and 0xFF
            if ((crun + 1) * 4 + crun > 511) return@forEachFkp
            for (i in 0 until crun) {
                val fcA = le32(page, i * 4); val fcB = le32(page, (i + 1) * 4)
                val rgb = page[(crun + 1) * 4 + i].toInt() and 0xFF
                if (rgb == 0) continue
                val off = rgb * 2
                if (off !in page.indices) continue
                val cb = page[off].toInt() and 0xFF
                if (off + 1 + cb > page.size) continue
                val f = CharacterFormat()
                var pic = -1; var formData = false
                decodeSprms(page, off + 1, off + 1 + cb) { sprm, op, opAt ->
                    when (sprm) {
                        0x6A03 -> pic = op            // sprmCPicLocation
                        0x0806 -> formData = op != 0  // sprmCFData: a form field's data, not a picture
                        else -> applyCharSprm(f, sprm, op, page, opAt, fonts, authors)
                    }
                }
                if (formData) pic = -1
                if (f == CharacterFormat() && pic < 0 && styles.isEmpty()) continue
                chars.add(f); charSprms.add(page.copyOfRange(off + 1, off + 1 + cb)); val id = chars.size - 1
                if (pic >= 0) pics[id] = pic
                forEachCp(pieces, fcA, fcB, textLen) { cp -> charFmt[cp] = id }
            }
        }
        // Paragraph runs: PAPX FKP pages (13-byte BX entries; the PAPX starts with the style index).
        forEachFkp(wordDoc, table, fib.fcPlcfBtePapx, fib.lcbPlcfBtePapx) { page ->
            val crun = page[511].toInt() and 0xFF
            if ((crun + 1) * 4 + crun * 13 > 511) return@forEachFkp
            for (i in 0 until crun) {
                val fcA = le32(page, i * 4); val fcB = le32(page, (i + 1) * 4)
                val bOffset = page[(crun + 1) * 4 + i * 13].toInt() and 0xFF
                if (bOffset == 0) continue
                var at = bOffset * 2
                if (at + 2 > page.size) continue
                var cb = page[at].toInt() and 0xFF
                val start: Int; val len: Int
                if (cb == 0) { cb = page[at + 1].toInt() and 0xFF; start = at + 2; len = cb * 2 } else { start = at + 1; len = cb * 2 - 1 }
                if (len < 2 || start + len > page.size) continue
                val istd = le16(page, start)
                val pp = ParaProps(null, istd)
                // Its style's paragraph properties (through the styles it is based on), then its own over them.
                for (st in DocStyles.chain(styles, istd)) st.papx?.let { b -> decodeSprms(b, 0, b.size) { sprm, op, opAt -> paraSprm(pp, sprm, op, b, opAt, data) } }
                decodeSprms(page, start + 2, start + len) { sprm, op, opAt -> paraSprm(pp, sprm, op, page, opAt, data) }
                paras.add(pp); val id = paras.size - 1
                forEachCp(pieces, fcA, fcB, textLen) { cp -> paraFmt[cp] = id }
            }
        }
        val lists = runCatching { DocLists.read(table, fib.fcPlfLst, fib.lcbPlfLst, fib.fcPlfLfo, fib.lcbPlfLfo, fonts) }.getOrDefault(emptyMap())
        val styleLists = if (lists.isEmpty()) emptyMap() else runCatching { DocLists.styles(table, fib.fcStshf, fib.lcbStshf) }.getOrDefault(emptyMap())
        return Formatting(charFmt, chars, paraFmt, paras, pics, lists, styleLists, styles, charSprms, fonts, authors)
    }

    /**
     * The paragraph styles the document's paragraphs use, each with what it sets (size, weight, font, colour,
     * alignment, spacing, indents) and the style it is based on, into [doc]'s style sheet — so the style
     * gallery shows them as the file has them and Word gets them back.
     */
    private fun styleSheet(fmt: Formatting, doc: Document) {
        val used = doc.locations().mapNotNull { it.paragraph.format.rawStyleId }.toSet()
        val byId = fmt.styles.values.filter { it.stk == 1 }.associateBy { styleIdOf(it) }
        val wanted = LinkedHashSet<String>()
        fun want(id: String) {
            if (!wanted.add(id)) return
            val std = byId[id] ?: return
            fmt.styles[std.base]?.takeIf { std.base != 0xFFF && it.stk == 1 }?.let { want(styleIdOf(it)) }
        }
        used.forEach(::want)
        doc.styles.defaultSizePt = 10f
        for (id in wanted) {
            val std = byId[id] ?: continue
            val c = fmt.styleChar(std.istd)
            val pp = ParaProps(null, std.istd)
            for (st in DocStyles.chain(fmt.styles, std.istd)) st.papx?.let { b -> decodeSprms(b, 0, b.size) { sprm, op, opAt -> paraSprm(pp, sprm, op, b, opAt, null) } }
            doc.styles.put(com.therealityteam.aeropages.model.StyleDef(
                id, name = std.name.substringBefore(',').trim().ifEmpty { id },
                basedOn = fmt.styles[std.base]?.takeIf { std.base != 0xFFF }?.let { styleIdOf(it) },
                fontFamily = c.fontFamily, sizePt = c.fontSizePt, bold = c.bold, italic = c.italic, color = c.color,
                align = pp.align, spaceBeforePt = (pp.before ?: 0) / 20f, spaceAfterPt = (pp.after ?: 0) / 20f, lineSpacing = pp.line ?: 1f.takeIf { pp.lineHeight == null }, lineHeightPt = pp.lineHeight, lineExact = pp.lineExact,
                outlineLevel = builtinOf(std.sti)?.takeIf { it.name.startsWith("HEADING") }?.let { std.sti - 1 },
                indentLeftPt = pp.left?.let { it / 20f }, firstLineIndentPt = pp.first?.let { it / 20f }, indentRightPt = pp.right?.let { it / 20f },
                borders = pp.borders(), shading = pp.shading,
            ))
        }
    }

    /** The paragraph mark's at [cp] character format: its CHPX's over its paragraph's style's ([istd]). */
    private fun markFormat(fmt: Formatting?, cp: Int, istd: Int): CharacterFormat {
        fmt ?: return CharacterFormat()
        return fmt.runFormat(fmt.charFmt.getOrElse(cp) { 0 }, istd).also { it.link = null; it.superscript = false; it.subscript = false }
    }

    /** The app's built-in style a .doc style's identity ([MS-DOC] sti) stands for: Normal, heading 1–3, Title. */
    private fun builtinOf(sti: Int): ParagraphStyle? = when (sti) {
        0 -> ParagraphStyle.NORMAL; 1 -> ParagraphStyle.HEADING1; 2 -> ParagraphStyle.HEADING2; 3 -> ParagraphStyle.HEADING3; 62 -> ParagraphStyle.TITLE
        else -> null
    }

    /** A style's id in the document's style sheet: its built-in one, else its name (the first of its comma-separated aliases). */
    private fun styleIdOf(std: DocStyles.Std): String = builtinOf(std.sti)?.styleId ?: std.name.substringBefore(',').trim().ifEmpty { "Style${std.istd}" }

    /** One paragraph sprm into [pp]: alignment, indents, tabs, table membership and row definitions; a huge PAPX's (sprmPHugePapx) from the Data stream. */
    private fun paraSprm(pp: ParaProps, sprm: Int, op: Int, b: ByteArray, opAt: Int, data: ByteArray?) {
        val signed = op.toShort().toInt()
        when (sprm) {
            0x2403, 0x2461 -> pp.align = when (op) { 1 -> TextAlign.CENTER; 2 -> TextAlign.RIGHT; 3 -> TextAlign.JUSTIFY; else -> TextAlign.LEFT }
            0x840F, 0x845E -> pp.left = signed
            0x840E, 0x845D -> pp.right = signed
            0x8411, 0x8460 -> pp.first = signed
            0x4456 -> pp.leftChars = signed
            0x4455 -> pp.rightChars = signed
            0x4457 -> pp.firstChars = signed
            0x460B -> pp.ilfo = op
            0x260A -> pp.ilvl = op
            0x2407 -> pp.pageBreakBefore = op != 0
            0x2447 -> pp.snap = op != 0
            0x2405 -> pp.keepTogether = op != 0
            0x2406 -> pp.keepNext = op != 0
            0x2431 -> pp.widowControl = op != 0
            0x240C -> pp.noLineNumbers = op != 0
            0x442C -> pp.dcs = op
            0x842F -> pp.fromText = op
            0xA413 -> pp.before = op
            0xA414 -> pp.after = op
            0x245B -> if (op != 0) pp.before = 280
            0x245C -> if (op != 0) pp.after = 280
            // LSPD: a multiple of single spacing (240ths), or — not a multiple — a height in twips: exactly when negative, else at least.
            0x6412 -> if (opAt + 4 <= b.size) {
                val dya = (b[opAt].toInt() and 0xFF or ((b[opAt + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
                val mult = (b[opAt + 2].toInt() and 0xFF or ((b[opAt + 3].toInt() and 0xFF) shl 8)) != 0
                if (mult) { pp.line = (kotlin.math.abs(dya) / 240f).coerceAtLeast(0.5f); pp.lineHeight = null }
                else if (dya != 0) { pp.line = null; pp.lineHeight = kotlin.math.abs(dya) / 20f; pp.lineExact = dya < 0 }
            }
            0xC60D -> readTabs(b, opAt, pp.tabs)
            // Borders: the old (BRC80, four bytes) and the new (BRC, eight after a size byte) — top, left, bottom, right, between.
            0x6424, 0x6425, 0x6426, 0x6427, 0x6428 -> paraBorder(pp, PARA_SIDES[sprm - 0x6424], b, opAt, 4)
            0xC64E, 0xC64F, 0xC650, 0xC651, 0xC652 -> paraBorder(pp, PARA_SIDES[sprm - 0xC64E], b, opAt + 1, 8)
            // Shading: SHD80 (Word's 16 colours) or SHD (colour references after a size byte).
            0x442D -> pp.shading = shading(wordColor(op and 0x1F), wordColor((op ushr 5) and 0x1F), (op ushr 10) and 0x3F)
            0xC64D -> if (opAt + 11 <= b.size) pp.shading = shading(colorRef(b, opAt + 1), colorRef(b, opAt + 5), (b[opAt + 9].toInt() and 0xFF) or ((b[opAt + 10].toInt() and 0xFF) shl 8))
            0x2416 -> { pp.inTable = op != 0; if (pp.inTable && pp.itap == 0) pp.itap = 1 }
            0x6649 -> { pp.itap = op; if (op > 0) pp.inTable = true }
            0x2417 -> pp.ttp = op != 0
            0xD608 -> readRowDef(b, opAt, pp)
            0x3404 -> (pp.row ?: RowDef().also { pp.row = it }).header = op != 0
            0x360D -> (pp.row ?: RowDef().also { pp.row = it }).pc = op
            0x940E -> (pp.row ?: RowDef().also { pp.row = it }).dxaAbs = signed
            0x940F -> (pp.row ?: RowDef().also { pp.row = it }).dyaAbs = signed
            0x5400, 0x548A -> (pp.row ?: RowDef().also { pp.row = it }).jc = op
            0x9407 -> (pp.row ?: RowDef().also { pp.row = it }).rowHeight = signed
            0xD609 -> shades80(b, opAt, pp)
            0xD612 -> shades(b, opAt, pp)
            0xD605, 0xD613 -> {
                // Six borders: top, left, bottom, right, inside horizontal and vertical.
                val cb = b[opAt].toInt() and 0xFF
                val each = if (sprm == 0xD605) 4 else 8
                var drawn = false
                val sides = HashMap<String, com.therealityteam.aeropages.model.CellBorder?>()
                listOf("top", "left", "bottom", "right", "insideH", "insideV").forEachIndexed { k, side ->
                    val o = opAt + 1 + k * each
                    if (o + each <= minOf(opAt + 1 + cb, b.size)) {
                        if (brcType(b, o, each) !in setOf(0, 0xFF)) drawn = true
                        sides[side] = brcLine(b, o, each)
                    }
                }
                (pp.row ?: RowDef().also { pp.row = it }).apply { tableBorders = drawn; tableSides = sides }
            }
            // The rest of the PAPX in the Data stream (a table row's definition too big for its page).
            0x6646 -> if (data != null && op >= 0 && op + 2 <= data.size) {
                val cb = le16(data, op)
                decodeSprms(data, op + 2, minOf(op + 2 + cb, data.size)) { s, o, at -> paraSprm(pp, s, o, data, at, null) }
            }
        }
    }

    /** A border's type in a BRC80 ([each] 4: dptLineWidth, brcType…) or a BRC ([each] 8: colour, dptLineWidth, brcType…). */
    private fun brcType(b: ByteArray, o: Int, each: Int): Int = b[o + if (each == 4) 1 else 5].toInt() and 0xFF

    /**
     * A BRC80's or BRC's line ([MS-DOC] 2.9.15 / 2.9.16): its kind (brcType: single, thick, double, dotted, dashed,
     * dot-dash …), width (dptLineWidth, eighths of a point) and colour (a BRC80's ico from Word's 16 colours, a
     * BRC's COLORREF; automatic: black); null for none.
     */
    private fun brcLine(b: ByteArray, o: Int, each: Int): com.therealityteam.aeropages.model.CellBorder? {
        val type = brcType(b, o, each)
        val style = when (type) {
            0, 0xFF -> return null
            3, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 21 -> com.therealityteam.aeropages.model.LineStyle.DOUBLE
            6 -> com.therealityteam.aeropages.model.LineStyle.DOTTED
            7, 22 -> com.therealityteam.aeropages.model.LineStyle.DASHED
            8, 23 -> com.therealityteam.aeropages.model.LineStyle.DASH_DOT
            9 -> com.therealityteam.aeropages.model.LineStyle.DASH_DOT_DOT
            else -> com.therealityteam.aeropages.model.LineStyle.THIN
        }
        val width = (b[o + if (each == 4) 0 else 4].toInt() and 0xFF).let { if (it == 0) 4 else it }.coerceIn(2, 96) / 8f
        val color: Long? = if (each == 4) wordColor(b[o + 2].toInt() and 0xFF)
            else if ((b[o + 3].toInt() and 0xFF) == 0xFF) null
            else 0xFF000000L or ((b[o].toLong() and 0xFF) shl 16) or ((b[o + 1].toLong() and 0xFF) shl 8) or (b[o + 2].toLong() and 0xFF)
        return com.therealityteam.aeropages.model.CellBorder(style, width, color?.takeIf { it != 0xFF000000L })
    }

    private val PARA_SIDES = listOf("top", "left", "bottom", "right", "between")

    /** One side of a paragraph's borders: a BRC80's or BRC's line at [o] and its space (dptSpace, points). */
    private fun paraBorder(pp: ParaProps, side: String, b: ByteArray, o: Int, each: Int) {
        if (o + each > b.size) return
        pp.sides[side] = brcLine(b, o, each) to (b[o + if (each == 4) 3 else 6].toInt() and 0x1F).toFloat()
    }

    /** A COLORREF at [o] (red, green, blue, then fAuto: 0xFF for automatic, null). */
    private fun colorRef(b: ByteArray, o: Int): Long? =
        if ((b[o + 3].toInt() and 0xFF) == 0xFF) null
        else 0xFF000000L or ((b[o].toLong() and 0xFF) shl 16) or ((b[o + 1].toLong() and 0xFF) shl 8) or (b[o + 2].toLong() and 0xFF)

    /** Shading's colour: a clear pattern's background, a solid one's foreground, a percentage's background (else foreground). */
    private fun shading(fore: Long?, back: Long?, ipat: Int): Long? = when (ipat) {
        0 -> back
        1 -> fore ?: 0xFF000000L
        else -> back ?: fore
    }

    /** Word's 16 colours by their ico (0: automatic). */
    private fun wordColor(ico: Int): Long? = when (ico) {
        1 -> 0xFF000000; 2 -> 0xFF0000FF; 3 -> 0xFF00FFFF; 4 -> 0xFF00FF00; 5 -> 0xFFFF00FF; 6 -> 0xFFFF0000; 7 -> 0xFFFFFF00; 8 -> 0xFFFFFFFF
        9 -> 0xFF000080; 10 -> 0xFF008080; 11 -> 0xFF008000; 12 -> 0xFF800080; 13 -> 0xFF800000; 14 -> 0xFF808000; 15 -> 0xFF808080; 16 -> 0xFFC0C0C0
        else -> null
    }

    /**
     * sprmTDefTable's operand at [at]: its size (two bytes, the rest of it plus one), the cells' count,
     * their boundaries (one more than cells, signed twips) and a TC80 per cell — its merges in tcgrf
     * (bits 0–1 horizontal: 1 first, 2 merged on; bits 5–6 vertical: 3 restart, 1 continue) and four
     * BRC80 borders.
     */
    private fun readRowDef(b: ByteArray, at: Int, pp: ParaProps) {
        if (at + 3 > b.size) return
        val end = minOf(at + 2 + le16(b, at) - 1, b.size)
        val n = b[at + 2].toInt() and 0xFF
        var p = at + 3
        if (n == 0 || p + (n + 1) * 2 > end) return
        val def = pp.row ?: RowDef().also { pp.row = it }
        def.bounds = IntArray(n + 1) { le16(b, p + it * 2).toShort().toInt() }
        p += (n + 1) * 2
        def.horz = IntArray(n); def.vert = IntArray(n)
        var nil = true
        val sides = ArrayList<Map<String, com.therealityteam.aeropages.model.CellBorder?>>()
        for (i in 0 until n) {
            val o = p + i * 20
            if (o + 20 > end) { nil = false; break }
            val tcgrf = le16(b, o)
            def.horz[i] = tcgrf and 3
            def.vert[i] = (tcgrf ushr 5) and 3
            // Its four BRC80s (top, left, bottom, right): all zero says nothing (the table's), all ones nil (none).
            val own = HashMap<String, com.therealityteam.aeropages.model.CellBorder?>()
            listOf("top", "left", "bottom", "right").forEachIndexed { k, side ->
                val at = o + 4 + k * 4
                if (brcType(b, at, 4) !in setOf(0, 0xFF)) def.cellBorders = true
                if (le32(b, at) != -1) nil = false
                if (le32(b, at) != 0) own[side] = brcLine(b, at, 4)
            }
            sides.add(own)
        }
        def.cellsNil = nil
        def.cellSides = sides
    }

    /** sprmTDefTableShd80's operand: an SHD80 per cell (icoFore 5 bits, icoBack 5 bits, ipat 6 bits) — the back colour where the pattern is clear. */
    private fun shades80(b: ByteArray, at: Int, pp: ParaProps) {
        val cb = b[at].toInt() and 0xFF
        val def = pp.row ?: RowDef().also { pp.row = it }
        for (i in 0 until cb / 2) {
            val o = at + 1 + i * 2
            if (o + 2 > b.size) break
            val v = le16(b, o)
            val back = (v ushr 5) and 0x1F; val pat = (v ushr 10) and 0x3F
            if (pat == 0 && back != 0) ICO_COLORS.getOrNull(back)?.let { def.shades[i] = it }
            else if (pat == 1) ICO_COLORS.getOrNull(v and 0x1F)?.let { def.shades[i] = it }   // solid: the fore colour
        }
    }

    /** sprmTDefTableShd's operand: an SHD per cell (cvFore, cvBack as COLORREF — red, green, blue, auto — and ipat). */
    private fun shades(b: ByteArray, at: Int, pp: ParaProps) {
        val cb = b[at].toInt() and 0xFF
        val def = pp.row ?: RowDef().also { pp.row = it }
        def.shades.clear()
        for (i in 0 until cb / 10) {
            val o = at + 1 + i * 10
            if (o + 10 > b.size) break
            val pat = le16(b, o + 8)
            val cv = if (pat == 1) o else o + 4   // solid: the fore colour, else the back one
            if (b[cv + 3].toInt() and 0xFF == 0xFF) continue   // automatic: none
            if (pat != 0 && pat != 1) continue
            def.shades[i] = 0xFF000000L or ((b[cv].toLong() and 0xFF) shl 16) or ((b[cv + 1].toLong() and 0xFF) shl 8) or (b[cv + 2].toLong() and 0xFF)
        }
    }

    /**
     * sprmPChgTabsPapx's operand at [at] (its size byte first): the positions it deletes, then the stops it
     * adds — each a position (twips from the margin) and a TBD byte (bits 0–2 the alignment, 3–5 the leader).
     */
    private fun readTabs(b: ByteArray, at: Int, out: MutableList<com.therealityteam.aeropages.model.TabStop>) {
        if (at + 2 > b.size) return
        val end = at + 1 + (b[at].toInt() and 0xFF)
        var p = at + 1
        val dels = b[p].toInt() and 0xFF; p += 1 + dels * 2
        if (p >= end || p >= b.size) return
        val adds = b[p].toInt() and 0xFF; p++
        if (p + adds * 3 > minOf(end, b.size)) return
        for (k in 0 until adds) {
            val pos = le16(b, p + k * 2).toShort().toInt()
            val tbd = b[p + adds * 2 + k].toInt() and 0xFF
            val align = when (tbd and 7) {
                1 -> com.therealityteam.aeropages.model.TabAlign.CENTER
                2 -> com.therealityteam.aeropages.model.TabAlign.RIGHT
                3 -> com.therealityteam.aeropages.model.TabAlign.DECIMAL
                4 -> continue   // a bar: a line, not a stop
                else -> com.therealityteam.aeropages.model.TabAlign.LEFT
            }
            val leader = when ((tbd shr 3) and 7) {
                1 -> com.therealityteam.aeropages.model.TabLeader.DOT
                2 -> com.therealityteam.aeropages.model.TabLeader.HYPHEN
                3, 4 -> com.therealityteam.aeropages.model.TabLeader.UNDERSCORE
                5 -> com.therealityteam.aeropages.model.TabLeader.MIDDLE_DOT
                else -> com.therealityteam.aeropages.model.TabLeader.NONE
            }
            out.removeAll { kotlin.math.abs(it.posPt - pos / 20f) < 0.5f }
            out.add(com.therealityteam.aeropages.model.TabStop(pos / 20f, align, leader))
        }
    }

    /** Visit every 512-byte FKP page a bin table (PlcBte…) points at. */
    private inline fun forEachFkp(wordDoc: ByteArray, table: ByteArray, fc: Int, lcb: Int, block: (ByteArray) -> Unit) {
        if (fc < 0 || lcb < 8 || fc + lcb > table.size) return
        val n = (lcb - 4) / 8
        for (i in 0 until n) {
            val pn = le32(table, fc + (n + 1) * 4 + i * 4) and 0x3FFFFF
            val off = pn * 512
            if (off + 512 > wordDoc.size) continue
            block(wordDoc.copyOfRange(off, off + 512))
        }
    }

    /** Map the file-offset range [fcA, fcB) onto character positions through the piece table. */
    private inline fun forEachCp(pieces: List<Piece>, fcA: Int, fcB: Int, textLen: Int, block: (Int) -> Unit) {
        for (p in pieces) {
            val count = p.cpEnd - p.cpStart
            if (count <= 0) continue
            val bpc = if (p.compressed) 1 else 2
            val bStart = if (p.compressed) p.fc / 2 else p.fc
            val bEnd = bStart + count * bpc
            val a = maxOf(fcA, bStart); val b = minOf(fcB, bEnd)
            if (a >= b) continue
            val cpA = p.cpStart + (a - bStart) / bpc; val cpB = p.cpStart + (b - bStart + bpc - 1) / bpc
            for (cp in cpA until minOf(cpB, textLen)) block(cp)
        }
    }

    /** Walk a grpprl: each sprm's operand size comes from its spra bits ([MS-DOC] 2.2.5.1). */
    private inline fun decodeSprms(b: ByteArray, from: Int, to: Int, block: (sprm: Int, op: Int, opAt: Int) -> Unit) {
        var pos = from
        while (pos + 2 <= to) {
            val sprm = le16(b, pos); pos += 2
            val size = when (sprm ushr 13) {
                0, 1 -> 1
                2, 4, 5 -> 2
                3 -> 4
                7 -> 3
                // sprmTDefTable's size is two bytes: the rest of its operand plus one ([MS-DOC] 2.9.316).
                else -> if (pos < to) (if (sprm == 0xD608) le16(b, pos) + 1 else (b[pos].toInt() and 0xFF) + 1) else return
            }
            if (pos + size > to) return
            val op = when (size) { 1 -> b[pos].toInt() and 0xFF; 2 -> le16(b, pos); 4 -> le32(b, pos); else -> 0 }
            block(sprm, op, pos)
            pos += size
        }
    }

    /** A CHPX's sprms into [f]; a toggle's 0x80 / 0x81 ("as the style", "the opposite of the style") taken against [base]. */
    private fun applyChpx(f: CharacterFormat, b: ByteArray, base: CharacterFormat, fonts: List<String>, authors: List<String> = emptyList()) {
        decodeSprms(b, 0, b.size) { sprm, op, opAt ->
            fun t(styled: Boolean) = when (op) { 0 -> false; 1 -> true; 0x80 -> styled; 0x81 -> !styled; else -> op and 1 == 1 }
            when (sprm) {
                0x0835 -> f.bold = t(base.bold)
                0x0836 -> f.italic = t(base.italic)
                0x0837 -> f.strikethrough = t(base.strikethrough)
                0x083A -> f.smallCaps = t(base.smallCaps)
                0x6A03, 0x0806 -> {}
                else -> applyCharSprm(f, sprm, op, b, opAt, fonts, authors)
            }
        }
    }

    private fun applyCharSprm(f: CharacterFormat, sprm: Int, op: Int, page: ByteArray, opAt: Int, fonts: List<String>, authors: List<String> = emptyList()) {
        fun toggle(v: Int) = v == 1 || v == 129 // 128/129 are "same as / opposite of the style"; Normal is off
        fun author(i: Int) = authors.getOrNull(i)?.takeIf { it.isNotEmpty() }
        when (sprm) {
            0x0835 -> f.bold = toggle(op)
            0x0836 -> f.italic = toggle(op)
            0x0837 -> f.strikethrough = toggle(op)
            0x2A3E -> f.underline = op != 0
            0x4A43 -> f.fontSizePt = op / 2f
            0x2A42 -> ICO_COLORS.getOrNull(op)?.let { f.color = it }
            0x6870 -> if (opAt + 3 <= page.size) { val r = page[opAt].toInt() and 0xFF; val g = page[opAt + 1].toInt() and 0xFF; val bl = page[opAt + 2].toInt() and 0xFF; f.color = 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or bl.toLong() }
            0x4A4F -> fonts.getOrNull(op)?.let { f.fontFamily = it }
            0x2A48 -> { f.superscript = op == 1; f.subscript = op == 2 }   // sprmCIss
            0x083A -> f.smallCaps = toggle(op)                              // sprmCFSmallCaps
            0x8840 -> f.letterSpacingPt = (op.toShort() / 20f).takeIf { it != 0f }   // sprmCDxaSpace, twips
            0x2A0C -> f.highlight = ICO_COLORS.getOrNull(op)                // sprmCHighlight
            // Revision marks: deleted / inserted (a deletion's author and date win over an insertion's), a property revision.
            0x0800 -> f.deleted = toggle(op)                                // sprmCFRMarkDel
            0x0801 -> f.inserted = toggle(op)                               // sprmCFRMarkIns
            0x4804 -> if (f.revAuthor == null) f.revAuthor = author(op)     // sprmCIbstRMark
            0x6805 -> if (f.revDate == null) f.revDate = dttm(op)           // sprmCDttmRMark
            0x4863 -> f.revAuthor = author(op) ?: f.revAuthor               // sprmCIbstRMarkDel
            0x6864 -> f.revDate = dttm(op) ?: f.revDate                     // sprmCDttmRMarkDel
            0xCA57 -> if (opAt + 8 <= page.size && page[opAt + 1].toInt() != 0) {   // sprmCPropRMark90: fPropRMark, ibst, dttm
                f.formatChanged = true
                if (f.revAuthor == null) f.revAuthor = author(le16(page, opAt + 2))
                if (f.revDate == null) f.revDate = dttm(le32(page, opAt + 4))
            }
        }
    }

    /** A DTTM ([MS-DOC] 2.9.68: minutes, hours, day, month, years since 1900) as epoch milliseconds, read as UTC; null for none. */
    internal fun dttm(v: Int): Long? {
        if (v == 0) return null
        val min = v and 0x3F; val hr = (v ushr 6) and 0x1F; val dom = (v ushr 11) and 0x1F; val mon = (v ushr 16) and 0xF; val yr = 1900 + ((v ushr 20) and 0x1FF)
        if (mon !in 1..12 || dom !in 1..31 || hr > 23 || min > 59) return null
        return runCatching { java.time.LocalDateTime.of(yr, mon, dom, hr, min).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /** Word's 16-entry colour index (sprmCIco); 0 = automatic. */
    private val ICO_COLORS = listOf<Long?>(null, 0xFF000000, 0xFF0000FF, 0xFF00FFFF, 0xFF00FF00, 0xFFFF00FF, 0xFFFF0000, 0xFFFFFF00, 0xFFFFFFFF,
        0xFF000080, 0xFF008080, 0xFF008000, 0xFF800080, 0xFF800000, 0xFF808000, 0xFF808080, 0xFFC0C0C0)

    /** Font names from the SttbfFfn string table (each FFN carries its name as UTF-16 at offset 40). */
    private fun parseFontNames(table: ByteArray, fc: Int, lcb: Int): List<String> {
        if (fc < 0 || lcb < 6 || fc + lcb > table.size) return emptyList()
        val out = ArrayList<String>()
        val extended = le16(table, fc) == 0xFFFF
        val count = le16(table, fc + if (extended) 2 else 0)
        var pos = fc + (if (extended) 6 else 4)
        val end = fc + lcb
        repeat(count) {
            if (pos >= end) return out
            val cb = table[pos].toInt() and 0xFF
            val sb = StringBuilder()
            var k = pos + 40
            while (k + 1 < end && k + 1 <= pos + cb) { val ch = le16(table, k); if (ch == 0) break; sb.append(ch.toChar()); k += 2 }
            out.add(sb.toString())
            pos += cb + 1
        }
        return out
    }

    /** A field being read: its code until its separator (0x14), its result after; a HYPERLINK's target. */
    private class Field { var inCode = true; val code = StringBuilder(); var link: String? = null; var token: String? = null
        /** A live field (SEQ, REF, PAGEREF): its result's runs are its own. */
        var live: com.therealityteam.aeropages.model.DocField? = null }

    /**
     * Where a `HYPERLINK` field's code goes: its address (`HYPERLINK "https://…"`), a place in it
     * (`\l "name"`: `address#name`), or — with no address — a place in this document (`#name`, a
     * bookmark); null for any other field. `\o` (a tooltip) and `\t` (a target frame) take an argument.
     */
    internal fun hyperlinkOf(code: CharSequence): String? {
        // Control characters are spaces (a .doc code ends with 0x01, standing for the link's data in the Data stream).
        val m = HYPERLINK.find(code.map { if (it < ' ') ' ' else it }.joinToString("")) ?: return null
        var url: String? = null
        var place: String? = null
        val args = fieldArgs(m.groupValues[1])
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a.equals("\\l", ignoreCase = true) -> { place = args.getOrNull(i + 1); i += 2 }
                a.equals("\\o", ignoreCase = true) || a.equals("\\t", ignoreCase = true) -> i += 2
                a.startsWith("\\") -> i++
                else -> { if (url == null) url = a; i++ }
            }
        }
        val address = url?.takeIf { it.isNotBlank() }
        val at = place?.takeIf { it.isNotBlank() }
        return when {
            address != null && at != null -> "$address#$at"
            address != null -> address
            at != null -> "#$at"
            else -> null
        }
    }

    private val HYPERLINK = Regex("""^\s*HYPERLINK\b(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** A field code's arguments: quoted ones without their quotes (a doubled backslash in them one), and bare words. */
    private fun fieldArgs(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '"' -> {
                    val e = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                    out.add(s.substring(i + 1, e).replace("\\\\", "\\"))
                    i = e + 1
                }
                else -> {
                    var e = i
                    while (e < s.length && !s[e].isWhitespace()) e++
                    out.add(s.substring(i, e))
                    i = e
                }
            }
        }
        return out
    }

    /** What a `MACROBUTTON name text` field shows: its text (Word's templates' placeholders); null for any other field. */
    internal fun macroButtonText(code: CharSequence): String? =
        MACROBUTTON.find(code)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private val MACROBUTTON = Regex("""^\s*MACROBUTTON\s+\S+\s+(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * The paragraphs of the characters [from] until [to] (a story, or part of one), each with the CP it
     * starts at. In .doc, 0x0D ends a paragraph, 0x0B is a manual line break, cell and row marks end a
     * paragraph too, and other control codes are dropped. A field (0x13 code 0x14 result 0x15, nesting)
     * shows its result — a date, a link's text, a table of contents — and not its code (`TIME \@ "MMMM D"`,
     * `HYPERLINK "…"`); a hyperlink's result links to its target; a MACROBUTTON shows its code's text; in
     * a header ([pageFields]) the page number and count become their tokens (HeaderFooters). Runs follow
     * the decoded character formats; alignment, indents, tabs and the built-in heading styles come from
     * the PAPX. [markers]: the note references, their text ("[1]", "[i]") put in superscript at their CPs.
     * A picture character (0x01 with a picture's location) gives its paragraph the picture [picture]
     * reads from that location (none: pictures are left out). [bookmarks]: the bookmark ends at their
     * CPs (readBookmarks), put into the paragraphs where the text they stand before lands.
     */
    private fun paragraphs(text: String, fmt: Formatting?, from: Int, to: Int, markers: Map<Int, String> = emptyMap(), pageFields: Boolean = false,
                           picture: ((Int) -> com.therealityteam.aeropages.model.ImageBlock?)? = null,
                           bookmarks: Map<Int, List<Pair<String, Boolean>>> = emptyMap(), sectionEnds: Set<Int> = emptySet()): List<Para> {
        val out = ArrayList<Para>()
        val runs = ArrayList<TextRun>()
        // Each run's character format (its CHPX index; -1: none) and whether it is a note marker, to be set over its paragraph's style.
        val runChars = ArrayList<Pair<Int, Boolean>>()
        val pictures = ArrayList<com.therealityteam.aeropages.model.ImageBlock>()
        var current = StringBuilder()
        var currentId = -1
        var currentIstd = 0
        var currentLink: String? = null
        var currentField: com.therealityteam.aeropages.model.DocField? = null
        val marks = ArrayList<com.therealityteam.aeropages.model.BookmarkMark>()
        fun mark(cp: Int) = bookmarks[cp]?.forEach { (name, end) ->
            marks.add(com.therealityteam.aeropages.model.BookmarkMark(name, runs.sumOf { it.text.length } + current.length, end))
        }
        var start = from
        var lastCp = from
        val fields = ArrayList<Field>()
        // A page break (0x0C not ending a section) met with nothing before it in its paragraph: the break stands for that.
        var pendingBreak = false
        fun pageBreak(cp: Int) { out.add(Para(cp, Paragraph.of(PAGE_BREAK, pFormat = ParagraphFormat(spaceBeforePt = 0f, spaceAfterPt = 0f)), null, false)) }
        fun empty() = runs.isEmpty() && current.isEmpty() && pictures.isEmpty()
        fun endRun() {
            if (current.isNotEmpty()) {
                runs.add(TextRun(current.toString(), (fmt?.runFormat(currentId, currentIstd) ?: CharacterFormat()).also { f -> currentLink?.let { f.link = it }; f.field = currentField }))
                runChars.add(currentId to false)
            }
            current = StringBuilder()
        }
        fun emit(t: String, cp: Int) {
            val id = fmt?.charFmt?.getOrElse(cp) { 0 } ?: 0
            val istd = fmt?.paras?.getOrNull(fmt.paraFmt.getOrElse(cp) { 0 })?.istd ?: 0
            if (id != currentId || istd != currentIstd) { endRun(); currentId = id; currentIstd = istd }
            current.append(t)
        }
        fun flush(cp: Int) {
            endRun()
            val props = fmt?.paras?.getOrNull(fmt.paraFmt.getOrElse(cp) { 0 })
            // A paragraph's style is its mark's: its runs' formats go over that style's character properties.
            if (fmt != null && fmt.styles.isNotEmpty()) {
                val istd = props?.istd ?: 0
                for (i in runs.indices) {
                    val (id, marker) = runChars.getOrNull(i) ?: continue
                    if (id < 0) continue
                    val f = fmt.runFormat(id, istd).also { it.link = runs[i].format.link; it.field = runs[i].format.field; if (marker) { it.superscript = true; it.subscript = false } }
                    runs[i] = TextRun(runs[i].text, f)
                }
            }
            // An empty paragraph is as tall as its mark: the mark's own character properties over its style's (a cover's
            // spacing is often empty paragraphs whose marks are 16 or 26 pt).
            if (runs.isEmpty()) runs.add(TextRun("", markFormat(fmt, cp, props?.istd ?: 0).also { it.deleted = false; it.inserted = false; it.formatChanged = false; it.revAuthor = null; it.revDate = null }))
            val pf = ParagraphFormat()
            // The paragraph mark's own revision (its CHPX's revision mark): inserted or deleted with the paragraph.
            if (fmt != null && text.getOrNull(cp)?.code == 0x0D) markFormat(fmt, cp, props?.istd ?: 0).let { m ->
                if (m.deleted) pf.revisionMark = com.therealityteam.aeropages.model.RevisionMark(false, m.revAuthor, m.revDate)
                else if (m.inserted) pf.revisionMark = com.therealityteam.aeropages.model.RevisionMark(true, m.revAuthor, m.revDate)
            }
            props?.align?.let { pf.align = it }
            // Indents; one in characters is so many times the paragraph's text size.
            if (props != null) {
                val em = runs.firstOrNull { it.text.isNotBlank() }?.format?.fontSizePt ?: pf.style.sizePt
                fun pt(twips: Int?, chars: Int): Float? = if (chars != 0) chars / 100f * em else twips?.let { it / 20f }
                pt(props.left, props.leftChars)?.let { pf.indentLeftPt = it }
                pt(props.right, props.rightChars)?.let { pf.indentRightPt = it }
                pt(props.first, props.firstChars)?.let { pf.firstLineIndentPt = it }
                if (props.tabs.isNotEmpty()) pf.tabStops = props.tabs.sortedBy { it.posPt }
                // Its list: its own, else its style's; the level's indents where it sets none.
                val styled = DocLists.ofStyle(fmt.styleLists, props.istd)
                val ilfo = props.ilfo ?: styled?.first
                val lv = (props.ilvl ?: styled?.second ?: 0).coerceIn(0, 8)
                fmt.lists[ilfo]?.takeIf { ilfo != null && ilfo in 1..0x7FE && !it.def.level(lv).blank }?.let { inst ->
                    pf.listId = inst.listId; pf.listLevel = lv
                    pf.listKind = if (inst.def.level(lv).format == com.therealityteam.aeropages.model.NumFormat.BULLET) com.therealityteam.aeropages.model.ListKind.BULLET else com.therealityteam.aeropages.model.ListKind.NUMBER
                    val (left, first) = inst.indents.getOrNull(lv) ?: (null to null)
                    if (props.left == null && props.leftChars == 0) left?.let { pf.indentLeftPt = it / 20f }
                    if (props.first == null && props.firstChars == 0) first?.let { pf.firstLineIndentPt = it / 20f }
                }
            }
            // Word 97's paragraphs: no space around them and single spacing unless their style or they set some.
            pf.spaceBeforePt = (props?.before ?: 0) / 20f
            pf.spaceAfterPt = (props?.after ?: 0) / 20f
            pf.lineSpacing = props?.line ?: 1f
            props?.lineHeight?.let { pf.lineHeightPt = it; pf.lineExact = props.lineExact }
            if (props != null) {
                pf.pageBreakBefore = props.pageBreakBefore; pf.keepTogether = props.keepTogether; pf.snapToGrid = props.snap
                props.keepNext?.let { pf.keepWithNext = it }; props.widowControl?.let { pf.widowControl = it }
                pf.borders = props.borders(); pf.shading = props.shading; pf.suppressLineNumbers = props.noLineNumbers
                pf.dropCap = props.dropCap()
            }
            val std = fmt?.styles?.get(props?.istd ?: 0)
            if (std != null) {
                // Its style by its built-in identity (heading 1–3, title), else by name; its look is in the runs already.
                val builtin = builtinOf(std.sti)
                pf.style = builtin ?: ParagraphStyle.NORMAL
                pf.rawStyleId = builtin?.styleId ?: styleIdOf(std)
            } else {
                // No style sheet: the old reading of the first heading styles.
                val style = when (props?.istd) { 1 -> ParagraphStyle.HEADING1; 2 -> ParagraphStyle.HEADING2; 3 -> ParagraphStyle.HEADING3; else -> null }
                if (style != null) {
                    pf.style = style; pf.rawStyleId = style.styleId
                    val def = StyleSheet.builtin(style)
                    runs.forEach { r -> if (r.format.fontSizePt == null) r.format.fontSizePt = def.sizePt; r.format.bold = true }
                }
            }
            out.add(Para(start, Paragraph(runs.toMutableList(), pf, bookmarks = marks.toMutableList()), props, text.getOrNull(cp)?.code == 0x07, pictures.toList()))
            runs.clear(); runChars.clear(); pictures.clear(); marks.clear(); currentId = -1
            start = cp + 1
        }
        for (cp in from until minOf(to, text.length)) {
            val c = text[cp]
            lastCp = cp
            if (bookmarks.isNotEmpty()) mark(cp)
            when (c.code) {
                0x13 -> { fields.add(Field()); continue }
                0x14 -> {
                    fields.lastOrNull()?.let { f ->
                        f.inCode = false; f.link = hyperlinkOf(f.code)
                        // A caption's number, a cross-reference: live (a cross-reference's \h links to its bookmark).
                        f.live = com.therealityteam.aeropages.model.Fields.of(f.code)?.also { live -> if (live.kind != "SEQ" && live.has("\\h")) f.link = "#" + live.target }
                        // A page number or count in a header: its token, standing for the result that follows.
                        if (pageFields && fields.none { it.token != null }) com.therealityteam.aeropages.model.HeaderFooters.fieldToken(f.code)?.let { t ->
                            f.token = t; emit(t, cp)
                        }
                        // A date or time that updates (DATE, TIME): today's, not the day the file was saved.
                        if (f.token == null && fields.none { it.token != null }) com.therealityteam.aeropages.model.FieldDates.shown(f.code.toString())?.let { t ->
                            f.token = t; emit(t, cp)
                        }
                    }
                    continue
                }
                0x15 -> {
                    if (fields.isNotEmpty()) {
                        val f = fields.removeAt(fields.lastIndex)
                        if (f.inCode && fields.none { it.inCode || it.token != null }) {
                            // A MACROBUTTON (a template's "[Click here and type…]") shows what its code says; it has
                            // no result; a header's page number without one still shows.
                            (macroButtonText(f.code) ?: com.therealityteam.aeropages.model.HeaderFooters.fieldToken(f.code)?.takeIf { pageFields })?.let { emit(it, cp) }
                        }
                    }
                    continue
                }
            }
            // Inside a field's code — its own or a field nested in it — nothing shows; nor a page field's result.
            val coding = fields.lastOrNull { it.inCode }
            if (coding != null) { if (fields.last() === coding) coding.code.append(c); continue }
            if (fields.any { it.token != null }) continue
            val marker = markers[cp]
            if (marker != null) {
                endRun()
                val f = (fmt?.runFormat(fmt.charFmt.getOrElse(cp) { 0 }, fmt.paras.getOrNull(fmt.paraFmt.getOrElse(cp) { 0 })?.istd ?: 0) ?: CharacterFormat()).also { it.superscript = true; it.subscript = false }
                runs.add(TextRun(marker, f)); runChars.add(fmt?.charFmt?.getOrElse(cp) { 0 }.let { (it ?: -1) to true }); currentId = -1
                continue
            }
            val id = fmt?.charFmt?.getOrElse(cp) { 0 } ?: 0
            val link = fields.lastOrNull { it.link != null }?.link
            val live = fields.lastOrNull { !it.inCode && it.live != null }?.live
            // Text after a page break at a paragraph's start: the break, then the paragraph on the next page.
            if (pendingBreak && c.code !in setOf(0x0D, 0x07, 0x0C)) { pageBreak(start); pendingBreak = false; start = cp }
            when (c.code) {
                // A paragraph holding only a page break is the break (Word keeps its mark with the break).
                0x0D -> if (pendingBreak && empty()) { pendingBreak = false; pageBreak(start); start = cp + 1 } else flush(cp)
                0x07 -> flush(cp)                     // cell/row mark - treat as paragraph break
                // 0x0C ends a section (its own page already) or breaks the page: the text before it, then the break.
                0x0C -> when {
                    cp in sectionEnds -> flush(cp)
                    empty() -> { pendingBreak = true; start = cp + 1 }
                    else -> { flush(cp); pageBreak(cp) }
                }
                0x01 -> fmt?.pics?.get(id)?.let { at -> picture?.invoke(at)?.let { pictures.add(it) } }
                0x1F -> { /* optional hyphen - drop */ }
                in 0x00..0x08, 0x0E, 0x0F, in 0x10..0x1D -> { /* drop other controls */ }
                else -> {
                    if (id != currentId || link != currentLink || live !== currentField) { endRun(); currentId = id; currentLink = link; currentField = live }
                    when (c.code) {
                        0x0B -> current.append('\n')        // manual line break
                        0x09 -> current.append('\t')
                        0x1E -> current.append('-')         // non-breaking hyphen
                        else -> current.append(c)
                    }
                }
            }
        }
        if (current.isNotEmpty() || runs.isNotEmpty() || pictures.isNotEmpty()) flush(lastCp)
        // Bookmarks ending after the last character: at the last paragraph's end.
        if (bookmarks.isNotEmpty()) {
            for (cp in minOf(to, text.length)..to) mark(cp)
            out.lastOrNull()?.paragraph?.let { p -> marks.forEach { p.bookmarks.add(it.copy(offset = p.plainText().length)) } }
        }
        return out
    }

    /**
     * A paragraph of a story: the CP it starts at, its PAPX's properties, whether a cell mark (0x07) ends
     * it, its inline pictures; and the text boxes and pictures anchored in it ([floating], each with
     * where it stands on the page: x, y in twips).
     */
    private class Para(val start: Int, val paragraph: Paragraph, val props: ParaProps?, val cellMark: Boolean,
                       val pictures: List<com.therealityteam.aeropages.model.ImageBlock> = emptyList()) {
        val floating = ArrayList<Triple<Int, Int, com.therealityteam.aeropages.model.Block>>()

        /** Whether it holds pictures and nothing else to show: its pictures stand for it. */
        val onlyPictures get() = pictures.isNotEmpty() && paragraph.plainText().isEmpty() && paragraph.format.sectionBreak == null
    }

    /**
     * The main text's blocks: its paragraphs, and its tables — runs of paragraphs in a table, each cell
     * ending at a cell mark, each row at the paragraph that ends it (its TTP, carrying the row's
     * definition: cell widths, merges, shading, header row, borders). A nested table's paragraphs stay
     * in the outer table's cell they are in. A paragraph's inline pictures follow it (a cell's go under
     * its text), standing for it when it holds nothing else; what is anchored in it follows them, in the
     * order it stands on the page — top to bottom, left to right; what is anchored in a table, after
     * the table.
     */
    private fun blocksOf(paras: List<Para>): List<com.therealityteam.aeropages.model.Block> {
        val out = ArrayList<com.therealityteam.aeropages.model.Block>()
        val rows = ArrayList<Pair<MutableList<Cell>, RowDef?>>()
        var cells = ArrayList<Cell>()
        var cell = Cell()
        val anchored = ArrayList<Triple<Int, Int, com.therealityteam.aeropages.model.Block>>()
        fun place(floating: List<Triple<Int, Int, com.therealityteam.aeropages.model.Block>>) =
            out.addAll(floating.sortedWith(compareBy({ it.second }, { it.first })).map { it.third })
        fun endRow(def: RowDef?) {
            if (!cell.isEmpty()) { cells.add(cell); cell = Cell() }
            if (cells.isNotEmpty()) rows.add(cells to def)
            cells = ArrayList()
        }
        fun endTable() {
            endRow(null)
            if (rows.isNotEmpty()) out.add(com.therealityteam.aeropages.model.TableBlock(tableOf(rows)))
            rows.clear()
            place(anchored); anchored.clear()
        }
        // A drop cap's frame paragraph, held until the paragraph whose letters it holds comes (see DropCaps.merge).
        var frame: Para? = null
        fun flushFrame() {
            val f = frame ?: return
            frame = null
            f.paragraph.format.dropCap = null
            out.add(com.therealityteam.aeropages.model.ParagraphBlock(f.paragraph))
            out.addAll(f.pictures); place(f.floating)
        }
        for (p in paras) {
            val pp = p.props
            if (pp == null || !pp.inTable) {
                endTable()
                val plain = p.pictures.isEmpty() && p.floating.isEmpty() && p.paragraph.format.sectionBreak == null
                var merged = false
                frame?.let { f ->
                    if (plain && com.therealityteam.aeropages.model.DropCaps.takesLetters(p.paragraph)) {
                        frame = null
                        com.therealityteam.aeropages.model.DropCaps.merge(f.paragraph, p.paragraph, f.paragraph.format.dropCap!!)
                        merged = true
                    } else flushFrame()
                }
                if (!merged && p.paragraph.format.dropCap != null) {
                    if (plain && p.paragraph.plainText().isNotBlank() && p.paragraph.plainText().length <= 32) { frame = p; continue }
                    p.paragraph.format.dropCap = null
                }
                if (!p.onlyPictures) out.add(com.therealityteam.aeropages.model.ParagraphBlock(p.paragraph))
                out.addAll(p.pictures)
                place(p.floating)
                continue
            }
            flushFrame()
            anchored.addAll(p.floating)
            if (pp.ttp) { endRow(pp.row); continue }
            if (!p.onlyPictures) cell.paragraphs.add(p.paragraph)
            cell.images.addAll(p.pictures)
            if (p.cellMark && pp.itap <= 1) { cells.add(cell); cell = Cell() }
        }
        flushFrame()
        endTable()
        return out
    }

    /** A table cell being read: its paragraphs and the pictures under them. */
    private class Cell {
        val paragraphs = ArrayList<Paragraph>()
        val images = ArrayList<com.therealityteam.aeropages.model.ImageBlock>()
        fun isEmpty() = paragraphs.isEmpty() && images.isEmpty()
    }

    /**
     * A table from its rows' cells and definitions: the grid every row's cell boundaries make together
     * (each cell spanning the grid columns between its own), cells merged across into the first,
     * vertical merges as a span on the top cell over the cells it covers, shading, header rows; drawn
     * with borders unless no row's table or cells have any.
     */
    private fun tableOf(rows: List<Pair<MutableList<Cell>, RowDef?>>): com.therealityteam.aeropages.model.Table {
        val grid = java.util.TreeSet<Int>()
        rows.forEach { (cells, def) -> def?.bounds?.takeIf { it.size == cells.size + 1 }?.forEach { grid.add(it) } }
        val edges = grid.toList()
        val table = com.therealityteam.aeropages.model.Table()
        if (edges.size >= 2) for (i in 0 until edges.size - 1) table.columnWidths.add((edges[i + 1] - edges[i]).coerceAtLeast(1).toFloat())
        val starts = ArrayList<IntArray>()   // each row's cells' first grid column
        val own = java.util.IdentityHashMap<com.therealityteam.aeropages.model.TableCell, Map<String, com.therealityteam.aeropages.model.CellBorder?>>()
        for ((cells, def) in rows) {
            val bounds = def?.bounds?.takeIf { it.size == cells.size + 1 }
            val row = com.therealityteam.aeropages.model.TableRow(header = def?.header == true)
            def?.rowHeight?.takeIf { it != 0 }?.let { h -> row.heightPt = kotlin.math.abs(h) / 20f; row.exactHeight = h < 0 }
            val first = ArrayList<Int>()
            cells.forEachIndexed { i, read ->
                val paras = read.paragraphs
                val span = bounds?.let { b -> (edges.indexOf(b[i + 1]) - edges.indexOf(b[i])).coerceAtLeast(1) } ?: 1
                val col = bounds?.let { b -> edges.indexOf(b[i]).coerceAtLeast(0) } ?: i
                val prev = row.cells.lastOrNull()
                if (def != null && def.horz.getOrElse(i) { 0 } == 2 && prev != null) {
                    // Merged on from the cell before: one cell over both, its text and pictures added on.
                    prev.gridSpan += span
                    if (paras.any { it.plainText().isNotBlank() }) prev.content.addAll(paras)
                    prev.images.addAll(read.images)
                    return@forEachIndexed
                }
                val c = com.therealityteam.aeropages.model.TableCell(paras.ifEmpty { listOf(Paragraph.of("")) }.toMutableList(), def?.shades?.get(i), gridSpan = span,
                    images = read.images.toMutableList())
                def?.cellSides?.getOrNull(i)?.let { own[c] = it }
                when (def?.vert?.getOrElse(i) { 0 }) { 3 -> c.rowSpan = 0; 1 -> c.vMerged = true }
                row.cells.add(c); first.add(col)
            }
            table.rows.add(row); starts.add(first.toIntArray())
        }
        // A vertical merge's top cell spans the rows below whose cell at its column continues it.
        table.rows.forEachIndexed { ri, row ->
            row.cells.forEachIndexed { ci, c ->
                if (c.rowSpan != 0) return@forEachIndexed
                val col = starts[ri][ci]
                var span = 1
                for (r2 in ri + 1 until table.rows.size) {
                    val k = starts[r2].indexOf(col)
                    if (k >= 0 && table.rows[r2].cells[k].vMerged) span++ else break
                }
                c.rowSpan = span
            }
        }
        val defs = rows.mapNotNull { it.second }
        // Borders: each cell's own (its TC80), else the table's (its first row's sprmTTableBorders), Word's way.
        if (defs.isNotEmpty()) com.therealityteam.aeropages.format.docx.WordBorders.resolve(table, defs.firstNotNullOfOrNull { it.tableSides } ?: emptyMap()) { ri, ci ->
            own[table.rows[ri].cells[ci]]
        }
        // In the text: as wide as its cells' span, where its first row's definition aligns it or its first boundary indents it.
        val firstDef = defs.firstOrNull()
        if (firstDef != null && !firstDef.positioned && edges.size >= 2) {
            table.widthPt = (edges.last() - edges.first()) / 20f
            table.align = when (firstDef.jc) { 1 -> TextAlign.CENTER; 2 -> TextAlign.RIGHT; else -> TextAlign.LEFT }
            table.indentPt = edges.first() / 20f
        }
        // A positioned table: where its first row's definition puts it, as wide as its cells' span.
        defs.firstOrNull()?.takeIf { it.positioned }?.let { def ->
            // Its left border: the position plus its first cell boundary (Word's -108, the cell margin the text is set in by).
            table.floating = tablePosition(def).also { if (it.hAlign == null && edges.isNotEmpty()) it.x += edges.first() / 20f }
            if (edges.size >= 2) table.widthPt = (edges.last() - edges.first()) / 20f
        }
        return table
    }

    /**
     * A positioned table's place ([MS-DOC] sprmTPc, sprmTDxaAbs, sprmTDyaAbs): down from the margins,
     * the page or its paragraph (pcVert), across from its column, the margins or the page (pcHorz), at
     * an offset in twips — or, the XAS / YAS special values, aligned: centred, at the right (-4, -8;
     * inside -12, outside -16), at the top, middle or bottom (-4, -8, -12; inside -16, outside -20).
     */
    private fun tablePosition(def: RowDef): com.therealityteam.aeropages.model.FloatPosition {
        val pc = def.pc ?: 0x20   // unsaid: from its paragraph, across from its column
        val vFrom = when ((pc shr 4) and 3) {
            0 -> com.therealityteam.aeropages.model.FloatFrame.MARGIN
            1 -> com.therealityteam.aeropages.model.FloatFrame.PAGE
            else -> com.therealityteam.aeropages.model.FloatFrame.PARAGRAPH
        }
        val hFrom = when ((pc shr 6) and 3) {
            1 -> com.therealityteam.aeropages.model.FloatFrame.MARGIN
            2 -> com.therealityteam.aeropages.model.FloatFrame.PAGE
            else -> com.therealityteam.aeropages.model.FloatFrame.COLUMN
        }
        val xas = def.dxaAbs ?: 0; val yas = def.dyaAbs ?: 0
        val hAlign = when (xas) {
            -4 -> com.therealityteam.aeropages.model.FloatAlign.CENTER
            -8, -16 -> com.therealityteam.aeropages.model.FloatAlign.END
            -12 -> com.therealityteam.aeropages.model.FloatAlign.START
            else -> null
        }
        val vAlign = when (yas) {
            -4, -16 -> com.therealityteam.aeropages.model.FloatAlign.START
            -8 -> com.therealityteam.aeropages.model.FloatAlign.CENTER
            -12, -20 -> com.therealityteam.aeropages.model.FloatAlign.END
            else -> null
        }
        return com.therealityteam.aeropages.model.FloatPosition(if (hAlign == null) xas / 20f else 0f, hFrom, hAlign,
            if (vAlign == null) yas / 20f else 0f, vFrom, vAlign, com.therealityteam.aeropages.model.FloatWrap.SQUARE)
    }

    /** The note references in the main text (their markers by CP) and the footnotes' and endnotes' text. */
    private class Notes(val markers: Map<Int, String>, val footnotes: List<String>, val endnotes: List<String>)

    /**
     * Footnotes and endnotes: each reference's CP in the main text (PlcffndRef / PlcfendRef) gets its
     * marker — "[n]", endnotes "[i]", as the docx reader has them — and each note's text is its range of
     * its story (PlcffndTxt / PlcfendTxt), its own mark left out.
     */
    private fun readNotes(table: ByteArray, fib: Fib, text: String, fmt: Formatting?, s: Stories): Notes {
        val markers = HashMap<Int, String>()
        fun read(refFc: Int, refLcb: Int, txtFc: Int, txtLcb: Int, story: Int, marker: (Int) -> String): List<String> {
            val refs = plc(table, refFc, refLcb, 2) ?: return emptyList()
            val txts = plc(table, txtFc, txtLcb, 0) ?: return emptyList()
            val out = ArrayList<String>()
            for (k in 0 until refs.n) {
                if (k + 1 >= txts.cps.size) break
                markers[refs.cps[k]] = marker(k + 1)
                out.add(paragraphs(text, fmt, story + txts.cps[k], story + txts.cps[k + 1]).joinToString("\n") { it.paragraph.plainText() }.trim())
            }
            return out
        }
        val footnotes = read(fib.fcPlcffndRef, fib.lcbPlcffndRef, fib.fcPlcffndTxt, fib.lcbPlcffndTxt, s.ftn) { "[$it]" }
        val endnotes = read(fib.fcPlcfendRef, fib.lcbPlcfendRef, fib.fcPlcfendTxt, fib.lcbPlcfendTxt, s.edn) { "[${com.therealityteam.aeropages.model.Roman.of(it)}]" }
        return Notes(markers, footnotes, endnotes)
    }

    /**
     * The sections (PlcfSed): each one's page from its SEPX — size, margins, header and footer distances,
     * columns — on the paragraph that ends it (the last one's the document's), and whether the first
     * section's first page has its own header.
     */
    private fun readSections(wordDoc: ByteArray, table: ByteArray, fib: Fib, doc: Document, main: List<Para>) {
        val sed = plc(table, fib.fcPlcfSed, fib.lcbPlcfSed, 12) ?: return
        val pages = (0 until sed.n).map { i -> sep(wordDoc, le32(table, sed.dataAt + i * 12 + 2)) }
        doc.pageBorders = pages.first().third
        for (i in 0 until sed.n - 1) {
            val end = sed.cps[i + 1] - 1
            main.lastOrNull { it.start <= end }?.paragraph?.format?.sectionBreak = pages[i].first
        }
        doc.page.assignFrom(pages.last().first)
        doc.differentFirstPage = pages.first().second
    }

    /**
     * A section's page from its SEPX at [fc] (Word's defaults where it says nothing), its title page flag, and its page
     * borders (sprmSBrcTop80…/sprmSBrcTop… with sprmSPgbProp: which pages, from the page's edge or the text).
     */
    private fun sep(wordDoc: ByteArray, fc: Int): Triple<com.therealityteam.aeropages.model.PageSetup, Boolean, com.therealityteam.aeropages.model.PageBorders?> {
        val pg = com.therealityteam.aeropages.model.PageSetup(widthPt = 612f, heightPt = 792f, marginTopPt = 72f, marginBottomPt = 72f, marginLeftPt = 90f, marginRightPt = 90f)
        var titlePage = false
        var restart = false; var start = 1
        var clm = 0; var pitch = 0
        var lnnMod = 0; var lnc = 0; var lnnMin = 0; var dxaLnn = 0
        val lines = HashMap<String, com.therealityteam.aeropages.model.CellBorder?>()
        val spaces = HashMap<String, Float>()
        var shadow = false; var pgb = 0
        // A border's line, and its space (dptSpace, points) and shadow (fShadow) from the byte that holds both.
        fun border(side: String, b: ByteArray, o: Int, each: Int) {
            if (o + each > b.size) return
            lines[side] = brcLine(b, o, each)
            val bits = b[o + if (each == 4) 3 else 6].toInt() and 0xFF
            spaces[side] = (bits and 0x1F).toFloat()
            if (bits and 0x20 != 0) shadow = true
        }
        if (fc < 0 || fc + 2 > wordDoc.size) return Triple(pg, false, null)
        val end = minOf(fc + 2 + le16(wordDoc, fc), wordDoc.size)
        decodeSprms(wordDoc, fc + 2, end) { sprm, op, opAt ->
            when (sprm) {
                0xB01F -> if (op > 0) pg.widthPt = op / 20f
                0xB020 -> if (op > 0) pg.heightPt = op / 20f
                0xB021 -> pg.marginLeftPt = op / 20f
                0xB022 -> pg.marginRightPt = op / 20f
                0x9023 -> pg.marginTopPt = kotlin.math.abs(op.toShort().toInt()) / 20f
                0x9024 -> pg.marginBottomPt = kotlin.math.abs(op.toShort().toInt()) / 20f
                0xB017 -> pg.headerPt = op / 20f
                0xB018 -> pg.footerPt = op / 20f
                0x500B -> pg.columns = (op + 1).coerceIn(1, com.therealityteam.aeropages.model.PageMetrics.MAX_COLUMNS)
                // sprmSDxaColumns (the space between columns, twips) and sprmSLBetween (a line between them).
                0x900C -> pg.columnGapPt = op / 20f
                0x3019 -> pg.columnLine = op != 0
                // Line numbers: sprmSNLnnMod (every n-th shown; 0: none), sprmSLnc (restart: each page, each section, never),
                // sprmSLnnMin (the value before the first), sprmSDxaLnn (distance from the text, twips; 0: Auto).
                0x5015 -> lnnMod = op
                0x3013 -> lnc = op
                0x501B -> lnnMin = op
                0x9016 -> dxaLnn = op.toShort().toInt()
                0x300A -> titlePage = op != 0
                // Page numbers: their format (an MSONFC), whether the section starts them again, and from what.
                0x300E -> pg.pageNumberFormat = DocLists.formatOf(op).takeIf { it.numbered } ?: com.therealityteam.aeropages.model.NumFormat.DECIMAL
                0x3011 -> restart = op != 0
                0x501C, 0x7044 -> start = op
                // The document grid: its kind (sprmSClm: none, lines and characters, lines, characters snapped) and line pitch.
                0x5032 -> clm = op
                0x9031 -> pitch = op.toShort().toInt()
                // Page borders, the old (BRC80, four bytes) and the new (BRC, eight after a size byte), and their options.
                0x702B -> border("top", wordDoc, opAt, 4)
                0x702C -> border("left", wordDoc, opAt, 4)
                0x702D -> border("bottom", wordDoc, opAt, 4)
                0x702E -> border("right", wordDoc, opAt, 4)
                0xD234 -> border("top", wordDoc, opAt + 1, 8)
                0xD235 -> border("left", wordDoc, opAt + 1, 8)
                0xD236 -> border("bottom", wordDoc, opAt + 1, 8)
                0xD237 -> border("right", wordDoc, opAt + 1, 8)
                0x522F -> pgb = op
            }
        }
        if (restart) pg.pageNumberStart = start
        if (lnnMod > 0) pg.lineNumbers = com.therealityteam.aeropages.model.LineNumbering(
            start = lnnMin + 1, countBy = lnnMod,
            restart = when (lnc) { 1 -> com.therealityteam.aeropages.model.LineNumberRestart.SECTION; 2 -> com.therealityteam.aeropages.model.LineNumberRestart.CONTINUOUS; else -> com.therealityteam.aeropages.model.LineNumberRestart.PAGE },
            distancePt = dxaLnn.takeIf { it > 0 }?.let { it / 20f },
        )
        if (clm in 1..3 && pitch > 0) pg.linePitchPt = pitch / 20f
        // sprmSPgbProp: pgbApplyTo (bits 0–2: all, first, all but first), pgbOffsetFrom (bits 5–7: 0 text, 1 the page's edge).
        val fromText = (pgb ushr 5) and 0x7 == 0
        val dflt = if (fromText) 4f else 24f
        val borders = com.therealityteam.aeropages.model.PageBorders(
            top = lines["top"], bottom = lines["bottom"], left = lines["left"], right = lines["right"],
            spaceTop = spaces["top"] ?: dflt, spaceBottom = spaces["bottom"] ?: dflt, spaceLeft = spaces["left"] ?: dflt, spaceRight = spaces["right"] ?: dflt,
            fromText = fromText, shadow = shadow,
            pages = when (pgb and 0x7) { 1 -> com.therealityteam.aeropages.model.PageBorderPages.FIRST; 2 -> com.therealityteam.aeropages.model.PageBorderPages.NOT_FIRST; else -> com.therealityteam.aeropages.model.PageBorderPages.ALL },
        ).takeIf { !it.none }
        return Triple(pg, titlePage, borders)
    }

    /**
     * The first section's headers and footers (PlcfHdd: six separator stories, then each section's even
     * header, header, even footer, footer, first page's header and footer), each its paragraphs as one
     * with a line break between them, the page fields as tokens, its first paragraph's alignment.
     */
    private fun readHeaders(table: ByteArray, fib: Fib, text: String, fmt: Formatting?, s: Stories, doc: Document) {
        val hdd = plc(table, fib.fcPlcfHdd, fib.lcbPlcfHdd, 0) ?: return
        fun story(k: Int): Paragraph? {
            if (k + 1 >= hdd.cps.size) return null
            val a = s.hdd + hdd.cps[k]; val b = s.hdd + hdd.cps[k + 1]
            if (b <= a) return null
            val paras = paragraphs(text, fmt, a, b, pageFields = true).map { it.paragraph }
                .dropWhile { it.plainText().isBlank() }.dropLastWhile { it.plainText().isBlank() }
            if (paras.isEmpty()) return null
            val runs = ArrayList<TextRun>()
            paras.forEachIndexed { i, p ->
                if (i > 0) runs.add(TextRun("\n", p.runs.first().format.copyOf()))
                runs.addAll(p.runs.filter { it.text.isNotEmpty() })
            }
            return Paragraph(runs, ParagraphFormat(align = paras.first().format.align, spaceBeforePt = 0f, spaceAfterPt = 0f))
        }
        doc.evenHeader = story(6); doc.header = story(7); doc.evenFooter = story(8); doc.footer = story(9)
        doc.firstHeader = story(10); doc.firstFooter = story(11)
    }

    /**
     * What is anchored in the main text (PlcSpaMom: each anchor's CP and its FSPA — the shape, where it
     * stands and how big it is, in twips), each given to the paragraph its anchor is in: a text box —
     * its text, its range of the text box story (PlcftxbxTxt, the FTXBXS naming the shape) — as wide
     * and tall as the shape and looking as it does; a picture — the BLIP the shape's pib names in the
     * drawing group's store — at the shape's size, cropped as it says.
     */
    private fun anchorFloating(wordDoc: ByteArray, table: ByteArray, fib: Fib, text: String, fmt: Formatting?, s: Stories, main: List<Para>) {
        val spa = plc(table, fib.fcPlcSpaMom, fib.lcbPlcSpaMom, 26) ?: return
        val drawing = runCatching { readDrawing(table, fib) }.getOrNull()
        val boxes = HashMap<Int, List<Paragraph>>()
        plc(table, fib.fcPlcftxbxTxt, fib.lcbPlcftxbxTxt, 22)?.let { txbx ->
            for (k in 0 until txbx.n) {
                val at = txbx.dataAt + k * 22
                if (at + 22 > table.size) break
                val paras = paragraphs(text, fmt, s.txbx + txbx.cps[k], s.txbx + txbx.cps[k + 1]).map { it.paragraph }.dropLastWhile { it.plainText().isBlank() }
                if (paras.isNotEmpty()) boxes[le32(table, at + 14)] = paras
            }
        }
        for (i in 0 until spa.n) {
            val at = spa.dataAt + i * 26
            if (at + 26 > table.size) break
            val spid = le32(table, at)
            val x = le32(table, at + 4); val y = le32(table, at + 8)
            val w = kotlin.math.abs(le32(table, at + 12) - x) / 20f
            val h = kotlin.math.abs(le32(table, at + 16) - y) / 20f
            val host = main.lastOrNull { it.start <= spa.cps[i] } ?: continue
            val shape = drawing?.shapes?.get(spid)
            val pos = floatOf(le16(table, at + 20), x, y, shape?.props)
            // Word marks it below the text in its FSPA; WPS (and Word 2000 on) in the shape's own booleans.
            if (shape?.behindDocument == true && pos.wrap == com.therealityteam.aeropages.model.FloatWrap.FRONT) pos.wrap = com.therealityteam.aeropages.model.FloatWrap.BEHIND
            val block: com.therealityteam.aeropages.model.Block = boxes[spid]?.let { paras ->
                com.therealityteam.aeropages.model.TextBoxBlock(paras.toMutableList(), w.takeIf { it > 0f }, h.takeIf { it > 0f })
                    .also { box -> if (shape != null) lookOf(box, shape, drawing.look); box.floating = pos }
            } ?: shape?.takeIf { it.type in 136..175 }?.let {
                // Its box grown by the text box's inset about the same middle.
                val inset = com.therealityteam.aeropages.model.PageMetrics.BOX_INSET
                wordArt(it, w, h, drawing.look)?.also { box -> box.floating = pos.copy(x = pos.x - inset, y = pos.y - inset) }
            }
            ?: shape?.props?.get(0x0104)?.let { pib ->
                val (bytes, mime) = drawing.blips.getOrNull(pib - 1)?.let { blipOf(table, it, wordDoc) } ?: return@let null
                if (w <= 0f || h <= 0f) return@let null
                com.therealityteam.aeropages.model.ImageBlock(bytes, mime, w, h, floating = pos, flipH = shape.flipH, flipV = shape.flipV).also { cropOf(it, shape.props) }
            } ?: continue
            host.floating.add(Triple(x, y, block))
        }
    }

    /**
     * WordArt (MSOSPT 136–175, geometry text): its text (gtextUNICODE), face, size, bold and italic, in
     * its fill colour, centred in a frameless box [w] × [h] — its letters as tall as the box lets each
     * line be when the WordArt is stretched or fitted to it.
     */
    private fun wordArt(shape: DrawnShape, w: Float, h: Float, look: com.therealityteam.aeropages.format.ppt.OfficeArtLook): com.therealityteam.aeropages.model.TextBoxBlock? {
        val text = shape.complex[0x00C0]?.let { String(it, Charsets.UTF_16LE) }?.substringBefore('\u0000')?.replace("\r\n", "\n")?.replace('\r', '\n')?.trimEnd()
        if (text.isNullOrEmpty() || w <= 0f || h <= 0f) return null
        val face = shape.complex[0x00C5]?.let { String(it, Charsets.UTF_16LE) }?.substringBefore('\u0000')?.trim()?.takeIf { it.isNotEmpty() }
        val bools = shape.props[0x00FF] ?: 0
        fun flag(bit: Int) = bools and (1 shl (bit + 16)) != 0 && bools and (1 shl bit) != 0
        val lines = text.split('\n')
        val given = shape.props[0x00C3]?.let { it / 65536f } ?: 36f
        val size = (if (flag(10) || flag(9) || flag(8)) h / lines.size / 1.2f else given).coerceIn(6f, 200f)
        val color = look.fillOf(shape.props, null)?.takeIf { (it ushr 24) != 0L } ?: look.lineOf(shape.props, null) ?: 0xFF000000L
        val paras = lines.map { line ->
            com.therealityteam.aeropages.model.Paragraph(mutableListOf(TextRun(line, CharacterFormat(bold = flag(5), italic = flag(4), fontSizePt = size, fontFamily = face, color = color))),
                ParagraphFormat(align = when (shape.props[0x00C2]) { 2 -> TextAlign.LEFT; 3 -> TextAlign.RIGHT; else -> TextAlign.CENTER }, spaceBeforePt = 0f, spaceAfterPt = 0f))
        }
        // WordArt's letters fill its frame; a text box keeps its inset: the box grows by it all round (see anchorFloating).
        val inset = com.therealityteam.aeropages.model.PageMetrics.BOX_INSET
        return com.therealityteam.aeropages.model.TextBoxBlock(paras.toMutableList(), w + 2 * inset, h + 2 * inset,
            com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX, fill = null, border = null).also { it.anchor = com.therealityteam.aeropages.model.ShapeAnchor.CENTER })
    }

    /**
     * Where a shape anchored in the text stands: its FSPA's box ([xTw], [yTw] twips from the frame its
     * bx / by name — the margins, the page, the column or paragraph; all the page's when fRcaSimple),
     * its wrapping (wr: none — behind the text when fBelowText, else in front —, above and below only,
     * around), and — Word 2000 on — its shape's posrelh / posrelv frames and posh / posv alignments,
     * which take the FSPA's place ([MS-DOC] 2.9.88, [MS-ODRAW] 2.3.4.19–22).
     */
    private fun floatOf(flags: Int, xTw: Int, yTw: Int, props: Map<Int, Int>?): com.therealityteam.aeropages.model.FloatPosition {
        val simple = flags and 0x2000 != 0
        fun hFrame(code: Int) = when (code) {
            0 -> com.therealityteam.aeropages.model.FloatFrame.MARGIN
            1 -> com.therealityteam.aeropages.model.FloatFrame.PAGE
            else -> com.therealityteam.aeropages.model.FloatFrame.COLUMN
        }
        fun vFrame(code: Int) = when (code) {
            0 -> com.therealityteam.aeropages.model.FloatFrame.MARGIN
            1 -> com.therealityteam.aeropages.model.FloatFrame.PAGE
            else -> com.therealityteam.aeropages.model.FloatFrame.PARAGRAPH
        }
        fun align(code: Int?) = when (code) {
            1, 4 -> com.therealityteam.aeropages.model.FloatAlign.START
            2 -> com.therealityteam.aeropages.model.FloatAlign.CENTER
            3, 5 -> com.therealityteam.aeropages.model.FloatAlign.END
            else -> null
        }
        val hFrom = props?.get(0x0390)?.let(::hFrame) ?: if (simple) com.therealityteam.aeropages.model.FloatFrame.PAGE else hFrame((flags shr 1) and 3)
        val vFrom = props?.get(0x0392)?.let(::vFrame) ?: if (simple) com.therealityteam.aeropages.model.FloatFrame.PAGE else vFrame((flags shr 3) and 3)
        val wrap = when ((flags shr 5) and 0xF) {
            3 -> if (flags and 0x4000 != 0) com.therealityteam.aeropages.model.FloatWrap.BEHIND else com.therealityteam.aeropages.model.FloatWrap.FRONT
            1 -> com.therealityteam.aeropages.model.FloatWrap.TOP_BOTTOM
            else -> com.therealityteam.aeropages.model.FloatWrap.SQUARE
        }
        return com.therealityteam.aeropages.model.FloatPosition(xTw / 20f, hFrom, align(props?.get(0x038F)), yTw / 20f, vFrom, align(props?.get(0x0391)), wrap)
    }

    /**
     * A text box as its shape looks: its fill and outline (none: Word's frameless box), a rounded
     * rectangle or an ellipse as one, its text anchored as it says — and as wide and tall as the app's
     * own inset (PageMetrics.BOX_INSET) leaves its text the room the shape's insets did.
     */
    private fun lookOf(box: com.therealityteam.aeropages.model.TextBoxBlock, shape: DrawnShape, look: com.therealityteam.aeropages.format.ppt.OfficeArtLook) {
        val p = shape.props
        val fill = look.fillOf(p, null)?.takeIf { (it ushr 24) != 0L }   // a fill wholly see-through is none
        val line = look.lineOf(p, null)
        val type = when {
            fill == null && line == null -> com.therealityteam.aeropages.model.ShapeType.TEXTBOX
            shape.type == 2 -> com.therealityteam.aeropages.model.ShapeType.ROUNDED
            shape.type == 3 -> com.therealityteam.aeropages.model.ShapeType.ELLIPSE
            else -> com.therealityteam.aeropages.model.ShapeType.RECT
        }
        box.shape = com.therealityteam.aeropages.model.SlideShape(type, fill = fill, border = line).also { sh ->
            fill?.let { look.gradient(sh, it, p, null) }
            p[0x01CB]?.let { if (it > 0) sh.borderWidthPt = (it / 12700f).coerceIn(0.25f, 12f) }
            sh.dash = look.dashOf(p)
            sh.noWrap = p[0x0085] == 2   // WrapText: square, by points, none
            sh.anchor = when (p[0x0087]) {
                1, 4, 7 -> com.therealityteam.aeropages.model.ShapeAnchor.CENTER
                2, 5, 8 -> com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM
                else -> com.therealityteam.aeropages.model.ShapeAnchor.TOP
            }
        }
        // Text in automatic colour shows white on a dark fill, as Word shows it.
        if (fill != null && com.therealityteam.aeropages.model.TextColors.luminance(fill) < 0.5) {
            box.paragraphs.forEach { para -> para.runs.forEach { if (it.format.color == null) it.format.color = 0xFFFFFFFFL } }
        }
        fun emu(id: Int, default: Int) = (p[id] ?: look.defaultOpt[id] ?: default) / 12700f
        val inset = com.therealityteam.aeropages.model.PageMetrics.BOX_INSET
        box.widthPt = box.widthPt?.let { it + 2 * inset - emu(0x0081, 91440) - emu(0x0083, 91440) }
        box.heightPt = box.heightPt?.let { it + 2 * inset - emu(0x0082, 45720) - emu(0x0084, 45720) }
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
