#!/usr/bin/env python3
"""Review packets for native speakers, and the checks a machine can make on the translations.

    python3 tools/i18n/review.py            -> build/i18n-review/<code>.csv (one per language) + summary.md

Each CSV (UTF-8 with a BOM, so Excel opens it as such) lists every string of the app in review order:

    priority  1 = seen all the time (ribbon tabs, groups and commands, home, status bar),
              2 = dialogs, panes, menus and template content, 3 = the rest (function help, screen-reader labels…)
    key, where (the source files that show it), English, Simplified Chinese (a second reference), the translation,
    flags     what the checks below found, for the reviewer to look at first.

Checks:
    inconsistent  the same English text is translated differently elsewhere (sometimes right: "Size" of paper or
                  of a picture — the reviewer decides)
    same-as-en    left identical to the English (fine for names and codes; suspicious in a non-Latin script)
    long          more than 1.8 times the English and the English is a short label (may not fit a ribbon button)
    placeholders  the %1$s … placeholders differ (TranslationsTest also fails on these)
"""
import collections, csv, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, 'app/src/main/res')
SRC = os.path.join(ROOT, 'app/src/main/java')
OUT = os.path.join(ROOT, 'build/i18n-review')
LOCALES = {
    'zh': 'values-zh', 'zh-TW': 'values-zh-rTW', 'ja': 'values-ja', 'ko': 'values-ko', 'de': 'values-de',
    'fr': 'values-fr', 'es': 'values-es', 'it': 'values-it', 'pt-BR': 'values-pt-rBR', 'ru': 'values-ru',
    'nl': 'values-nl', 'pl': 'values-pl', 'tr': 'values-tr', 'vi': 'values-vi', 'th': 'values-th',
    'id': 'values-in', 'hi': 'values-hi',
}
# Keys not to flag as left in English: a code the app reads (the letter template's layout), and the new sheet's name,
# which several languages' Excel keeps as "Sheet1" (asked of the reviewers in docs/i18n-review.md).
CODES = {'tpl_letter_style', 'txt_sheet_n'}
LATIN = {'de', 'fr', 'es', 'it', 'pt-BR', 'nl', 'pl', 'tr', 'vi', 'id'}
LINE = re.compile(r'^\s*<string name="([^"]+)"[^>]*>(.*)</string>\s*$')
PH = re.compile(r'%(?:\d+\$)?[sdf]|%%')
# Text that stays as it is in every language: the app's and modules' names, file types, codes, function names.
KEEP = re.compile(r'^(Aero Desk|Pages|Slides|Sheets|PDF|CSV|SmartArt|OK|Calibri|.*\.(docx|xlsx|pptx|odt|ods|odp)|[A-Z0-9_.#!/?%$€¥£ -]+|[^A-Za-z]*)$')


def unescape(v):
    v = v.replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')
    if len(v) >= 2 and v.startswith('"') and v.endswith('"'):
        v = v[1:-1]
    return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', lambda m: chr(int(m.group(1)[1:], 16)) if m.group(1)[0] == 'u' and len(m.group(1)) == 5
                  else {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), v)


def strings(d):
    out = collections.OrderedDict()
    for line in open(os.path.join(RES, d, 'strings.xml'), encoding='utf-8'):
        m = LINE.match(line)
        if m:
            out[m.group(1)] = unescape(m.group(2))
    return out


def usages():
    """key -> the source files that use R.string.key (basenames, without .kt)."""
    found = collections.defaultdict(set)
    ref = re.compile(r'R\.string\.([a-z0-9_]+)')
    for base, _, files in os.walk(SRC):
        for f in files:
            if f.endswith('.kt'):
                for k in ref.findall(open(os.path.join(base, f), encoding='utf-8').read()):
                    found[k].add(f[:-3])
    return found


def priority(key, where):
    if key.startswith(('tab_', 'group_', 'status_', 'home_')):
        return 1
    if any(w in ('Ribbon', 'SheetsRibbon', 'SlidesRibbon', 'AeroKit', 'Controls', 'AeroDesk', 'FileMenu') for w in where):
        return 1
    if key.startswith(('fn_', 'a11y_')):
        return 3
    if key.startswith('tpl_') or any(('Dialog' in w or 'Pane' in w or 'Panel' in w or 'Menu' in w) for w in where):
        return 2
    return 3


def norm(t):
    return t.strip().rstrip('…').rstrip('.').strip().lower()


def main():
    en = strings('values')
    where = usages()
    os.makedirs(OUT, exist_ok=True)
    zh = strings('values-zh')
    # English text -> its keys, for the consistency check (texts used by two keys or more).
    by_text = collections.defaultdict(list)
    for k, v in en.items():
        if len(v) <= 40:
            by_text[norm(v)].append(k)
    summary = []
    for code, d in LOCALES.items():
        tr = strings(d)
        rows, counts = [], collections.Counter()
        for k, e in en.items():
            t = tr.get(k, '')
            flags = []
            others = {norm(tr.get(o, '')) for o in by_text.get(norm(e), []) if o != k and o in tr}
            if others and norm(t) not in others | {''} and code != 'zh':
                flags.append('inconsistent')
            if t == e and not KEEP.match(e) and re.search('[A-Za-z]{3}', e) and k not in CODES:
                if code not in LATIN or len(e.split()) > 2:
                    flags.append('same-as-en')
            if len(e) <= 16 and len(t) > max(1.8 * len(e), len(e) + 6) and code not in ('zh', 'zh-TW', 'ja', 'ko'):
                flags.append('long')
            if sorted(PH.findall(e)) != sorted(PH.findall(t)):
                flags.append('placeholders')
            for f in flags:
                counts[f] += 1
            w = sorted(where.get(k, ()))
            rows.append((priority(k, w), k, ' '.join(w[:3]), e, zh.get(k, ''), t, ' '.join(flags)))
        rows.sort(key=lambda r: (r[0], 0 if r[6] else 1))
        with open(os.path.join(OUT, f'{code}.csv'), 'w', encoding='utf-8-sig', newline='') as f:
            w = csv.writer(f)
            w.writerow(['priority', 'key', 'where', 'English', '简体中文', code, 'flags', 'reviewer: OK or better text'])
            for r in rows:
                w.writerow(list(r) + [''])
        p1 = sum(1 for r in rows if r[0] == 1)
        summary.append((code, len(rows), p1, counts))
    with open(os.path.join(OUT, 'summary.md'), 'w', encoding='utf-8') as f:
        f.write('| Language | Strings | Priority 1 | inconsistent | same-as-en | long | placeholders |\n|---|---|---|---|---|---|---|\n')
        for code, n, p1, c in summary:
            f.write(f"| {code} | {n} | {p1} | {c['inconsistent']} | {c['same-as-en']} | {c['long']} | {c['placeholders']} |\n")
    print(open(os.path.join(OUT, 'summary.md'), encoding='utf-8').read())


if __name__ == '__main__':
    sys.exit(main())
