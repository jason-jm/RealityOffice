#!/usr/bin/env python3
"""
AeroDesk's icon set: PICO ICON 7.0 icons plus Office icons drawn in the same style (24 x 24 grid,
2 dp strokes, flat ends, mitred corners). Writes app/src/main/res/drawable/ic_*.xml,
the key table ui/AeroIcons.kt, and — with --sheet <png> — a contact sheet for review.

    python3 tools/icons/make-icons.py [--sheet build/icons/sheet.png]

Each custom icon is a list of parts: ("s", d) a 2 dp stroke, ("s15", d) a 1.5 dp stroke,
("f", d) a fill, ("e", d) an even-odd fill. Coordinates are stroke centre lines.
"""
import json, os, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DRAWABLE = os.path.join(ROOT, "app/src/main/res/drawable")
KOTLIN = os.path.join(ROOT, "app/src/main/java/com/therealityteam/aeropages/ui/AeroIcons.kt")
# PICO icon paths redistributed under Apache-2.0; see THIRD_PARTY_NOTICES.md.
with open(os.path.join(os.path.dirname(__file__), "pico-icon-7.0.paths.json"), encoding="utf-8") as icon_file:
    ICON70 = json.load(icon_file)


def rr(x0, y0, x1, y1, r):
    """A rounded rectangle through the stroke centre line."""
    return (f"M{x0 + r} {y0}H{x1 - r}A{r} {r} 0 0 1 {x1} {y0 + r}V{y1 - r}A{r} {r} 0 0 1 {x1 - r} {y1}"
            f"H{x0 + r}A{r} {r} 0 0 1 {x0} {y1 - r}V{y0 + r}A{r} {r} 0 0 1 {x0 + r} {y0}Z")


def circle(cx, cy, r):
    return f"M{cx - r} {cy}A{r} {r} 0 1 0 {cx + r} {cy}A{r} {r} 0 1 0 {cx - r} {cy}Z"


# A page standing upright (most document icons start from it).
PAGE = None


def dots(*centres, r=1.5):
    return "".join(circle(x, y, r) for x, y in centres)


PAGE = rr(5, 3, 19, 21, 2)


def glyph_run(ox, oy, deg, arrow=True):
    """The letters "Ab" set along a baseline turned [deg] counterclockwise from ([ox], [oy]), an arrow under them (text orientation icons)."""
    import math
    t = math.radians(deg)
    d = (math.cos(t), -math.sin(t))   # along the baseline
    u = (-math.sin(t), -math.cos(t))  # up from it

    def pt(x, h):
        return (round(ox + x * d[0] + h * u[0], 2), round(oy + x * d[1] + h * u[1], 2))

    def line(*pts):
        q = [pt(x, h) for x, h in pts]
        return "M" + "L".join(f"{a} {b}" for a, b in q)

    path = line((0, 0), (2.6, 7), (5.2, 0)) + line((1, 2.6), (4.2, 2.6)) + line((7.4, 7.6), (7.4, 0))
    cx, cy = pt(9.4, 2.1)
    parts = [("s15", path), ("s15", circle(cx, cy, 2.0))]
    if arrow:
        a0, a1 = pt(-0.5, -3.2), pt(12.5, -3.2)
        h1, h2 = pt(10.3, -1.4), pt(10.3, -5.0)
        parts.append(("s15", f"M{a0[0]} {a0[1]}L{a1[0]} {a1[1]}M{h1[0]} {h1[1]}L{a1[0]} {a1[1]}L{h2[0]} {h2[1]}"))
    return parts

# --- ICON 7.0 icons used as they are (resource name -> catalog name) -----------------------------
FROM_ICON70 = {
    "ic_home": "ic_home",
    "ic_pin": "ic_pin",
    "ic_lightning": "ic_lightning",
    "ic_undo": "ic_undo",
    "ic_copy": "ic_stack",
    "ic_cut": "ic_scissors",
    "ic_plus": "ic_plus",
    "ic_minus": "ic_minus",
    "ic_close": "ic_cross",
    "ic_check": "ic_check",
    "ic_delete": "ic_delete",
    "ic_edit": "ic_edit",
    "ic_link": "ic_link",
    "ic_picture": "ic_picture",
    "ic_crop": "ic_crop",
    "ic_video": "ic_video",
    "ic_audio": "ic_volume_loud",
    "ic_play": "ic_player_play",
    "ic_settings": "ic_settings",
    "ic_lock": "ic_lock",
    "ic_list": "ic_list",
    "ic_keyboard": "ic_keyboard",
    "ic_font_grow": "ic_text_format",     # the catalog's "A+"
    "ic_font_shrink": "ic_search_minus",  # the catalog's "A-"
    "ic_arrow_right": "ic_arrow_long_right",
    "ic_chevron_down": "ic_arrow_down",
    "ic_chevron_up": "ic_arrow_up",
    "ic_folder": "ic_folder",
    "ic_history": "ic_history",
    "ic_share": "ic_share",
    "ic_eye": "ic_eye",
    "ic_info": "ic_info_circle",
    "ic_arrow_up": "ic_arrow_long_up",
    "ic_arrow_down": "ic_arrow_long_down",
    "ic_chevron_left": "ic_arrow_left",
    "ic_actual_size": "ic_original",
    "ic_rotate_right": "ic_arrow_circle_right",
    "ic_plus_circle": "ic_plus_circle",
    "ic_minus_circle": "ic_minus_circle",
    "ic_grid": "ic_grid",
    "ic_book": "ic_book",
    "ic_expand": "ic_expand",
    "ic_cube": "ic_cube",
    "ic_person": "ic_person_fill",
    "ic_tv": "ic_tv",
    "ic_eye_slash": "ic_eye_slash",
    "ic_clock": "ic_clock",
    "ic_sight": "ic_sight",
    "ic_accessibility": "ic_body",
    "ic_document": "ic_article",
    "ic_warning": "ic_report_fill",
    "ic_arrow_left_long": "ic_arrow_long_left",
    "ic_chevron_right": "ic_arrow_right",
}

