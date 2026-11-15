# Reality Office features

**Word** — paragraph and character styling, a real style sheet (styles.xml is read with its
basedOn chains and document defaults, drives the formatting of foreign documents, can be modified
from the ribbon and is written back), find and replace, proofing (Review → Proofing asks the
device's spell checker — LatinIME's on PICO — for the words it does not know and lists each with its
suggestions, one tap putting one in with the word's own formatting, Ignore skipping a word; Latin text
is checked in English when the system language has no dictionary, so a Chinese device still checks
English, and repeated words and double spaces are found as well; without a spell checker it says
only those ran), review with tracked changes attributed
to reviewers (one colour per author, accept/reject per change or per author, `w:author`/`w:date`
and `w:rPrChange` in docx; a paragraph mark's own change (`w:pPr/w:rPr/w:ins|del`) so accepting a deleted paragraph or
rejecting an added one removes it; Track Changes on is the document's own setting (docx `w:trackRevisions`, ODT
`text:track-changes`), so a document opens recording as it was saved, the status bar saying so while it is on; Lock
Tracking (Review ▸ Tracking, Word's `w:documentProtection w:edit="trackedChanges"`, LibreOffice's `RedlineProtectionKey`
in ODT) keeps it on with Track Changes and Accept / Reject dimmed — a lock Word set with a password is kept as written and
not taken off here (Word's legacy password hash can't be checked); on a change, the text's More menu has Word's Accept /
Reject Insertion, Deletion or Format Change for the part selected (the whole change at the caret); in ODT as LibreOffice keeps them — `text:tracked-changes` with each insertion, deletion
and format change, the text as accepting the deletions gives it (a deletion's text in its region, a deleted paragraph mark
the break between two of its paragraphs), an inserted mark an insertion running into the next paragraph — where ODT used
to write deleted text back as ordinary text and read LibreOffice's changes as if accepted; Review ▸ Compare: pick the revised document and the label, and a Compare Result document
opens in a window of its own with the differences tracked — paragraphs matched by text, a changed one word by word (CJK by
character; as in Word, the words removed in one place come before those added), keeping formatting — text formatted
otherwise a format revision, a table of the same rows and cells compared cell by cell — and the status bar counting
insertions, deletions and formatting; More >> holds Word's comparison settings, remembered on the device: Formatting, Case
changes, White space and Tables can be left out, and changes can show at character level ("colo[u]r") instead of by word; a format revision remembers how the text looked before it (docx `w:rPrChange`'s
properties, read and written — we used to write an empty one, which Word reads as "no formatting before"), so rejecting it
brings that look back, and the revisions pane says what it changed (Bold, Not Italic, Font Size: 14); Review as Word lays it out — Display for Review: All Markup, Simple Markup (the changes
taken, a red bar beside each changed paragraph), No Markup, Original — and Show Markup ▾ (Comments, Insertions and
Deletions, Formatting, Specific People: a change left out reads as taken, Next / Previous skip it, Accept ▾ / Reject ▾
offer "All Changes Shown", and "Accept/Reject All Changes and Stop Tracking") — on screen and in the PDF, which used to print a
deletion as ordinary text and now prints the markup or leaves it out as the display does; Accept ▾ / Reject ▾ — the
change at the caret and on to the next, that change, all —, Previous / Next change; text typed next to a change is not
part of it, and typed with tracking off is plain) and comment threads (replies and resolved state through Word's
commentsExtended part; a mark in the right margin beside each commented paragraph opens the pane on it, Previous /
Next comment, Delete ▾ — this one or all of them; a new comment is on the selected text, across paragraphs too, or
with nothing selected on the word at the caret, as Word's are — tinted on the page (the current one darker), its range
keeping to its text through every edit and selected when Previous / Next goes to it —, kept as Word's
`commentRangeStart` / `commentRangeEnd` and, in ODT, as LibreOffice's `office:annotation` … `office:annotation-end`
with replies naming their thread (`loext:parent-name`) and the resolved flag; ODT used to drop comments altogether),
pictures inside table cells (kept in their cell in docx and ODT alike),
cropped pictures (Word's crop read from and written to `a:srcRect`, ODT's `fo:clip`; the ⌗ corner
button opens the same Crop dialog as Slides, the picture keeping its scale; the page and the PDF
show only the kept part), zoom that keeps a picture's proportions, different odd/even and first-page headers
and footers (headerReference types, `w:titlePg`, `w:evenAndOddHeaders`), an outline navigation
pane whose selected heading offers Word's outline tools (promote / demote the level between
Heading 1 and 3, move the heading with everything under it above or below its sibling section,
each one undo step), page canvas rendering, and section breaks: page size, orientation, margins and column count
belong to a section rather than the document, so a landscape or two-column stretch sits inside a
portrait document. Breaks ride on the paragraph that ends the section (OOXML `w:pPr/w:sectPr`), so
they move with the text and undo with it, and the canvas and the PDF export both lay out each
section with its own geometry. The Layout tab sets them as Word's does: Margins (Normal — 2.54 cm above and
below, 3.18 cm at the sides in Chinese and Japanese, 2.54 cm all round otherwise — Narrow, Moderate, Wide),
Orientation, Size (A4, A3, A5, B5, 16K, 32K, Letter, Legal, Executive) and Columns are galleries of small pages
drawn as each choice lays the page out, with its measurements in centimetres (inches in the United States) and
the current one marked; Breaks inserts a page break or a section break, or takes out the caret's section break;
indents and the space before and after paragraphs step beside them. Page Setup sets the margins to the
hundredth (typed, or a tenth at a press), the paper's width and height, the header's and footer's distances
from the edge, different odd and even and first pages, and the document grid (none, or so many lines a page),
a preview of the page drawn beside them, for the caret's section or the whole document as one step back.
(The tab used to show every size, margin and column choice as a row of buttons, Normal margins were 2.54 cm
all round in every language, and nothing set them to the hundredth.) ODT keeps them as LibreOffice does: each distinct section page a
page layout (its text columns among its properties) and a master page of its own, the first
section's `Standard`, and each later section's first paragraph naming its master
(`style:master-page-name`, which starts new pages — even of the same master, Word's next-page
break); reading, a block naming a master starts a section, and the columns of a `text:section`
(how LibreOffice saves a Word section's) are its section's. Before, ODT kept one page for the whole
document and no columns. Footnotes and endnotes: footnotes are anchored by superscript `[1]`
markers and printed at the bottom of the page that holds the anchor; endnotes use roman markers
(`[i]`, `[ii]`) and are collected after the body, so the two never renumber each other. Both are
edited in the References pane, round-trip through Word's `footnotes.xml` / `endnotes.xml`
(`w:footnoteReference` / `w:endnoteReference`) and ODF's `text:note` with its note class, and are
drawn by the canvas and the PDF export. Long tables flow across pages row by row, and rows marked
as header rows (Insert → Header Row; Word's `w:tblHeader`, ODF's `table:table-header-rows`) repeat
at the top of every continuation page on the canvas and in the PDF. Text boxes (Word's `wps`
shapes — read once even though Word repeats them in an `mc:Fallback` — legacy VML text boxes, and
ODF `draw:text-box` frames) keep their paragraphs as a framed block after the paragraph that
anchored them, editable like any other paragraph, and are written back as wps / draw:text-box.
Word's drawn shapes — rectangles, rounded boxes, ovals, stars, arrows, lines and connectors, with
or without text — used to be dropped; now each is kept in the flow as that block with its look
(read by the same DrawingML reader as slides and sheets: theme-coloured style fills and rules,
gradients, dashes, arrowheads, turn and flips, its text white where Word's style says so and placed
top, middle or bottom), drawn by the slides' painter on the canvas and in the PDF, and written back
as wps shapes (a line as a connector) and in ODT as `draw:custom-shape` / `draw:line` — ODT's own
shapes are read too, where their text used to be run into the paragraph that held them;
Insert → Text Box adds one. Insert → Symbol (in Pages, Sheets and Slides alike) shows the symbols used
last, then common ones, and More Symbols… opens Word's Symbol dialog: fifteen subsets (Chinese
punctuation, currency, maths, super- and subscripts, enclosed numbers, arrows, shapes, Greek, pinyin
tones, letterlike symbols and units…), a search by Unicode name or code (`arrow`, `00B0`, `U+2192`),
the picked character large with its name and code, and Insert that keeps the dialog open; the
recently used list is kept on the device. In Sheets the symbol goes after what the active cell holds
and leaves it in editing. The table of contents lists Heading 1–3 (Word's default) with dotted
leaders and page numbers on the canvas and in the PDF (a second layout pass numbers the headings'
real pages), each line jumping to its heading; it is written as Word's contents control with a
`TOC \o "1-3" \h \z \u` field marked for update, and as ODF's `text:table-of-content`, and both
— including contents made by Word or LibreOffice — open again as a live table of contents rather
than copied lines; References → Table of Contents puts it where the caret is. Its page numbers — and page
references' (PAGEREF) and the table of figures' — are the pages as their sections number them (front matter i, ii,
the body from 1), and the saved file holds them as the field's last result (the pages the canvas laid out), with
LibreOffice's Contents and index paragraph styles in ODT (a dotted leader to the right edge), so LibreOffice and
previews that show the stored entries show the numbers too (they used to show none). Captions and cross-references
as Word makes them: Insert Caption (the label — Figure, Table, Equation, those the document numbers, a new one —,
below or above the picked picture or the table the caret is in, numbered 1, a, A, i or I, with or without its label)
writes a Caption-style paragraph whose number is a sequence field (`SEQ Figure \* ARABIC`), and the captions
renumber as one is put before them. "Include chapter number" puts the Heading 1's number and a separator (- . : — –)
before it, as Word does — `STYLEREF 1 \s`, the separator, `SEQ Figure \* ARABIC \s 1` — so each chapter numbers its
own (Figure 1-1, 1-2, 2-1); an unnumbered chapter shows 0 as in Word (the dialog says to number the headings), and
in ODT it is LibreOffice's chapter-numbered sequence (`display-outline-level`, `separation-character`), which
LibreOffice shows the same way; Cross-reference refers to a heading, a bookmark or a caption — its text, a
caption's label and number or its text only, its number, its page, above or below — as a REF or PAGEREF field naming
a hidden `_Ref…` bookmark round what it shows, linking to it when asked; Update Fields (and F9) works them all out
again, pages as the canvas lays them out. Fields read as a light grey ground (LibreOffice's shading); text typed after
one is not part of it. They are kept as Word's complex fields in docx (read from Word's and LibreOffice's), as
`text:sequence` / `text:bookmark-ref` in ODT (as LibreOffice writes Word's), and read from .doc. Insert Table of
Figures lists a label's captions (each whole caption, a dotted leader, its page, a link to it — no heading of its own,
as Word's has none), shown in the dialog as it will list them before it goes in at the caret; one per label (inserting
it again moves it); References → Remove takes out the table of contents. It is a live list like the contents: in
docx a `TOC \h \z \c "Figure"` field in a content control with Word's "table of figures" paragraph style (which
LibreOffice needs — without it LibreOffice ran the entries into one paragraph), in ODT LibreOffice's illustration
index (a table index for tables) of that caption sequence; LibreOffice opens either as its own live index.

An index, as Word's References ▸ Index: Mark Entry opens the Mark Index Entry pane beside the document (Word keeps
its dialog open while one selects text): the main entry follows the selection, a subentry may go under it; Mark puts
the entry right after the selection (where Word puts its XE field), Mark All after the selected text in every
paragraph it appears in; the pane lists the entries marked (tap one to go to it, its bin takes the mark out). An
entry rides along with its text as bookmarks do and goes when the text round it is deleted. Insert Index (a dialog
with a preview of the document's own entries) puts a live index at the caret, or lays out anew the one there: main
entries sorted as the language sorts words, subentries indented under them, each with its pages after a comma — or
right-aligned after a dotted leader — and, when asked, a letter heading before each letter (accents off, digits under
"#"; scripts without capitals go on unheaded). In docx the entries are XE fields and the index an INDEX field
(`\e` for the tab, `\h "A"` for headings) holding the lines as last laid out in Word's index 1 / index 2 / index
heading styles; Word's own index read back is one live index, its section kept (Word sets it in two columns). In ODT
they are LibreOffice's alphabetical index marks (a subentry's main entry as `text:key1`; LibreOffice's range marks are
read too) and an alphabetical index with its Index 1–3 and Index Separator styles; LibreOffice reads both, and its own
conversions come back as one index with the entries.

Find and Replace is Word's, beside the window, on three tabs (Home ▸ Find, Replace, Go To; Ctrl+F, Ctrl+H, Ctrl+G):
Find with Match case, Find whole words only and Use wildcards — Word's `?`, `*`, `[a-z]`, `[!x]`, `{n,m}`, `@`, `<`,
`>`, groups and `\x` — and its special characters a click away (`^t` tab, `^?` any character, `^#` any digit, `^$` any
letter, `^w` white space, `^l` line break), which match of how many is selected ("第 3 项，共 12 项"), Previous /
Next round the document; Replace and Replace All with `^&` (what was found) and `\1`… (a wildcard group) in the
replacement, in the found text's look, as tracked changes while Track Changes is on (text deleted with it is no
match), Replace All one undo step; Go To a page by number (or + / − pages from the caret), the next or previous
heading, table or comment, or a bookmark. While searching, every match is shaded yellow on the page, and the navigation
pane searches the same: its search box, then Headings or Results — each match in a few words of its paragraph, click to
go there.

Insert ▸ Table is Word's size grid (point at "4×3 表格", click it) with Convert Text to Table…: the selected
paragraphs split at tabs (chosen when they have them), commas of either width, spaces, paragraph marks or a character,
the columns that makes (more or fewer — extra fields stay in the last cell) and the rows it will have, each cell keeping
its text's look; the Table tab's Data group has Convert to Text (cells joined by a paragraph mark, tab, comma, space or
character; a cell's own paragraphs stay paragraphs) and Sort (by a column named by its header, as text in the
language's order — Chinese by Pinyin —, numbers or dates in their usual ways of writing, ascending or descending, the
header row staying first; empty cells last; a table with cells spanning rows cannot be sorted, as in Word — Home ▸
Paragraph ▸ Sort opens it in a table and Word's Sort Text elsewhere: the selected paragraphs, or the run the caret is in,
by up to three keys — the whole paragraph or a field split at tabs, commas, spaces or a character — as text (with or
without case), numbers or dates, ascending or descending, a header line kept on top, blank lines first as Word puts
them; whole paragraphs move, their look, lists, bookmarks and comments with them, one undo step), and Formula:
Word's `=` field in a cell — =SUM(ABOVE) under numbers, =SUM(LEFT) beside them as its first guess, SUM, AVERAGE,
COUNT, MAX, MIN, PRODUCT over ABOVE / LEFT / BELOW / RIGHT, cells and ranges (B2:C4), ABS, INT, ROUND, MOD, + − × ÷,
its `\#` number format (0.00, #,##0.00, ¥#,##0.00), the result shown as it is set up; kept live (Update Fields, F9:
a total of totals reads the products worked out first), Word's own field in docx, LibreOffice's `text:table-formula`
in ODT (cells in angle brackets, ABOVE as the range it takes) — and LibreOffice works ours out (the ODT files now
declare its formula namespaces, which they never did: undeclared, it read every such formula as 0). Each is one undo
step.

Review ▸ Chinese Conversion (中文简繁转换) is Word's, in Pages, Sheets and Slides alike when the app speaks Chinese:
繁转简, 简转繁 and the dialog (which way, and 转换常用词汇 — the everyday and computing words the mainland and Taiwan
say differently, 软件 / 軟體, 网络 / 網路, 打印机 / 印表機…, longest first, the ambiguous ones one way only). The
characters are the device's ICU transliterators (Hans-Hant, Hant-Hans); each run keeps its look even when a word
changes length (内存 → 記憶體), one undo step. Pages converts the selection or the whole document with its headers
and footers, Sheets the selected cells (the whole sheet when one is selected; formulas and locked cells left alone),
Slides the selected text or every box and the speaker notes.

The View tab is Word's: Print Layout or Web Layout; Show ▸ Ruler and Navigation Pane; Zoom ▸ the zoom menu, 100 %,
One Page (the whole page in the window), Multiple Pages (pages side by side, more across as one zooms out) and Page
Width; Word Count moved to Review ▸ Proofing, beside spelling. The ruler stands over the page the caret is on: the
margins shaded, centimetres (inches in the US) counted out from the column's edge, and the caret's paragraph's
first-line indent, hanging indent, left indent (the box that moves both) and right indent, and its tab stops, with
the default stops as small ticks after them. Drag a marker and the paragraphs follow on release — snapped to quarter
centimetres (sixteenths of an inch), a dotted line down the page and a label with the value while dragging, one undo
step; click the strip to set a tab stop of the kind in the corner box (click it for left, centre, right, decimal), drag
a stop along to move it or down off the ruler to clear it, double-click one for the Tabs dialog (also Layout ▸ Tabs…):
the paragraph's stops listed, a position, alignment and leader to set, clear or clear all, and the default spacing.

