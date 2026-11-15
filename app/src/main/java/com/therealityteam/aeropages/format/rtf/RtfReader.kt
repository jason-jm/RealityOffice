package com.therealityteam.aeropages.format.rtf

import com.therealityteam.aeropages.format.doc.DocLists
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.DocFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ListDef
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.ListLevel
import com.therealityteam.aeropages.model.NumFormat
import com.therealityteam.aeropages.model.PAGE_BREAK
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import java.nio.charset.Charset

/**
 * Rich Text Format (the RTF 1.9.1 specification), as Word, WordPad, WPS and TextEdit write it: the
 * text in its code page (`\ansicpg`, each font's `\fcharset`: GBK for Chinese) and in Unicode
 * (`\uN` with its `\ucN` fallback skipped), character formatting (bold, italic, underline,
 * strikethrough, size, font, colour, highlight, super- and subscript, hidden text left out),
 * paragraphs (alignment, indents, spacing, line spacing, styles — headings by their names —, page
 * breaks), lists (`\listtable` / `\listoverridetable`, `\ls` / `\ilvl`), tables (`\trowd` … `\cell`
 * … `\row`, `\cellx` widths, merges, borders), pictures (PNG, JPEG, EMF, WMF), links (HYPERLINK
 * fields), footnotes, the header and footer. Everything else's text — font names, the style sheet,
 * document information, bookmarks' names — stays out of the text.
 */
object RtfReader {
    /** A comment range end's name while its comment is not yet read: this and the range's number. */
    private const val RANGE = "\u0001rtfatn"

    fun read(bytes: ByteArray): Document = Parser(bytes).run()

    /** Character formatting, as a group holds it. */
    private data class Chr(
        var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false, var strike: Boolean = false,
        var sizeHalf: Int = 24, var font: Int = -1, var color: Int = 0, var highlight: Int = 0,
        var sup: Boolean = false, var sub: Boolean = false, var caps: Boolean = false, var hidden: Boolean = false,
        /**
         * Tracked changes: deleted (`\deleted`) or inserted (`\revised`), each with its author (an index into `\revtbl`) and
         * date (a DTTM); a formatting change's (`\crauth` / `\crdate`).
         */
        var deleted: Boolean = false, var inserted: Boolean = false, var revAuth: Int = -1, var revDttm: Int = 0,
        var delAuth: Int = -1, var delDttm: Int = 0, var crAuth: Int = -1, var crDate: Int = 0,
    )

    /** Paragraph formatting (RTF keeps it per group too; `\pard` resets it). */
    private data class Par(
        var align: TextAlign = TextAlign.LEFT, var left: Int = 0, var right: Int = 0, var first: Int = 0,
        var before: Int = 0, var after: Int = 0, var line: Int = 0, var lineMult: Boolean = false,
        var style: Int = 0, var inTable: Boolean = false, var ls: Int = 0, var ilvl: Int = 0,
        var keepNext: Boolean = false, var keepLines: Boolean = false, var pageBreakBefore: Boolean = false, var outline: Int = -1,
    )

    private enum class Dest { BODY, SKIP, FONTS, COLORS, STYLES, REVTBL, ATRFSTART, ATRFEND, ATNAUTHOR, ATNREF, ATNDATE, ANNOTATION, PICT, FLDINST, FLDRSLT, LISTTABLE, LISTOVERRIDES, FOOTNOTE, HEADER, FOOTER, LEVELTEXT, LEVELNUMBERS }

    private class State(var dest: Dest, var chr: Chr, var par: Par, var uc: Int) {
        fun copy() = State(dest, chr.copy(), par.copy(), uc)
    }

    /** The story text goes into: the body, a footnote, the header, the footer. */
    private class Story {
        val blocks = ArrayList<com.therealityteam.aeropages.model.Block>()
        val runs = ArrayList<TextRun>()
        val text = StringBuilder()
        var runChr: CharacterFormat? = null
        var link: String? = null
        /** The paragraph being read's comment range ends (`\atrfstart` / `\atrfend`, named by their number until their comment is known) and its comments. */
        val marks = ArrayList<com.therealityteam.aeropages.model.BookmarkMark>()
        val comments = ArrayList<com.therealityteam.aeropages.model.Comment>()
    }

