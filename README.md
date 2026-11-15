# Reality Office for PICO Space Pro

**English** · [Deutsch](docs/languages/de.md) · [Español](docs/languages/es.md) · [Français](docs/languages/fr.md) · [Bahasa Indonesia](docs/languages/id.md) · [Italiano](docs/languages/it.md) · [Nederlands](docs/languages/nl.md) · [Polski](docs/languages/pl.md) · [Português (Brasil)](docs/languages/pt-BR.md) · [Русский](docs/languages/ru.md) · [Türkçe](docs/languages/tr.md) · [Tiếng Việt](docs/languages/vi.md) · [हिन्दी](docs/languages/hi.md) · [ไทย](docs/languages/th.md) · [日本語](docs/languages/ja.md) · [한국어](docs/languages/ko.md) · [简体中文](docs/languages/zh-CN.md) · [繁體中文](docs/languages/zh-TW.md)

Reality Office is a free, open-source office suite built for **PICO Space Pro**. Write and review documents in [Pages](#pages-editor), analyze data in [Sheets](#sheets-editor), and design and deliver presentations in [Slides](#slides-editor). Each editor works offline. You can keep a source document within view while you work on a spreadsheet or slide deck in a second window, then take the editable files back to your computer.

**[Download 1.3.4 (APK)](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4/RealityOffice-v1.3.4.apk)** · [Installation guide](docs/INSTALL.md#english)

Requires **PICO OS 6.2 or later** on arm64. The app interface is available in 18 languages.

<p>
  <a href="docs/gallery/cover-en.jpg">
    <picture>
      <source media="(max-width: 600px)" srcset="docs/gallery/cover-en-mobile.jpg">
      <img src="docs/gallery/cover-en.jpg" alt="Reality Office, built for PICO Space Pro. The main image shows the Saturday Print Club deck in Slides, beside the Pages schedule and Sheets budget.">
    </picture>
  </a>
</p>

## Working across spatial windows

Choose **Open in New Window** to put two files side by side. Keep the original brief open while you draft a response, or consult a workbook while you update a chart in a presentation. From the home screen you can start with a report, budget or presentation template, search your recent files, and pin documents you open regularly. Each editor has a keyboard shortcut guide for working with a physical keyboard.

[![Home shows blank files, templates and recently opened Saturday Print Club documents.](docs/gallery/posters/home.webp)](docs/gallery/en/home.webp)

<a name="pages-editor"></a>

## Pages · Document layout and review

Give a long report a structure you can navigate. Apply heading styles, jump between sections in the outline and insert a linked table of contents with page numbers. Place pictures and tables in the text, add footnotes and use headers and footers to carry a title or page number across the document.

When someone returns an edited draft, **Compare** opens the differences in a separate document. With **Track Changes**, you can see who made an edit and accept or reject it individually. Attach a comment to a passage, reply in the thread and mark it resolved when the wording is settled.

[![Pages with the opening schedule, a table of times, printmaking activities and locations.](docs/gallery/posters/pages.webp)](docs/gallery/en/pages.webp)

<a name="sheets-editor"></a>

## Sheets · Formulas, charts and data analysis

Use formulas across worksheets to build budgets, order lists or forecasts. `SUMIFS` adds only the rows that meet your criteria, while `XLOOKUP` can bring a matching price into an order sheet. If you know the result you need, **Goal Seek** can work backwards to find an input, such as the unit price that brings an order within budget.

Freeze a heading row while you scroll, sort by several columns and filter down to the records you need. Drop-down lists help keep entries consistent. Conditional formatting can flag amounts over a limit, and column, line or pie charts let you compare categories, see a trend or show how a total breaks down.

[![Sheets with an itemised materials budget. Four paper packs bring the total to €96 and leave €24.](docs/gallery/posters/sheets.webp)](docs/gallery/en/sheets.webp)

<a name="slides-editor"></a>

## Slides · Design, rehearsal and spatial presentation

Start with a slide layout, arrange text and pictures with alignment guides, and add tables or charts. Apply a transition between slides or animate an object to reveal a point as you speak. You can add notes to each slide and record how long you spend on it during rehearsal.

During a show, the presenter console keeps your notes, the current slide and the next slide in view while the presentation runs in a separate panel. **Present on Wall** can place that panel on a wall detected by the headset when you grant spatial access. If no wall is available or you decline access, the slides float in front of you.

[![Slides editing the Saturday Print Club welcome deck, with Hokusai’s breaking wave on the first slide.](docs/gallery/posters/slides.webp)](docs/gallery/en/slides.webp)

## File formats and PDF export

| Editor | Open and save | Also opens |
| --- | --- | --- |
| Pages | DOCX, ODT, TXT | DOC, RTF |
| Sheets | XLSX, ODS, CSV | XLS |
| Slides | PPTX, ODP | PPT |

All three editors export to PDF when you want to share a finished copy. Edits to older DOC, XLS and PPT files are saved in a newer format alongside the original. CSV is useful for exchanging a sheet of values. Choose XLSX or ODS to keep formulas, formatting and multiple worksheets together.

## Offline editing, privacy and recovery

The editors open and save files on the headset without an app account. The release APK does not request Android’s Internet permission. You can still choose a cloud drive through the system file picker if you use one. That provider handles the files you select.

AutoSave keeps edits on disk, and AutoRecover keeps a local draft you can restore after an interruption. If another app changes the open file on disk, automatic saving pauses so you can decide which version to keep. When Reality Office detects content it cannot edit, such as macros, it offers to save a copy before replacing the original.

## Try the editors

The screenshots show the English 1.3.4 release in the PICO OS 6.2 emulator. Download the same editable documents to explore a formatted run sheet, a budget with live formulas and a three-slide presentation with speaker notes.

[Get the sample files (ZIP)](https://raw.githubusercontent.com/jason-jm/RealityOffice/main/examples/print-club/Saturday-Print-Club.zip)

[Format support](docs/FEATURES.md) · [Privacy](DATA.md#english) · [Build from source](docs/BUILDING.md) · [MIT license](LICENSE)