Pages break the same way on screen and on paper: the canvas measures every
paragraph exactly as it draws it, and one page-flow model shared with the PDF export decides the
breaks — a paragraph continues on the next page between two lines with Word's widow/orphan
control, headings keep with the paragraph after them, tables break between rows, footnotes are
reserved at the foot of the page that references them, and multi-column sections fill their
columns left to right. Both sides use the same line pitch (the line's largest font × its face's own
single line — one em for the classic Chinese and Japanese faces such as 宋体, 黑体, 仿宋, MS Mincho, 1.15 for
Times New Roman and Arial, 1.22 for Calibri, 1.32 for 微软雅黑, 1.2 for a face not known — × the paragraph's
line spacing, so 1.5 and double spacing show while editing; on a section's line grid — Word's
`w:docGrid` of lines, .doc's sprmSClm / sprmSDyaLinePitch, ODF's line layout grid, which most Chinese
documents have at 15.6 pt — each body line takes whole pitches, a paragraph off the grid
(`w:snapToGrid w:val="0"`, sprmPFUsePgsuSettings, `style:snap-to-layout-grid`) its own height; the grid
and the paragraphs off it are written back to docx and ODT; exact and at-least line spacing — Word's
`w:lineRule="exact"` / `"atLeast"`, .doc's LSPD in twips, RTF's `\sl` without `\slmult`, ODF's `fo:line-height`
as a length and `style:line-height-at-least`, in paragraphs and styles — are heights of their own, an exact one
whatever the letters and the grid (they used to be read as multiples: a bid document's 21 pt lines came out
1.75 lines tall), and are written back as such), paragraph spacing, indents, list
numbers that keep counting across pages, cell and picture geometry, and break CJK text between
characters with the usual punctuation rules, so the page count, the line breaks and the contents'
page numbers on screen are the PDF's. Every PDF the app makes — pages, sheets, slides — keeps its
text whole for copying and search, its letters placed at their exact (fractional) advances: PICO
Sans takes I, i, J, j and l through its required ligatures to glyphs no character maps to, so
those are left out on paper wherever the script needs none (Arabic, Indic and the like keep them).
The header and footer sit in the page margins as in Word — the header's top the header distance
below the page's edge, the footer's foot the footer distance above it (`w:header` / `w:footer`,
which Word requires and the app now writes) — and keep what Word and LibreOffice put in them:
formatted runs, several lines, the page number and page count as real fields (Word's PAGE /
NUMPAGES / SECTIONPAGES, simple or complex, read without their codes leaking into the text and
written back as fields Word updates; ODF's `text:page-number` / `text:page-count`), and tabs that
place text in the left, centre and right slots of Word's Header and Footer styles ("Title⇥⇥Page 3"
puts the number at the right margin; absolute tabs go to their slot). A header shows on every page
as it prints, dimmed as Word dims it, and a tap on any page opens it for typing — a line with tabs
as three fields where its left, centre and right slots stand, each changing only its part, so the
runs' formatting survives typing (it used to come back as one plain run); the PDF draws it the same
way. Insert → Header and Footer offer Word's built-in ones (Blank, Blank (Three Columns), Title and
Page Number, Page X of Y), drawn on a small page: picking one fills it in and opens it with its
"[Type here]" selected, so typing replaces it; Edit and Remove are beside them. Insert → Page Number
puts the number at the top or bottom of the page, left, centred or right (six small pages, the
current place marked), as 1, Page 1 or Page 1 of 3, next to what is already there — a centred
"Confidential" stays centred with the number at the right margin, a taken slot gives the number a
line of its own — and moves it rather than adding a second; the section's number format and start
are in the same panel, and Remove Page Numbers takes them out again (a header or footer left empty
goes). It used to replace the whole footer with "Page X of Y". ODT keeps the even pages' and first page's headers (`style:header-left`,
`style:header-first`) and the distances, read and written as LibreOffice converts Word's (the
page's top margin the header distance, the header area reaching down to the body), and a Word
watermark — a VML text path in the header — opens as the document's watermark. Pictures in headers
and footers — a letterhead's logo, a banner on the first page, a mark beside the page number —
open where they stand on every page their header shows on (Word's anchored drawings in the header
parts, through each part's own relationships; ODF's frames in the master page's header paragraphs),
drawn under the page's text on screen and in the PDF. A picture standing in a header's line keeps
its line: the header is laid out line by line, a line as tall as its tallest picture with the text
on its foot, so a logo in a paragraph of its own sits above the address rather than over it, each
line's pictures and text placed together as its paragraph aligns them (a line of pictures alone as
its own paragraph did). A header or footer that reaches past its margin — several lines, a tall
logo, or a floating picture the text keeps clear of — pushes the body down (or ends it higher) as
Word and LibreOffice do, on screen and in the PDF, and footnotes stand above such a footer; one
standing mid-page keeps the text out of its stretch. They are written back: each header or footer a
part of its own in Word, a paragraph per line, pictures in line in their line's paragraph (`wp:inline`,
the line centred as it was) and floating ones anchored to the first (a first page's header holding
only a logo too); in ODT a paragraph per line in the master page's header, `as-char` frames in their
line and anchored frames in the first (their styles among styles.xml's automatic styles). Over a
header that holds only pictures the "Add header" hint shows only while pointed at. Tapping a
paragraph puts the caret where it was tapped. Selections run across paragraphs: press and
hold a word to select it and drag on to stretch the selection over the following (or preceding)
paragraphs — the pages scroll when the pointer nears the top or bottom — then drag the handles at its
two ends to adjust it — a small glass bar beside such a selection offers Cut, Copy, Paste, Bold,
Italic and Underline right where the eyes are, and Home → Clipboard (Paste, Cut, Copy, Format
Painter) reaches them without a keyboard; Shift+arrows and Shift+tap carry a selection from one
paragraph into the next, Ctrl+A or Home → Select All takes the whole document, and such a selection copies, cuts, deletes (its two ends join, and tables or
pictures between them go too, as in Word; one running into a table only clears the text), is
replaced by whatever is typed, and takes character formatting (Bold & co. follow Word's rule: all
bold → not bold, otherwise bold) and paragraph formatting (alignment, styles, lists, indents, line
spacing, break options) on every paragraph it touches. Arrow keys move the caret from paragraph to
paragraph (Up and Down keep its column), Ctrl+Home / Ctrl+End go to the document's ends, typed or
pasted lines become paragraphs, and wherever the caret moves — the navigation pane, the contents,
Find, arrows, undo — the page scrolls to show it. Long documents open without a pause: the
pages in view are measured first and the rest is refined in short slices between frames, and a
keystroke that leaves the paragraph's lines as they were re-measures only that paragraph. Word's
line and page break options — keep with next (Heading 1–3 by default), keep lines together, page
break before, widow/orphan control — sit under the line-spacing button, apply on screen and in the
PDF, and round-trip through docx (`w:keepNext`, `w:keepLines`, `w:pageBreakBefore`,
`w:widowControl`) and ODF (`fo:keep-with-next`, `fo:keep-together`, `fo:break-before`,
`fo:widows` / `fo:orphans`). Indents follow Word's Paragraph ▸ Indentation under the same button:
left and right indents and a special indent — the first line two characters in (首行缩进) or hanging
out (悬挂缩进, a reference list) — counted in characters of the paragraph's text, as Chinese Word
counts them, with the paragraph's outer edge kept where it was; a style's indents apply with the
style and follow when it is modified. They show on screen and in the PDF and round-trip through
docx (`w:ind` left/right/firstLine/hanging, `start`/`end`, and the `…Chars` character forms Word
and WPS write — two characters of the paragraph's own text size — in paragraphs and in styles),
ODF (`fo:margin-left`/`-right`, `fo:text-indent`, and LibreOffice's `loext:text-indent="2ic"`)
and .doc (the `sprmPDxa…` / `sprmPDxc…` sprms). Each section numbers its pages its own way — 1, i, I, a,
A or 一, from a number of its own or on from the section before (a report's front matter in i, ii …, its
body from 1) — in its headers and footers on screen and in the PDF; Insert ▸ Page number format sets it
for the caret's section, and it is read from docx (`w:pgNumType`), .doc (the section's SEP) and ODT (the
page layout's `style:num-format`, `style:page-number` on the section's first paragraph) and written back
to docx and ODT. Tab stops work as Word's: each tab goes on to the
paragraph's next stop — left, centre, right or decimal, with dot, hyphen, rule or middle-dot leaders
— or to the left indent where the first line hangs out of it ("1.⇥text"), else to the next of the
document's default stops past the last custom one; on screen the tab shows as a space stretched to
exactly that width (the text keeps its length, so carets and selections are unaffected, and the page
measurement agrees), and the PDF lays the same widths out, so a form's values, a contents list's
dotted lines to the page numbers and a column of amounts line up alike on screen, on paper and in
LibreOffice. Stops are read and written in docx (`w:tabs` in paragraphs and styles, clears included;
`w:defaultTabStop`), ODF (`style:tab-stops`, counted from the indent as ODF does unless the document
says otherwise; `style:tab-stop-distance`) and .doc (sprmPChgTabsPapx, the DOP's dxaTab); the Tab key
types a tab, or moves a list item at its start a level in (Shift+Tab out). Lists number as Word and
LibreOffice number them: each list's nine levels have their own format — 1, a, i, A, I, 01, 一 / 二
(十一, 一百零五), 壹 / 贰, 甲 / 乙, 子 / 丑, ①, ⑴, ⒈, full-width digits, bullets — and label ("%1.%2." is
"1.2.", "（%2）" is "（二）", "第%1章", legal numbering showing every level in digits), a level starts
over under the level above unless it says not to, and a list counts on through the paragraphs and
tables between its items (Word's numbered headings, a contract's clauses) while the plain bullets and
numbers start over after anything else. The marker column grows for a long label ("1.1.1."), the
contents list shows the headings' numbers, and Symbol's and Wingdings' bullets show as the characters
they draw. They are read from docx (`numbering.xml`: each `w:num`; the ones sharing an abstract
definition counting on together as Word's do, one restarting or redefining a level a list of its own;
`w:start`, `w:numFmt`, `w:lvlText`, `w:lvlRestart`, `w:isLgl`, the bullet's font; a style's `w:numPr`
numbering its headings, its level from the definition's linked style), ODF (`text:list-style` levels
— `style:num-format` with prefix, suffix and `text:display-levels`, LibreOffice's
`loext:num-list-format`, bullets —, nested lists as levels (their items' text was lost before), lists
continued through `text:continue-list` / `text:continue-numbering`, headings numbered by the
`text:outline-style`, and lists in table cells and text boxes) and .doc (PlfLst / LVL and PlfLfo with
their overrides, `sprmPIlfo` / `sprmPIlvl`, the style sheet's lists for headings, a level's own
indents), and written back to each (Word: one definition per list, each run of plain numbers its own
`w:num` restarting at 1 — one numId used to make every numbered list in a saved file count on from the
one before; ODF: a list style per list with its levels, nested lists, continued parts, headings'
numbers as the outline numbering, so LibreOffice keeps their heading look). Home ▸ Paragraph has the
bullet and numbering libraries next to the two buttons (1. / 1) / 1、 / 一、 / （一） / ① / A. / a) / I.
/ i. / 第一章 / 1.1.1, and •, ◦, ▪, ■, ◆, ➢, ✓, ★, –): a list takes the look throughout, other
paragraphs become a new list; Restart at 1 and Continue numbering split a list or join it to the one
before; numbering a paragraph just after a list makes it that list's next item. Word templates'
MACROBUTTON placeholders ("[Click here and type…]") show their text in docx and .doc. Legacy .doc
(and WPS's .wps / .wpt) read their style sheet (STSH): each paragraph takes its style's paragraph
properties through the chain of styles it is based on, its text its style's character properties under
its own (a run's "as the style" / "opposite of the style" toggles taken against it), and the styles go
into the document's style sheet as the file has them — a Japanese template's 10.5 pt "heading 2" no longer
comes out as the app's 16 pt bold one; their spacing before and after and line spacing are read (Word 97's
paragraphs have none and single spacing, not the app's 8 pt and 1.15), with page break before, keep with
next, keep lines together and widow control; a page-break character (0x0C) that does not end a section
breaks the page, as does a page break in the middle of a docx paragraph. Legacy .doc
files now open with everything their other stories hold, not just the main text: headers and
footers (PlcfHdd; the page number and count as fields; first-page and even-page ones), footnotes
and endnotes with their markers in the text (PlcffndRef/Txt, PlcfendRef/Txt), text boxes after the
paragraph they are anchored in, in the order they stand on the page (the text box story, PlcSpaMom
and the FTXBXS naming the shape), each looking as its OfficeArt shape does — fill, outline and its
dashes, text insets, white automatic text on a dark fill — and each section's page: size, margins,
header and footer distances, columns, a title page (the SEPX of PlcfSed), so A4 documents are A4.
CP-1252 text keeps its curly quotes, dashes and euro signs. Their tables open as tables too (they
used to come apart into a paragraph per cell): cells end at their cell marks and rows at the
paragraphs that end them, whose TAP gives the cells' boundaries (one grid for the whole table, cells
spanning the columns between their own), merges across and down, shading, header rows and whether
any borders are drawn — read from the Data stream when a row's definition is too big for its page;
a nested table's text stays in its outer cell. Table borders are each cell's own, side by side: a
line's kind (single, double, dotted, dashed, dot-dash), width and colour, as Word works them out — a
cell's `w:tcBorders`, else the table's `w:tblBorders` (its outer sides on the table's edges, its inside
ones between cells), else its style's (Word's Normal Table draws none), an edge two cells share taking
the line of the one that set it itself — and from .doc's TC80 BRC80s and table borders, ODF's cells'
`fo:border-*` (a double line's width split in three); one line everywhere is kept as the table's grid,
anything else cell by cell, so three-rule tables, forms of lines to write on, coloured frames and
dashed inside lines print and show as they are (faint blue dashes where a cell has none, to edit by),
and go back to Word as tcBorders and to ODT as each cell's own style. While the caret is in a table a
contextual Table tab shows (Word's Table Design and Layout in one): a style gallery drawn as little
tables — grid, no borders, three-line, light rows, blue / green / orange bands, navy rows — set as the
cells' own shading and borders and the header row's text, so every format keeps it; rows inserted
above or below the caret's row (like it: spans, shading, borders, text format; inside a vertical merge
the merge goes on) and columns left or right of its cell (a cell spanning the new column's place
widens over it); delete row, column or table; merge right or down and split a merged cell; narrower,
wider and evenly distributed columns; the repeated header row. Slides' table rows and columns likewise
go beside the cell being typed in. A table keeps its own width in the text — narrower than the column,
left (indented), centred or right — and its rows their heights (at least, or exactly), from docx
(`w:tblW` in twips, `w:jc`, `w:tblInd`, `w:trHeight`), .doc (its cells' boundaries, sprmTJc,
sprmTDyaRowHeight) and ODT (`style:width`, `table:align`, `fo:margin-left`, `style:min-row-height` /
`style:row-height`), and writes them back; cells are padded and rows sized nearly as Word does (5.4 pt
across, a line at least), so forms and fax sheets fill their page as they do in Word. An empty paragraph
in docx is as tall as its mark's text (`w:pPr/w:rPr`, and written back there), in .doc its paragraph
mark's character properties, in ODT its paragraph style's text properties — as a cover page's spacing lines
are (a WPS thesis cover's empty lines are 16–26 pt; read at 10.5 pt, its text boxes, placed from those
paragraphs, landed on each other). .doc runs also keep their character spacing (sprmCDxaSpace, a
letter-spaced school name), small capitals and highlight, their tracked changes (the revision marks — deleted, inserted,
a property revision — with their authors from SttbfRMark and their dates, and a paragraph mark's own, where a deletion
used to read as kept text, and the document's Track Changes setting), their comments (Word 97's annotations: author from
GrpXstAtnOwners, text, the range each is on from PlcfAtnBkf/PlcfAtnBkl, the date and reply from AtrdExtra when Word
wrote them — they used to be lost; RTF's `\annotation` with `\atnauthor`, `\atndate` and its `\atrfstart` … `\atrfend`
range too), and a text box that does not wrap its text
(WrapText none; docx `wrap="none"`, ODF `fo:wrap-option="no-wrap"`) keeps each line whole, placed as its
paragraph aligns it, on screen and in the PDF. Character spacing now shows in the PDF too, after each
letter as Word spaces it.
Table → Borders draws a line of
the chosen kind, width and colour along the caret cell's bottom, top, left or right (its neighbour's
facing side with it) or clears its sides, or draws all, outside or inside borders of the whole table,
or none — each an undo step; new rows and columns take their neighbours' borders. ODT also keeps a
cell spanning several columns (its covered cells used to read as extra cells). The column-resize handles between cells only show when the eyes or the pointer rest on them.
Links go to places within a document too. Such a place is a bookmark — a named place in the text
(Word's `w:bookmarkStart` / `w:bookmarkEnd`, also between paragraphs and table cells; ODF's
`text:bookmark` / `-start` / `-end`; .doc's SttbfBkmk, PlcfBkf and PlcfBkl), which stays where it
stands as text is typed, deleted, split or joined round it (typed at its start the text goes into it,
at its end after it, as in Word; with changes tracked it stays in the struck text) — and Word's
`w:hyperlink w:anchor`, HYPERLINK fields (`\l` places, an address with a place) and ODF's `#name`
links read and write as links to it. With the caret in a link a bar above it says where the link goes
(an address, a bookmark, or the heading a hidden bookmark marks), opens it — a place here is
selected and scrolled to, a document beside this one opens in a window of its own, an address goes
to the app that opens it — or changes or removes the whole link; Ctrl+click follows one. Insert →
Link goes to a web address or to a place in this document: a heading (through a hidden bookmark round
it, as Word does) or a bookmark; Insert → Bookmark names the selection (or the caret's place) and
lists the bookmarks (Word's hidden ones on request) to go to or delete. Typing at a link's end no
longer stretches the link. A table of contents written to docx or ODT links each entry to its heading
(a `_Toc` bookmark round it), so Word and LibreOffice follow its entries.
Legacy .doc files show their pictures too: inline ones (a picture character whose PICF and shape
are in the Data stream — the size the PICF gives, less its crop, scaled; a picture alone in its
paragraph stands for it, one in a table's cell goes under the cell's text) and floating ones (a
shape anchored in the text whose picture is in the drawing group's store, the BLIP in the
WordDocument stream), at their frame's size and cropped as they are — PNG, JPEG, DIB, TIFF and
EMF/WMF, sizes as LibreOffice shows them; WPS's cover-page templates now open with their artwork.
Pictures on the page no longer carry their buttons all the time: pointed at, a picture is outlined,
and a tap picks it — a frame, and its actions on its corner (smaller, larger, crop, shadow, copy and
a new Delete); Ctrl+C / Ctrl+X copy or cut a picked picture, Delete removes it, a tap in the text
lets it go. Floating pictures, text boxes and shapes now stand where the document puts them: one
behind or in front of the text is drawn over its page — at its offset from, or aligned in, the
page, the margins, a margin's strip or band, its column or its paragraph — taking no room in the
text (cover pages' artwork, corner ornaments, logos, notes at a page's foot); one the text goes
around keeps its place across the column (the text does not yet flow beside it). They are read
from Word (`wp:anchor`: positionH / positionV, the wrap, behindDoc; pictures' flips), from .doc (the
FSPA's frames and wrapping, Word 2000's posh / posrelh / posv / posrelv, fBelowText or the shape's
fBehindDocument, flips and half turns) and from ODF (frames and shapes anchored to a paragraph or
the page: svg:x / svg:y, horizontal / vertical pos and rel, wrap and run-through, mirroring), drawn
on screen and in the PDF, and written back — to docx as anchors inside the paragraph they float
from (no empty paragraph for them), to ODT as frames anchored to it (a place in the top or bottom
margin's band as an offset from the page, which ODF has no frame for); LibreOffice shows both as
we do. A paragraph that only anchors floating objects is kept (it is a line in Word, and they count
from it). .doc WordArt (its text in the shape's properties) shows as a frameless box of its text,
as large as its frame lets it be. Positioned (floating) tables stand where they are put too — a
cover's title table low on the page, a box of figures at the margins' top right — at their own
width: from Word's `w:tblpPr` (and `tblW`, the left border a cell margin left of `tblpX`, as Word
and LibreOffice place it), from .doc's sprmTPc / sprmTDxaAbs / sprmTDyaAbs (with the XAS / YAS
alignments), from an ODF frame whose text box holds the table and from a Word text box holding one
(how LibreOffice keeps both), and back as `w:tblpPr` and as such a frame — a text frame LibreOffice
keeps its table in. Anything placed down the page from other than its paragraph (a positioned
table, a letterhead's logo) is drawn over its page where it stands, and the text keeps clear of it
when it goes above and below it or leaves too little room beside it: the text steps over its height
(paragraphs and tables break around it, a table's header rows not repeating there), on screen and
in the PDF alike — the page it is on decided before the text makes room, so making room never moves
it. Beside a square-wrapped object with room for lines next to it (a logo, a photo — page-placed, or moving
with its paragraph as Word's "Move with text" places one) the text is set beside it, as Word and LibreOffice set
it: the lines it stands beside start past it, or end short of it, on its wider side, Word's 0.13 in from it, and
those below run the full width again — on screen (each such line its own paragraph style, so the text, the caret
and the selection keep the paragraph's offsets, and typing beside it re-lays the lines), and in the PDF (each line
broken at its own width, a justified one spread across it); an object moving with its paragraph is placed from
where that paragraph lands, laid out again while the text beside one object above pushes the next one down.
A picked picture's Wrap Text options (Word's Layout Options) set how the text goes by it — in line
with the text, square, top and bottom, behind or in front of the text, the current one ticked —
and, floating, its place across: left, centred or right. One taken out of line keeps where it
stood (centred just below its paragraph, from which it now floats); a floating one picked comes to
the front, and dragging it moves it — its offsets from the same page, margin or paragraph, one undo
step when let go. Insert → Shapes (the gallery Slides and Sheets use) puts a shape in front of
the text in Word's default look — accent blue, a darker rule, white text in its middle — floating
just below the caret's paragraph, centred in the column, the caret in it to type; it goes to Word
as an anchored shape. Text boxes and shapes get the same treatment as pictures: with the caret in
one (or a shape without text tapped), a bar over its corner offers its wrapping options, a handle
to drag it by over the page and Delete; and a Shape Format tab joins the ribbon while one is
worked on — its fill (or none), its outline's colour, weight and dashes (or none), its shadow, and
smaller / larger — Slides' menus, one undo step each; a plain text box first takes Word's look
(white, a thin black rule). A .doc table whose cells all say "no border" draws none, whatever the table's own borders
say. Charts in a document — Word's (an inline drawing naming a chart
part) and LibreOffice's chart objects — are read with their size and data and drawn on the page
and in the PDF as Slides and Sheets draw charts; a tap picks one to show its data as a column, bar,
line or pie chart, edit the data in a grid, or delete it, and Insert → Chart adds Word's sample
column chart. They go back as chart parts in docx and chart objects (with a local data table) in
ODT, and LibreOffice renders both. Equations from Word (Office Math, `m:oMath` / `m:oMathPara`) and
from LibreOffice (MathML formula objects) are kept: a displayed equation is typeset on the page
and in the PDF by the app's own TeX-style setter — fractions on the math axis, raised and lowered
scripts, radicals, big operators with their limits, brackets stretched to what they hold,
matrices, accents, bars, limits, braces and equation arrays — and can be picked to copy it as
linear text or delete it; an equation in the text reads as formatted runs (italic letters, scripts
raised and lowered) that keep it. Both go back as they came — OMML in docx, a MathML object with a
StarMath annotation (what LibreOffice sets it from) in ODT — until an edit inside an equation in
the text makes it plain text. Insert → Equation types a new one in LaTeX (`\frac{a}{b}`, `x^{2}`,
`\sqrt[3]{x}`, `\sum_{i=1}^{n}`, `\int_0^1`, `\lim_{x \to 0}`, `\left( … \right)`, `pmatrix` /
`bmatrix` / `cases`, accents, `\text{…}`, Greek letters and the usual symbols), with keys for the
structures and symbols and the formula set as it is typed; it goes on its own line or into the text
at the caret (always into the text in a table cell or a text box). The same dialog edits the picked
displayed equation (its Edit action) or the equation in the text at the caret, one undo step each. Legacy `.doc` files open with their text, paragraphs and basic
formatting (bold / italic / underline / strike / size / colour / font from the CHPX pages,
alignment and Heading 1-3 from the PAPX pages).

**Sheets** — a formula engine (233 functions: maths and rounding on Excel's fifteen-digit values, statistics
— PERCENTILE, QUARTILE, CORREL, SLOPE, FORECAST, the normal distribution —, loans and investments — PMT, IPMT,
PPMT, PV, FV, NPV, IRR, XIRR, RATE, NPER, depreciation —, dates and times — TIME, HOUR, WEEKNUM, ISOWEEKNUM,
YEARFRAC, DAYS360, NETWORKDAYS.INTL and WORKDAY.INTL with holidays —, text, lookups — LOOKUP, XMATCH, ADDRESS,
wildcards in MATCH, VLOOKUP and SEARCH — and the dynamic-array UNIQUE / FILTER / SORT; array constants
(`{0.03,0.1;…}`) and arrays worked item by item (`SUMPRODUCT((A1:A9>1)*B1:B9)`, the Chinese income-tax formula);
dependency-tracked incremental recalculation, formula-bar autocomplete and argument hints, Insert Function
(Formulas → fx, and a button per category in the Function Library: financial, logical, text, date and time,
lookup and reference, maths and trigonometry, statistical, information — every function in one, with a line
saying what it does in each of the app's languages; search by name or by those words; Insert starts it in the
active cell, inside a formula being written when the caret follows an operator), AutoSum's menu (sum, average,
count, max, min over the run of numbers above the cell, else to its left, as Excel finds it), Excel's `_xlfn.`
prefixes and LibreOffice's `COM.MICROSOFT.` names handled on save and load; checked against LibreOffice's engine
by a workbook of 532 formulas and the 8,133 formulas of WPS's templates, every difference being one where
LibreOffice departs from Excel) that works with Excel's values:
TRUE and FALSE (comparisons, AND / OR / NOT, the IS functions, IF without a "false" value, typed TRUE
cells), Excel's order across kinds (numbers before text before FALSE and TRUE, a blank as 0, "" or
FALSE, text without case, numbers to fifteen digits so 0.1 + 0.2 = 0.3), strict operators (text in
arithmetic is #VALUE!, unless it reads as a number — `1,234`, `50%`, a date; a blank cell is 0 but a
formula's empty text "" is text), ranges whose TRUE, FALSE
and text SUM and COUNT pass by, criteria that compare numbers with numbers and text with text, and
Excel's error values — #N/A from a lookup that finds nothing, #NAME?, #VALUE!, #REF!, #NUM!, #DIV/0!,
an error written into a formula — with NA, ISNA, ISERR, IFNA catching #N/A alone, ERROR.TYPE,
ISLOGICAL, ISNONTEXT and ISFORMULA; an error in a range counts as a value where Excel's do (COUNTA,
COUNTIF, a lookup passing it by) and fails the rest (SUM). Files keep each formula's value beside it (xlsx `<v>` with its type,
ODS `office:value`, as Excel and LibreOffice do, so previews and scripts that do not work formulas
out see them) and TRUE / FALSE as logical cells (xlsx `t="b"`, ODS booleans with a boolean style,
OpenFormula's `TRUE()` in formulas). Sort orders by values in Excel's order — blanks last either
way, text in the language's collation, formulas following their rows with their references —
from one cell sorting the data around it by that column, a header row guessed as Excel guesses it
(Custom Sort is Excel's Sort dialog: "My data has headers", which names the columns, and levels to add, delete, copy
and move up or down — up to 64, as Excel's —, each a column, what it sorts on — Cell Values, Cell Color or Font Color —
and its order: ascending or descending, or which colour goes On Top or On Bottom, the others keeping their order; a filter
button's menu has Sort by Color too). Data → Remove Duplicates is Excel's dialog: the columns to compare (Select All,
Unselect All, named by the header when "My data has headers"), values compared as Excel does (text without case, 10 the
same as 10.0, formulas by what they give), the duplicate rows deleted within the block — the rows under them moving up,
the cells beside it staying, formulas following the cells that moved and a reference to a deleted one becoming #REF!;
in a table the table shrinks and its total row moves up under the rest, as Excel deletes table rows — and Excel's message of how many went and how many unique rows stay. A cell's long-press menu has Excel's Sort and Filter
submenus: Sort A→Z / Z→A, Put Selected Cell Color (or Font Color) On Top and Custom Sort…; Reapply, Clear Filter From
"…", and Filter by Selected Cell's Value, Color or Font Color (turning the filter on when the sheet has none). A filter's
values have no case, as Excel's: "Paris" and "PARIS" are one item in its list and both show. Pivot tables, cell editing, spelling (Review → Spelling
checks the cells of words on every sheet shown — not numbers or formulas — with the device's spell checker, each typo named by
its sheet and cell, a suggestion put in in the word's own look within a cell of mixed formatting), hyperlinks (to the web or
to a place in the workbook — a cell or range on a sheet, a defined name: Excel's `#Sheet2!A1`, an xlsx hyperlink's
`location`, ODS's `#Sheet2.A1`; a `=HYPERLINK(location, name)` formula is a link too, blue and underlined; the selected
cell's link shows in a tip under it whose arrow follows it — to that sheet with the cells selected, or the browser —, and
Insert → Link picks a sheet and a reference or a defined name; LibreOffice reads and writes them all), page setup
as Excel's Page Layout tab has it — Margins (Normal, Wide, Narrow, each drawn with its six measurements), Orientation,
Size (A4, Letter, A3, A5, B5, Legal, Executive), Print Area, Print Titles, Scaling (a percentage, or fit the sheet, its
columns or its rows on one page — or so many pages wide and tall), gridlines and headings shown and printed, and Page
Setup (page, margins and centring, header and footer from Excel's presets with a preview, the sheet's print area and the
rows and columns repeated on every page) — kept in xlsx (`pageMargins`, `pageSetup` scale and fit with `fitToPage`,
`printOptions`, `headerFooter`, `_xlnm.Print_Titles`; LibreOffice's `paperWidth`/`paperHeight` read too) and ODS (a page
layout and master page per setup: margins, `scale-to` or `scale-to-X`/`-Y`, `style:print` grid and headers, table
centring, the header's and footer's regions with their fields, `table:print-ranges`), and printed so: the PDF now lays
a sheet out at Excel's true sizes (a default column 48 pt, a row 15 pt, text its own size — about ten columns and fifty
rows to an A4 page, as Excel prints it; it used to be columns 72 pt wide and text at three quarters), inside its margins,
scaled or shrunk until it fits its pages, its title rows and columns on every page, gridlines and headings only when they
print, centred when asked, the header and footer (page N of M, the sheet's and file's names, the date) at their
distances from the edges — no longer always the sheet's name, the headings and the grid (print titles in ODS are the
table's header rows and columns, as LibreOffice keeps its rows and columns to repeat), Excel's manual page breaks (Page
Layout ▸ Breaks: insert a break above the active cell's row and left of its column — only across in column A, only down in
row 1 —, remove it, reset them all; they move with their rows and columns, fitting to pages ignores them, and once there
is one the grid draws its pages' dashed lines, solid where a break is manual) and Page Break Preview (View ▸ Workbook
Views or the status bar: the print range framed in blue, its breaks solid or dashed, "Page N" on each page and the cells
beyond it grey; there each break and each edge of the print range can be dragged — an automatic break dragged becomes
manual, one dragged off the range goes, the range's edges set the print area, and a break dragged past what a page holds
shrinks the scale until it does, as Excel does —, with its own zoom, 60 % at first), and the Page order (down, then over — Excel's and LibreOffice's default, which the PDF now follows —, or
over, then down) — kept in xlsx (`rowBreaks`/`colBreaks` with `man="1"`, `view="pageBreakPreview"` and
`zoomScaleSheetLayoutView`, `pageOrder`) and ODS (`fo:break-before="page"` on the rows' and columns' styles,
`style:print-page-order`, Calc's `ShowPageBreakPreview` and `PageViewZoomValue`), as LibreOffice reads and writes them, charts bound to a cell block (they follow edits and
row/column changes, can be re-pointed at a new selection, and are written as real range formulas
Excel keeps live), sparklines (line, column and win/loss mini charts drawn inside a cell from a
range, stored as Excel's `x14:sparklineGroups` and Calc's `calcext:sparkline-groups`), conditional
formats of every kind Excel has — a value compared with one or two values or formulas (blank cells
count as 0, text ranks above numbers, as in Excel), text containing / not containing / beginning /
ending with, blanks, errors, duplicate and unique values, top and bottom N or N percent, above and
below average, dates in a period around today (weeks from Sunday), and formulas worked out at each
cell from the range's top-left (ROW() and COLUMN() counting from the cell) — each giving its matching
cells a fill, a text colour, bold, italic, underline or strikethrough, the sheet's rules applied in
their order (the first to set a property wins, Stop If True ends the search), plus two- and
three-colour scales (min, max, number, percent, percentile or formula points), gradient data bars
(their shortest and longest lengths as Excel 2007 or 2010 sets them) and twenty icon sets (arrows,
flags, traffic lights, signs, symbols, stars, triangles, ratings, quarters, boxes, drawn as paths,
reversed or shown without the value); drawn on the grid and in the PDF, worked out once per sheet
content (a range's ranks, average and repeats computed once, each cell's result kept), and read and
written in xlsx with every rule of every block over every range of its sqref in priority order, the
dxf's font and fill (theme and indexed colours too), Excel 2010's extensions (`x14:conditionalFormatting`
rules and a data bar's lengths) and Excel's own formulas beside the text, blank, error and date rules,
and in ODS as LibreOffice keeps them (`calcext:conditional-formats` at each table's end — conditions such
as `between(…)`, `contains-text(…)`, `top-elements(…)`, `formula-is(…)` relative to their base cell,
`calcext:date-is`, colour scales, data bars and icon sets — their looks named cell styles, found by
LibreOffice's display names); Home → Conditional Formatting builds them as Excel's menu does —
Highlight Cells Rules (values against a number, quoted text or a formula, text, dates, duplicates,
uniques, blanks, errors), Top/Bottom Rules, Data Bars, Color Scales and Icon Sets with previews, and
formulas (one that cannot be worked out at the selection's corner is pointed out), each in one of
Excel's five looks — and Manage Rules lists the selection's or the sheet's rules with their looks and
ranges, to move up or down, stop if true, delete, or clear from the selection or the sheet (each an
undo step). Rules go where their cells go, as in Excel: inserted and deleted rows and columns move a
rule's range and its formula with it (references to other sheets' cells follow those sheets'
changes too); Paste and Paste Special → Formats put the copied cells' rules over the cells pasted
onto (a rule's formula following them), Clear All and Clear Formats take them off (the rest of a rule
stays), Cut moves them, and the fill handle, Fill Down and Fill Right carry them into the filled cells
— growing the rule the fill continues, so a top-10 or a colour scale still sees all of its range.
Defined names and sparklines follow inserted and deleted rows and columns as well, and TRUE and FALSE
work as values in formulas (`=IF(A1>5,TRUE,FALSE)`); pictures
over the cells (Insert → Picture: placed at the active cell at their natural size; a tap picks one
to drag it, resize it in proportion from a corner, nudge it with the arrow keys, crop it, reset its
size, duplicate it with Ctrl+D or delete it; each moves with its anchor cell as rows and columns
come and go, prints in the PDF, and is kept as Excel's one-cell anchor with an `a:srcRect` crop in
xlsx and, as LibreOffice stores it, as a frame inside its anchor cell with an `fo:clip` in ODS),
shapes, text boxes and lines over the cells (Insert → Shapes: rectangle, rounded, oval, triangle,
star, arrow, line, Line Arrow and Line Arrow: Double; Insert → Text Box, typed into straight away; a
tap picks one to drag it, resize it freely from a corner, nudge it, edit its text — Enter or F2 —,
give it a fill or outline colour, an outline weight and dashes and a line's arrowheads, duplicate
or delete it; they move with their anchor cells and print in the PDF; Excel's own
drawings are read — `xdr:sp` and `xdr:cxnSp`, groups through their transform, colours from the
shape style's references and the workbook theme, rich text with its levels and breaks — where they
used to be dropped, and written back; ODS keeps them in their anchor cells as Calc does), Excel's
sheet-tab commands (a tap on the active tab, marked ▾, opens them: Rename — every formula, validation
rule and defined name naming the sheet follows, in any script, and names Excel refuses are refused
with the reason —, Move left or right among the tabs shown, Duplicate as "Name (2)" right after it
with its tables renamed and its formulas following them, Hide and Unhide — a very hidden sheet is
kept but not offered, the last sheet showing cannot go, the Name Box will not go to a hidden sheet and
the PDF leaves hidden sheets out —, Delete, asked twice, with references to the sheet turned into
`#REF!` as Excel does, and a Tab Color drawn on the tab; each one undo step; kept as `sheet@state`,
`bookViews` and `sheetPr/tabColor` in xlsx — theme colours with their tint and indexed colours read too
—, as `table:display` and `table:tab-color` (with LibreOffice's older `tableooo:tab-color` beside it) table styles in ODS, and read from the BOUNDSHEET and
SHEETEXT records of .xls files, which now open with their real sheet names), sheet
protection with per-cell unlocking (`sheetProtection` and `protection@locked`, `table:protected`
and `style:cell-protect` in ODS) — Review ▸ Protect Sheet is Excel's dialog: an optional password to unprotect
it (entered twice; Unprotect Sheet asks for it and says when it is wrong) and Excel's fifteen "Allow all users of
this worksheet to" permissions (select locked or unlocked cells, format cells, columns, rows, insert and delete
columns and rows, insert hyperlinks, sort, AutoFilter, PivotTables, objects, scenarios), each command asking for
its own; Protect Workbook locks the structure (no sheet added, deleted, renamed, moved, copied, hidden, shown or
recoloured — the sheet tab menu and its ⊕ grey those out), with its own password. A password is kept as Excel's salted SHA-512 (100,000 spins; Excel's old
16-bit hash is read too) for xlsx and as LibreOffice's SHA-256 for ODS, both made when it is set; xlsx keeps the
permissions as `sheetProtection`'s attributes and the structure as `workbookProtection`, ODS as LibreOffice's
`loext:table-protection` and `table:structure-protected`. A password read from one format cannot be carried to the
other (each keeps only its own hash; LibreOffice drops it the same way), cell notes (Review → Comment; a cell with a note shows Excel's
red corner triangle and reads it out for accessibility, and the selected cell's note sits under the
formula bar with an edit shortcut; each note keeps who wrote it — a new one is the reviewer's, an edited one keeps its
author, shown with an avatar in the note and in the Comments pane — written to xlsx with its authors list and the VML
note shape Excel shows notes through (`legacyDrawing`), and to ODS with `dc:creator` / `dc:date` and a paragraph per
line, as LibreOffice keeps them; Review → Show/Hide Comment keeps the active cell's note shown beside it — Excel's pale
yellow box with the author in bold and a line from the cell's corner, saved as the VML shape's `x:Visible` and ODF's
`office:display` — and Show All Comments shows every note for the session; Page Setup → Sheet → Comments prints them
not at all, at the end of the sheet — pages after the sheet's listing "Cell: A2", "Comment: Ann:" and the note, with
the sheet's header and footer and the page count running on — or as displayed, the shown boxes drawn on the page;
xlsx keeps it as `pageSetup@cellComments`, ODS at the end as LibreOffice's "annotations"; the same Sheet tab has Excel's
Black and white — no fills or data bars, text and borders in black, pictures in greys — Draft quality — no gridlines,
pictures, shapes or shown notes — and Cell errors as displayed, <blank>, "--" or "#N/A", kept in xlsx as `pageSetup`'s
`blackAndWhite`, `draft` and `errors`), mixed formatting inside a cell (Excel's rich runs in shared
and inline strings and Calc's `text:span`s are kept as per-run formatting, drawn in the grid and
written back as `<is><r><rPr>` / spans with text styles; select part of the text in the formula
bar and Bold / Italic / colour / size apply to that stretch only, while with no selection they
apply to the whole cell, runs included; retyping the text resets the cell to one format; the PDF
export draws the runs too, and cell italics, underline, strike and colour along with them), Goal
Seek (Data → What-If Analysis ▾ → Goal Seek: set a
formula cell to a target value by changing an input cell — secant steps with bisection once a
sign change is bracketed, the result snapped to the roundest value that still hits the target,
one undo step), Data Table (Data → Data Table over a selected block: the formula at the corner or
along the first row / column, input values along the top row and left column, row and / or column
input cells; the interior is filled with the result for every combination as values, one undo
step), a Scenario Manager (Data → Scenarios: the selected cells' values saved under a name,
shown back with one undo step, kept in xlsx as Excel's `<scenarios>` element, and summarised on
a new sheet that lists every input and result cell per scenario), Subtotal (Data → Subtotal on a
sorted block: a "<key> Total" row with `SUBTOTAL` formulas at each change of the group column
and a Grand Total row after the block, outlined in two levels like Excel so the level buttons
collapse the list to its subtotals; `SUBTOTAL` itself is in the engine with Excel's function
numbers and skips nested subtotal cells), row outlines (Data → Group / Ungroup / Clear Outline:
nested groups up to eight levels, touching groups at one level merging as in Excel, a gutter
beside the row numbers with Excel's brackets, −/+
toggles on each group's summary row and 1 / 2 / … level buttons; folded rows are kept apart from
filtered ones, move with inserted and deleted rows, skip printing, and travel as `outlineLevel` /
`collapsed` rows in xlsx and nested `table:table-row-group`s in ODS), column outlines the same way
(Data → Group / Ungroup Columns: a band over the column letters with brackets, −/+ on each group's
summary column to its right and level buttons in the corner; `col@outlineLevel` / `collapsed` and
`outlineLevelCol` in xlsx, nested `table:table-column-group`s in ODS), hidden rows and columns
(Home → Hide / Unhide Rows and Columns: select across hidden ones, or next to them, to show them
again; rows hidden by hand stay apart from a filter's, so clearing a filter leaves them hidden —
xlsx tells them apart by the `autoFilter`, ODS by `table:visibility` "collapse" vs "filter"; arrow
keys step over hidden, folded and filtered cells; nothing hidden prints), whole rows / columns /
the sheet selected by tapping a row number, a column letter or the corner (the headers of the
selected range light up, as in Excel), columns and rows resized by dragging a header's edge (the
edge follows the pointer from where it was pressed; several whole columns or rows selected
resize together; one undo step per drag) or fitted by double-tapping it — AutoFit measures CJK
and full-width text at twice the width of Latin text and takes each cell's font size (and, for
rows, wrapped text) into account —, blocks selected with the pointer (press and hold a cell, then
drag; or drag the round grips on the selection's first and last corners; the sheet scrolls when
the pointer nears its edge, and a block grows over the merged cells it cuts through; press and hold
a column letter or a row number, then drag, for several whole columns or rows), an editable Name
Box beside the formula bar that shows the selection (`B3:D8`, `B:D`, `3:5`) and goes to whatever is
typed into it — a reference, one on another sheet (`'Q3 Sales'!A1`) or a defined name — while a new
valid name names the selection, as in Excel (defined names are stored absolute and quoted, as
`'Q3 Sales'!$A$1:$B$5`, which is how Excel reads them); defined names as Excel has them — a range, a constant
(`TaxRate` = 0.08) or a formula (`=Sales!$A$2*Sales!$B$2`), worked out where they are used, a block named in an
expression standing for its values (`=SUMPRODUCT(Qty*Price)`), a name naming itself #NAME? — kept in xlsx as written and
in ODS as `table:named-range` or `table:named-expression`; Formulas → Defined Names: the Name Manager (each name with what
it comes to and what it refers to; New, Edit — renaming too —, Delete; the selection to use; a name Excel would refuse or
one already taken said so), Define Name, Use in Formula ▾ (a name into the formula being written) and Create from
Selection (the labels of the top row, left column, bottom row or right column name the cells beside them, made into
names as Excel makes them), each one undo step; Formulas → Formula Auditing as Excel lays it out: Trace Precedents
and Trace Dependents draw Excel's tracer arrows over the grid — blue, a dot at the tail and a head at the formula, a range
boxed with one arrow from its box, red from a cell showing an error, dashed from a small sheet icon for another sheet —
one level more each time they are pressed (worked out from the references the formula engine records), Remove Arrows ▾
takes all, the precedent or the dependent ones, and any edit takes them away as Excel's does; Show Formulas, Error
Checking, and Evaluate Formula, which works out the active cell's formula a step at a time — each reference left to right
(a range as {its;values}, a defined name), then the innermost call or bracket, then the whole — the part to work out next
underlined and the last result in italics, Restart once it is done; and the Watch Window, a pane beside the sheet that
keeps chosen cells in view — each with its sheet, the defined name naming it, the cell, what it shows now and its
formula — as you work elsewhere (Add Watch… for the selection or any reference, Delete Watch; a second tap goes to the
cell; a watch follows its sheet through renames and goes with it, and its cell as rows and columns are inserted or
deleted — undo puts it back, a deleted cell's watch goes; kept for the session, as Excel's are);
the active cell kept in view as it moves,
room past the sheet's end as you scroll or go there (empty rows and columns are no edit and are not
saved), and multi-sheet workbooks. Excel's AutoComplete: text typed into a cell that begins exactly one entry of the
column block above or below (case aside; numbers, dates and formulas are not entries) gets the rest of it, selected —
typing on replaces it, Backspace takes it away, Enter keeps it in the entry's own case; and Pick From Drop-down List
(Alt+↓ on any cell, or the cell's press-and-hold menu) lists that column's entries alphabetically to pick one. Excel's
Excel's keyboard: Ctrl+arrows to the edge of the data (Ctrl+Shift+arrows select to there), Home, Ctrl+Home (past
frozen panes), Ctrl+End, Page Up / Down, Tab, Ctrl+Space / Shift+Space (whole columns / rows), Ctrl+B / I / U / 5,
Ctrl+Shift+$ % # @ ! ^ ~ (number formats), Ctrl+1 (Format Cells), Ctrl+K (link), Ctrl+; and Ctrl+Shift+; (today's
date, the time), Alt+= (AutoSum), Ctrl+9 / Ctrl+0 (hide; with Shift, show), and in the formula bar F4 (A1 → $A$1 →
A$1 → $A1, both ends of a range) and Esc (the entry given up). Flash Fill (Data ▸ Flash Fill, Home ▸ Fill ▸ Flash Fill, Ctrl+E): type what you want beside your data once or twice —
a first name, "Lovelace, Ada", initials, part of a code, two columns joined into an e-mail address — and the rest of
the column fills the same way, in one undo step (a header row is recognised; the status bar says how many cells, or
that no pattern fits). AutoFill works as in Excel: drag the fill handle (the square on
the selection's bottom edge) down, up or sideways and each column or row continues its series — a
dashed frame shows the cells it will fill and a tip the value the last one gets — or double-tap it
to fill down as far as the data beside the block goes. Numbers follow their trend (a lone number
repeats; `$1,250.50`, `1,000` and `15%` are numbers too and keep that dress), dates go by the day
or, whole months apart, by the month or year (month ends stay month ends), `9:00` counts up by the
hour, text counts on from its last number (`Item 1`, `第1季度`, `Room 009`, `1st`), and weekdays,
months (`12月` → `1月`), quarters (`Q4` → `Q1`) and the stems and branches cycle — Excel's custom lists: weekdays and
months short and long in every language the app speaks (`Montag` → `Dienstag`, `ЯНВАРЬ` → `ФЕВРАЛЬ`), and the user's
own lists (Edit Custom Lists, in Search: NEW LIST typed one entry per line, or imported from cells; kept on the device;
the built-in ones shown read-only) — keeping the seed's capitals in any script; the Sort dialog's Order also offers each
level's custom list either way and Custom List…, which sorts by a list's order with values not in it after them; formulas move their relative references and every cell
takes its seed's format. Fill Series applies the same rules to the blanks after the
seeds, and Fill Down / Fill Right (Ctrl+D / Ctrl+R) move relative references as Excel does; Home →
Fill also fills up and left (the block's bottom row or right column into the rest). Home →
Clipboard pastes, cuts and copies cells (formats and formulas within the app, tab-separated text for
other apps) — the caret beside Paste opens Excel's paste options (formulas, values, values and
number formats, formats, transpose, link, Paste Special…) — and a block selected with the pointer
gets a small bar above it with Cut, Copy, Paste, Paste Values, Clear and Fill Down. Format Painter
takes the selection's formats (font and fill, alignment, number format, borders and its
conditional formats): the next cell tapped, block dragged over or row / column header picked takes
them — a single cell the copied block's size from there, a larger selection the block repeated over
it — in one undo step; Esc puts it down, Enter paints the selection. Home's groups are laid out as
Excel lays them out in a narrow window (Format as Table and Cell Styles, Insert and Delete, Fill and
Clear stacked beside the larger buttons), so the whole tab shows in the default window. Home → Format
is Excel's menu by its headings — Cell Size (Row Height… in points, Column Width… in characters of
the default font, 0 hiding them, and AutoFit for each), Visibility, Organize Sheets and Protection.
Review → Previous / Next Comment step through the cells with comments row by row, and Delete Comment
is only offered on a cell that has one.
Sheets' Find and Replace is Excel's, beside the window and modeless as Excel's is (Home ▸ Find and Select, Ctrl+F):
Find what — `*` any characters, `?` one, `~` before either to find it itself — Within the sheet or the workbook (sheets
shown, in order), Search by rows or by columns, Look in formulas, values (what a cell shows) or comments, Match case,
Match entire cell contents; Find All lists every cell (its sheet, address and contents — click to go there), Previous
and Next go round, onto other sheets when searching the workbook; Replace (in formulas, as Excel replaces) the active
cell's matches and finds the next, Replace All every one on every sheet searched in one undo step — locked cells of a
protected sheet are left alone. (It used to be case-sensitive, this sheet's typed text only, Next and Replace All.)
Home ▸ Find & Select is Excel's menu: Find…, Replace…, Go To… (F5, Ctrl+G — the defined names and the last four
references gone to; a reference or a name, never defining one as the Name Box does; Special… from there), Go To
Special…, then Formulas, Comments, Conditional Formatting, Constants and Data Validation, each selecting those cells
at once. Go To Special selects comments, constants or formulas (of which kinds — numbers, text, logicals, errors),
blanks, the current region, row or column differences, precedents or dependents (direct only or all levels), the last
cell, the visible cells only, or the cells with conditional formats or data validation (all, or the same as the active
cell's) — within the selection when it is more than one cell, else in the used range — and says so when there are none.
What it finds is selected as several areas, as in Excel, as Ctrl+click adds an area: the grid and the headers shade
every area, the status bar's figures count every cell once, and formatting (font, fill, borders, alignment, number
formats, cell styles, wrap, orientation, indent) and clearing take every area in one undo step.
Slides' Find and Replace is PowerPoint's, beside the window: Match case, Find whole words only and Word's wildcards (the
search Pages uses); Previous / Next select each match in its text box — titles, bodies, shapes, table cells — slide
after slide, round the deck ("第 1 项，共 14 项 · 第 1 张幻灯片"); Replace the selected one, Replace All (speaker notes
too) in one undo step, `^&` and `\1` in the replacement. (Find used to focus the box only, not the words.)