    private class Parser(val b: ByteArray) {
        var i = 0
        val stack = ArrayList<State>()
        var st = State(Dest.BODY, Chr(), Par(), 1)
        var codepage: Charset = cp(1252) ?: Charsets.ISO_8859_1
        val fonts = HashMap<Int, Pair<String, Charset?>>()
        val colors = ArrayList<Long?>()
        var colorParts = intArrayOf(-1, -1, -1)
        val styleNames = HashMap<Int, String>()
        /** The revision authors (`\revtbl`), which the revision marks' author numbers index. */
        val revAuthors = ArrayList<String>()
        /** The comment being read: its author (`\atnauthor`), the range it is on (`\atnref`) and its date (`\atndate`, a DTTM); each comment's range number. */
        var atnAuthor = ""; var atnRef: Int? = null; var atnDate = 0
        val commentRefs = HashMap<com.therealityteam.aeropages.model.Comment, Int>()
        var styleNum = 0
        var defaultFont = 0
        val doc = Document(mutableListOf())
        val body = Story()
        var story = body
        val stories = ArrayList<Story>()
        // Bytes of \'hh escapes (and raw 8-bit text) waiting to be decoded together: a GBK character is two of them.
        val pending = java.io.ByteArrayOutputStream()
        var skipFallback = 0
        // Text of the current destination that is not the story's (a font name, a field's instruction, a picture's hex).
        val aside = StringBuilder()
        // Tables: the row being read, its cells' right edges, merges and whether any border is drawn.
        var row: TableRow? = null
        var table: Table? = null
        var cellParas = ArrayList<Paragraph>()
        val cellRights = ArrayList<Int>()
        val cellVMerge = ArrayList<Int>()   // 0 none, 1 first, 2 continued
        val cellHMerge = ArrayList<Int>()
        var nextVMerge = 0; var nextHMerge = 0
        var rowBorders = false; var cellBorderSeen = false
        val tableRowsRights = ArrayList<List<Int>>()
        // Pictures.
        var pictType: String? = null; var picW = 0; var picH = 0; var picGoalW = 0; var picGoalH = 0; var picScaleX = 100; var picScaleY = 100
        // Lists.
        val listDefs = HashMap<Int, MutableList<ListLevel>>()   // \listid → levels
        var listId = 0; var curLevels: MutableList<ListLevel>? = null
        var lvlNfc = 0; var lvlStart = 1; var lvlText = StringBuilder(); var lvlNumbers = ArrayList<Int>(); var lvlFont = -1
        val overrides = HashMap<Int, Int>()    // \ls → \listid
        // The group depths a \list, a \listlevel and a \listoverride opened at (-1: not in one).
        var listDepth = -1; var levelDepth = -1; var overrideDepth = -1
        var footRefs = 0
        val cellImages = ArrayList<ImageBlock>()
        var overrideList = 0; var overrideLs = 0
        val lsIds = HashMap<Int, Int>()        // \ls → the app's list id
        var fldInst = StringBuilder()

        fun run(): Document {
            while (i < b.size) {
                val c = b[i].toInt() and 0xFF
                when (c) {
                    '{'.code -> { flushPending(); stack.add(st); st = st.copy(); i++ }
                    '}'.code -> { flushPending(); endGroup(); i++ }
                    '\\'.code -> control()
                    '\r'.code, '\n'.code -> i++
                    else -> { text(c); i++ }
                }
            }
            flushPending()
            endParagraph(force = false)
            endTable()
            doc.blocks.addAll(body.blocks)
            if (doc.blocks.isEmpty()) doc.blocks.add(ParagraphBlock(Paragraph.of("")))
            nameRanges()
            doc.sourceFormat = DocFormat.DOCX
            return doc
        }

        // --- tokens ---------------------------------------------------------------------------------

        fun control() {
            i++
            if (i >= b.size) return
            val c = b[i].toInt() and 0xFF
            if (!isLetter(c)) {
                i++
                when (c) {
                    '\''.code -> {
                        val h = if (i + 2 <= b.size) String(b, i, 2, Charsets.ISO_8859_1).toIntOrNull(16) else null
                        i += 2
                        if (h != null) {
                            if (skipFallback > 0) { skipFallback--; return }
                            // A level's text: its length and the levels' numbers as bytes, its other characters in the code page.
                            if (st.dest == Dest.LEVELTEXT && h < 0x80) { flushPending(); lvlText.append(h.toChar()); return }
                            if (st.dest == Dest.LEVELNUMBERS) { lvlNumbers.add(h); return }
                            pending.write(h)
                        }
                    }
                    '~'.code -> emit(" ")
                    '_'.code -> emit("-")
                    '-'.code -> Unit
                    '*'.code -> st.dest = if (st.dest == Dest.FLDINST || st.dest == Dest.LISTTABLE || st.dest == Dest.LISTOVERRIDES) st.dest else starredDest()
                    '\\'.code, '{'.code, '}'.code -> text(c)
                    '\r'.code, '\n'.code -> word("par", null)
                    else -> Unit
                }
                return
            }
            val start = i
            while (i < b.size && isLetter(b[i].toInt() and 0xFF)) i++
            val name = String(b, start, i - start, Charsets.ISO_8859_1)
            var param: Int? = null
            if (i < b.size && (b[i] == '-'.code.toByte() || b[i] in '0'.code.toByte()..'9'.code.toByte())) {
                val ps = i
                i++
                while (i < b.size && b[i] in '0'.code.toByte()..'9'.code.toByte()) i++
                param = String(b, ps, i - ps, Charsets.ISO_8859_1).toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
            }
            if (i < b.size && b[i] == ' '.code.toByte()) i++
            if (name == "bin") { flushPending(); i += (param ?: 0).coerceAtLeast(0); return }
            word(name, param)
        }

        /** `\*\x`: a destination this reader knows (a field's instruction, a list table), or one to skip. */
        fun starredDest(): Dest {
            val save = i
            if (i < b.size && b[i] == '\\'.code.toByte()) {
                var j = i + 1
                val s = j
                while (j < b.size && isLetter(b[j].toInt() and 0xFF)) j++
                val name = String(b, s, j - s, Charsets.ISO_8859_1)
                i = save
                return when (name) {
                    "fldinst" -> Dest.FLDINST
                    "listtable", "listoverridetable", "shppict", "atrfstart", "atrfend", "atnauthor", "annotation", "atnref", "atndate" -> st.dest
                    else -> Dest.SKIP
                }
            }
            return Dest.SKIP
        }