# --- Office icons drawn in the ICON 7.0 style ------------------------------------------------------
CUSTOM = {
    # Title row
    "ic_save": [
        ("s", "M6 4H15.6L20 8.4V18A2 2 0 0 1 18 20H6A2 2 0 0 1 4 18V6A2 2 0 0 1 6 4Z"),
        ("s", "M8 4.5V8.5H14.5V4.5"),
        ("s", "M7.5 19.5V14H16.5V19.5"),
    ],
    "ic_window_new": [
        ("s", rr(4, 9, 16, 20, 2)),
        ("s", "M8.5 6.5V6A2 2 0 0 1 10.5 4H18A2 2 0 0 1 20 6V14"),
    ],
    "ic_redo": "mirror:ic_undo",
    # Clipboard
    "ic_paste": [
        ("s", "M9 5H7A2 2 0 0 0 5 7V19A2 2 0 0 0 7 21H17A2 2 0 0 0 19 19V7A2 2 0 0 0 17 5H15"),
        ("s", rr(9, 3, 15, 7, 1)),
        ("s", "M9 12H15M9 16H13"),
    ],
    "ic_format_painter": [
        ("s", rr(4, 3.5, 17, 9.5, 1.5)),
        ("s", "M17 6.5H20V13H12V15"),
        ("f", "M10.5 15H13.5V21H10.5Z"),
    ],
    # Font
    # The catalog's A, kept clear of the colour bar the button draws under it.
    "ic_font_color": [("icon70", "ic_letter_a", 0.74)],
    "ic_highlight": [
        ("s", "M14.2 3.4L19.6 8.8L11.6 16.8H6.2V11.4Z"),
        ("s", "M8.6 9L14 14.4"),
    ],
    "ic_char_spacing": [
        ("s", "M4 4V20M20 4V20"),
        ("s", "M7.5 12H16.5"),
        ("s", "M10 9.5L7.5 12L10 14.5M14 9.5L16.5 12L14 14.5"),
    ],
    # Paragraph
    "ic_bullets": [
        ("f", dots((5, 6), (5, 12), (5, 18))),
        ("s", "M9 6H20M9 12H20M9 18H20"),
    ],
    "ic_numbering": [
        ("s15", "M4 4.6L5.6 3.6V8.6"),
        ("s15", "M3.8 11.2C4.1 10.5 4.7 10.2 5.4 10.2C6.3 10.2 7 10.8 7 11.6C7 12.3 6.6 12.8 5.9 13.4L3.8 15.2H7.2"),
        ("s15", "M3.9 16.9C4.3 16.5 4.8 16.3 5.4 16.3C6.3 16.3 6.9 16.8 6.9 17.5C6.9 18.2 6.3 18.6 5.4 18.6"
                "C6.4 18.6 7.1 19.1 7.1 19.9C7.1 20.7 6.4 21.2 5.4 21.2C4.8 21.2 4.2 21 3.8 20.6"),
        ("s", "M10 6H20M10 12.7H20M10 18.8H20"),
    ],
    "ic_indent_decrease": [
        ("s", "M4 5H20M11 10H20M11 14H20M4 19H20"),
        ("f", "M8 9V15L4 12Z"),
    ],
    "ic_indent_increase": [
        ("s", "M4 5H20M11 10H20M11 14H20M4 19H20"),
        ("f", "M4 9V15L8 12Z"),
    ],
    "ic_line_spacing": [
        ("s", "M11 6H20M11 12H20M11 18H20"),
        ("s", "M6 5.5V18.5"),
        ("s", "M3.5 8L6 5.5L8.5 8M3.5 16L6 18.5L8.5 16"),
    ],
    "ic_align_left": [("s", "M4 5H20M4 9.7H14M4 14.3H20M4 19H14")],
    "ic_align_center": [("s", "M4 5H20M7 9.7H17M4 14.3H20M7 19H17")],
    "ic_align_right": [("s", "M4 5H20M10 9.7H20M4 14.3H20M10 19H20")],
    "ic_align_justify": [("s", "M4 5H20M4 9.7H20M4 14.3H20M4 19H20")],
    # Editing
    "ic_find": [
        ("s", circle(10.5, 10.5, 6.5)),
        ("s", "M15.4 15.4L20.5 20.5"),
    ],
    "ic_replace": [
        ("s", "M4 8H17.5M14.5 5L17.5 8L14.5 11"),
        ("s", "M20 16H6.5M9.5 13L6.5 16L9.5 19"),
    ],
    "ic_select_all": [
        ("s", "M4 8.5V4H8.5M15.5 4H20V8.5M20 15.5V20H15.5M8.5 20H4V15.5"),
        ("f", "M8.5 8.5H15.5V15.5H8.5Z"),
    ],
    # Slides: the speaker notes under a slide
    "ic_notes": [
        ("s", rr(3, 4, 21, 13, 2)),
        ("s", "M3 17.5H21M3 21H14"),
    ],
    # Comments
    "ic_comment": [
        ("s", "M6 4H18A2 2 0 0 1 20 6V15A2 2 0 0 1 18 17H11L7 20.5V17H6A2 2 0 0 1 4 15V6A2 2 0 0 1 6 4Z"),
        ("s", "M8 8.5H16M8 12.5H13"),
    ],
    # --- batch 2: drawing, arrange, tables, data, alignment -----------------------------------------
    "ic_fill": [
        ("s", "M10 3.5L17.5 11L11 17.5L3.5 10Z"),
        ("s", "M7.3 6.8L4.8 4.3"),
        ("f", "M19.5 12.5C19.5 12.5 21.2 14.8 21.2 15.9A1.7 1.7 0 0 1 17.8 15.9C17.8 14.8 19.5 12.5 19.5 12.5Z"),
    ],
    "ic_shadow": [
        ("s", rr(3, 3, 15, 15, 2)),
        ("f", "M17 7H21V21H7V17H17Z"),
    ],
    "ic_outline": [
        ("s", "M5 16L14.5 6.5L17.5 9.5L8 19H5Z"),
        ("s", "M12.5 8.5L15.5 11.5"),
    ],
    "ic_shape_rect": [("s", rr(4, 6, 20, 18, 1))],
    "ic_shape_rounded": [("s", rr(4, 6, 20, 18, 4))],
    "ic_shape_oval": [("s", "M4 12A8 6 0 1 0 20 12A8 6 0 1 0 4 12Z")],
    "ic_shape_triangle": [("s", "M12 4.5L20.5 19H3.5Z")],
    "ic_shape_star": [("s", "M12 3.5L14.4 9.2L20.5 9.7L15.8 13.7L17.3 19.7L12 16.4L6.7 19.7L8.2 13.7L3.5 9.7L9.6 9.2Z")],
    "ic_shape_arrow": [("s", "M3.5 9H13V5L20.5 12L13 19V15H3.5Z")],
    "ic_shape_line": [("s", "M4.5 19.5L19.5 4.5")],
    "ic_new_slide": [
        ("s", rr(3, 4, 16, 14, 2)),
        ("s", "M18.5 14.5V21.5M15 18H22"),
    ],
    "ic_chart": [
        ("s", "M3 3V21H21"),
        ("f", "M7 12H10V18H7ZM12 6H15V18H12ZM17 9H20V18H17Z"),
    ],
    "ic_bring_forward": [
        ("s", "M12 6V5A2 2 0 0 0 10 3H5A2 2 0 0 0 3 5V10A2 2 0 0 0 5 12H6"),
        ("f", "M9 7H19A2 2 0 0 1 21 9V19A2 2 0 0 1 19 21H9A2 2 0 0 1 7 19V9A2 2 0 0 1 9 7Z"),
    ],
    "ic_send_backward": [
        ("f", "M3 5A2 2 0 0 1 5 3H12A2 2 0 0 1 14 5V6H8A2 2 0 0 0 6 8V14H5A2 2 0 0 1 3 12Z"),
        ("s", rr(8, 8, 20.5, 20.5, 2)),
    ],
    "ic_bring_front": [
        ("f", "M9 7H15A2 2 0 0 1 17 9V15A2 2 0 0 1 15 17H9A2 2 0 0 1 7 15V9A2 2 0 0 1 9 7Z"),
        ("s", "M9 5V4.5A1.5 1.5 0 0 0 7.5 3H4.5A1.5 1.5 0 0 0 3 4.5V7.5A1.5 1.5 0 0 0 4.5 9H5"),
        ("s", "M15 19V19.5A1.5 1.5 0 0 0 16.5 21H19.5A1.5 1.5 0 0 0 21 19.5V16.5A1.5 1.5 0 0 0 19.5 15H19"),
    ],
    "ic_send_back": [
        ("s", rr(7, 7, 17, 17, 2)),
        ("f", "M3 3H10V5H5V10H3ZM21 21H14V19H19V14H21Z"),
    ],
    "ic_textbox": [
        ("s", rr(3, 4, 21, 20, 2)),
        ("s", "M8 9H16M12 9V16"),
    ],
    "ic_spelling": [
        ("s", "M3 14L6.5 5L10 14M4.3 11H8.7"),
        ("s", "M12.5 15L15.5 18L21 11.5"),
    ],
    "ic_table": [
        ("s", rr(3, 4, 21, 20, 2)),
        ("s", "M3 9.5H21M3 14.75H21M9 4V20M15 4V20"),
    ],
    "ic_layout": [
        ("s", rr(3, 4, 21, 20, 2)),
        ("s", "M3 9H21M11 9V20"),
    ],
    "ic_eraser": [
        ("s", "M4 14.5L13.5 5L19 10.5L11.5 18H7.5Z"),
        ("s", "M8.5 10L14 15.5"),
        ("s", "M12 20.5H20"),
    ],
    "ic_rotate_left": "mirror:ic_rotate_right",
    "ic_flip_h": [
        ("s", "M12 3V21"),
        ("s", "M9.5 7V17H3.5Z"),
        ("f", "M14.5 6V18H21.5Z"),
    ],
    "ic_flip_v": [
        ("s", "M3 12H21"),
        ("s", "M7 9.5H17V3.5Z"),
        ("f", "M6 14.5H18V21.5Z"),
    ],
    "ic_zoom": [
        ("s", circle(10.5, 10.5, 6.5)),
        ("s", "M15.4 15.4L20.5 20.5"),
        ("s", "M10.5 7.5V13.5M7.5 10.5H13.5"),
    ],
    "ic_sort_asc": [
        ("s", "M3 6H8M3 11.5H10.5M3 17H13"),
        ("s", "M18 4V19M15 16L18 19L21 16"),
    ],
    "ic_sort_desc": [
        ("s", "M3 6H13M3 11.5H10.5M3 17H8"),
        ("s", "M18 4V19M15 16L18 19L21 16"),
    ],
    "ic_filter": [("s", "M4 5H20L14 12.5V19.5L10 17.5V12.5Z")],
    "ic_text_orientation": glyph_run(6.2, 17.2, 45),
    "ic_text_angle_ccw": glyph_run(6.2, 17.2, 45),
    "ic_text_angle_cw": glyph_run(5.0, 7.4, -45),
    "ic_text_rotate_up": glyph_run(14.5, 19.5, 90),
    "ic_text_rotate_down": glyph_run(9.5, 4.5, -90),
    "ic_text_vertical": [
        ("s15", "M6.5 11L9.1 4L11.7 11M7.5 8.4H10.7"),
        ("s15", "M8 13V20.6"), ("s15", circle(10, 18.5, 2.0)),
        ("s15", "M17.5 4V20M15.7 17.8L17.5 20L19.3 17.8"),
    ],
    "ic_merge_across": [("s", rr(3, 4, 21, 20, 2)), ("s15", "M3 9.33H21M3 14.67H21"), ("s15", "M7 12H17")],
    "ic_merge_cells": [("s", rr(3, 6, 21, 18, 2)), ("s15", "M12 6V8.5M12 15.5V18")],
    "ic_unmerge": [("s", rr(3, 6, 21, 18, 2)), ("s15", "M12 6V18"), ("s15", "M9.5 12H5.5M7.3 10.2L5.5 12L7.3 13.8M14.5 12H18.5M16.7 10.2L18.5 12L16.7 13.8")],
    "ic_merge": [
        ("s", rr(3, 6, 21, 18, 2)),
        ("s", "M6 12H10M8 10L10 12L8 14M18 12H14M16 10L14 12L16 14"),
    ],
    "ic_borders": [("s", "M4 4H20V20H4ZM12 4V20M4 12H20")],
    "ic_valign_top": [("s", "M4 4H20"), ("s", "M12 20V8M8.5 11.5L12 8L15.5 11.5")],
    "ic_valign_middle": [("s", "M4 12H20"), ("s", "M12 3V8.5M9.5 6L12 8.5L14.5 6M12 21V15.5M9.5 18L12 15.5L14.5 18")],
    "ic_valign_bottom": [("s", "M4 20H20"), ("s", "M12 4V16M8.5 12.5L12 16L15.5 12.5")],
    "ic_wrap_text": [
        ("s", "M4 6H20M4 12H17A3 3 0 0 1 17 18H13M4 18H9"),
        ("s", "M15 15.5L12.5 18L15 20.5"),
    ],
    # --- batch 3: tables, page layout, references, review, view, slides, data -------------------------
    "ic_insert_row_above": [("s", rr(3, 10.5, 21, 21, 2)), ("s", "M3 15.75H21"), ("s", "M12 2.5V8.5M9 5.5H15")],
    "ic_insert_row_below": [("s", rr(3, 3, 21, 13.5, 2)), ("s", "M3 8.25H21"), ("s", "M12 15.5V21.5M9 18.5H15")],
    "ic_insert_col_left": [("s", rr(10.5, 3, 21, 21, 2)), ("s", "M15.75 3V21"), ("s", "M2.5 12H8.5M5.5 9V15")],
    "ic_insert_col_right": [("s", rr(3, 3, 13.5, 21, 2)), ("s", "M8.25 3V21"), ("s", "M15.5 12H21.5M18.5 9V15")],
    "ic_delete_row": [("s", rr(3, 3, 21, 13, 2)), ("s", "M3 8H21"), ("s", "M9.5 16L14.5 21M14.5 16L9.5 21")],
    "ic_delete_col": [("s", rr(3, 3, 13, 21, 2)), ("s", "M8 3V21"), ("s", "M16 9.5L21 14.5M21 9.5L16 14.5")],
    "ic_fill_down": [("s", rr(4, 3, 20, 9, 1)), ("s", "M12 11V20M8.5 16.5L12 20L15.5 16.5")],
    # Excel's Flash Fill: a two-column table beside a lightning bolt.
    "ic_flash_fill": [("s", rr(3, 3, 13, 21, 1.5)), ("s", "M8 3V21M3 9H13M3 15H13"), ("f", "M18.5 5L14.5 12.5H17.4L15.6 20L21 11H18L20 5Z")],
    # Excel's Formula Auditing: arrows from two cells into one (precedents), from one out to two (dependents), an arrow struck out.
    "ic_trace_precedents": [("s", rr(2, 3, 8, 9, 1)), ("s", rr(2, 15, 8, 21, 1)), ("s", rr(15, 9, 22, 15, 1)),
                            ("s15", "M6 6L14 11M11 10.7L14 11L12.7 8.3M6 18L14 13M12.7 15.7L14 13L11 13.3")],
    "ic_trace_dependents": [("s", rr(2, 9, 9, 15, 1)), ("s", rr(16, 3, 22, 9, 1)), ("s", rr(16, 15, 22, 21, 1)),
                            ("s15", "M10 11L17 6.5M14 6.3L17 6.5L15.8 9.2M10 13L17 17.5M15.8 14.8L17 17.5L14 17.7")],
    # Evaluate Formula: a formula box (=) under a magnifying glass.
    "ic_evaluate_formula": [("s", rr(2, 5, 15, 16, 1.5)), ("s", "M5.5 9H11.5M5.5 12H11.5"),
                            ("s", "M20.5 16A3.5 3.5 0 1 1 13.5 16A3.5 3.5 0 1 1 20.5 16Z"), ("s", "M19.6 18.6L22 21")],
    # Protect Workbook: a closed book with a padlock on its cover.
    "ic_protect_workbook": [("s", "M5 4.5A1.5 1.5 0 0 1 6.5 3H17V17H6.5A1.5 1.5 0 0 0 5 18.5Z"), ("s", "M5 18.5A1.5 1.5 0 0 0 6.5 20H11"),
                            ("s", rr(13, 15, 21, 21, 1)), ("s", "M15 15V13.5A2 2 0 0 1 19 13.5V15")],
    # Watch Window: a small window with a magnifier over a cell.
    "ic_watch_window": [("s", rr(2, 4, 18, 18, 1.5)), ("s", "M2 8H18M8 8V18"),
                        ("s", "M21.5 17A3 3 0 1 1 15.5 17A3 3 0 1 1 21.5 17Z"), ("s", "M20.7 19.2L22.5 21")],
    # Replace Fonts: one letter A, an arrow, another A.
    # Compare: two pages side by side, lines on each, the second set apart.
    "ic_compare": [("s", rr(2.5, 4, 10.5, 19, 1.5)), ("s", rr(13.5, 4, 21.5, 19, 1.5)), ("s15", "M5 9H8M5 12H8M16 9H19M16 12H19M16 15H18")],
    # Pen Style: a solid line, a dashed one, a dotted one.
    "ic_pen_style": [("s", "M3 6H21M3 12H7M10 12H14M17 12H21"), ("f", dots((4, 18), (8, 18), (12, 18), (16, 18), (20, 18), r=1.1))],
    # Pen Weight: lines growing thicker.
    "ic_pen_weight": [("s", "M3 5H21"), ("f", "M3 10H21V12.5H3Z"), ("f", "M3 16.5H21V20.5H3Z")],
    # Pen Color: a pencil over the colour it draws.
    "ic_pen_color": [("s", "M14.5 3.5L18.5 7.5L9 17H5V13Z"), ("s", "M12.5 5.5L16.5 9.5"), ("f", "M3 19.5H21V22H3Z")],
    "ic_cover_page": [("s", rr(5, 3, 19, 21, 1.5)), ("f", "M5 8H19V12H5Z"), ("s15", "M8 16H14M8 18.5H12")],
    "ic_blank_page": [("s", "M6 3H14L19 8V21H6Z"), ("s15", "M14 3V8H19")],
    "ic_drop_cap": [("s", "M3 13L6.5 3.5L10 13M4.3 9.6H8.7"), ("s15", "M13 4.5H21M13 8.5H21M13 12.5H21M3 16.5H21M3 20.5H15")],
    "ic_drop_cap_none": [("s15", "M3 9.5L4.8 4.5L6.6 9.5M8.5 7H21M3 12.5H21M3 16.5H21M3 20.5H15")],
    "ic_drop_cap_margin": [("s", "M2 13L5.5 3.5L9 13M3.3 9.6H7.7"), ("s15", "M12 4.5H21M12 8.5H21M12 12.5H21M12 16.5H21M12 20.5H18")],
    # Action (Insert ▸ Links): a star and the pointer that clicks it.
    "ic_action": [("s", "M8.50 2.50L10.15 6.73L14.68 6.99L11.16 9.87L12.32 14.26L8.50 11.80L4.68 14.26L5.84 9.87L2.32 6.99L6.85 6.73Z"), ("s", "M13.5 12.5V21.5L15.8 19.3L17.6 22.6L19.4 21.7L17.6 18.4H20.8Z")],
    "ic_replace_fonts": [("s", "M2.5 15L6 5L9.5 15M3.8 11.5H8.2"), ("s15", "M10.5 9.5H14M12.5 8L14 9.5L12.5 11"), ("s", "M14.5 20L18 10L21.5 20M15.8 16.5H20.2")],
    "ic_remove_arrows": [("s15", "M3 18L13 8M9.6 7.6L13 8L12.6 11.4"), ("s", "M15.5 15.5L20.5 20.5M20.5 15.5L15.5 20.5")],
    "ic_calendar": [("s", rr(3, 5, 21, 21, 2)), ("s", "M3 10H21M8 3V7M16 3V7"), ("f", "M7 13H10V16H7ZM14 13H17V16H14Z")],
    "ic_group": [("s", "M3 7V3H7M17 3H21V7M21 17V21H17M7 21H3V17"), ("s", rr(7, 7, 12.5, 12.5, 1)), ("f", "M12 11.5H17V17H11.5V12Z")],
    "ic_ungroup": [("s", rr(4, 4, 11, 11, 1.5)), ("f", "M14.5 13H19.5A1.5 1.5 0 0 1 21 14.5V19.5A1.5 1.5 0 0 1 19.5 21H14.5A1.5 1.5 0 0 1 13 19.5V14.5A1.5 1.5 0 0 1 14.5 13Z")],
    "ic_width_increase": [("s", "M4 6V18M20 6V18"), ("s", "M7.5 12H16.5M10 9.5L7.5 12L10 14.5M14 9.5L16.5 12L14 14.5")],
    "ic_width_decrease": [("s", "M4 6V18M20 6V18"), ("s", "M6.5 12H10.5M8 9.5L10.5 12L8 14.5M17.5 12H13.5M16 9.5L13.5 12L16 14.5")],
    "ic_height_increase": [("s", "M6 4H18M6 20H18"), ("s", "M12 7.5V16.5M9.5 10L12 7.5L14.5 10M9.5 14L12 16.5L14.5 14")],
    "ic_height_decrease": [("s", "M6 4H18M6 20H18"), ("s", "M12 6.5V10.5M9.5 8L12 10.5L14.5 8M12 17.5V13.5M9.5 16L12 13.5L14.5 16")],
    "ic_table_style": [("s", rr(3, 4, 21, 20, 2)), ("f", "M3 6A2 2 0 0 1 5 4H19A2 2 0 0 1 21 6V9.5H3Z"), ("s", "M3 14.75H21M9 9.5V20M15 9.5V20")],
    "ic_print_titles": [("s", rr(3, 4, 21, 20, 2)), ("f", "M3 6A2 2 0 0 1 5 4H19A2 2 0 0 1 21 6V9H3Z"), ("f", "M3 9H8V20H5A2 2 0 0 1 3 18Z"), ("s", "M8 14.5H21M14.5 9V20")],
    "ic_text_to_columns": [("s", rr(3, 5, 9, 19, 1.5)), ("s", "M11 12H14M12.5 10L14.5 12L12.5 14"), ("s", rr(16, 5, 21, 19, 1.5))],
    "ic_margins": [("s", PAGE), ("s15", "M8.5 3V21M15.5 3V21M5 7H19M5 17H19")],
    "ic_orientation": [("s", rr(3, 3, 11, 15, 1.5)), ("s", rr(13, 11, 21, 18.5, 1.5)), ("s15", "M14 4.5A4 4 0 0 1 18 8.5M16.5 7.5L18 9L19.5 7.5")],
    "ic_paper_size": [("s", PAGE), ("s15", "M9 17L15 7M9 13.5V17H12.5M15 10.5V7H11.5")],
    "ic_show_formulas": [("s", rr(3, 5, 21, 19, 2)), ("s", "M8.5 10.5H15.5M8.5 13.5H15.5")],
    "ic_no_color": [("s", circle(12, 12, 8)), ("s", "M6.3 17.7L17.7 6.3")],
    "ic_move": [("s", "M12 3.5V20.5M3.5 12H20.5"), ("s", "M9.5 6L12 3.5L14.5 6M9.5 18L12 20.5L14.5 18M6 9.5L3.5 12L6 14.5M18 9.5L20.5 12L18 14.5")],
    "ic_header_footer": [("s", PAGE), ("f", "M8 6H16V8.5H8ZM8 15.5H16V18H8Z"), ("s15", "M8 12H16")],
    "ic_header": [("s", PAGE), ("f", "M8 6H16V8.5H8Z"), ("s15", "M8 12.5H16M8 15.5H16M8 18.5H13")],
    "ic_footer": [("s", PAGE), ("s15", "M8 6.5H16M8 9.5H16M8 12.5H13"), ("f", "M8 15.5H16V18H8Z")],
    "ic_page_number": [("s", PAGE), ("s15", "M8 7.5H16M8 10.5H16"), ("f", "M10.5 15H13.5V18H10.5Z")],
    "ic_wordart": [("s15", "M4.5 20L10.5 4H13.5L19.5 20H16L14.6 16H9.4L8 20ZM10.3 13H13.7L12 8.3Z")],
    "ic_slide_number": [("s", rr(3, 5, 21, 19, 2)), ("s15", "M10.5 9V15M13.5 9V15M8.5 10.75H15.5M8.5 13.25H15.5")],
    "ic_designer": [("f", "M11 3L12.6 9.4L19 11L12.6 12.6L11 19L9.4 12.6L3 11L9.4 9.4Z"), ("f", "M18.5 14.5L19.3 17.2L22 18L19.3 18.8L18.5 21.5L17.7 18.8L15 18L17.7 17.2Z")],
    "ic_slide_size": [("s", rr(3, 6, 21, 18, 2)), ("s15", "M8 15L16 9M8 11.5V15H11.5M16 12.5V9H12.5")],
    "ic_format_background": [("s", rr(3, 5, 21, 19, 2)), ("f", "M19 9V16A1 1 0 0 1 18 17H11Z")],
    "ic_slide_master": [("s", rr(3, 9, 17, 20, 2)), ("s", "M7 6V5.5A1.5 1.5 0 0 1 8.5 4H19.5A1.5 1.5 0 0 1 21 5.5V13.5A1.5 1.5 0 0 1 19.5 15H19")],
    "ic_comment_add": [("s", "M6 4H18A2 2 0 0 1 20 6V15A2 2 0 0 1 18 17H11L7 20.5V17H6A2 2 0 0 1 4 15V6A2 2 0 0 1 6 4Z"), ("s", "M12 7.5V13.5M9 10.5H15")],
    "ic_comment_delete": [("s", "M6 4H18A2 2 0 0 1 20 6V15A2 2 0 0 1 18 17H11L7 20.5V17H6A2 2 0 0 1 4 15V6A2 2 0 0 1 6 4Z"), ("s", "M9.5 8L14.5 13M14.5 8L9.5 13")],
    "ic_comments": [("s", "M4 3H14A2 2 0 0 1 16 5V11A2 2 0 0 1 14 13H9L6 15.5V13H4"), ("s", "M19 9H20A1.5 1.5 0 0 1 21.5 10.5V16.5A1.5 1.5 0 0 1 20 18H19V20.5L16 18H11.5A1.5 1.5 0 0 1 10 16.5V15.5")],
    "ic_outline_view": [("s", "M3 5H21M8 10H21M8 15H21M3 20H21")],
    "ic_ruler": [("s", rr(3, 8, 21, 16, 1.5)), ("s15", "M6.5 8V11M10 8V12M13.5 8V11M17 8V12")],
    "ic_gridlines": [("s15", "M3 8H21M3 16H21M8 3V21M16 3V21")],
    "ic_guides": [("s", rr(3, 3, 21, 21, 2)), ("s15", "M12 3V21M3 12H21")],
    "ic_line_thicker": [("f", "M3 9.5H13V14.5H3Z"), ("s", "M18 8V16M14 12H22")],
    "ic_line_thinner": [("s15", "M3 12H13"), ("s", "M15 12H21")],
    "ic_select_multiple": [("s", rr(3, 3, 10, 10, 1.5)), ("s", rr(14, 3, 21, 10, 1.5)), ("f", "M11.5 12L20.5 15.5L16.8 16.8L15.5 20.5Z")],
    "ic_distribute_h": [("s", "M4 4V20M20 4V20"), ("s", rr(9, 8, 15, 16, 1.5))],
    "ic_distribute_v": [("s", "M4 4H20M4 20H20"), ("s", rr(8, 9, 16, 15, 1.5))],
    "ic_align_objects": [("s", "M4 3V21"), ("s", rr(7.5, 5.5, 20, 10.5, 1.5)), ("s", rr(7.5, 13.5, 15, 18.5, 1.5))],
    "ic_shape_height": [("s", "M5 3H19M5 21H19"), ("s15", "M12 6.5V17.5M9 9.5L12 6.5L15 9.5M9 14.5L12 17.5L15 14.5")],
    "ic_shape_width": [("s", "M3 5V19M21 5V19"), ("s15", "M6.5 12H17.5M9.5 9L6.5 12L9.5 15M14.5 9L17.5 12L14.5 15")],
    "ic_chart_add": [("s", "M3 3V21H21"), ("s", "M7.5 17V12M11.5 17V8"), ("s15", "M18 4V12M14 8H22")],
    "ic_reset_picture": [("s", "M13 4H6A2 2 0 0 0 4 6V18A2 2 0 0 0 6 20H18A2 2 0 0 0 20 18V12"), ("s15", "M4 16L9 11L14 16"), ("s15", "M13.5 7A3.5 3.5 0 1 0 17 3.5M18.8 1.7L17 3.5L18.8 5.3")],
    "ic_draw_table": [("s", rr(3, 3, 15, 15, 2)), ("s", "M3 9H15M9 3V15"), ("f", "M13 21H16L21.5 15.5L18.5 12.5L13 18Z")],
    "ic_switch_row_col": [("s", rr(3, 3, 21, 21, 2)), ("s", "M3 9H21M9 3V21"), ("s15", "M12.5 12.5L18 18M12.5 15.5V12.5H15.5M18 15V18H15")],
    "ic_edit_data": [("s", rr(3, 3, 15, 15, 2)), ("s15", "M3 7.5H15M3 11H15M7.5 3V15"), ("f", "M13 21H16L21.5 15.5L18.5 12.5L13 18Z")],
    "ic_custom_show": [("s", rr(3, 5, 21, 19, 2)), ("s15", "M7 9H17M7 12H17M7 15H13")],
    "ic_show_setup": [("s", rr(3, 4, 21, 15, 2)), ("s", "M12 15V20M8 20.5H16")],
    "ic_export_pdf": [("s", "M13 3H7A2 2 0 0 0 5 5V19A2 2 0 0 0 7 21H17A2 2 0 0 0 19 19V9L13 3Z"), ("s", "M13 3V9H19"), ("s15", "M12 11.5V18M9.5 15.5L12 18L14.5 15.5")],
    "ic_apply_all": [("s", "M7 6V5.5A1.5 1.5 0 0 1 8.5 4H19.5A1.5 1.5 0 0 1 21 5.5V13.5A1.5 1.5 0 0 1 19.5 15H19"), ("s", rr(3, 9, 17, 20, 2)), ("s15", "M7 14.5L9.5 17L13.5 12.5")],
    "ic_convert_range": [("s", rr(3, 4, 13, 20, 1.5)), ("s15", "M3 9.5H13M3 14.75H13M8 4V20"), ("s", "M15 12H21M18.5 9.5L21 12L18.5 14.5")],
    "ic_bookmark": [("s", "M6 3H18V21L12 16.5L6 21Z")],
    "ic_odd_even": [("s", rr(3, 4, 11, 20, 1.5)), ("s", rr(13, 4, 21, 20, 1.5)), ("s15", "M5.5 8H8.5M15.5 8H18.5")],
    "ic_first_page": [("s", PAGE), ("s15", "M11 8L13 7V13M8 17H16")],
    "ic_merge_down": [("s", rr(3, 3, 21, 21, 2)), ("s", "M12 3V21M3 12H12")],
    "ic_distribute_columns": [("s", rr(3, 5, 21, 19, 2)), ("s", "M9 5V19M15 5V19")],
    "ic_distribute_rows": [("s", rr(5, 3, 19, 21, 2)), ("s", "M5 9H19M5 15H19")],
    "ic_table_to_text": [("s", rr(3, 3, 12, 12, 1.5)), ("s15", "M3 7.5H12M7.5 3V12"), ("s", "M15 7.5H21M3 16H21M3 20.5H15")],
    "ic_palette": [("s", "M12 3A9 9 0 1 0 12 21C13.5 21 14 20 14 19C14 17.5 15 17 16.5 17H18A3 3 0 0 0 21 14A9 9 0 0 0 12 3Z"), ("f", dots((8, 10), (11.5, 7), (16, 9)))],
    "ic_watermark": [("s", PAGE), ("s15", "M12 8C12 8 15 11.5 15 13.5A3 3 0 0 1 9 13.5C9 11.5 12 8 12 8Z")],
    "ic_page_color": [("s", PAGE), ("f", "M8 12H16V18H8Z")],
    "ic_columns": [("s", "M3 5H10M3 9H10M3 13H10M3 17H10M14 5H21M14 9H21M14 13H21M14 17H21")],
    "ic_breaks": [("s", "M5 3V7A2 2 0 0 0 7 9H17A2 2 0 0 0 19 7V3"), ("s", "M3 12H6M9 12H15M18 12H21"), ("s", "M5 21V17A2 2 0 0 1 7 15H17A2 2 0 0 1 19 17V21")],
    "ic_tabs": [("s", "M3 19H21"), ("s", "M5 5V14H10M19 5V14H14")],
    # Excel's Page Break Preview: a page of cells cut across by a dashed break.
    "ic_page_break_preview": [("s", rr(4, 3, 20, 21, 1.5)), ("s15", "M8 7.5H16M8 16.5H16"), ("s", "M2 12H5M8 12H11M14 12H17M20 12H22")],
    # Word's Line Numbers: lines of text with 1, 2, 3 in the margin beside them.
    "ic_line_numbers": [("s", "M10 5.5H21M10 12H21M10 18.5H21"), ("s15", "M4.2 4.2L5.4 3.4V8"),
                        ("s15", "M3.6 10.6A1.4 1.4 0 1 1 5.9 11.9L3.6 14.6H6.6"), ("s15", "M3.6 16.4H6.2L4.8 18.2A1.4 1.4 0 1 1 3.6 20.6")],
    "ic_caption": [("s", rr(3, 3, 21, 15, 2)), ("s15", "M6.5 12L10 8.5L13.5 12M12 10.5L14 8.5L17.5 12"), ("s", "M6 19.5H18")],
    "ic_table_of_figures": [("f", "M3 5H8V10H3ZM3 14H8V19H3Z"), ("s", "M11 7.5H21M11 16.5H21")],
    # Word's Mark Entry: lines of text, a tag hung on the last one marks it for the index.
    "ic_mark_entry": [("s15", "M3 5H17M3 9.5H13M3 14H9"), ("s", "M12 13H17.5L21 16.75L17.5 20.5H12Z"), ("f", circle(14.6, 16.75, 1.1))],
    # Word's Insert Index: a letter heading over the entries under it.
    "ic_insert_index": [("s", "M3.5 12L6.5 4L9.5 12M4.6 9.3H8.4"), ("s15", "M12.5 5.5H20.5M12.5 9.5H18.5M3.5 15.5H20.5M6.5 19.5H17")],
    "ic_cross_reference": [("s", rr(3, 3, 11, 13, 1.5)), ("s", rr(13, 11, 21, 21, 1.5)), ("s15", "M7 15V17.5H11M9.5 16L11 17.5L9.5 19")],
    "ic_markup": [("s", "M4 6H20M4 12H13M4 18H10"), ("s15", "M14 18H20M15 14.5L17 11.5L19 14.5")],
    "ic_accept": [("s", rr(3, 3, 15, 17, 1.5)), ("s15", "M6 7.5H12M6 10.5H12"), ("s", "M11 17L14.5 20.5L21 13.5")],
    "ic_reject": [("s", rr(3, 3, 15, 17, 1.5)), ("s15", "M6 7.5H12M6 10.5H12"), ("s", "M14.5 14.5L20.5 20.5M20.5 14.5L14.5 20.5")],
    "ic_print_layout": [("s", rr(6, 3, 18, 21, 1.5)), ("s15", "M9 7.5H15M9 11H15M9 14.5H13")],
    "ic_web_layout": [("s", rr(3, 5, 21, 19, 2)), ("s", "M3 9H21"), ("s15", "M6 13H18M6 16H14")],
    "ic_one_page": [("s", rr(7, 3, 17, 21, 1.5)), ("s15", "M9.5 7.5H14.5M9.5 10.5H14.5")],
    "ic_multi_page": [("s", rr(3, 4, 11, 20, 1.5)), ("s", rr(13, 4, 21, 20, 1.5))],
    "ic_effect_options": [("s", "M9 3L10.7 7.15L15.18 7.49L11.76 10.4L12.82 14.76L9 12.4L5.18 14.76L6.24 10.4L2.82 7.49L7.3 7.15Z"), ("s", "M13 19H21M18 16L21 19L18 22")],
    "ic_dialog_launcher": [("s", "M20 10V5.5A1.5 1.5 0 0 0 18.5 4H5.5A1.5 1.5 0 0 0 4 5.5V18.5A1.5 1.5 0 0 0 5.5 20H10"), ("s", "M11 11L20 20M20 14V20H14")],
    "ic_focus": [("s", rr(8.5, 7, 15.5, 17, 1)), ("s", "M3 7.5V3H7.5M16.5 3H21V7.5M21 16.5V21H16.5M7.5 21H3V16.5")],
    "ic_focus_exit": [("s", "M7.5 3V7.5H3M21 7.5H16.5V3M16.5 21V16.5H21M3 16.5H7.5V21")],
    "ic_page_width": [("s", PAGE), ("s15", "M8 12H16M10 10L8 12L10 14M14 10L16 12L14 14")],
    "ic_conditional_format": [("s", rr(3, 3, 21, 21, 2)), ("s15", "M3 9H21M3 15H21M9 3V21M15 3V21"), ("f", "M9.75 9.75H14.25V14.25H9.75Z")],
    "ic_pivot": [("s", rr(3, 3, 21, 21, 2)), ("f", "M8 4H20V8H8ZM4 8H8V20H4Z"), ("s15", "M8 14H21M14 8V21")],
    "ic_spark_line": [("s", rr(3, 6, 21, 18, 2)), ("s15", "M6 14.5L10 10.5L13 13L18 8.5")],
    "ic_spark_column": [("s", rr(3, 6, 21, 18, 2)), ("f", "M7 12H9.5V15H7ZM10.75 9H13.25V15H10.75ZM14.5 11H17V15H14.5Z")],
    "ic_spark_winloss": [("s", rr(3, 6, 21, 18, 2)), ("f", "M7 8.5H9.5V12H7ZM10.75 12H13.25V15.5H10.75ZM14.5 8.5H17V12H14.5Z")],
    "ic_tag": [("s", "M3.5 3.5H11L20.5 13L13 20.5L3.5 11Z"), ("f", circle(7.5, 7.5, 1.6))],
    "ic_sort_custom": [("s", "M3 6H11M3 11.5H11M3 17H8"), ("s", "M17 4V20M14 7L17 4L20 7M14 17L17 20L20 17")],
    "ic_filter_clear": [("s", "M3 5H16L11.5 10.5V17L7.5 15V10.5Z"), ("s", "M15 15L20 20M20 15L15 20")],
    "ic_remove_duplicates": [("s", rr(3, 4, 15, 9, 1.5)), ("s", rr(3, 12, 15, 17, 1.5)), ("s", "M17 15L21 19M21 15L17 19")],
    "ic_data_validation": [("s", rr(3, 4, 15, 13, 1.5)), ("s", "M10 17.5L13.5 21L20.5 13.5")],
    "ic_print_area": [("s", PAGE), ("f", "M8 7H16V13H8Z")],
    "ic_fit_page": [("s", PAGE), ("s15", "M8 7L11 10M8 10V7H11M16 17L13 14M16 14V17H13")],
    "ic_unlock": [("s", rr(5, 10, 19, 21, 2)), ("s", "M8 10V7A4 4 0 0 1 15.8 5.7"), ("f", circle(12, 15.5, 1.6))],
    "ic_zoom_selection": [("s", circle(10.5, 10.5, 6.5)), ("s", "M15.4 15.4L20.5 20.5"), ("f", "M8.5 8.5H12.5V12.5H8.5Z")],
    "ic_freeze": [("s", rr(3, 3, 21, 21, 2)), ("f", "M4 8H20V10H4ZM8 4H10V20H8Z"), ("s15", "M3 15H21M15 3V21")],
    "ic_line_double": [("s", "M4 12H20M7 9L4 12L7 15M17 9L20 12L17 15")],
    "ic_chart_bar": [("s", "M3 3V21H21"), ("f", "M6 6H14V9H6ZM6 11H18V14H6ZM6 16H11V19H6Z")],
    "ic_chart_line": [("s", "M3 3V21H21"), ("s", "M6.5 16L10.5 11L13.5 14L19.5 7")],
    "ic_chart_area": [("s", "M3 3V21H21"), ("f", "M6 18V13L10.5 8.5L14 12L19 7V18Z")],
    "ic_chart_pie": [("s", circle(12, 12, 8.5)), ("f", "M12 4.5A7.5 7.5 0 0 1 19.5 12H12Z")],
    "ic_chart_doughnut": [("s", circle(12, 12, 8.5)), ("s", circle(12, 12, 3.5)), ("s", "M12 3.5V8.5M15.5 12H20.5")],
    "ic_chart_scatter": [("s", "M3 3V21H21"), ("f", dots((8, 15), (11, 10), (14, 13), (17, 7), (18, 16), r=1.6))],
    "ic_chart_radar": [("s", "M12 3L20.5 9.2L17.3 19H6.7L3.5 9.2Z"), ("s15", "M12 7.5L15.5 11L13 15.5H9.5L8.5 10.5Z")],
    "ic_more": [("f", dots((5.5, 12), (12, 12), (18.5, 12), r=1.75))],
    # Floating bars on the canvas
    "ic_open_link": [
        ("s", "M11 5H7A2 2 0 0 0 5 7V17A2 2 0 0 0 7 19H17A2 2 0 0 0 19 17V13"),
        ("s", "M14 4H20V10M20 4L11.5 12.5"),
    ],
    "ic_unlink": [
        ("s", "M10.5 7.5L12.4 5.6A3.5 3.5 0 0 1 17.4 10.6L15.5 12.5"),
        ("s", "M13.5 16.5L11.6 18.4A3.5 3.5 0 0 1 6.6 13.4L8.5 11.5"),
        ("s", "M4 4L7 7M17 17L20 20M3 9H6M9 3V6M21 15H18M15 21V18"),
    ],
    # Word's Layout Options: how text goes by a picture or a shape.
    "ic_wrap_inline": [("s", "M3 5H21M11 12H21M3 19H21"), ("f", rr(3, 9, 9, 15, 0.5))],
    "ic_wrap_square": [("s", "M3 4H21M3 10H6M3 14H6M18 10H21M18 14H21M3 20H21"), ("f", rr(8, 7.5, 16, 16.5, 0.5))],
    "ic_wrap_top_bottom": [("s", "M3 4H21M3 20H21"), ("f", rr(7, 8, 17, 16, 0.5))],
    "ic_wrap_behind": [("s15", rr(7, 3.75, 17, 20.25, 0.5)), ("s", "M3 7H21M3 12H21M3 17H21")],
    "ic_wrap_front": [("s", "M3 7H6M18 7H21M3 12H6M18 12H21M3 17H6M18 17H21"), ("f", rr(7, 3, 17, 21, 0.5))],
    "ic_size_grow": [
        ("s", rr(8, 8, 16, 16, 1)),
        ("s", "M3 8V3H8M3 3L6.5 6.5M21 16V21H16M21 21L17.5 17.5"),
    ],
    "ic_size_shrink": [
        ("s", rr(9, 9, 15, 15, 1)),
        ("s", "M7 3V7H3M7 7L3 3M17 21V17H21M17 17L21 21"),
    ],
    "ic_save_as": [
        ("s", "M11 20H6A2 2 0 0 1 4 18V6A2 2 0 0 1 6 4H15.6L20 8.4V11"),
        ("s", "M8 4.5V8.5H14.5V4.5"),
        ("f", "M13 21H15.8L21.5 15.3L18.7 12.5L13 18.2Z"),
    ],
    "ic_smartart": [("s", rr(3, 9, 9, 15, 1.5)), ("s", rr(15, 3, 21, 9, 1.5)), ("s", rr(15, 15, 21, 21, 1.5)), ("s15", "M9 12H12V6H15M12 12V18H15")],
}

