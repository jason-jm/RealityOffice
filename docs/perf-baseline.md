# Performance baseline

Measured on 2026-09-07 with the debug build 1.2 (3) on a PICO B3110 headset running PICO OS
6.2.0. The numbers are the reference points for future
regressions; re-measure with the steps at the end after any change to the grid, the page
layout engine, the formula engine or the document readers.

## Scenarios

Debug builds expose load hooks through the `debug.aero.dialog` system property. Each hook
generates the content in memory, loads it into the editor exactly like an opened file and logs
two lines under the `AeroPerf` logcat tag: `<hook> generated+loaded in N ms` (generation plus
`editor.load`, on the main thread) and `<hook> first frame after N ms` (the first frame drawn
with the loaded content, measured from the same start with a `Choreographer` callback).

| Hook            | Module | Content                                                                |
| --------------- | ------ | ---------------------------------------------------------------------- |
| `bigsheet`      | Sheets | 6 000 rows × 3 columns (18 000 cells)                                  |
| `bigsheet100k`  | Sheets | 25 000 rows × 4 columns (100 000 cells, of which 25 000 are formulas)  |
| `bigsheetzoom`  | Sheets | `bigsheet` at the lowest zoom, 25 % (the most cells the grid shows)     |
| `bigdoc`        | Pages  | 600 paragraphs (≈ 50 pages)                                            |
| `bigdoc1000`    | Pages  | 12 000 paragraphs (≈ 1 000 pages)                                      |

## Results

Load time (best of two runs; the spread between runs was under 10 %):

| Scenario                     | generated + loaded | first frame drawn | memory after load (PSS) |
| ---------------------------- | -----------------: | ----------------: | ----------------------: |
| Sheets, blank workbook       |                  – |                 – |                  128 MB |
| Sheets, `bigsheet`           |             227 ms |                 – |                  134 MB |
| Sheets, `bigsheet100k`       |             627 ms |          1 365 ms |                  140 MB |
| Pages, blank document        |                  – |                 – |                  124 MB |
| Pages, `bigdoc`              |              26 ms |                 – |                  126 MB |
| Pages, `bigdoc1000`          |             428 ms |          1 091 ms |                  152 MB |

Memory is `TOTAL PSS` from `dumpsys meminfo` once the window is idle; the resident set
(RSS) of the 100 000-cell workbook was 300–330 MB because of the shared graphics buffers of
the spatial window, which every window pays regardless of the document.

CPU load from `pico-cli perf live run` over 15 s while the loaded document sits idle:

| Scenario               | system CPU avg / max | app CPU avg / max |
| ---------------------- | -------------------: | ----------------: |
| Sheets, blank          |          5.4 / 6.6 % |       1.0 / 3.3 % |
| Sheets, `bigsheet`     |          3.2 / 6.4 % |       0.7 / 3.4 % |
| Sheets, `bigsheet100k` |          3.5 / 6.4 % |       1.9 / 3.3 % |
| Pages, blank           |         4.5 / 10.0 % |       1.0 / 3.3 % |
| Pages, `bigdoc`        |          5.2 / 6.8 % |       0.3 / 3.2 % |
| Pages, `bigdoc1000`    |          4.2 / 6.8 % |       0.7 / 3.3 % |

So a loaded document costs no measurable CPU while idle: the grid and the page list are
virtualised, formulas are memoised with dependency tracking and nothing recomposes without an
edit. The 100 000-cell workbook keeps the main thread busy for about 0.6 s on load (25 000
formulas are evaluated lazily, on first display) and shows its first frame after 1.4 s; the
1 000-page document lays out in 0.43 s and is on screen after 1.1 s.

What the tooling could not measure: `pico-cli perf live run` reports App FPS, System FPS and
GPU usage as 0 / N/A for a spatial `WindowContainer` panel (the compositor, not the app,
presents the frames), and `pico-cli perf doctor` needs the Perfetto Trace Processor, which was
not installed on the build machine, so no Perfetto trace was captured. Frame pacing during
scrolling therefore remains a visual check.

## Reproducing

```bash
ADB="$(command -v adb)"
DEV=your-device-serial   # from pico-cli device list
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --device $DEV
pico-cli shell --device $DEV "setprop debug.aero.module sheets; setprop debug.aero.dialog bigsheet100k"
pico-cli app launch com.therealityteam.realityoffice --device $DEV
pico-cli perf live run --app com.therealityteam.realityoffice --device $DEV --adb $ADB -d 15 --save -o sheets-100k.json
pico-cli shell --device $DEV "logcat -d -s AeroPerf"
pico-cli shell --device $DEV "dumpsys meminfo com.therealityteam.realityoffice | grep 'TOTAL PSS'"
pico-cli shell --device $DEV "setprop debug.aero.dialog ''; setprop debug.aero.module ''"
```

Use `pages` + `bigdoc1000` for the document scenario. The `summary` block of the saved report
holds the CPU figures quoted above (`systemCpu`, `appCpu`); `rawData` has the per-second samples.
Clear both properties afterwards, otherwise every new window keeps loading the test content.

### Zoom (2026-10-15, emulator)

Sheets compose a cell for each cell they show, so the zoom sets how many there are: `bigsheet` drew its first frame
after 1 564 ms at 100 % and 2 814 ms at 25 % (PSS 225 MB). At 10 % — Excel's lowest — a window shows tens of
thousands of cells and the app ran out of memory, so the lowest zoom is 25 %; a file's lower zoom opens at 25 %.