        fun isLetter(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

        fun text(c: Int) {
            if (st.dest == Dest.SKIP) return
            if (skipFallback > 0) { skipFallback--; return }
            when (st.dest) {
                Dest.FONTS, Dest.STYLES, Dest.REVTBL -> {
                    if (c >= 0x80) { pending.write(c); return }
                    flushPending()
                    if (c == ';'.code) commitName() else aside.append(c.toChar())
                    return
                }
                Dest.FLDINST, Dest.PICT, Dest.ATRFSTART, Dest.ATRFEND, Dest.ATNAUTHOR, Dest.ATNREF, Dest.ATNDATE -> { if (c >= 0x80) pending.write(c) else { flushPending(); aside.append(c.toChar()) }; return }
                Dest.COLORS -> { if (c == ';'.code) { colors.add(if (colorParts[0] < 0) null else 0xFF000000L or (colorParts[0].toLong() shl 16) or (colorParts[1].toLong() shl 8) or colorParts[2].toLong()); colorParts = intArrayOf(-1, -1, -1) }; return }
                Dest.LEVELTEXT -> { flushPending(); if (c != ';'.code) lvlText.append(c.toChar()); return }
                Dest.LEVELNUMBERS, Dest.LISTTABLE, Dest.LISTOVERRIDES -> return
                else -> Unit
            }
            if (c >= 0x80) { pending.write(c); return }
            flushPending()
            emit(c.toChar().toString())
        }

        /** A font's or a style's name, ended by its ';'. */
        fun commitName() {
            val name = aside.toString().trim()
            aside.setLength(0)
            // A revision author's name keeps its place even empty (the marks number them).
            if (st.dest == Dest.REVTBL) { revAuthors.add(name); return }
            if (name.isEmpty()) return
            if (st.dest == Dest.FONTS) fonts[styleNum] = name to fonts[styleNum]?.second else styleNames[styleNum] = name
        }

        /** The waiting bytes, in the current font's code page (else the document's). */
        fun flushPending() {
            if (pending.size() == 0) return
            val bytes = pending.toByteArray(); pending.reset()
            val cs = (if (st.dest == Dest.FONTS) fonts[styleNum]?.second else fonts[st.chr.font]?.second) ?: codepage
            val s = runCatching { String(bytes, cs) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))
            when (st.dest) {
                Dest.FONTS, Dest.STYLES, Dest.REVTBL, Dest.FLDINST, Dest.PICT, Dest.ATRFSTART, Dest.ATRFEND, Dest.ATNAUTHOR, Dest.ATNREF, Dest.ATNDATE -> aside.append(s)
                Dest.LEVELTEXT -> lvlText.append(s)
                Dest.SKIP, Dest.COLORS, Dest.LEVELNUMBERS, Dest.LISTTABLE, Dest.LISTOVERRIDES -> Unit
                else -> emit(s)
            }
        }

        // --- control words --------------------------------------------------------------------------