# --- What the existing ribbon keys draw now ---------------------------------------------------------
ALIASES = {
    "⌂": "ic_home", "↶": "ic_undo", "↷": "ic_redo", "💾": "ic_save", "💾*": "ic_save",
    "⧉": "ic_copy", "✂": "ic_cut", "📋": "ic_paste", "🖌": "ic_format_painter",
    "A↑": "ic_font_grow", "A↓": "ic_font_shrink", "⟷": "ic_char_spacing", "•": "ic_bullets", "1.": "ic_numbering", "⇤": "ic_indent_decrease", "⇥": "ic_indent_increase",
    "indent_dec": "ic_indent_decrease", "indent_inc": "ic_indent_increase", "↕": "ic_line_spacing",
    "⇠": "ic_align_left", "⇔": "ic_align_center", "⇢": "ic_align_right", "⇿": "ic_align_justify",
    "✎": "ic_edit", "x_search": "ic_find", "⇄": "ic_replace", "→": "ic_arrow_right",
    "🗑": "ic_delete", "🔗": "ic_link", "🖼": "ic_picture", "x_crop": "ic_crop", "🎬": "ic_video",
    "🔊": "ic_audio", "▶": "ic_play", "⏵": "ic_play", "x_gear": "ic_settings", "x_lock": "ic_lock",
    "💬": "ic_comment", "☰": "ic_list", "kbd_help": "ic_keyboard", "✕": "ic_close", "A": "ic_font_color", "🗒": "ic_notes",
    "填充": "ic_fill", "阴影": "ic_shadow", "轮廓": "ic_outline", "▆": "ic_highlight",
    "⬛": "ic_shape_rect", "▢": "ic_shape_rounded", "◯": "ic_shape_oval", "△": "ic_shape_triangle", "☆": "ic_shape_star",
    "➜": "ic_shape_arrow", "─": "ic_shape_line", "╲": "ic_shape_line", "➕": "ic_new_slide", "dup": "ic_copy", "📊": "ic_chart",
    "x_chart": "ic_chart", "置顶": "ic_bring_front", "置底": "ic_send_back", "前移": "ic_bring_forward", "后移": "ic_send_backward",
    "文本框": "ic_textbox", "拼写": "ic_spelling", "▦": "ic_table", "x_table": "ic_table", "▤": "ic_layout", "⌫": "ic_eraser",
    "↻": "ic_rotate_right", "↺": "ic_rotate_left", "flipH": "ic_flip_h", "flipV": "ic_flip_v", "x_zoom": "ic_zoom",
    "x_sortasc": "ic_sort_asc", "x_sortdesc": "ic_sort_desc", "x_filter": "ic_filter", "x_merge": "ic_merge", "x_bdall": "ic_borders",
    "x_valtop": "ic_valign_top", "⤒": "ic_valign_top", "x_valmid": "ic_valign_middle", "⇕": "ic_valign_middle",
    "x_valbottom": "ic_valign_bottom", "⤓": "ic_valign_bottom", "x_wrap": "ic_wrap_text",
    "▲": "ic_arrow_up", "▼": "ic_arrow_down", "◀": "ic_chevron_left", "100": "ic_actual_size",
    "↗": "ic_share", "🌐": "ic_share", "⛓": "ic_link", "🗑行": "ic_delete_row", "🗑列": "ic_delete_col",
    "上方插入": "ic_insert_row_above", "下方插入": "ic_insert_row_below", "插入列": "ic_insert_col_right", "x_insright": "ic_insert_col_right",
    "x_insrow": "ic_insert_row_above", "x_inscol": "ic_insert_col_left", "x_filldown": "ic_fill_down", "📅": "ic_calendar",
    "⊞": "ic_group", "⊟": "ic_ungroup", "⊕": "ic_plus_circle", "⊖": "ic_minus_circle",
    "宽+": "ic_width_increase", "宽-": "ic_width_decrease", "高+": "ic_height_increase", "高-": "ic_height_decrease", "x_cellsize": "ic_width_increase",
    "x_tablestyle": "ic_table_style", "x_split": "ic_text_to_columns", "x_headers": "ic_print_titles", "x_margins": "ic_margins",
    "x_orientation": "ic_orientation", "x_paper": "ic_paper_size", "x_papersize": "ic_paper_size", "x_showformula": "ic_show_formulas", "x_resetsize": "ic_rotate_left",
    "∅": "ic_no_color", "✥": "ic_move", "◫": "ic_smartart", "页眉页脚": "ic_header_footer", "艺术字": "ic_wordart", "#": "ic_slide_number",
    "设计器": "ic_designer", "幻灯片大小": "ic_slide_size", "背景格式": "ic_format_background", "母版": "ic_slide_master",
    "辅助功能": "ic_accessibility", "新建批注": "ic_comment_add", "显示批注": "ic_comments", "大纲": "ic_outline_view", "浏览": "ic_grid",
    "备注页": "ic_notes", "阅读视图": "ic_book", "ruler": "ic_ruler", "grid": "ic_gridlines", "guides": "ic_guides", "缩放": "ic_zoom",
    "适应窗口": "ic_expand", "线粗+": "ic_line_thicker", "线粗-": "ic_line_thinner", "⊕⊕": "ic_select_multiple", "⇹": "ic_distribute_h",
    "⇳": "ic_distribute_v", "⊡": "ic_actual_size", "⟲": "ic_rotate_left", "绘制表格": "ic_draw_table", "橡皮擦": "ic_eraser",
    "切换行列": "ic_switch_row_col", "编辑数据": "ic_edit_data", "⬡3D": "ic_cube", "x_customshow": "ic_custom_show", "👤": "ic_person",
    "▣": "ic_tv", "x_newwindow": "ic_window_new", "x_showsetup": "ic_show_setup", "x_hideslide": "ic_eye_slash", "x_rehearse": "ic_clock",
    "export_pdf": "ic_export_pdf", "x_applyall": "ic_apply_all", "逐项": "ic_bullets", "x_torange": "ic_convert_range", "bookmark": "ic_bookmark",
    "x_header": "ic_header", "x_footer": "ic_footer", "x_pagenumber": "ic_page_number", "①②": "ic_odd_even", "①": "ic_first_page",
    "x_mergedown": "ic_merge_down", "x_distribute": "ic_distribute_columns", "x_totext": "ic_table_to_text", "🎨": "ic_palette", "x_theme": "ic_palette",
    "💧": "ic_watermark", "▥": "ic_page_color", "x_pagecolumns": "ic_columns", "x_breaks": "ic_breaks", "x_tabs": "ic_tabs",
    "x_sources": "ic_book", "≣": "ic_document", "x_caption": "ic_caption", "x_tof": "ic_table_of_figures", "x_xref": "ic_cross_reference",
    "x_updatefields": "ic_rotate_right", "x_spatialnote": "ic_cube", "x_markup": "ic_markup", "x_acceptall": "ic_accept", "x_rejectall": "ic_reject",
    "x_printlayout": "ic_print_layout", "x_weblayout": "ic_web_layout", "x_onepage": "ic_one_page", "x_multipage": "ic_multi_page", "x_focus": "ic_focus",
    "x_pagewidth": "ic_page_width", "−": "ic_minus", "+": "ic_plus", "x_condfmt": "ic_conditional_format", "x_pivot": "ic_pivot",
    "x_sparkline": "ic_spark_line", "x_sparkcolumn": "ic_spark_column", "x_sparkwinloss": "ic_spark_winloss", "x_clearspark": "ic_eraser",
    "x_errorcheck": "ic_warning", "x_names": "ic_tag", "🏷": "ic_tag", "x_fromsel": "ic_select_all", "x_sortmulti": "ic_sort_custom",
    "x_filterclr": "ic_filter_clear", "x_dedupe": "ic_remove_duplicates", "x_validate": "ic_data_validation", "x_target": "ic_sight",
    "x_printarea": "ic_print_area", "x_fit": "ic_fit_page", "x_delcomment": "ic_comment_delete", "x_unlock": "ic_unlock",
    "x_zoomsel": "ic_zoom_selection", "x_freeze": "ic_freeze", "x_freezerow": "ic_freeze", "x_unfreeze": "ic_freeze", "↔": "ic_line_double",
    "x_fn_info": "ic_info", "x_selectall": "ic_select_all", "x_normal": "ic_print_layout", "x_formulabar": "ic_show_formulas",
    "x_bdout": "ic_borders", "x_bdnone": "ic_no_color", "x_fillright": "ic_fill_down", "x_clearrules": "ic_eraser", "x_hidecol": "ic_eye_slash",
    "x_unhidecol": "ic_eye", "x_hiderow": "ic_eye_slash", "x_unhiderow": "ic_eye", "x_groupcol": "ic_group", "x_ungroupcol": "ic_ungroup",
    "x_fillseries": "ic_fill_down", "x_crop": "ic_crop",
    "›": "ic_chevron_right", "⬆": "ic_arrow_up", "⋯": "ic_more", "⤡": "ic_expand", "⚠": "ic_warning", "＋": "ic_plus",
    "📝": "ic_edit", "✔": "ic_check", "✘": "ic_close", "x_openlink": "ic_open_link", "x_unlink": "ic_unlink",
    "x_bigger": "ic_size_grow", "x_smaller": "ic_size_shrink",
}