Right-click menus, Office's way on a headset: a press and hold let go without moving opens the menu for what was pressed.
In Sheets it is Excel's for the cells, a row number or a column letter (the clipboard row, Paste Special…, inserting and
deleting, Clear Contents, sorting, the filter, the comment, Format Cells…, Define Name…, Link…; Row Height… / Column
Width…, Hide and Unhide for rows and columns) and for a sheet's tab (Insert, Delete, Rename…, Move, Copy, Tab Color…,
Hide, Unhide…); in Slides PowerPoint's for a slide's thumbnail, and More (⋯) on a picked object's bar; in Pages Word's
for selected text, from More on the selection bar (Font…, Paragraph…, Search, Link…, New Comment, and in a table its
rows, columns and deleting). After a drag-selection Sheets' bar ends with Excel's Quick Analysis: Formatting (data
bars, a colour scale, an icon set, Greater Than…, Top 10 %, Clear Format), Charts (column, line, pie, bar, scatter),
Totals (Sum, Average or Count under each column of numbers or beside each row, bold and in the values' number format),
Tables (a table, a PivotTable) and Sparklines, each one undo step (Ctrl+Q opens it, as in Excel).

Sheets zoom as Excel's do, from 25 to 400 % (Excel goes to 10 %; the grid composes each cell it
shows, and at 10 % a window shows tens of thousands — a file's lower zoom opens at 25 %): the status bar's − / + step by tens and its percentage
opens View → Zoom (25 … 400 %, 100 %, Zoom to Selection — the selected block made to fill the
window and brought to its top left); the grid is laid out at a density scaled by the zoom, so cells,
type, pictures, shapes, handles and gestures all follow, while the floating bars and tips over it
keep their size, and zooming keeps the cell at the top left where it was. Each sheet keeps its own
zoom and gridlines (View → Gridlines used to be one switch for the whole app, never saved): xlsx's
`sheetView` `zoomScale` and `showGridLines`, and in ODS Calc's `settings.xml` view settings — each
sheet's `ZoomValue`, `ShowGrid` and frozen panes (`HorizontalSplitMode` 2 and its position), which
ODS files lost before (frozen rows came back unfrozen), with the view's `HasColumnRowHeaders` for
the headings. View → Freeze Panes is Excel's menu (Freeze Panes or Unfreeze Panes, Freeze Top Row,
Freeze First Column); Freeze Top Row used to move the selection and also freeze the columns left
of it. Paste Special, as in Excel, pastes all, formulas, values (as
copied), values with number formats, formats or comments; adds, subtracts, multiplies or divides
the copied numbers into the ones pasted onto (a formula there gains the operation, `=(B1*2)+5`);
skips copied blanks; transposes (a formula's references turn with it, `=A1+A2` → `=C6+D6`); or
pastes links (`=A1`, `='Q3 Sales'!A1`). A copied cell or block pasted over a selection that holds
it a whole number of times fills it, and a protected sheet refuses the paste. After a paste the pasted block is
selected and Excel's Paste Options button sits at its bottom-right corner until the next edit: Paste, Formulas,
Transpose, Values, Values & Number Formatting, Formatting or Paste Link pastes the same cells again that way, still
one undo step (not after a cut, which moves the cells, as in Excel). Where the system refuses the app a read of the clipboard (Android allows it
only to the focused window), the text last copied in the app is pasted instead.
Text copied in Pages keeps its formatting when pasted in Pages (any window): the runs' fonts, colours and emphasis,
and for whole paragraphs their paragraph formatting (each paragraph mark brings its style, list and spacing, as in
Word; another document's list becomes plain). Word's Paste Options button then sits just after the pasted text until
the next change: Keep Source Formatting, Merge Formatting (the formatting where it landed, keeping bold, italic,
underline and links) or Keep Text Only pastes it again that way, one undo step; with Track Changes on a paste is an
insertion. The system clipboard still carries plain text for other apps. Slides does the same in its text boxes with
PowerPoint's options (Use Destination Theme, Keep Source Formatting, Keep Text Only, the button by the box's lower
right corner), and the formatting travels between the two apps: Pages' paragraphs become a box's lines, a box's lines
become paragraphs.
Objects travel between the modules as Office's do between its apps: a picture, a drawn shape or
text box, a chart or a slide's table copied in one window (Copy / Cut or Ctrl+C / X with it picked
and no text selected; on a page, a chart picked or a picture's copy button) pastes in any other —
on a slide beside where it sat or centred at its size, on a sheet at the active cell (a chart as a
chart of its own, a slide's table as cells), on a page after the caret's paragraph (a shape as a
shape box with its text, a slide's table as a table) — with its look, its crop and its data. Which
of an object and some text was copied last is the system clipboard's to say: the text copied beside
an object (its words, for other apps) carries a label of that copy alone, so a paste finding it
pastes the object and anything copied since, in the app or elsewhere, pastes as text. Duplicate
leaves the clipboard as it was; an empty shape on a slide asks for its text only once picked.
Format Cells is Excel's six tabs, each opening on the active cell's settings and applied together as one
undo step: Number (General, Number with decimal places, the thousands separator and four ways to show
negatives — each shown as it looks —, Currency and Accounting with a symbol, Date, Time and Fraction types
shown on the cell's value, Percentage, Scientific, Text, Custom codes; a cell's code opens on the choice
that makes it), Alignment (Excel's eight horizontal alignments — General, Left, Center, Right, Fill (the text repeated
across the cell), Justify, Center Across Selection (centred over the empty cells after it set the same way, without
merging them) and Distributed (letters spread edge to edge) — with the indent, vertical, wrap, Shrink to fit (the type
made smaller until the text fits; greyed while wrapping), merge, and Excel's Orientation: a "Text"
tile for stacked text beside a half dial, tapped or dragged to −90…90 degrees, with a Degrees box), Font (face, size, bold, italic, underline,
strikethrough, colour, with a sample), Border (none / outline / inside, each outer and inside line, nine
line styles, colour, a picture of the result), Fill and Protection (locked, and why it waits for Protect
Sheet). Home → Alignment is Excel's two rows: top / middle / bottom (the active cell's pressed), Orientation ▾
(Angle Counterclockwise and Clockwise, Vertical Text, Rotate Text Up and Down — ticked when the cell reads that
way, picked again to turn it back — and Format Cell Alignment…), Wrap Text; left / centre / right, Decrease and
Increase Indent (three spaces a level; indenting General or centred text aligns it left, as Excel does) and Merge &
Center with its menu (Merge & Center, Merge Across — every row on its own —, Merge Cells, Unmerge Cells). Turned text
is drawn turned in the grid and the PDF, never spills into its neighbours and makes the row as tall as it stands;
stacked text keeps whole letters (a Thai vowel mark stays on its letter). xlsx keeps `textRotation` (1–90, 91–180
for clockwise, 255 stacked) and `indent`; ODS `style:rotation-angle` (−45° is 315), `style:direction="ttb"` and a
7.5 pt `fo:margin-left` a level, as LibreOffice reads and writes them both ways; Fill, Distributed and Shrink to fit
go to xlsx as `fill` / `distributed` / `shrinkToFit` and to ODS as `style:repeat-content`, `css3t:text-justify` and
`style:shrink-to-fit`, Center Across Selection to xlsx as `centerContinuous` (ODF has none: it is written centred). An
empty cell with formats of its own (a fill, borders, an alignment) is saved to xlsx and read back, as Excel keeps it. Cells take Excel's number format codes (Format Cells → Custom, with examples and a live preview of
the active cell): up to four sections (positive; negative; zero; text) or
conditions (`[>=1000000]0.0,,"M";[>=1000]0.0,"K";0`), colours (`[Red]`, `[Color10]`, `[红色]`),
digit placeholders `0 # ?` with grouping and scaling commas, percent, exponents (`##0.0E+0` in
threes), fractions (`# ?/?`, `# ?/8`), literal text and `_x` spacing, currency and locale tags
(`[$€-407]`, `[$-804]`), dates and times (`yyyy"年"m"月"d"日"`, `dddd`, `mmm`, `aaaa`, `AM/PM`,
`上午/下午`, elapsed `[h]:mm:ss`, `ss.00`, month and weekday names — and `AM/PM` as 上午/下午 or 午前/午後 —
in the app's language unless a tag names one (`[$-409]` keeps them English), the Japanese calendar
(`[$-411]ggge"年"` 令和6年, eras from Meiji on), times to the nearest second as Excel rounds them), and Chinese numerals (`[DBNum1]` 一千二百三十四, `[DBNum2]` 壹仟贰佰叁拾肆 — with General or digit
placeholders alike, as `TEXT(n,"[DBNum1]0")` shows 四万六千二百九十五); `;;;` hides a value.
The Text format (`@`) keeps what is typed — `00123` stays `00123` and SUM passes it by, as in
Excel. Increase / Decrease Decimal edits a custom code in place (`0.0,,"M"` → `0.00,,"M"`), `TEXT()`
uses the same engine, the PDF export prints what the grid shows, and xlsx files keep the codes:
custom `numFmt`s and Excel's built-ins — the Chinese-locale ones (27–36, 50–58) and the currency and
accounting ones (5–8, 41–44) that files use without defining them included — read back as Excel shows
them (`0%` with no decimals); the codes are written back as built-ins only where every Excel shows them
alike (not a currency, which each Excel shows in its own). What cells show is checked against LibreOffice's
"as shown" CSV (FormatOracleTest: 474 values under 77 codes and every cell of WPS's templates — 15,881 of
16,097 alike, the rest where LibreOffice departs from Excel or the app's ISO dates). ODS keeps
them too, as the number styles LibreOffice writes for each (date and time parts, `style:map`
conditions for the sections, `truncate-on-overflow` for elapsed time, display factors, embedded
text, fractions, text styles, colours, Chinese numerals as its "long" transliteration, `?` places as its
blank digits, Japanese eras as `number:era` and gengou years, a tag's language — `[$-409]` AM/PM stays
English), and reads
LibreOffice's own back as codes — checked both ways through LibreOffice itself (`NumberStylesTest`).
Reading ODS also takes the style a column lends its cells (`default-cell-style-name`, how Calc
writes a style its cells share), keeps empty cells that have a look of their own (a form's boxes),
turns dates and times into the serials the sheet computes with, and keeps a formatted text cell's
own `string-value`.
Cells align as Excel's General alignment does until they are aligned by hand: numbers — dates,
times and formatted values too — on the right, text on the left, TRUE / FALSE and errors centred.
The alignment buttons and the character formats (bold, italic, underline, colours, font and size)
apply to the whole selection in one undo step with Excel's toggles — Bold makes the block bold
unless the active cell already is, and an alignment pressed again returns the cells to General —
and a protected sheet refuses them. The PDF export aligns the same way, and xlsx and ods keep
General apart from a set left alignment (Calc's `text-align-source`; older files' bare `start`
reads as General).
Entries are read as Excel reads them once the cell is left (or on Enter in the formula bar, which
moves down): `15%`, `$1,234.50`, `¥80`, `1,000`, `($12)`, `1.5E3`, dates (`2026-09-29`,
`2026/9/29`, `9/29/2026` in English, `2026年9月29日`, `9月29日`, `29-Sep-2026`, `Sep 29, 2026`),
times (`9:30`, `9:30 PM`, `9:30:15`, elapsed `25:30`, `9时30分`) and a date with a time become
numbers in the format they were typed in, so formulas count them; a cell with a format keeps it
(20 typed into a percentage cell is 20%) and a Text cell keeps everything as typed. Unlike Excel,
plain numbers keep their digits — `00123`, an 18-digit ID — instead of being rounded into E
notation, and a leading apostrophe keeps the rest as text. The formula bar shows percentages,
dates and times as they would be typed (15%, 2026-09-29, 9:30), not the fraction or serial
behind them. Typing and its reading are one undo step; paste, fill and the ribbon write as is.
Data → Filter (Ctrl+Shift+L) puts Excel's filter buttons on the header row of the selection — or,
from one cell, of the data around it — and a button's menu sorts A→Z / Z→A under the header,
searches, and ticks the values to show (numbers in order, then text, and (Blanks)); several
columns filter together, each listing only what the others let through, a filtering column's
button turns into a funnel, and Clear Filter From "…", Clear and Reapply work as in Excel. The
keyword filter is a Contains condition on the same filter. The menu also has Excel's two submenus:
Filter by Color (the column's fills and font colours, No Fill and Automatic by name) and Number,
Text or Date Filters — whichever kind of value most of the column holds — with Equals… through
Between… opening Excel's Custom AutoFilter (two comparisons joined by And or Or, values typed or
picked from the column's, `?` and `*` wildcards, dates typed in any way a cell takes them), Top
10… (the largest or smallest so many items or percent, ties kept), Above and Below Average, and
for dates Tomorrow / Today / Yesterday and the next, this and last week, month, quarter and year
and Year to Date (weeks from Sunday, as Excel's). xlsx keeps them all — `filterColumn` values with
blanks, one or two custom filters (`and="1"`, `*text*`, not begins/ends with), `top10`,
`dynamicFilter`, and `colorFilter` with its colour as a dxf (a solid fill in fgColor and bgColor, as
Excel and LibreOffice write it) — beside the hidden rows; ODS keeps the conditions (an Or of one
column's two inside the columns' And), Top and Bottom values or percent and LibreOffice's colour
filters (`loext:data-type`); inserting or deleting rows and columns moves the filter with its data.
Data → Data Validation is Excel's dialog: Settings allow any value, a whole number, a decimal, a
date, a time or a text length compared by any of Excel's eight operators (between, not between,
equal to, greater than or equal to …) with bounds typed as numbers, dates, times or formulas
(`=TODAY()`, `=$B$1`), a list — its values, or a range, a defined name or `INDIRECT(…)` of one for
lists that depend on another cell — or a Custom formula (`=COUNTIF($A:$A,A1)=1` allows no repeats);
Input Message shows a note beside the cell while it is selected; Error Alert picks Stop, Warning or
Information with a title and message. An entry is checked when it is made, not on every key (a
list's "Yes" used to be refused at its "Y"): Stop puts the cell back and offers Retry / Cancel,
Warning asks Continue? (Yes / No / Cancel), Information says so (OK / Cancel). A list cell gets
Excel's arrow beside it (Alt+↓ opens it too), picking a value enters it in one undo step, and lists
match regardless of case. Rules belong to each cell — a new rule or Clear All on part of a rule
leaves the rest of it, its formulas rebased — and follow inserted and deleted rows and columns.
They also go where their cells go, as in Excel: Paste (and Paste Special → Validation, the rules
alone) puts the copied cells' rules on the cells pasted onto — a cell copied without one takes
theirs away — over a tiled selection too; Fill Down / Right, Fill Series and the fill handle give
the filled cells their seeds' rules; a cut takes them along. A rule's relative references follow
it (`=B1>0` on A1, filled down, reads B6 on A6), adjoining copies of one rule join back into one
range (a dropdown filled down a column is one rule, not one per cell), and each is one undo step.
Circle Invalid Data rings the values that break their rules (typed before the rule, pasted, or
worked out by formulas; at most 255, as in Excel) until each is put right or the circles are
cleared. xlsx keeps it all as Excel writes it (operators, alert styles, titles and messages,
`showDropDown`, formulas, several ranges per rule, and Excel 2010's `x14:dataValidation` form for
lists on another sheet), and so does ODS as LibreOffice Calc writes it: `table:content-validations`
(`cell-content-is-whole-number() and cell-content-is-between(0,40)`, `is-true-formula(…)`, lists of
values or of a range, help and error messages), each covered cell naming its rule — a rule down a
whole column is a few repeated rows, not a million — while ODS also keeps defined names
(`table:named-range`) and the AutoFilter with its criteria (LibreOffice's anonymous sheet database
range and `table:filter`, values as `filter-set-item`s). LibreOffice reads all of it back, and what
it writes reads back here. The formula engine now takes a criteria's value — `COUNTIF(A:A,B1)`,
`SUMIF(B:B,">"&D2)` — where it used to take the argument as written; ODS formulas keep quoted sheet
names (`'My Data'!B2` ↔ `['My Data'.B2]`). The xlsx files were audited by round-tripping samples
through LibreOffice: column widths are now in Excel's own units (its default column is 9.140625 in
the file, 64 px — they were written 8% narrow and read 8% wide), a row's fitted height or a column's
width that only restates the sheet's default is not taken for a size of its own (LibreOffice writes
one for every row and column), and rich-text runs spell out their font and size as Excel's do (runs
without them came up as Cambria, and the cell bold, in LibreOffice).
Rows follow their content as in Excel: a row without a height of its own grows with its largest
type (mixed runs included) and with every line its wrapping cells break into, and shrinks back when
that goes — worked out again after each edit, column resize and undo, never saved as a custom height.
A row given a height (dragging its edge, Row Height) keeps it until AutoFit Row Height (double-tap the
edge) hands it back to its content. xlsx keeps the two apart (`customHeight="1"`, or a fitted `ht`
Excel caches), ODS too (`use-optimal-row-height`), and a custom height on an empty row is no longer
lost. Printing and PDF follow the same heights: pages break by row height instead of a fixed count,
text takes its cell's size, wraps inside wrapping cells, sits at the top, middle or bottom as the
cell says, and bold and italic are real faces (PDF pages drew no fake bold, so bold cells and slide
titles came out regular).
Delete clears the contents only, as in Excel — the cell keeps its formats, comment and link (it
used to lose them all) — and Home → Clear offers Excel's menu: Clear All, Clear Formats (merges
undone, the text kept), Clear Contents, Clear Comments and Clear Hyperlinks; Cut still moves formats
with the cells.
Home → Cell Styles is Excel's gallery — Normal, Good, Bad, Neutral; Calculation, Check Cell,
Explanatory Text, Input, Linked Cell, Note, Output, Warning Text; Heading 1–4, Title, Total; the six
accents and their 20% tints; Comma, Currency and Percent — each chip drawn in its style, and each
style setting only what Excel's does (Good its font and fill, a heading its font and rule, Comma the
number format; Normal clears them). Font sizes, faces and strikethrough now also survive xlsx (they
used to be dropped on opening).
Tables are Excel's: Insert → Table or Home → Format as Table makes the selection — or, from one
cell, the data around it — a named table (Table1 …) in any of Excel's 60 built-in styles (Light
1–21, Medium 1–28, Dark 1–11, each drawn in the gallery as it will look), its header naming the
columns (a blank or repeated name becomes Column1 or Sales2) and its filter buttons the sheet's
filter. The Table Design tab, there while the active cell is in a table, renames it (every formula
follows), converts it to a range (its look kept as the cells' own formatting, its references
turned into cell references), and switches Header Row, Total Row (Total under the first column, the
last summed, and a SUBTOTAL list — Sum, Average, Count, Max … — for each total cell), Banded Rows
and Columns, First and Last Column and the Filter Button. Formulas reach tables by structured
references — `=SUM(Sales[Amount])`, `=[@Qty]*[@[Unit Price]]`, `Sales[[#Totals],[Amount]]`,
`Sales[#Headers]`, `Sales[[Qty]:[Price]]`, a bare `Sales` for its data — from any sheet; typing
over a header renames the column in every formula, a value typed just below or beside a table
extends it (an undo step of its own), a formula typed into an empty table column fills the column
(Excel's calculated column, which new rows take up), and a formula copied out of its table names
it (`[@Qty]` → `Sales[@Qty]`). Rows and columns inserted into a table grow it. xlsx keeps real
table parts (`xl/tables/tableN.xml`: name, range, header and total rows with each column's total
function or label, the filter and its criteria, the style and its options; formulas in the file's
spelling, `Sales[[#This Row],[Qty]]`), and LibreOffice computes them the same; ODS, which has no
tables, gets their look as cell formatting and their references as plain ranges. Copying and
shifting formulas no longer touches what strings and brackets hold (`"A1"`, a column named `Q1`).
Cell borders take Excel's thirteen line styles (hair, thin, dotted, dashed, dash-dot, medium and
its dashes, slant dash-dot, thick, double) and colours: Home → Borders opens Excel's menu — each side,
none, all, outside, a thick box, a double or thick bottom, top and bottom (thick or double) — with
the line style and colour it draws in. Borders draw over the gridlines in the grid and in the PDF,
and xlsx and ods keep them (Excel's indexed colours read as its palette; Calc's widths pick hair,
thin, medium or thick).
What does not fit its column shows as in Excel: long left-aligned text runs on over the empty
cells to its right — with no gridlines through it, up to the next cell that has something, a
merge or the frozen panes' edge — and a number is never cut off: General numbers lose decimals
and then go to E notation (`1.23457E+23`), formatted ones (amounts, dates) show `####`. The PDF
export draws both the same way.
ODS import also keeps rows in place after a run of repeated empty rows.
Charts also
travel through ODS as real embedded chart objects (`Object N/content.xml` with a `chart:chart`,
listed in the manifest with the chart media type): the series and category ranges point into
the sheet so Calc keeps them live, the local table carries the data, and a Calc-made chart binds
back to its block on open.