        fun word(name: String, p: Int?) {
            if (name != "u") flushPending()
            val c = st.chr; val par = st.par
            val on = p == null || p != 0
            when (name) {
                // Destinations.
                "fonttbl" -> st.dest = Dest.FONTS
                "colortbl" -> st.dest = Dest.COLORS
                "stylesheet" -> st.dest = Dest.STYLES
                "revtbl" -> { st.dest = Dest.REVTBL; aside.setLength(0) }
                // Comments: a range's ends, the comment's author, its text (a story of its own), its range and date.
                "atrfstart" -> { st.dest = Dest.ATRFSTART; aside.setLength(0) }
                "atrfend" -> { st.dest = Dest.ATRFEND; aside.setLength(0) }
                "atnauthor" -> { st.dest = Dest.ATNAUTHOR; aside.setLength(0) }
                "annotation" -> { openStory(Dest.ANNOTATION); atnRef = null; atnDate = 0 }
                "atnref" -> { st.dest = Dest.ATNREF; aside.setLength(0) }
                "atndate" -> { st.dest = Dest.ATNDATE; aside.setLength(0) }
                "info", "xmlnstbl", "generator", "rsidtbl", "pgdsctbl", "themedata", "colorschememapping", "latentstyles", "datastore",
                "fldtype", "bkmkstart", "bkmkend", "nonshppict", "pntext", "pntxta", "pntxtb", "txe", "xe", "tc", "atnid",
                "object", "objdata", "mmathPr", "wgrffmtfilter", "protusertbl", "userprops", "docvar", "fchars", "lchars", "template",
                "ftnsep", "ftnsepc", "aftnsep", "aftnsepc", "listpicture", "falt", "panose", "sp", "sn", "sv" -> st.dest = Dest.SKIP
                "listtable" -> st.dest = Dest.LISTTABLE
                "listoverridetable" -> st.dest = Dest.LISTOVERRIDES
                "list" -> if (st.dest == Dest.LISTTABLE) { curLevels = ArrayList(); listId = 0; listDepth = stack.size }
                "listid" -> if (st.dest == Dest.LISTTABLE) listId = p ?: 0 else if (st.dest == Dest.LISTOVERRIDES) overrideList = p ?: 0
                "listlevel" -> if (st.dest == Dest.LISTTABLE) { lvlNfc = 0; lvlStart = 1; lvlText = StringBuilder(); lvlNumbers = ArrayList(); lvlFont = -1; levelDepth = stack.size }
                "levelnfc", "levelnfcn" -> lvlNfc = p ?: 0
                "levelstartat" -> lvlStart = p ?: 1
                "leveltext" -> st.dest = Dest.LEVELTEXT
                "levelnumbers" -> st.dest = Dest.LEVELNUMBERS
                "listname", "liststylename" -> st.dest = Dest.SKIP
                "listoverride" -> if (st.dest == Dest.LISTOVERRIDES) { overrideList = 0; overrideLs = 0; overrideDepth = stack.size }
                "ls" -> if (st.dest == Dest.LISTOVERRIDES) overrideLs = p ?: 0 else par.ls = p ?: 0
                "ilvl" -> par.ilvl = (p ?: 0).coerceIn(0, 8)
                "listtext" -> st.dest = Dest.SKIP
                "field" -> Unit
                "fldinst" -> { st.dest = Dest.FLDINST; aside.setLength(0) }
                "fldrslt" -> { st.dest = Dest.FLDRSLT; story.link = linkOf(fldInst.toString()) }
                "pict" -> { st.dest = Dest.PICT; aside.setLength(0); pictType = null; picW = 0; picH = 0; picGoalW = 0; picGoalH = 0; picScaleX = 100; picScaleY = 100 }
                "pngblip" -> pictType = "image/png"
                "jpegblip" -> pictType = "image/jpeg"
                "emfblip" -> pictType = "image/emf"
                "wmetafile" -> pictType = "image/wmf"
                "picw" -> picW = p ?: 0
                "pich" -> picH = p ?: 0
                "picwgoal" -> picGoalW = p ?: 0
                "pichgoal" -> picGoalH = p ?: 0
                "picscalex" -> picScaleX = p ?: 100
                "picscaley" -> picScaleY = p ?: 100
                "footnote" -> openStory(Dest.FOOTNOTE)
                "header", "headerr", "headerf", "headerl" -> if (name == "header" || name == "headerr") openStory(Dest.HEADER) else st.dest = Dest.SKIP
                "footer", "footerr", "footerf", "footerl" -> if (name == "footer" || name == "footerr") openStory(Dest.FOOTER) else st.dest = Dest.SKIP
                // Code pages and fonts.
                "ansicpg" -> p?.let { cp(it)?.let { cs -> codepage = cs } }
                "deff" -> defaultFont = p ?: 0
                "f" -> when (st.dest) {
                    Dest.FONTS -> { aside.setLength(0); styleNum = p ?: 0; fonts[styleNum] = "" to null }
                    Dest.LISTTABLE -> lvlFont = p ?: -1
                    else -> c.font = p ?: -1
                }
                "fcharset" -> if (st.dest == Dest.FONTS) fonts[styleNum] = (fonts[styleNum]?.first ?: "") to charsetOf(p ?: 0)
                "cpg" -> if (st.dest == Dest.FONTS) p?.let { cp(it) }?.let { fonts[styleNum] = (fonts[styleNum]?.first ?: "") to it }
                "red" -> colorParts[0] = p ?: 0
                "green" -> colorParts[1] = p ?: 0
                "blue" -> colorParts[2] = p ?: 0
                "s" -> if (st.dest == Dest.STYLES) { styleNum = p ?: 0; aside.setLength(0) } else par.style = p ?: 0
                // Unicode.
                "uc" -> st.uc = (p ?: 1).coerceAtLeast(0)
                "u" -> {
                    val v = (p ?: 0).let { if (it < 0) it + 65536 else it }
                    flushPending()
                    when (st.dest) {
                        Dest.FONTS, Dest.STYLES, Dest.REVTBL, Dest.FLDINST, Dest.ATNAUTHOR -> aside.append(v.toChar())
                        Dest.LEVELTEXT -> lvlText.append(v.toChar())
                        Dest.BODY, Dest.FLDRSLT, Dest.FOOTNOTE, Dest.HEADER, Dest.FOOTER, Dest.ANNOTATION -> emit(v.toChar().toString())
                        else -> Unit
                    }
                    skipFallback = st.uc
                }
                // Character formatting.
                "plain" -> st.chr = Chr(font = defaultFont)
                "b" -> c.bold = on
                "i" -> c.italic = on
                "ul", "uld", "uldb", "ulth", "ulw", "ulwave", "uldash" -> c.underline = on
                "ulnone" -> c.underline = false
                "strike", "striked" -> c.strike = on
                "fs" -> c.sizeHalf = p ?: 24
                "cf" -> c.color = p ?: 0
                "highlight", "cb", "chcbpat" -> if (name == "highlight") c.highlight = p ?: 0
                "super" -> { c.sup = on; if (on) c.sub = false }
                "sub" -> { c.sub = on; if (on) c.sup = false }
                "nosupersub" -> { c.sup = false; c.sub = false }
                "caps", "scaps" -> c.caps = on
                "v" -> c.hidden = on
                // Tracked changes.
                "deleted" -> c.deleted = on
                "revised" -> c.inserted = on
                "revauth" -> c.revAuth = p ?: -1
                "revdttm" -> c.revDttm = p ?: 0
                "revauthdel" -> c.delAuth = p ?: -1
                "revdttmdel" -> c.delDttm = p ?: 0
                "crauth" -> c.crAuth = p ?: -1
                "crdate" -> c.crDate = p ?: 0
                // Paragraph formatting.
                "pard" -> st.par = Par()
                "ql" -> par.align = TextAlign.LEFT
                "qc" -> par.align = TextAlign.CENTER
                "qr" -> par.align = TextAlign.RIGHT
                "qj", "qd" -> par.align = TextAlign.JUSTIFY
                "li", "lin" -> par.left = p ?: 0
                "ri", "rin" -> par.right = p ?: 0
                "fi" -> par.first = p ?: 0
                "sb" -> par.before = p ?: 0
                "sa" -> par.after = p ?: 0
                "sl" -> par.line = p ?: 0
                "slmult" -> par.lineMult = on
                "keepn" -> par.keepNext = true
                "keep" -> par.keepLines = true
                "pagebb" -> par.pageBreakBefore = true
                "outlinelevel" -> par.outline = p ?: -1
                "intbl" -> par.inTable = true
                // Breaks.
                "par" -> endParagraph(force = true)
                "line", "lbr" -> emit("\n")
                "tab" -> emit("\t")
                "page" -> { endParagraph(force = false); story.blocks.add(ParagraphBlock(Paragraph.of(PAGE_BREAK))) }
                "emdash" -> emit("—"); "endash" -> emit("–"); "bullet" -> emit("•")
                "lquote" -> emit("‘"); "rquote" -> emit("’"); "ldblquote" -> emit("“"); "rdblquote" -> emit("”")
                "emspace", "enspace", "qmspace" -> emit(" ")
                "chftn" -> if (st.dest == Dest.BODY || st.dest == Dest.FLDRSLT) { val sup = c.sup; c.sup = true; emit("[${++footRefs}]"); c.sup = sup }
                // Tables.
                "trowd" -> { cellRights.clear(); cellVMerge.clear(); cellHMerge.clear(); nextVMerge = 0; nextHMerge = 0; rowBorders = false }
                "clvmgf" -> nextVMerge = 1
                "clvmrg" -> nextVMerge = 2
                "clmgf" -> nextHMerge = 1
                "clmrg" -> nextHMerge = 2
                "clbrdrt", "clbrdrb", "clbrdrl", "clbrdrr", "trbrdrt", "trbrdrb", "trbrdrl", "trbrdrr", "trbrdrh", "trbrdrv" -> cellBorderSeen = true
                "brdrs", "brdrth", "brdrdb", "brdrdot", "brdrdash", "brdrsh" -> if (cellBorderSeen) rowBorders = true
                "brdrnone", "brdrnil", "brdrtbl" -> Unit
                "cellx" -> { cellRights.add(p ?: 0); cellVMerge.add(nextVMerge); cellHMerge.add(nextHMerge); nextVMerge = 0; nextHMerge = 0; cellBorderSeen = false }
                "cell" -> endCell()
                "row" -> endRow()
                "nestcell", "nestrow" -> if (name == "nestcell") endCell() else Unit
            }
        }