def vector_xml(parts, mirror=False):
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp"', '    android:height="24dp"',
           '    android:viewportWidth="24"', '    android:viewportHeight="24">']
    ind = "    "
    if mirror:
        out.append('    <group android:pivotX="12" android:scaleX="-1">')
        ind = "        "
    for part in parts:
        kind, d = part[0], part[1]
        if kind == "icon70":
            out.append(f'{ind}<group android:pivotX="12" android:pivotY="2.5" android:scaleX="{part[2]}" android:scaleY="{part[2]}">')
            for k2, d2 in icon70_parts(d):
                ft = ' android:fillType="evenOdd"' if k2 == "e" else ""
                out.append(f'{ind}    <path android:pathData="{d2}" android:fillColor="#FF000000"{ft}/>')
            out.append(f'{ind}</group>')
            continue
        if kind in ("s", "s15"):
            w = "2" if kind == "s" else "1.5"
            out.append(f'{ind}<path android:pathData="{d}" android:strokeColor="#FF000000" android:strokeWidth="{w}"'
                       ' android:strokeLineCap="butt" android:strokeLineJoin="miter" android:fillColor="#00000000"/>')
        else:
            ft = ' android:fillType="evenOdd"' if kind == "e" else ""
            out.append(f'{ind}<path android:pathData="{d}" android:fillColor="#FF000000"{ft}/>')
    if mirror:
        out.append("    </group>")
    out.append("</vector>")
    return "\n".join(out) + "\n"