Word's and PowerPoint's keys: in Pages Ctrl+L / E / R / J (alignment), Ctrl+Shift+> / < and Ctrl+] / [ (font size),
Ctrl+1 / 2 / 5 (line spacing), Ctrl+Alt+1 / 2 / 3 and Ctrl+Shift+N (Heading 1–3, Normal), Ctrl+Shift+L (bullets),
Ctrl+M / Ctrl+Shift+M (indent), Ctrl+= / Ctrl+Shift+= (sub / superscript), Shift+F3 (case round), Ctrl+Space (plain
characters), Ctrl+D (Font), Ctrl+K (link), Ctrl+Enter (page break — as Insert ▸ Page Break now does, the paragraph is
split at the caret and the caret goes to the top of the new page); in Slides F5 / Shift+F5, Ctrl+M (new slide), Ctrl+D
(duplicate the object, else the slide), Delete, Ctrl+G / Ctrl+Shift+G, Ctrl+K, arrows to nudge a picked picture, table
or chart, and in a text box Ctrl+B / I / U, Ctrl+L / E / R / J and Ctrl+Shift+> / <. Each app's Keyboard Shortcuts
dialog lists them.

A document's own styles, as in Word: Home ▸ Styles ▸ Create a Style (and New Style in the Styles pane) opens Create New
Style from Formatting — a name (the next free "Style N" offered; an empty or taken name is refused), the style it is
based on (the caret's paragraph's) and a sample in the paragraph's look — and makes a paragraph style of the caret's
paragraph as it looks (its font, size, bold, italic and colour, alignment, spacing, line spacing and indents where they
differ from the base), applied to the selected paragraphs, one undo step. The Styles pane lists the document's own
styles after the built-in ones — those a Word file brought too, which could not be seen or applied before — each with
where it is used, Update to Match Selection and Delete (its paragraphs take the style it was based on); the Styles menu
lists those in use, and the gallery shows the caret's own style as a card. A style based on a heading keeps its
paragraphs headings (navigation, contents). docx keeps the styles (`styles.xml`) and their paragraphs; so does ODT,
as named paragraph styles (a name, a display name, the style it is based on and only what each sets), the paragraphs'
automatic styles standing on them. Read back, a style someone made comes in whether used or not, and LibreOffice's
own pool styles (Text body, Caption…) only as paragraphs use them; LibreOffice lists the styles by name and keeps them
when it saves a docx.

Text columns as Word sets them: Layout ▸ Columns has one, two and three, and More Columns opens Word's Columns dialog
— the presets, the number of columns (up to six, as many as fit at Word's least width of half an inch), the spacing
(in centimetres or inches) and the width each column then has, a line between them, a preview, and Apply to (this
section or the whole document). The spacing is the page's own (Word's default half an inch; the layout used a fixed
18 pt before, while files said 36), read and written in docx (`w:cols` space and sep — columns listed one by one read
as equal), ODT (`fo:column-gap`, `style:column-sep`) and .doc (sprmSDxaColumns, sprmSLBetween); the line between runs
as tall as the tallest column on the page, on the canvas and in the PDF.

Word's page borders (Design ▸ Page Borders): the Borders and Shading dialog's Page Border tab — the setting (none, box,
shadow, custom), the line's style (single, dotted, dashed, dash-dot, dash-dot-dot, double), colour and width, a preview
of the page with a button for each side, which pages (whole document, first page only, all except the first), and how
far from the edge of the page (Word's 24 pt) or from the text (4 pt), each side 0–31 pt. The canvas and the PDF draw them
with one painter, so both match. docx keeps every side (`w:pgBorders` with offsetFrom, display, each side's space and the
shadow); ODT splits each side's margin into the border's place, the line and the padding (and back when read, so the
text keeps its place), with `style:shadow`; Word's .doc borders (old and new BRC, sprmSPgbProp) are read. LibreOffice
draws our docx and ODT borders where we do (it has no "all except the first page").

Word's paragraph borders and shading: Home ▸ Paragraph has Shading (Office's palette) and Borders (bottom, top, left,
right, none, all, outside, inside horizontal — a side turns off when the caret's paragraph has it —, Horizontal Line, and
Borders and Shading…). The Borders and Shading dialog has Word's three tabs: Borders (none, box or custom; the line's
style, colour and width; a preview of two paragraphs with a button for each side and the line between; each side's
distance from the text), Page Border, and Shading (the fill). Neighbouring paragraphs with the same borders, shading and
indents are one box — the top line over the first, the bottom line under the last, the line between in between, the
sides and the fill down the whole box —; the top and bottom lines take their room above and below the text, the side
lines stand out beside it (the text keeps its indents), the same on the canvas, in the page breaks and in the PDF. Styles
carry borders too (Word 2007's Title has its line under it). docx keeps them (`w:pBdr` with each side's space and the
between line, `w:shd`, the styles' as well, a paragraph turning its style's off as nil); ODT writes each side as a line
and padding at the margin, the margin moved out so the text keeps its place (and back when read); Word's .doc borders and
shading (BRC80 / BRC, SHD80 / SHD) are read. Insert ▸ Horizontal Line is an empty paragraph with a line under it (what
Word makes of "---"), no longer a row of dash characters. LibreOffice draws our ODT's boxes as Word does.