        /** The story's comment range ends and comments so far go to paragraph [p]. */
        fun takeMarks(p: Paragraph) {
            p.bookmarks.addAll(story.marks); story.marks.clear()
            story.comments.forEach { it.targetId = p.id }; story.comments.clear()
        }

        /**
         * Each comment's range ends, named by their number while read, as the comment's marks (Bookmarks.commentMark), the
         * comment on the paragraph its range starts in; ends no comment names go.
         */
        fun nameRanges() {
            val byRef = commentRefs.entries.associate { (c, n) -> n to c }
            for (p in doc.locations().map { it.paragraph }) {
                if (p.bookmarks.none { it.name.startsWith(RANGE) }) continue
                val kept = p.bookmarks.mapNotNull { m ->
                    if (!m.name.startsWith(RANGE)) return@mapNotNull m
                    val c = m.name.removePrefix(RANGE).toIntOrNull()?.let { byRef[it] } ?: return@mapNotNull null
                    if (!m.end) c.targetId = p.id
                    m.copy(name = com.therealityteam.aeropages.model.Bookmarks.commentMark(c.id))
                }
                p.bookmarks.clear(); p.bookmarks.addAll(kept)
            }
            // A comment no paragraph took (one the text ended in): on the last.
            val last = doc.locations().lastOrNull()?.paragraph?.id
            doc.comments.removeAll { it.targetId == -1L && last == null }
            doc.comments.forEach { if (it.targetId == -1L) it.targetId = last!! }
        }

        fun openStory(dest: Dest) {
            st.dest = dest
            st.par = Par()
            story = Story().also { stories.add(it) }
        }