def svg_of(parts, mirror=False):
    body = []
    for part in parts:
        kind, d = part[0], part[1]
        if kind == "icon70":
            k = part[2]
            inner = "".join(f'<path d="{d2}" fill="#000"{" fill-rule=\'evenodd\'" if k2 == "e" else ""}/>' for k2, d2 in icon70_parts(d))
            body.append(f'<g transform="translate(12 2.5) scale({k}) translate(-12 -2.5)">{inner}</g>')
            continue
        if kind in ("s", "s15"):
            w = 2 if kind == "s" else 1.5
            body.append(f'<path d="{d}" fill="none" stroke="#000" stroke-width="{w}" stroke-linecap="butt" stroke-linejoin="miter"/>')
        else:
            rule = ' fill-rule="evenodd"' if kind == "e" else ""
            body.append(f'<path d="{d}" fill="#000"{rule}/>')
    g = "".join(body)
    if mirror:
        g = f'<g transform="translate(24 0) scale(-1 1)">{g}</g>'
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">{g}</svg>'


def icon70_parts(name):
    return ICON70[name]


def main():
    os.makedirs(DRAWABLE, exist_ok=True)
    resolved = {}
    for res, src in FROM_ICON70.items():
        resolved[res] = (icon70_parts(src), False)
    for res, spec in CUSTOM.items():
        if isinstance(spec, str) and spec.startswith("mirror:"):
            continue
        resolved[res] = (spec, False)
    for res, spec in CUSTOM.items():
        if isinstance(spec, str) and spec.startswith("mirror:"):
            resolved[res] = (resolved[spec[7:]][0], True)
    for res, (parts, mirror) in sorted(resolved.items()):
        with open(os.path.join(DRAWABLE, res + ".xml"), "w") as f:
            f.write(vector_xml(parts, mirror))
    for key, res in ALIASES.items():
        assert res in resolved, f"{key} -> {res} is not drawn"
    # The Kotlin table: resource names and the old keys.
    lines = ["package com.therealityteam.aeropages.ui", "",
             "import com.therealityteam.aeropages.R", "",
             "// Generated by tools/icons/make-icons.py — edit the script, not this file.", "",
             "/** The app's vector icons by name (\"ic_save\") and by the ribbon keys that used to be drawn (\"💾\"). */",
             "internal object AeroIcons {",
             "    val byName: Map<String, Int> = mapOf("]
    for res in sorted(resolved):
        lines.append(f'        "{res}" to R.drawable.{res},')
    lines += ["    )", "", "    private val aliases: Map<String, String> = mapOf("]
    for key, res in ALIASES.items():
        k = key.replace("\\", "\\\\").replace('"', '\\"')
        lines.append(f'        "{k}" to "{res}",')
    lines += ["    )", "",
              "    /** The drawable for [key] (a name or an old key), or 0. */",
              "    fun of(key: String): Int = byName[key] ?: aliases[key]?.let { byName[it] } ?: 0",
              "}", ""]
    with open(KOTLIN, "w") as f:
        f.write("\n".join(lines))
    print(f"{len(resolved)} icons, {len(ALIASES)} aliases")

    if "--sheet" in sys.argv:
        sheet = sys.argv[sys.argv.index("--sheet") + 1]
        tmp = tempfile.mkdtemp()
        pngs = []
        for res, (parts, mirror) in sorted(resolved.items()):
            svg = os.path.join(tmp, res + ".svg")
            open(svg, "w").write(svg_of(parts, mirror))
            png = os.path.join(tmp, res + ".png")
            subprocess.run(["rsvg-convert", "-w", "48", "-h", "48", "-b", "white", "-o", png, svg], check=True)
            pngs.append(png)
        os.makedirs(os.path.dirname(os.path.abspath(sheet)), exist_ok=True)
        font = "/System/Library/Fonts/HelveLTMM"
        subprocess.run(["magick", "montage", "-font", font, "-label", "%t", *pngs, "-tile", "10x",
                        "-geometry", "48x48+26+8", "-pointsize", "10", sheet], check=True)
        print("sheet:", sheet)


if __name__ == "__main__":
    main()