Word's line numbers (Layout ▸ Page Setup ▸ Line Numbers): none, continuous, restart each page, restart each section,
suppress for the current paragraph, and Line Numbering Options (start at, distance from the text — Auto is a quarter
inch —, count by, numbering, this section or the whole document). Every line of the body's paragraphs is counted, column
by column and page by page (not tables, text boxes, headers or footnotes, nor a paragraph that suppresses them), and every
"count by"-th number shows in the margin on its line's baseline — the same count on the canvas and in the PDF. docx keeps
them per section (`w:lnNumType`, its start one less than the first number as Word writes it) and per paragraph
(`w:suppressLineNumbers`); ODT has one setting for the whole document (`text:linenumbering-configuration`: no start, no
restart per section) and `text:number-lines`; Word's .doc line numbers are read.
Word's drop cap (Insert ▸ Text ▸ Drop Cap): None, Dropped, In Margin and Drop Cap Options (the letters' font, lines
to drop from 1 to 10, distance from the text). The caret's paragraph — a body paragraph that starts with a letter, not
a list item or a table's — gets its first letter set large (an opening quote or bracket goes with it, as in Word), its capitals running from the first line's cap height
down to the dropped line's baseline; dropped, the first lines are indented past it, in the margin it stands left of
the text. The letter stays part of the paragraph's text, so the caret, Find and editing work on it as before; Enter
leaves the drop cap with the paragraph's first letters. The canvas and the PDF set it alike. docx keeps it the way Word
does — a frame paragraph of its own before the text (`w:framePr w:dropCap`), merged back into the paragraph when read —
ODT as `style:drop-cap` (lines, length — a number or the first word — distance, the letters' text style; ODF has no
drop cap in the margin), and Word's .doc drop caps (sprmPDcs) are read.
Word's Insert ▸ Pages group: Cover Page ▾ — four built-in covers drawn as small pages (Simple: a large title over a
rule; Band: the title in white on a band of colour across the page; Sideline: a thick line beside the title and
subtitle; Color Block: the whole page in colour), each filled with the file's title, subject and author (Word's
"[Document title]" placeholders where they are empty, chosen so typing replaces them) and today's date, in the colour
of the document's Heading 1 — put at the start of the document, replacing the cover it has; Remove Current Cover
Page — Blank Page (a page of its own at the caret) and Page Break. docx keeps the cover in Word's "Cover Pages" content
control (read back as the document's cover); ODT keeps its content. ODT now also keeps manual page breaks — written as
a break before the next paragraph, as LibreOffice writes them (they used to be lost).

Word's AutoCorrect as you type, in Pages: a sentence's first word gets its capital when it is finished (not after
"e.g." or an initial), "THe" becomes "The", "i" and common English slips ("teh", "dont", "recieve") are put right in
English text, "(c)" "(r)" "(tm)" "(e)" "..." "-->" "<--" "==>" become © ® ™ € … → ← ⇒, straight quotes become the
language's curly ones (“ ” in English, „ “ in German, « » with no-break spaces in French, « » in Russian), "word--word"
an em dash and "a - b" an en dash, "1st" gets its "st" raised, "1/2" becomes ½, "* " "- " "> " or "1. " "1) " "a) " at a
paragraph's start make a list, and a typed web or e-mail address becomes a link (also when Enter ends it). Each
correction is its own undo step — Ctrl+Z right after puts back what was typed — and Word's AutoCorrect Options button
(a lightning bolt under the corrected text, until the next key) offers to undo it, to stop that kind of correction (or
that one word) and the AutoCorrect dialog (Settings ▸ Proofing, or Search): every kind can be switched off, and the
words no longer corrected can be corrected again. Not while changes are tracked, nor inside a link, a field or a
formula. Slides corrects the same way in its text boxes (each line of a box starts a sentence; a box keeps its own
bullets, so "* " is left as typed), its AutoCorrect Options button by the box's lower left corner.
English day names take their capital ("monday" → "Monday"), and the AutoCorrect dialog takes entries of one's own
(Word's "Replace … With …": "addr" → your address; a key in small letters also matches typed with a capital, a key
ending in a mark such as ":)" is replaced at once), each listed with Delete. Sheets corrects as Excel does while a cell
is typed in the formula bar — the replacements, day names and capitals after ". " (a cell's first word is left as typed),
no quotes, dashes or lists — and an entry that is a web or e-mail address becomes a link when Enter is pressed (Ctrl+Z
takes entry and link together). Slides and Sheets reach the dialog from Search too.

**Slides** — slide editing, layouts, proofing (Review → Spelling checks every title, body, shape, table
cell and the speaker notes with the device's spell checker, each typo named by its slide and part, a suggestion
put in in the word's own formatting; words written twice and double spaces too), comments as PowerPoint keeps them (each
with its author, date, place on the slide, replies and resolved state: PowerPoint's classic `p:cmLst` with
commentAuthors.xml, a reply by `p15:threadingInfo`, its modern `p188` comments read; LibreOffice's `officeooo:annotation`
in ODP — they used to be saved only in a custom property of this app's and PowerPoint's own were dropped), their markers on
the slide opening them in the Comments pane (author cards, replies, Resolve, Delete Thread), free shapes (gradient fills, groups written as `p:grpSp`,
multi-selection with align / distribute, smart guides while dragging any object — shapes, pictures, tables, charts and
the title and body frames snap to the slide's centre and edges and to each other's edges and centres, the guide held only
while the pointer is near it), tables and charts, video
and sound (PowerPoint's media — a picture whose `p:nvPr` names an `a:videoFile` / `a:audioFile`,
with the file's own copy in `p14:media`, or a link elsewhere — shown by its poster frame or icon
under a play or speaker mark, played where it sits (in the show a tap plays it, pauses it, plays it
again, the poster until its first frame and a line and clock for how far it has played; in the
editor a selected one has PowerPoint's play bar; a copy the file holds plays from the cache, a link
from where it points or, as PowerPoint looks for it, next to the presentation, a web link in the
browser — Reality Office itself has no network access; it pauses when the app goes to the background), and written back as it came: one media part per clip however many
slides show it, the link and the copy, a click that plays it; LibreOffice reads them back; in ODP
they are Impress's: a frame's `draw:plugin` with the file under `Media/` and the poster as its
`draw:image`, LibreOffice's own read too — an older one's catch-all media type, the files a
conversion from PowerPoint leaves under `ppt/media/`, a poster-less one shown as a black frame;
Insert → Video and Audio put one in from the app's folder or a typed path — MP4, MOV, M4V, WebM… and MP3,
M4A, WAV, OGG… up to 150 MB, embedded as PowerPoint embeds them — a video large in the middle with a frame
from its start as its poster (past the black it may open with; a dark frame where the device cannot
decode one), a sound as a speaker icon, both playing in the show and written to pptx and ODP like any other),
pictures placed and resized anywhere on the slide (real `a:xfrm` frames in pptx, pixel sizes
read from the image headers; drawn stretched to their frame as PowerPoint and Word draw them, the
corner grip, zoom and two-hand pinch keeping their proportions) and cropped as in PowerPoint (Picture Format → Crop: a preview with
the cut part dimmed, per-side percentages, Crop to Aspect Ratio 1:1 … 9:16, Reset Crop; the kept
part stays where it was at the same scale; `a:srcRect` in pptx, `fo:clip` measured against the
picture's own resolution in ODP, and the show, the canvas and the PDF draw only the kept part),
animations as PowerPoint times them (entrance, emphasis and exit effects — Fly In / Out, Float,
Split, Wipe, Zoom, Grow & Turn, Bounce, Spinner, Pulse, Teeter, Spin, Grow/Shrink, Transparency,
Fade, Disappear, Shrink & Turn — each On Click, With Previous or After Previous, with its duration,
delay and direction, and text built By Paragraph (the body's bullets one per click, as PowerPoint
builds a text placeholder); an Animation Pane that orders them across the slide, numbered tags on the
slide, Add Animation, One by One; pptx reads and writes the main sequence of click groups, chains and
build list, ODP the page's `anim:par` timing root with Impress's preset names and SMIL behaviours, so
PowerPoint and LibreOffice play them, and the show plays them step by step; the Animations tab draws
each effect as a little star in its class's colour, its Effect Options as arrows, and the timing as
compact Start / Duration / Delay rows with Move Earlier / Later beside them), twelve slide transitions —
Fade, Push, Zoom, Cover, Uncover, Wipe, Split, Dissolve, Cut, Wheel, Random Bars, Shape — each drawn as a
little scene in the Transitions tab, with PowerPoint's Effect Options (from which side; split out or in,
horizontal or vertical; zoom and shape in or out), a duration of 0.5, 0.75 or 1 s, advance after so many
seconds, and Apply To All (transition, options and timing, one undo step); the show plays them as they
read — pushes and covers from their side, the old slide drawn away for Uncover, splits opening or closing
on the middle line, bars, blocks, a circle and four spokes revealing the next slide — and the files keep
them (`p:transition` with `spd`, `advTm` and the effect's `dir`/`orient`; SMIL `smil:type`, `smil:subtype`
and `smil:direction="reverse"` in ODP, as Impress writes them), sections
that group slides in the thumbnail pane and the sorter (PowerPoint's `p14:sectionLst`), a slide
master (Design → Slide Master: the deck-wide background and the title / body font, size, colour
and weight every slide inherits unless it sets its own; the design theme presets live there too),
Design's designed looks — Business, Fresh, Midnight, the decks' templates' looks — on any deck: a cover
design under title and section slides and a content design under the rest (master pages drawn with
shapes, saved as masters of their own), their colours on the master, title and section placeholders
where the layout put them moved to the cover's places; a plain colour theme takes them off again, one
undo step either way; a new slide, or a slide given another layout, is drawn as the deck's other slides
of its kind are (their design and, from one laid out the same, where its title and body sit),
two-hand pinch / twist / drag transforms of the selected element, a detached slide show whose
editor window becomes the presenter console (current and next slide, notes, clock, next / previous / end),
and **Present on Wall** (Slide Show → 投影到墙面): the show opens in a passthrough Stage as a 2D
panel entity, plane tracking (`sense`) looks for a real wall and the panel snaps onto the first
sizeable vertical WALL plane — sized to about 85% of it, standing 3 cm off the surface, turned to
face the room — or floats 2 m in front of the user when no wall is found; the window keeps being
the presenter console, and when the Stage is refused the detached show window stands in.
Set Up Slide Show as PowerPoint has it: the show type (presented by a speaker, browsed in a window, or at a kiosk —
looping by its timings, taking no clicks but links, Esc to end), show without narration or without animation (each
slide shown whole at once), loop until Esc, advance manually or by the rehearsed timings, and which slides it plays — all
of them, slides N to M, or a custom show — counted as it is set; Custom Slide Shows (named selections of the slides in an
order of their own, hidden ones too: new, edit with the deck's slides on one side and the show's in order on the other,
copy, remove, show), played from the Slide Show tab's menu; the show follows the setup — its counter is the place in what
it plays, a link off the plan goes on in the deck's order — and Presenter View starts the show with the notes and the next
slide showing. The setup and the custom shows are kept in the files (pptx `presProps.xml` `p:showPr` and
`p:custShowLst`; ODP `presentation:settings` and its `presentation:show`s, as Impress keeps them — Impress has no end
slide for a range); they were lost on every save before.
Links and clicks work in the show: a link in a slide's text, or a click on a shape or picture
(PowerPoint's link on an object, an action button), goes to a slide — by the slide, so moving slides
keeps it; a duplicate is a slide of its own —, to the next, previous, first or last slide, ends the
show, or opens an address; taps elsewhere still turn the page. pptx reads and writes them as
PowerPoint does (`a:hlinkClick` with `ppaction://hlinksldjump` and a slide relationship,
`ppaction://hlinkshowjump?jump=…`, on runs and on shapes' and pictures' `p:cNvPr`), ODP as Impress
does (text links to `#page2`, LibreOffice's `#<page name>` and "Slide 3" too; shapes' and pictures'
`presentation:event-listener` on `dom:click` — next-page, previous-page, first-page, last-page, stop,
show), and LibreOffice takes every one of them both ways. Insert → Link links the selected text — or,
with a shape or picture selected and no text selected, its click — to a web address or a place in the
presentation (the show's steps, or a slide listed by number and title); the dialog changes or
removes one already there, and in the editor a small arrow marks the shapes and pictures that carry one.
Foreign decks keep their look: theme colours (`a:schemeClr` through the theme's colour scheme and
the master's colour map, with lumMod / lumOff / tint / shade), master and layout backgrounds, and
the master text styles (`p:txStyles`, `+mj-lt` / `+mn-lt` theme fonts) are resolved on open.
Placeholders sit where the deck puts them: a title / body box carries a frame taken from the
slide's own `a:xfrm`, else the layout placeholder with the same idx or type, else the master's
(a content placeholder typed only by its idx is read as the body; a Title Only layout shows no
body box), further text placeholders (a two-content layout's second column) become text boxes at
their frames, and the frames are written back to pptx and odp. Framed boxes drag and resize
like shapes while focused; Home → Free layout pins a flowing slide's title / body at their
layout positions for that, Auto layout re-flows them, and picking a layout re-flows too. As in
PowerPoint, a table, chart, picture or video inserted on a Title and Content slide whose body is
empty goes into the content placeholder (its frame; a table its width with 0.4 inch rows, a picture
as large as fits in its middle), which then shows no prompt and is not written — the object is
written as the placeholder (`p:ph idx="1"` on it); deleting the object brings the prompt back.
Text a slide leaves unset takes its layout placeholder's before the master's, as PowerPoint
resolves it: the list style's first level gives size, face, colour, weight and alignment, so a
PowerPoint title slide's title comes in at 60 pt and centred with its 24 pt centred subtitle, a
section header's text in its grey (before, every title was the master's 44 pt and left-aligned);
a layout placeholder with the slide's idx that places nothing sits where the master's does, not
where another of its kind does. The writer says left alignment too (`algn="l"`), so a left title
on a title slide stays left in PowerPoint and LibreOffice instead of taking the layout's centring.
A master that sets nothing — LibreOffice 26 writes a bare slide master and keeps each master
page's background and text on a layout — takes its look from the first slide's layout, so the
deck's background, title and body text and its date / number placement come back as the
master's rather than baked into each slide. A title slide shows its subtitle wherever it sits
(the canvas and the show used to hide one the layout arranges; the PDF showed it), with "Click to
add subtitle" while empty, and choosing the Title Slide layout centres the subtitle as
PowerPoint's does (another layout sets it from the left again).
A template's design comes with its slides: an Impress deck's master pages — a photograph as the
page's fill (`draw:fill="bitmap"` with its `draw:fill-image`), the pictures, shapes, lines and text
boxes drawn on the master (a group's members too; its placeholders and header / footer / date /
number fields are not its drawing) — become the deck's designs (`SlideDesign`), each slide on the
one its page names, drawn under its content in the editor, the show, the thumbnails and the PDF by
one painter (the editor and the show lay a picture of the design, rendered once per size off the
main thread). A slide keeps its own background over the design's, and one that hides the master's
objects (`presentation:background-objects-visible="false"`) shows the fill alone. Saved as ODP, each
design is a master page again. PowerPoint decks bring theirs too: each layout the slides use becomes
a design — its master's pictures, shapes, lines, text boxes and groups (unless the layout hides them)
then its own, never their placeholders — with the picture background of the layout, else of its
master; the layouts that add nothing to their master share one design, named as the master is (its
own name, else — one layout's master, as LibreOffice writes one — that layout's, else its theme's). A
slide with a picture background of its own gets its layout's drawing over that picture, and one that
hides background graphics (`showMasterSp="0"`) shows none of it. So a corporate template's logo,
bands and backdrop show under its slides, and LibreOffice's templates saved as pptx open as they do
in LibreOffice. Saved as pptx, each design that draws is a slide master of its own after the deck's
plain one, as PowerPoint keeps a design: named after it (its `p:cSld` and its own theme), its picture
the master's background, what it draws in the master's shape tree under the placeholders, with the
five layouts; its slides sit on that master's layouts, a picture drawn by several designs is saved
once, master and layout ids share one id space, and a design that is only a colour is its slides'
background. Design → Format Background → Hide background graphics (隐藏背景图形) hides a slide's
design pictures and shapes (its picture fill stays), undoable, kept in pptx and ODP; slide
thumbnails draw each slide over its design, as it prints.
A background can be a gradient (Format Background → Gradient: six presets, then which way it runs —
0°, 45°, 90°, 135° or out from the centre — undoable, and Apply to All takes it to the master): on a
slide, a design and the master; read from pptx (`a:gradFill` in `p:bgPr`, and a theme's background
style that `p:bgRef` names, its `phClr` the reference's colour), ODP (a drawing page's gradient, a
master page's that only colours its pages too) and .ppt (a shaded background shape); drawn in the
editor, the show, thumbnails and the PDF; written back to pptx and ODP, which LibreOffice reads back
the same.
EMF and WMF pictures — Windows' metafiles: old clip art, charts and diagrams pasted as pictures,
embedded objects' previews, in any format — draw too, by the app's own player (`format/metafile`:
the records' shapes, lines, Béziers, arcs, chords and pies, polygons with their fill modes, paths,
pens with their widths and dashes, solid and hatched brushes, text in its face, size, weight, turn,
alignment and per-character advances — 8-bit text in its font's character set, GBK for a Chinese
font — bitmaps (DIBs, and the PNGs and JPEGs they may hold), the map modes, window, viewport and
world transform, clips and saved states; a placeable WMF's box and inch, an EMF's frame), on slides,
pages and sheets, in thumbnails and every PDF.
SVG pictures draw — on slides, pages and sheets, in designs, thumbnails and every PDF — by the app's
own SVG renderer (`format/svg`: paths, rects with round corners, circles, ellipses, lines, polylines
and polygons, groups, `use` and `symbol`, nested `svg`, text and `tspan`, embedded pictures; styles
from attributes, `style` and `<style>` sheets with CSS inheritance and `currentColor`; every CSS colour
form; linear and radial gradients with their `href` chains, units, transforms and spreads; clip paths;
view boxes and `preserveAspectRatio`; gzipped svgz), so LibreOffice's Metropolis template opens with
its blue background, ribbon and rays. An SVG's size is the one it declares; pictures are known by
their bytes rather than their names (an SVG saved as "image3.png" is still an SVG), and the picture
dialog lists SVG, WebP and BMP files too. From PowerPoint, Word and Excel the SVG itself is read (its
`asvg:svgBlip`) rather than the PNG stand-in beside it, and saving to pptx, docx or xlsx writes both,
as Office does: a PNG the app draws from the SVG for older readers, the SVG for Office to draw.
Freeform shapes are drawn as their outlines (`Freeform`: moves, lines and cubic curves in the shape's
box, arcs and quadratic curves taken as cubics, each part filled and outlined as it says): Impress's
`draw:polygon`, `draw:polyline` and `draw:path` (SVG path data, relative commands, smooth curves and
arcs included), a custom shape whose look the app has no preset for — a hexagon, a puzzle piece, a
capsule-cornered rectangle, a left arrow, a pie — worked out from its own enhanced geometry (view
box, modifiers, equations with `left` … `logwidth`, `if`, `sin`, `atan2`; `M L C Q X Y T U A B W V G
Z N F S`; the stretch point that keeps a long shape's round corners round), and PowerPoint's,
Word's and Excel's custom geometry (`a:custGeom`: guides worked out as Office works them, `arcTo`
included). They save as `a:custGeom` in pptx, docx and xlsx and as an enhanced path in ODF, so
PowerPoint and LibreOffice draw them as the app does; an open one carries arrowheads as a line does.
Impress groups (`draw:g`) on a slide select and move together.
DrawingML's preset geometries draw as PowerPoint draws them: beyond the app's own shapes, some ninety
presets — polygons (pentagon to dodecagon), the rounded and snipped rectangles, the block arrows
(left, up, down, both ways, four ways, bent, U-turn, notched, striped, chevrons), stars of four to
thirty-two points, the flowchart shapes, the rectangle, rounded and oval callouts and the cloud
callout, brackets and braces, the math signs, can, cube, bevel, frame, donut, pie, chord, arc,
teardrop, heart, moon, lightning bolt, smiley and no-symbol, waves — are worked out from their
definitions (`format/pptx/PresetGeometries.kt`, in DrawingML's own guide language, evaluated with
the shape's adjust values) at the size the shape is shown, and saved back as their preset with
their adjust values. So a deck's hexagons are hexagons and its flowchart documents wave, where they
were rectangles; LibreOffice draws them all as the app does. A group member keeps its own size
however small (an icon's dots were drawn a hundredth of the slide wide).
Forty-three more came after, checked shape by shape against LibreOffice's own drawing of the same
pptx: the block arc and the pie wedge, the non-isosceles trapezoid, the left-right-up arrow, the
arrow callouts (left, right, up, down, both ways, four ways), the curved arrows of all four
directions and the circular arrow (SmartArt cycles fall back to them), the two explosions, the swoosh
arrow, the sun, the not-equal sign, the funnel, the corner, square and plaque tabs, the offline
storage flowchart shape, the twelve line callouts (with or without a border or an accent bar, one to
three leader segments) and the Back, Forward, Beginning, End and blank action buttons — 149 in all.
Their shaded faces show too: a path's `darken` / `darkenLess` / `lighten` / `lightenLess` fill (ODF's
H, I, J, K) is drawn darker or lighter than the shape's fill (40 % or 20 % of the way to black or
white) and kept in pptx and odp, so a cube's top and side, a bevel's edges, a can's lid, a folded
corner's fold and a curved arrow's back look three-dimensional instead of flat. Insert → Shapes
opens PowerPoint's gallery — Rectangles, Basic Shapes, Block Arrows, Equation Shapes, Flowchart,
Stars and Banners, Callouts, Action Buttons — every shape the app draws as an outline icon; the one
picked goes in an inch and a half square mid-slide, selected to type in (before, the Shapes button
put in a rectangle). A preset written to ODP is worked out at the shape's size then, so a resized one
keeps its arrowheads and corners.
Gradients run every way PowerPoint and Impress run them: linear along an angle, axial (mirrored from
a centre line), radial (circles from a centre — any point, a corner too) and rectangular. DrawingML
path gradients (`a:path` circle / rect with the `a:fillToRect` centre) and a symmetric three-stop
linear one come in as radial, rectangular and axial; Impress's axial, radial, ellipsoid, square and
rectangular styles with their centres too — each written back the same way, so LibreOffice draws
all five as the app does. Shape Fill offers Axial, Radial and Rectangular beside the angles.
A see-through fill keeps its transparency: DrawingML's `a:alpha` on a solid fill, a gradient's stops
and an outline, Impress's `draw:opacity` and its opacity gradients (`draw:opacity-name`: a fill that
fades out), and a .ppt shape's fill and line opacity — read, drawn and written back in pptx and odp.
Shapes cast outer shadows (`ShapeShadow`: a colour with its opacity, an offset on the page — a turned
shape's shadow still falls the same way — and a blur): PowerPoint's, Word's and Excel's
`a:effectLst/a:outerShdw` (distance, direction, blur radius, `a:alpha`) and Impress's `draw:shadow`
with its offset, colour and opacity (ODF's defaults when a style leaves them out) and LibreOffice's
`loext:shadow-blur`, inherited through styles — written back both ways. A shape's silhouette casts it:
its fill, or its outline when it has no fill. Slides' Shadow menu offers PowerPoint's presets (offset
bottom right, bottom, right, soft all round), each drawn as it looks. Pictures on slides cast them
too (`p:pic`'s effect list, an Impress picture frame's style) — an opaque picture its frame's shadow,
an icon on a clear ground its own silhouette's — in the editor, the show and the PDF, and the
Picture Format tab offers the same presets. Pictures on pages and sheets cast them as well (docx
`pic:spPr`, xlsx `xdr:pic`, an ODT or ODS picture frame's style), on screen and in the PDF, each
picture's corner bar or floating bar toggling one on and off. A shape whose style references the theme's effect style
(`a:effectRef` idx 1-3, how PowerPoint 2007's shapes got their soft shadow) takes the theme's shadow,
in the reference's colour where the theme says `phClr`. So LibreOffice's Focus template opens
with its coloured polygons, Blue Curve with its curve, Candy with its timelines, hexagons and icons —
where they were rectangles, or missing. Three fixes came with it: a shape's fill that its style sets
solid no longer takes the gradient its parent style names (Focus's polygons were grey), a mirrored
custom shape's gradient runs as Impress shows it (Impress mirrors the outline, not the fill), and
small shapes keep their size (an icon's dots were read as a fiftieth of the slide).
Slide layouts travel with the deck. A pptx is written with PowerPoint's own five layouts — Title
Slide, Title and Content, Section Header, Title Only, Blank — each with its placeholders where the
app puts them (and a master holding title and text placeholders), every slide on its layout with
its placeholders typed as that layout types them (`ctrTitle` / `subTitle` on a title slide), so
PowerPoint shows each slide's layout and a slide added or reset there gets its placeholders; on
reading, a slide's layout part (`title`, `secHead`, `titleOnly` …) or its placeholders say which it
is. An odp names each page's layout (`style:presentation-page-layout`, whose placeholders Impress
reads as its autolayouts) and writes an empty title, subtitle or outline as Impress's own empty
placeholder (`presentation:placeholder`, a presentation-family style — without one Impress takes a
frame for a plain text box), so LibreOffice shows "Click to add Text" where the app shows its
prompt, and the layout comes back; a PowerPoint section header LibreOffice opened is known by the
master page it makes of its layout. Before, a title slide came back from pptx as a content slide
and from odp a content slide with an empty body as a title slide (the body box then moved to the
subtitle's place in the next pptx), and a title slide's subtitle was left out of the PDF and the
thumbnails until it had been dragged.
Equations on slides: PowerPoint's (`a14:m` Office Math inside `mc:AlternateContent`) are read
once — their picture fallback is no longer taken as a second box and a stray picture — and a text
box that is an equation is typeset in two dimensions on the canvas, in the show and in the PDF,
while one in running text reads as formatted runs; Insert → Equation (π) adds an equation box from
LaTeX with a live preview, or edits the selected one. Equations go back as PowerPoint writes them,
with the formula as text in the fallback for readers without Office Math (LibreOffice shows that).
Shape text keeps its vertical anchor (`a:bodyPr@anchor`; PowerPoint text boxes are top-anchored,
drawn shapes centred) with a Home → Anchor top / middle / bottom button, in the show, the PDF
and ODP (`draw:textarea-vertical-align`). The slide pane, the slide sorter and the presenter console show each slide as it prints — its
shapes, pictures, tables and charts — rendered by the PDF painter into a picture off the main thread
from a copy of the slide, and again only when that slide (or its size, master, footer or number)
changes; the sorter's thumbnails grow with the window and hidden slides are faded with a white wash.
Insert → Header & Footer (页眉和页脚) is PowerPoint's: the date and time — updated each time the
slides show, in one of eight formats each offered as today's date (2026/10/1, 2026年10月1日, with
星期四, 2026-10-01, 10/1/2026, October 1, 2026 …), or fixed text — the slide number, the footer text
and "Don't show on title slide", applied to all. They sit where PowerPoint's Office theme and
Impress's default master put them (the date at the left of the foot, the footer centred, the number
at the right, 12 pt), a soft black on light slides and a soft white on dark ones, alike on the
canvas, in the show, the thumbnails and the PDF (`model/HeaderFooter.kt`). Undoable. Saved as pptx,
each slide gets PowerPoint's `dt` / `ftr` / `sldNum` placeholders (idx 10–12) — the date a
`datetimeN` field PowerPoint and LibreOffice update, the number a `slidenum` field — and the master
and layouts carry them too, so PowerPoint's own Header & Footer finds where they go; reading one, the
date's format comes back from its cached text, and title slides without them while others have them
are "don't show on title slide". Saved as ODP they are Impress's: `presentation:footer-decl` and
`date-time-decl` (a date that updates in the data style of its kind LibreOffice draws — digits, the
month's name, the weekday — the app's exact format kept as its `number:title`), each page naming
them and its style switching them on or off (`presentation:display-footer` / `-page-number` /
`-date-time`), and every master page with its date, footer and number frames. Impress decks bring
theirs: LibreOffice's templates' slide numbers now show, not on their title slides. A template's own
placement comes with it (`ChromeLook`: where its master — or a design's — puts the date, footer and
number, their alignment, size and colour): Impress masters' `date-time` / `footer` / `page-number`
frames with their text and presentation styles, pptx masters' and layouts' `dt` / `ftr` / `sldNum`
placeholders with their list styles — so Focus's number sits at its own corner in light grey 14 pt —
drawn there everywhere and written back to both formats; the app's own placement stays implicit, so
its files come back as they were.
Pictures turn and mirror: Picture Format → Rotate Left / Right 90°, Flip Horizontal / Vertical
(and a two-hand twist turns a picture as it does a shape), undoable; the canvas turns the picture with
its selection frame, and the show, thumbnails and PDF draw it turned and mirrored. pptx keeps a
picture's `a:xfrm@rot` / `flipH` / `flipV` (slides' and designs'); ODP keeps the turn as a
`draw:transform` and a left-to-right mirror as the frame style's `style:mirror`, and — LibreOffice
drawing no top-to-bottom mirror — a vertical flip as a half turn mirrored, as it saves one itself,
read back as the flip. ODP text in a style naming no face is in a sans one (a graphic
`default-style`), as before the master styles.
Text that sets no colour of its own reads over what it sits on — LibreOffice's automatic colour,
PowerPoint's colour map (`model/TextColors.kt`): the colour it inherits (the master's title or body
colour) where it keeps a 2:1 contrast there, else near-black over light and white over dark — a
title or body over its slide's background, a text box over the slide, a filled shape's text over its
fill (a gradient's middle, a see-through fill laid on the slide); over a design's picture the
inherited colour stays. A run's own colour is never changed. The editor (and its faint prompts), the
show, thumbnails, the PDF and spreadsheet shapes draw it alike. Saved as pptx a dark slide maps the
theme's light colours to text (`p:clrMapOvr/a:overrideClrMapping tx1="lt1"`, as PowerPoint's dark
background styles do; other slides `a:masterClrMapping` — the empty override written before was not
valid), the master's unset colours are the map's text colour (`tx1`), and a filled shape's text, or a
master colour a slide turns, carries the colour it shows in its list style, so PowerPoint and
LibreOffice show what the app does; reading, a slide's own map resolves its colours (PowerPoint's
`tx1` text on a dark slide is white), and a master's plain dark `tx1` is the automatic colour. ODP
leaves unset colours to LibreOffice's automatic one and writes a master colour a page turns as that
page's. A slide whose design draws pictures or shapes keeps its template's text colours: the text may
sit on them.
The master's title and body text — font, size, colour, weight — go to ODP as Impress keeps them:
each master page has its `<master>-title`, `-subtitle`, `-outline1` … `-outline9` presentation styles
(only what the master sets; an unset colour is LibreOffice's automatic one) and its title and outline
placeholders, and every page's placeholders take their look from them through their own styles'
parents, their paragraphs carrying only what differs — so LibreOffice's master view shows the master's
text, and the master comes back as the master's (it used to be baked into every run). A design
keeps its master page's own text too (`SlideDesign.titleText` / `bodyText`, laid over the deck's):
LibreOffice's templates bring each master's title look — Focus's white 32 pt on its title page and
#333333 on the others, Candy's white 66 pt — read from the styles its placeholders name, their parents
resolved; a page's text keeps a colour, font or weight only where it is not what it inherits. In pptx a
design's master carries its own text styles, and reading one, a master's text styles unlike the
deck's are its design's.
A deck keeps its slide size — Design → Slide Size offers PowerPoint's Standard (4:3) and Widescreen
(16:9) and Custom Slide Size: "Slides sized for" PowerPoint's thirteen sizes (Letter, Ledger, A3, A4,
B4 and B5 ISO paper, 35mm slides, Overhead, Banner, the on-screen shows), the width and height typed or
stepped in centimetres or inches, portrait or landscape, and Number slides from (pptx
`firstSlideNum`; LibreOffice shows 1 on, ODF has no place for it). When the slides change shape it
asks PowerPoint's question — Maximize (as large as the new slide allows, some running past its
edges) or Ensure Fit (scaled down to stay on it) — and scales everything on the slides, their
designs and the master's text and chrome by one factor, centred, the text's points with it, so
nothing is stretched; one step back. Any other size a file brings is kept, and every position on a
slide is a fraction of it: pptx reads and writes `p:sldSz` (a 4:3 PowerPoint deck's shapes used to be squeezed
into three quarters of the width, and every deck was saved as 16:9), ODP's page and the PDF page are
the slide, point for point (text is set in points, so it sits on a slide as it does in PowerPoint).
The editing canvas and the show draw the slide at the size it is shown — text, lines and gaps scale
with it, handles and tags keep the screen's size — so a slide looks the same in the editor, the
show, the PDF and PowerPoint; the canvas fits the room left under an open menu, and the show fits
the screen above its controls. Lines are lines — on the canvas, in the show, the PDF and
in pptx (`p:cxnSp` with `prst="line"`; level lines stay level) — and the Shape Outline menu gives
any shape PowerPoint's weights and dashes (`a:prstDash`; a custom `a:custDash` reads as the
nearest) and a line its arrowheads at either end: triangle, open, stealth, diamond or oval, in three
widths and lengths (`a:headEnd` / `a:tailEnd`; Insert → Line Arrow / Double Arrow). The canvas,
Sheets and both PDFs draw every shape with one painter, so what is on screen is what prints; flips
no longer mirror a shape's text. ODP and ODS keep them as `draw:stroke-dash`es and `draw:marker`s
(named as LibreOffice names PowerPoint's heads, so they survive its round trip) and gradients as
`draw:gradient`s, all read back — LibreOffice's own markers (Arrow, Line Arrow, Circle …) included. Shapes in an Impress-made deck keep their fill,
outline colour / width and text anchor: the reader resolves each shape's `graphic` style
through its `style:parent-style-name` chain into styles.xml. A turned shape is written as Impress
writes one — its `draw:transform` turning it about its corner and moving it back, no svg:x / svg:y
(Impress would move it by both and turn it about the page's corner) — and a transform is read
back as Impress applies it, so turned shapes keep their angle and place both ways (they used to
lose the angle, and LibreOffice's own came in at the page's corner). Text boxes are frames holding
a `draw:text-box` in ODP, ODS and ODT alike (Word's filled ones too), not rectangles; LibreOffice's
connectors are read as lines and the OOXML presets it keeps (`ooxml-roundRect` …) as their shapes;
a sheet's pictures and shapes come back in their `draw:z-index` order; a page's header, footer,
date and number fields are no longer taken for text boxes. An Impress deck is read at its slides'
size — the page layout of the master page its pages use; LibreOffice writes its handouts' A4
page layout first, which had every LibreOffice deck open portrait with its shapes stretched.
Slide charts are written to ODP as
embedded chart objects (class, title, legend, gridlines, data labels, series colours, the data
in a local table) and read back from Impress decks, instead of degrading to a title text box.
Charts draw like Office's on the canvas, in the show and in the PDF: a round-numbered value axis
that includes zero, gridlines, category labels (thinned when they would collide), data labels
when Show Values is on, horizontal bars with the first category at the bottom, filled areas,
a doughnut hole in the slide's colour and a pie legend that names the slices; titles, legends
and labels switch between dark and light ink to stay readable on the slide background. Radar
charts draw as real spider charts (a spoke per category, rings at the value ticks, a polygon per
series). Chart Elements (Chart Design tab in Slides, each chart card in Sheets) edits the chart
title, the category and value axis titles and the legend position (none, bottom, top, left,
right), one undo step each; pptx / xlsx charts keep the axis titles (`c:catAx` / `c:valAx`
`c:title`), the legend position, gridlines (`c:majorGridlines`) and data labels (`c:dLbls`), and
ODF charts keep axis titles and `chart:legend-position`. The same dialog formats the value axis as
Office's Format Axis does: a fixed minimum, maximum and major unit (blank = automatic; with zero
left out, bars grow from the nearer end of the axis, and values past a bound stop at it), and a
number format for the axis and the data labels — General, 0, 0.0, 0.00, #,##0, 0%, 0.0%, ¥ and $
amounts, or whatever code a file brings (decimals, thousands, percent, scaling commas, currency and
text around the number). pptx / xlsx keep it as `c:scaling` (`c:min`, `c:max`), `c:majorUnit` and
`c:numFmt`; ODF as the value axis style's `chart:minimum` / `chart:maximum` /
`chart:interval-major` and a number data style.
Export as PDF asks what each page holds: one slide per page, PowerPoint-style handouts with 2,
3 (slide beside ruled note lines), 4 or 6 slides per A4 page with slide and page numbers, or
notes pages (the slide above its speaker notes); handouts and notes pages are saved next to the
deck as `name.handouts.pdf` / `name.notes.pdf`. View → Outline swaps the thumbnail pane for an
outline: every slide's title and text as editable fields, focusing one selects its slide; Slides
from Outline (under Home → New Slide's caret) turns typed or pasted text into slides (unindented
lines are titles, indented lines become bulleted slide text, deeper indents deeper levels), one
undo step. Home is laid out as PowerPoint's narrow ribbon so it fits the default window: New Slide
adds a Title and Content slide, its caret opens a gallery of the layouts drawn as small slides (a
new slide of any of them, Duplicate Slide, Slides from Outline…), and the Layout gallery shows the
same cards with the current slide's marked; Layout / Reset and Section / Free layout stack beside
it; the clipboard gains Format Painter; the paragraph group gains Align Text (top, middle, bottom
of a shape) and the drawing group stacks Shape Fill / Outline and Shadow / Arrange (Bring to
Front, Bring Forward, Send Backward, Send to Back — Bring Forward and Send Backward on the Shape
and Picture Format tabs now move one place, as PowerPoint's do, where they used to jump to the
front or back). Arrange ends with PowerPoint's Selection Pane (also on the Shape and Picture Format tabs, Alt+F10,
and in Search): the slide's pictures, shapes, tables and charts, the one drawn on top first, named as PowerPoint
numbers them ("Rectangle 2", "Picture 1", "TextBox 3") unless they have a name of their own — tapped to select,
renamed in place, hidden or shown by their eye (a hidden object stays in the file but is not drawn on the slide, in
the show, the thumbnails or the PDF), Show All / Hide All, and the header's arrows bring the selected object forward
or send it backward among its kind; every change one step back. pptx keeps the names and `cNvPr@hidden`, ODP
`draw:name` and LibreOffice's `drawooo:display="none"`, both read and written as LibreOffice does.
New Slide's caret also has PowerPoint's Reuse Slides: a task pane that opens another presentation (Browse…, or one of
the recent presentations it lists; pptx, ppt, ODP, the templates and WPS decks) and shows its slides as thumbnails. A
tap inserts that slide after the current one, Insert All Slides every one, and "Keep source formatting" decides
whether they keep their own look — their design with what the source's master gives it (background, title and body
text colours, footer places) folded in, a design whose name is taken here coming along as "Name 2", and slides with
no design getting one made of the source's master — or take this deck's theme, as PowerPoint's default does; links
between the inserted slides follow them, links to slides left behind are dropped, and each insertion is one undo step.
Home's Editing group is PowerPoint's three small commands: Find, Replace ▾ (Replace…, Ctrl+H, and Replace Fonts…)
and Select ▾ (Select All, Ctrl+A when no text box is being edited — every shape on the slide that is not hidden — and
the Selection Pane; several picked shapes show their handles, not a bar each). Replace Fonts lists every font the deck uses (slide text, the master's title and body fonts, the
designs' text and their shapes; names compared without case) and swaps one for another everywhere in one undo step;
a design is replaced by a changed copy, never altered in place, so undo brings the original back.
Insert ▸ Links ▸ Action opens PowerPoint's Action Settings for the picked shape or picture: two tabs, Mouse Click and
Mouse Over, each None or Hyperlink to: the next, previous, first or last slide, the end of the show, a slide of the
presentation or a URL; one undo step for both. An action button from the Shapes gallery comes with the action its
picture says (Back → previous slide, Beginning → first, End → last…) and opens Action Settings at once. In the show a
mouse-over action fires when a pointer comes over the object (a mouse, or the controller ray where the system passes
hover on). Both are kept in pptx (`a:hlinkClick` and `a:hlinkHover` on the shape's `cNvPr`) and ODP
(`presentation:event-listener` for `dom:click` and `dom:mouseover`); LibreOffice keeps the click but has no mouse-over
action, so it drops that both ways.
All twelve of PowerPoint's action buttons are drawn — Back, Forward, Beginning, End, Home, Information, Return,
Movie, Document, Sound, Help and Blank, each to ECMA-376's geometry and shading and matching LibreOffice's drawing —
so the Shapes gallery offers all of them and they come in from pptx, ODP and the old .ppt (whose shape types 189–200
are now all mapped). The old .ppt's click and mouse-over actions (its InteractiveInfo records: a jump in the show, a
slide of the presentation, or an address from the document's hyperlink list) are read too, and so are the links in
its text (each InteractiveInfo followed by the TxInteractiveInfoAtom naming the characters it covers), in text boxes,
shapes and the slide list's titles and bodies.
Slide text has PowerPoint's outline levels: Tab at the start of a line (or over several selected
lines) takes it a level deeper, Shift+Tab back out, Enter starts the next line at the same level,
Backspace at a line's start takes a level off, and Home → Decrease / Increase List Level does the
same (they used to set an indent nothing drew). Each level indents a step further, with its own
bullet (•, –, •, –, » …) or its own numbering (1. / a. / i., each level counting on its own and
restarting under a shallower line), wrapped lines hanging under their text — on the canvas, in
the show (built by paragraph too), in the presenter's previews and in the PDF, which now also
draws every run's face, size, colour and marks, bullets and numbers (it drew plain lines and cut
a shape's text to one). Levels are pptx's `a:pPr@lvl` (with PowerPoint's indents, per-level
bullets and `buAutoNum` schemes; a deck's deeper levels without a size of their own take the
master's smaller sizes) and ODP's nested lists (the nesting LibreOffice writes is read too;
labels full size) or, without a list, indents. Shift+Enter breaks a line inside its item — the text
goes on under the same bullet, at its level, as PowerPoint's line breaks do — and pptx's `a:br` and
ODP's `text:line-break` read as such (they used to start a new bullet) and are written back; a
title with a line break keeps it.
Home → Line Spacing (0.9 … 3 lines, the box's own marked) now spaces the box's lines on the canvas,
in the show and in the PDF — every line, list lines included — and is kept as pptx's
`a:lnSpc/a:spcPct` and ODP's proportional `fo:line-height`; a PowerPoint deck's spacing is read from
the text, else from its master's title and body styles (Office's 90%), and exact spacing in points
becomes the matching multiple. (The list used to set a value nothing drew or saved.)

All three export to PDF, and all three write OpenDocument as well as OOXML: the save dialog has a
"Save as type" row (`.docx` / `.odt` / `.txt`, `.xlsx` / `.ods` / `.csv`, `.pptx` / `.odp`), and a
document opened as OpenDocument is saved back in the same format instead of being converted. Both
directions carry the content, not just the words: styles, headings, lists, tables and pictures for
text; typed cell values, formulas translated between the A1 and `of:` dialects (`=SUM(B2:B4)` ↔
`of:=SUM([.B2:.B4])`), number formats, merges and hidden rows for spreadsheets; title and outline
frames, shapes, pictures, tables and speaker notes for presentations. The readers resolve the
package's style tables, including inheritance, so a document saved as ODF and reopened is the same
document (`OdfRoundTripTest` asserts exactly that).

**Shared** — delta-based undo/redo (each step stores only what changed: a cell range, one
slide, a run of paragraphs), autosave and AutoRecover drafts that never overwrite a file another
app changed on disk (Save then asks: overwrite / save as), no save that silently drops content: a
file holding what Reality Office cannot edit — macros, embedded objects, form controls, SmartArt, audio
and video other than a slide's own (a transition's or an effect's sound, a document's or a sheet's clip), PivotTables, slicers, links to other files, data connections, a data model, chart sheets,
newer chart types, comment threads, ink, digital signatures — is recognised
from its package as it opens (part names, relationships and, in ODF, the manifest and a few content
elements; checked against every file the app writes and LibreOffice's own), a bar under the ribbon
says what it holds, autosave leaves it alone, and Save offers Save a Copy ("Report - Copy.xlsx"
beside it, which the document then is) before Replace Original; a file of a type the module does
not write (.doc, .xls, .ppt, .xlsm …) is saved as a new file beside it, never over another file
already there ("report (2).xlsx" instead); a password-protected file — Office 2007+ (encrypted or
rights-managed), .doc, .xls, .ppt or OpenDocument — is recognised and refused with what to do
about it, rather than opened as a blank document a save would write over it, and any file that
cannot be read is explained in a dialog; a damaged file (XML that only a lenient re-read could
salvage, or cut off before its end) is kept the same way — the bar says some of it may be missing,
and Save makes a copy — and saving into a plain type asks first when it cannot hold the document
(CSV: the other sheets, formulas, formatting, pictures and charts, notes and data rules; plain
text: formatting, objects, headers, footers and notes), offering the module's own type instead —
autosave holds meanwhile; a CSV holds the first sheet shown, not a hidden one, documents opened from file managers
or the share sheet (a second document opens in a side window), and a UI in 18 languages (English, Simplified and
Traditional Chinese, Japanese, Korean, German, French, Spanish, Italian, Brazilian Portuguese, Russian, Dutch, Polish,
Turkish, Vietnamese, Thai, Indonesian, Hindi) that follows the device language or an in-app choice.

### File formats

| Format | Read | Write |
| --- | :---: | :---: |
| `.docx` | ✅ | ✅ |
| `.odt` | ✅ | ✅ |
| `.doc` (legacy OLE compound file; fields show their result — a link's text, which links; DATE and TIME show today's date in their picture (`\@ "MMMM d, yyyy"`, Japanese weekdays …) as Word updates them, in docx, .doc and ODT's unfixed `text:date` alike; CREATEDATE and SAVEDATE keep the file's) | ✅ | — |
| `.xlsx` | ✅ | ✅ |
| `.xls` (legacy BIFF: text, numbers, sheet names, hidden sheets, tab colours) | ✅ | — |
| `.ods` | ✅ | ✅ |
| `.pptx` | ✅ | ✅ |
| `.ppt` (legacy PowerPoint 97–2003, read through its persist directory so an appended edit counts: slide size, titles and bodies at their places with levels, bullets, alignment and the runs' face, size, bold, italic, underline and colour over the master's styles (the document's own for other text) and colour scheme; shapes in Office's preset geometry with their adjust values, freeforms and other shapes of their own geometry (vertices, segments, guides), WordArt as its text, with fill, shading, transparency, outline, turn, flips and groups; pictures — PNG, JPEG, BMP; the master as the slide's design, title slides on their title master (PowerPoint 97's own master for them, its shapes over the main master's), a design template with no slides as one slide on its master, a slide's own background; text that does not wrap and text running down or up a box (txflTextFlow, the box turned); speaker notes; a file whose structure cannot be followed opens with its text) | ✅ | — |
| `.odp` | ✅ | ✅ |
| `.pdf` | — | ✅ (export) |
| `.txt` / plain text | ✅ | ✅ |
| `.rtf` (Word's, WPS's, WordPad's: the text in its code page — GBK and the other Windows code pages, by the document's and each font's — and in Unicode; bold, italic, underline, strikethrough, size, font, colour, highlight, super/subscript, hidden text left out; alignment, indents, spacing, line spacing, headings by their styles, page breaks; lists; tables with merges and borders; PNG, JPEG, EMF and WMF pictures; links; footnotes; the header and footer; tracked changes — `\deleted` / `\revised` with their authors from `\revtbl` and their dates, a formatting change's `\crauth`, a paragraph mark's own; comments with their authors, dates and ranges) | ✅ | — |
| WPS Office's own `.wps` / `.et` / `.dps` and templates `.wpt` / `.ett` / `.dpt` (Word, Excel and PowerPoint 97 files inside; saved as docx / xlsx / pptx beside them) | ✅ | — |
| Templates `.dotx` `.dot` `.xltx` `.xlt` `.potx` `.pot`, shows `.ppsx` `.pps`, OpenDocument templates `.ott` `.ots` `.otp` | ✅ | — |

A round-trip audit guards the readers and writers: every sample file the app's writers make (91 of
them — shapes, tables, charts, lists, notes, sections, pictures, validations …) is read, saved again
in its format and read back, and the two must hold the same content (`RoundTripAuditTest`); with
`-Daero.crossAudit=1` it also runs each through the other format of its kind and back and writes
what the conversion loses to the temp folder. Its first runs found: numbers stored from text that
only looked like one (`00123` saved as 123, a 24-digit code rounded — now kept as text in xlsx and
ODS alike), ODS rules read back in reverse order (which changes which one applies), dash-dot borders
saved as dashed, ODT headings forced bold by fixed heading styles and spans that turn bold off
ignored on reading (LibreOffice's own un-bolded words in a heading came in bold); the cross-format
pass then found custom number formats dropped by ODS (now its number styles, above) and slide
layouts lost through ODP and pptx (above). A third pass runs the samples through LibreOffice itself
(`-Daero.soffice=<its soffice>`: it converts each to the other format of its kind and saves it again
in its own, and the app reads its files back against the samples — `aero-lo-audit.txt`). It found
what files leave unsaid read differently by others: documents with no font named showed in Times
New Roman in Word and LibreOffice (now the theme's Calibri — a theme part and theme fonts in docx,
a default paragraph style in odt), slide text with no size at PowerPoint's 18 pt where the app shows
titles at 40 and bodies at 22 (now said in each layout's placeholders, in the theme's fonts, and a
default text style for shapes), a medium transition written without a speed, which PowerPoint reads
as fast (the speed is now always written; read as the schema has it, and from LibreOffice's real
duration), and Excel's cells, which sit at the bottom unless they say otherwise (the app's middle is
now written out, in the Normal style too, and a workbook's unaligned cells read at the bottom — a cell
that names no style takes the first one, as in Excel). And LibreOffice's charts in Impress and Calc
files, which carry a picture of themselves beside the chart, came in as that picture.
The other way round, an ODT's own defaults now come in: its paragraphs' default face and size
(`Standard`, else the paragraph `style:default-style` — LibreOffice's Liberation Serif 12 pt, which
showed as the app's sans 11 pt), a style's `style:font-name` taken through the document's font
declarations to the family it names, its Heading 1–3 and Title as it defines them (a size relative
to its parent — LibreOffice's 130% of its Heading's 14 pt — worked out, here and for every ODF
paragraph style), and text its styles leave unset set in the document's default; a document of the
app's own (Calibri, the app's default) comes back as it was. The OpenDocument samples now go through
LibreOffice as well (each source type into its own folder), which found the master's face and colour
LibreOffice writes onto every span of a slide's text taken as the text's own (now left to the master,
as PowerPoint keeps a placeholder's text — a later change to the master reaches it), the breaks and
level tabs between a slide's lines cut off from their text's look, a radial gradient's absent angle
read as a quarter turn, and lengths kept in hundredths of a millimetre coming back as 7.99 pt or a
594.99 pt page (paragraph spacing, indents and page measures are now read to Word's twentieth of a
point). What it leaves is LibreOffice's own: Calc drops a text box's bullets even saving its own
files, Writer and Word end a document with a paragraph (so does the app now: after a table at the
end of a document there is always a line to type on).
What the app reads is also checked against what LibreOffice reads, on real files
(`TextOracleTest`, `-Daero.soffice=… -Daero.textDir=<a folder>`): LibreOffice saves each document as
text and each presentation as a PDF, and the letters and digits it has that the app does not show —
counting list labels and the contents' entries — are content the app loses
(`aero-text-oracle.txt`, worst first, with the lines missing). On WPS's 296 bundled documents and
presentations it found 10.1% missing at first: RTF read as ASCII (its `\'xx` and `\uN` text dropped,
the font table's names left in — now a real reader, above), no list numbers, nested ODT lists lost;
now 2.3%, mostly text on slide masters and layouts and fields LibreOffice shows as today's date.
Slide text that does not wrap — PowerPoint's click-to-type text boxes (`a:bodyPr wrap="none"`), ODF's
`fo:wrap-option="no-wrap"` — keeps its lines whole and stands by its alignment in its box on screen, in the
show, in PDFs and thumbnails, and is written back; vertical text (pptx `vert`, `vert270`, Office's text flow)
turns its box a quarter, so it reads down or up as PowerPoint draws it; ODP's free text boxes now keep their place in a slide's
stacking order (they came back above every drawn shape).
Its first sample also showed every Chinese heading saved as ODT small and not bold in LibreOffice: the
heading styles said only `fo:font-size` / `fo:font-weight`, which apply to Latin text; ODF's text
properties are now written for every script (`style:…-asian`, `-complex`) in documents, slides and sheets.
Every module's File menu shows all it offers at once — what to do on the left (New, Open, Save, Save As, Save a
Copy, Export as PDF / CSV, Info, Autosave), templates and recent files on the right — where the single column used
to hide its last rows below the fold. File → Info is Office's: the file's properties to edit (title, subject,
author, keywords, comments) beside where the file is, its size, when it was made and last saved, by whom and how
many times, and the module's statistics (words and characters, paragraphs, comments; sheets, cells, formulas,
pictures; slides, hidden slides, notes, pictures, video and audio). The properties are kept in every format —
OOXML's `docProps/core.xml` (which the app never wrote before) and ODF's `meta.xml` (each keyword its own
`meta:keyword`, the author `meta:initial-creator`) — and each save stamps them as Office does: made then if never
saved, saved now, by the name the user reviews under (the author of a new document), one save more. LibreOffice
reads them both ways. Word 97, Excel 97 and PowerPoint 97 files (and WPS's) show theirs too, read from
their `SummaryInformation` property set (its code page, or UTF-16; FILETIME dates).