        fun endGroup() {
            val closing = st
            val depth = stack.size
            val outer = stack.removeLastOrNull() ?: return
            if ((closing.dest == Dest.FONTS || closing.dest == Dest.STYLES) && aside.isNotEmpty()) commitName()
            if (closing.dest == Dest.FLDINST && outer.dest != Dest.FLDINST) { fldInst = StringBuilder(aside); aside.setLength(0) }
            if (closing.dest == Dest.FLDRSLT && outer.dest != Dest.FLDRSLT) { endRun(); story.link = null }
            if (closing.dest == Dest.PICT && outer.dest != Dest.PICT) picture()
            if (closing.dest != outer.dest) when (closing.dest) {
                Dest.ATRFSTART, Dest.ATRFEND -> aside.toString().trim().toIntOrNull()?.let { n ->
                    story.marks.add(com.therealityteam.aeropages.model.BookmarkMark(RANGE + n, story.runs.sumOf { it.text.length } + story.text.length, closing.dest == Dest.ATRFEND))
                }
                Dest.ATNAUTHOR -> atnAuthor = aside.toString().trim()
                Dest.ATNREF -> atnRef = aside.toString().trim().toIntOrNull()
                Dest.ATNDATE -> atnDate = aside.toString().trim().toLongOrNull()?.toInt() ?: 0
                else -> Unit
            }
            if (closing.dest != outer.dest && closing.dest in setOf(Dest.ATRFSTART, Dest.ATRFEND, Dest.ATNAUTHOR, Dest.ATNREF, Dest.ATNDATE)) aside.setLength(0)
            // A comment's end: its text, on the paragraph it is in (its range's, once read), dated.
            if (closing.dest == Dest.ANNOTATION && outer.dest != Dest.ANNOTATION) {
                endParagraph(force = false)
                val text = story.blocks.mapNotNull { (it as? ParagraphBlock)?.paragraph?.plainText() }.joinToString("\n").trim()
                val c = com.therealityteam.aeropages.model.Comment(atnAuthor, text, -1L, dateMs = dttm(atnDate) ?: System.currentTimeMillis())
                doc.comments.add(c)
                atnRef?.let { commentRefs[c] = it }
                story = body
                body.comments.add(c)
            }
            // The ends of a list's level, a list, an override.
            if (depth == levelDepth) { curLevels?.add(levelOf()); levelDepth = -1 }
            if (depth == listDepth) { curLevels?.let { if (listId != 0) listDefs[listId] = it }; curLevels = null; listDepth = -1 }
            if (depth == overrideDepth) { if (overrideLs != 0) overrides[overrideLs] = overrideList; overrideDepth = -1 }
            if ((closing.dest == Dest.FOOTNOTE || closing.dest == Dest.HEADER || closing.dest == Dest.FOOTER) && outer.dest != closing.dest) {
                endParagraph(force = false)
                val text = story.blocks.mapNotNull { (it as? ParagraphBlock)?.paragraph?.plainText() }.joinToString("\n")
                    .replace(Regex("^\\[\\d+]\\s*"), "")
                when (closing.dest) {
                    Dest.FOOTNOTE -> doc.footnotes.add(com.therealityteam.aeropages.model.Footnote(text.trim()))
                    Dest.HEADER -> if (text.isNotBlank()) doc.header = Paragraph.of(text)
                    else -> if (text.isNotBlank()) doc.footer = Paragraph.of(text)
                }
                story = body
            }
            st = outer
        }

        /** The \listlevel just read. */
        fun levelOf(): ListLevel {
            val format = DocLists.formatOf(lvlNfc)
            val t = StringBuilder()
            val raw = lvlText.toString()
            // \leveltext: its length, then the text with each level's number as the character 0–8.
            val body = if (raw.isNotEmpty()) raw.substring(1).take(raw[0].code.coerceAtMost(raw.length - 1)) else ""
            for (ch in body) if (ch.code < 9) t.append('%').append(ch.code + 1) else t.append(ch)
            val font = fonts[lvlFont]?.first
            lvlText = StringBuilder()
            return ListLevel(format, t.toString(), lvlStart, if (format == NumFormat.BULLET) font else null)
        }

        // --- the text ---------------------------------------------------------------------------------

        fun chrFormat(): CharacterFormat {
            val c = st.chr
            return CharacterFormat(
                bold = c.bold, italic = c.italic, underline = c.underline, strikethrough = c.strike,
                superscript = c.sup, subscript = c.sub, fontSizePt = c.sizeHalf / 2f,
                fontFamily = fonts[c.font]?.first?.takeIf { it.isNotEmpty() },
                color = colors.getOrNull(c.color)?.takeIf { c.color > 0 },
                highlight = colors.getOrNull(c.highlight)?.takeIf { c.highlight > 0 },
                link = story.link,
            ).also { f ->
                when {
                    c.deleted -> { f.deleted = true; f.revAuthor = author(c.delAuth.takeIf { it >= 0 } ?: c.revAuth); f.revDate = dttm(if (c.delDttm != 0) c.delDttm else c.revDttm) }
                    c.inserted -> { f.inserted = true; f.revAuthor = author(c.revAuth); f.revDate = dttm(c.revDttm) }
                    c.crAuth >= 0 -> { f.formatChanged = true; f.revAuthor = author(c.crAuth); f.revDate = dttm(c.crDate) }
                }
            }
        }

        private fun author(i: Int): String? = revAuthors.getOrNull(i)?.takeIf { it.isNotEmpty() }
        private fun dttm(v: Int): Long? = com.therealityteam.aeropages.format.doc.DocReader.dttm(v)

