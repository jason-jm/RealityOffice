# Native review of the translations

The 14 languages added in October 2026 (ko, de, fr, es, it, pt-BR, ru, nl, pl, tr, vi, th, id, hi) were translated by
the model following Microsoft Office's terminology (see `docs/i18n.md`); zh-TW and ja were reviewed earlier only in
part. Before release each language should be read by a native speaker who uses Office in that language.

## The review packets

```
python3 tools/i18n/review.py
```

writes `build/i18n-review/<code>.csv` (one per language, UTF-8 with a BOM so Excel opens it) and `summary.md`. Each row
is one string: priority, key, where it shows (the source files that use it), English, Simplified Chinese (a second
reference for meaning), the translation, what the automatic checks flagged, and an empty column for the reviewer
("OK" or a better text). Rows are sorted by priority, flagged rows first.

| Priority | What | About |
| --- | --- | --- |
| 1 | ribbon tabs, groups and commands, the start page, title bar and status bar | 870 strings — always on screen; review these first |
| 2 | dialogs, task panes, menus, template sample content | the rest of what most people meet |
| 3 | spreadsheet function help, screen-reader labels, rarely seen messages | |

Automatic flags (a flag is a question, not an error):

- **inconsistent** — the same English text has a different translation elsewhere. Often right (German "Start" for the
  Home tab but "Startseite" for the home page; gendered "Nächste/Nächster"), sometimes a slip.
- **same-as-en** — left in English. Right for product names Office keeps (WordArt, PivotTable, "Slide" in pt-BR and
  id); suspicious otherwise.
- **long** — more than 1.8 times a short English label: may wrap or shorten on a ribbon button (the ribbon squeezes,
  but a shorter Office term is better).
- **placeholders** — `%1$s` and kin differ (the unit test `TranslationsTest` already fails on these).

To merge a reviewer's corrections: put them in a JSON `{key: text}` and run
`agent-notes/scripts/i18n.py merge <code> <file>`, then `./gradlew :app:testDebugUnitTest --tests "*TranslationsTest*"`.

## Questions for the reviewers

These were uncertain when translated and are worth a look in particular:

- New sheet names (`txt_sheet_n`): Tabelle1, Feuil1, Hoja1, Foglio1, Planilha1, Лист1, Blad1, Arkusz1, Sayfa1, 工作表1
  are Excel's; vi, th, id and hi keep "Sheet1" — the reviewer should say what their Excel names a new sheet.
- Animation and transition effect names (ru, es, ko): PowerPoint's names differ from the literal ones.
- Vietnamese "Chú thích" is used both for comments and for captions in places; Office distinguishes them.
- Keyboard key names in the shortcuts dialog ("Space" kept in fr, ko, th, hi).

## Checked by machine so far

- Every language has every string with the same placeholders (`TranslationsTest`).
- Every ribbon tab, the main dialogs and panes, the home screen and the templates were looked at on the emulator in
  de, ru, ja, ko, en and the en-XA pseudo-locale (rounds 67–70, 79–81, 86); labels that did not fit were fixed.
- The main flows (`DeskFlowsTest`) run in German as well as Chinese (round 87).