        fun emit(s: String) {
            if (st.chr.hidden) return
            when (st.dest) {
                Dest.BODY, Dest.FLDRSLT, Dest.FOOTNOTE, Dest.HEADER, Dest.FOOTER, Dest.ANNOTATION -> Unit
                else -> return
            }
            val f = chrFormat()
            if (story.runChr != f) { endRun(); story.runChr = f }
            story.text.append(if (st.chr.caps) s.uppercase() else s)
        }

        fun endRun() {
            val f = story.runChr ?: return
            if (story.text.isNotEmpty()) story.runs.add(TextRun(story.text.toString(), f))
            story.text.setLength(0)
            story.runChr = null
        }

        fun paragraphFormat(): ParagraphFormat {
            val par = st.par
            val style = styleOf(par)
            val f = ParagraphFormat(align = par.align, style = style)
            f.indentLeftPt = par.left / 20f; f.indentRightPt = par.right / 20f; f.firstLineIndentPt = par.first / 20f
            f.spaceBeforePt = par.before / 20f; f.spaceAfterPt = par.after / 20f
            if (par.line > 0 && par.lineMult) f.lineSpacing = (par.line / 240f).coerceIn(0.5f, 5f)
            else if (par.line == 0) f.lineSpacing = 1f
            // \sl without \slmult1: twips, at least when positive, exactly when negative.
            else { f.lineSpacing = 1f; f.lineHeightPt = kotlin.math.abs(par.line) / 20f; f.lineExact = par.line < 0 }
            if (par.keepNext) f.keepWithNext = true
            f.keepTogether = par.keepLines; f.pageBreakBefore = par.pageBreakBefore
            if (par.ls > 0) listFor(par.ls)?.takeIf { !it.second.level(par.ilvl).blank }?.let { (id, def) ->
                f.listId = id; f.listLevel = par.ilvl
                f.listKind = if (def.level(par.ilvl).format == NumFormat.BULLET) ListKind.BULLET else ListKind.NUMBER
            }
            return f
        }

        fun styleOf(par: Par): ParagraphStyle {
            val n = styleNames[par.style]?.lowercase()?.replace(" ", "")
            return when {
                n == "heading1" || n == "标题1" || par.outline == 0 -> ParagraphStyle.HEADING1
                n == "heading2" || n == "标题2" || par.outline == 1 -> ParagraphStyle.HEADING2
                n == "heading3" || n == "标题3" || par.outline == 2 -> ParagraphStyle.HEADING3
                n == "title" || n == "标题" -> ParagraphStyle.TITLE
                else -> ParagraphStyle.NORMAL
            }
        }

        /** [ls]'s list: the overrides of one list share a list of the app's. */
        fun listFor(ls: Int): Pair<Int, ListDef>? {
            val lid = overrides[ls] ?: return null
            val levels = listDefs[lid] ?: return null
            val base = if (levels.firstOrNull()?.format == NumFormat.BULLET) ListDef.BULLETS else ListDef.NUMBERS
            val def = ListDef(List(9) { levels.getOrNull(it) ?: base.level(it) })
            val id = lsIds.getOrPut(lid) { doc.newListId().also { doc.lists[it] = def } }
            return id to def
        }

        fun endParagraph(force: Boolean) {
            endRun()
            if (!force && story.runs.isEmpty()) return
            val pf = paragraphFormat()
            // The paragraph mark's revision: the change in force at its \par.
            if (force) st.chr.let { m ->
                if (m.deleted) pf.revisionMark = com.therealityteam.aeropages.model.RevisionMark(false, author(m.delAuth.takeIf { it >= 0 } ?: m.revAuth), dttm(if (m.delDttm != 0) m.delDttm else m.revDttm))
                else if (m.inserted) pf.revisionMark = com.therealityteam.aeropages.model.RevisionMark(true, author(m.revAuth), dttm(m.revDttm))
            }
            val p = Paragraph(ArrayList(story.runs).ifEmpty { mutableListOf(TextRun("", chrFormat().also { it.deleted = false; it.inserted = false; it.formatChanged = false; it.revAuthor = null; it.revDate = null })) }.toMutableList(), pf)
            story.runs.clear()
            takeMarks(p)
            if (st.par.inTable && story === body) { cellParas.add(p); return }
            if (story === body) endTable()
            story.blocks.add(ParagraphBlock(p))
        }

        fun endCell() {
            endRun()
            if (story.runs.isNotEmpty() || cellParas.isEmpty()) {
                cellParas.add(Paragraph(ArrayList(story.runs).ifEmpty { mutableListOf(TextRun("", chrFormat())) }.toMutableList(), paragraphFormat()).also { takeMarks(it) })
                story.runs.clear()
            } else cellParas.lastOrNull()?.let { takeMarks(it) }
            val r = row ?: TableRow().also { row = it }
            r.cells.add(TableCell(ArrayList(cellParas)).also { it.images.addAll(cellImages) })
            cellParas = ArrayList(); cellImages.clear()
        }

        fun endRow() {
            endRun()
            val r = row ?: return
            row = null
            val t = table ?: Table(mutableListOf(), borders = false).also { table = it }
            // Merges: a cell continuing one to its left goes; one continuing the cell above is covered by it.
            val cells = r.cells
            val kept = ArrayList<TableCell>()
            cells.forEachIndexed { k, cell ->
                if (cellHMerge.getOrNull(k) == 2 && kept.isNotEmpty()) { kept.last().gridSpan++; return@forEachIndexed }
                if (cellVMerge.getOrNull(k) == 2) {
                    cell.vMerged = true
                    // The cell this one continues: the first of its run in the rows above.
                    for (above in t.rows.asReversed()) {
                        val a = above.cells.getOrNull(kept.size) ?: break
                        if (!a.vMerged) { a.rowSpan++; break }
                    }
                }
                kept.add(cell)
            }
            r.cells.clear(); r.cells.addAll(kept)
            if (rowBorders) t.borders = true
            t.rows.add(r)
            tableRowsRights.add(ArrayList(cellRights))
        }

        fun endTable() {
            val t = table ?: return
            table = null
            if (row != null) endRow()
            // Column weights from the widest row's cell edges.
            val rights = tableRowsRights.maxByOrNull { it.size } ?: emptyList()
            tableRowsRights.clear()
            var left = 0
            rights.forEach { r -> t.columnWidths.add((r - left).coerceAtLeast(1).toFloat()); left = r }
            val cols = t.rows.maxOfOrNull { row -> row.cells.sumOf { it.gridSpan } } ?: 0
            while (t.columnWidths.size < cols) t.columnWidths.add(1f)
            body.blocks.add(TableBlock(t))
        }

        fun picture() {
            val type = pictType ?: run { aside.setLength(0); return }
            val hex = aside.filter { it.isLetterOrDigit() }
            aside.setLength(0)
            if (hex.length < 8) return
            val data = ByteArray(hex.length / 2) { k -> hex.substring(k * 2, k * 2 + 2).toInt(16).toByte() }
            // Its size: the goal (twips) scaled, else the picture's own (pixels for PNG/JPEG, hundredths of a millimetre for metafiles).
            val wPt = if (picGoalW > 0) picGoalW / 20f * picScaleX / 100f else if (type.startsWith("image/png") || type.startsWith("image/jpeg")) picW * 0.75f else picW / 2540f * 72f
            val hPt = if (picGoalH > 0) picGoalH / 20f * picScaleY / 100f else if (type.startsWith("image/png") || type.startsWith("image/jpeg")) picH * 0.75f else picH / 2540f * 72f
            val bytes = if (type == "image/wmf") placeable(data, (wPt * 20).toInt(), (hPt * 20).toInt()) else data
            val img = ImageBlock(bytes, type, wPt.coerceAtLeast(1f), hPt.coerceAtLeast(1f))
            if (st.par.inTable && story === body) { cellImages.add(img); return }
            endParagraph(force = false)
            story.blocks.add(img)
        }

        fun linkOf(inst: String): String? {
            val m = Regex("""HYPERLINK\s+(?:"([^"]*)"|(\S+))(?:.*?\\l\s+"([^"]*)")?""").find(inst.trim()) ?: return null
            val url = m.groupValues[1].ifEmpty { m.groupValues[2] }.replace("\\\\", "\\")
            val place = m.groupValues[3]
            return when {
                url.isNotEmpty() && place.isNotEmpty() -> "$url#$place"
                url.isNotEmpty() -> url
                place.isNotEmpty() -> "#$place"
                else -> null
            }
        }
    }

    /** A WMF as RTF holds it (no placeable header) given one: its bounds [w] × [h] twips. */
    private fun placeable(wmf: ByteArray, w: Int, h: Int): ByteArray {
        if (wmf.size >= 4 && (wmf[0].toInt() and 0xFF) == 0xD7 && (wmf[1].toInt() and 0xFF) == 0xCD) return wmf
        val head = ByteArray(22)
        fun put16(o: Int, v: Int) { head[o] = v.toByte(); head[o + 1] = (v ushr 8).toByte() }
        put16(0, 0xCDD7); put16(2, 0x9AC6); put16(10, w); put16(12, h); put16(14, 1440)
        var sum = 0
        for (k in 0 until 10) sum = sum xor ((head[k * 2].toInt() and 0xFF) or ((head[k * 2 + 1].toInt() and 0xFF) shl 8))
        put16(20, sum)
        return head + wmf
    }

    /** A Windows code page; null when Java does not know it. */
    private fun cp(n: Int): Charset? = runCatching {
        when (n) {
            936 -> Charset.forName("GBK"); 950 -> Charset.forName("Big5"); 932 -> Charset.forName("windows-31j"); 949 -> Charset.forName("x-windows-949")
            65001 -> Charsets.UTF_8; 10000 -> Charset.forName("x-MacRoman")
            else -> Charset.forName("windows-$n")
        }
    }.getOrNull()

    /** A font's `\fcharset` as its code page (2: Symbol, its bytes as they are). */
    private fun charsetOf(n: Int): Charset? = when (n) {
        0 -> cp(1252); 2 -> Charsets.ISO_8859_1; 77 -> cp(10000); 128 -> cp(932); 129 -> cp(949); 134 -> cp(936); 136 -> cp(950)
        161 -> cp(1253); 162 -> cp(1254); 163 -> cp(1258); 177 -> cp(1255); 178 -> cp(1256); 186 -> cp(1257); 204 -> cp(1251); 222 -> cp(874); 238 -> cp(1250)
        else -> null
    }
}
