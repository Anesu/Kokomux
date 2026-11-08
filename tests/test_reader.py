"""Tests for the reader's focus effect (src/tui.sh with src/theme.sh).

Run with:  python3 tests/test_reader.py   (or pytest)

The reading column is drawn as it would be on a 104×34 dark terminal, and the
colour of each row is read back as a level: 0 is the background, 1000 the text
colour. Text should fade with its distance from the part being read.
"""
import os
import re
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "src")
BG, FG = (0x16, 0x16, 0x1E), (0xE8, 0xE6, 0xF0)
PARTS = [
    "The road left the village at first light and climbed into the hills, where the fields gave way to heather.",
    "The wind never quite stopped up there, and the sky seemed closer than it did in the valley.",
    "For three days she walked without meeting anyone, sleeping in the lee of stone walls.",
    "She kept the map folded in her coat, though she had long since learned it by heart.",
    "Chapter Two. On the fourth morning a cart appeared on the ridge, pulled by a tired grey horse with a white blaze.",
    "The driver offered her a ride as far as the river, and after a moment's thought she accepted.",
    "They crossed the bridge at dusk, and the town lights came up one by one across the water.",
    "Neither of them spoke much. The horse knew the way, and the evening was too fine to fill with talk.",
    "At the inn by the ferry she paid for a room with the last of her coins and slept until noon.",
]


def draw(focus=1, a=41, b=49, ease_ms=None, extra=""):
    """Draw the column with part 5 playing and bytes a..b lit; return {row: [(level, text)]}."""
    with tempfile.TemporaryDirectory() as d:
        os.makedirs(os.path.join(d, "stream"))
        for i, t in enumerate(PARTS, 1):
            with open(os.path.join(d, "stream", f"{i:04d}.txt"), "w") as f:
                f.write(t)
        ease = f"now_ms; TUI_EASE_AT=$(( NOW - {ease_ms} ));" if ease_ms is not None else ""
        script = f"""
now_ms() {{ local t=${{EPOCHREALTIME//[!0-9]/}}; NOW=$(( t / 1000 )); }}
. {SRC}/theme.sh; theme_apply; . {SRC}/tui.sh
tmpd={d}; TW=104 TH=34 CW=76 LM=14 TUI_FOCUS={focus}
tui_hist=(1 2 3 4); TUI_PART=5 TUI_PARTS=9 TUI_A={a} TUI_B={b}
{ease} {extra}
tui_text; flush; echo "ease_at=$TUI_EASE_AT" >&2   # (a frame is written in one go)
"""
        env = {"PATH": os.environ["PATH"], "HOME": d, "KOKORO_TRUECOLOR": "1", "LANG": "C.UTF-8"}
        out = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, check=True).stderr
    rows = {}
    for m in re.finditer(r"\033\[(\d+);1H\033\[2K\033\[\d+G(.*?)(?=\033\[\d+;1H|\033\[\?2026l)", out, re.S):
        segs, level = [], 1000                              # 1000: the terminal's own text colour
        for tok in re.split(r"(\033\[[0-9;?]*[A-Za-z])", m.group(2)):
            c = re.fullmatch(r"\033\[38;2;(\d+);\d+;\d+m", tok)
            if c:
                level = round(1000 * (int(c.group(1)) - BG[0]) / (FG[0] - BG[0]))
            elif tok == "\033[0m":
                level = 1000
            elif not tok.startswith("\033") and tok.strip() and tok != "▎":   # (the bar marking this part)
                segs.append((level, tok))
        rows[int(m.group(1))] = segs
    return rows, out


def levels(rows):
    """Each row's level (its first coloured segment), top to bottom, with the current part marked."""
    return [(r, segs[0][0], segs[0][1]) for r, segs in sorted(rows.items()) if segs]


def test_text_fades_with_distance_from_the_part_being_read():
    rows, _ = draw()
    lv = levels(rows)
    cur = [i for i, (_, _, t) in enumerate(lv) if t.startswith("Chapter Two")][0]
    above = [l for _, l, _ in lv[:cur]]
    below = [l for _, l, _ in lv[cur + 2:]]                 # after this part's two rows
    assert above == sorted(above), above                    # brighter towards the middle
    assert below == sorted(below, reverse=True), below
    assert above[-1] >= 480 and below[0] >= 600            # next to it: still easy to read
    assert above[0] < 300 and below[-1] < 300              # at the edges: out of focus
    assert min(above + below) >= 140                       # never fainter than φ⁻⁴


def test_the_words_just_ahead_of_the_voice_are_in_sharpest_focus():
    rows, _ = draw()
    r = [r for r, segs in rows.items() if segs and segs[0][1].startswith("Chapter Two")][0]
    line = rows[r] + rows[r + 1]                            # this part's two rows
    assert line[0][0] == 1000                               # spoken: full
    ahead = [l for l, _ in line[2:]]                        # after the lit word
    assert ahead == sorted(ahead, reverse=True), ahead
    assert ahead[0] >= 780 and ahead[-1] < 700


def test_the_part_just_heard_eases_out_of_focus():
    settled, _ = draw()
    mid, _ = draw(ease_ms=191)
    done, out = draw(ease_ms=500)
    row = [r for r, segs in settled.items() if segs and segs[0][1].startswith("She kept")][0]
    a, m, d = settled[row][0][0], mid[row][0][0], done[row][0][0]
    assert a < m < 1000, (a, m)                             # halfway: between full and settled
    assert d == a and "ease_at=0" in out                    # then it stops redrawing
    far = [r for r, segs in settled.items() if segs and segs[0][1].startswith("The road")][0]
    assert mid[far][0][0] == settled[far][0][0]             # only the part just heard eases


def test_focus_off_shows_text_evenly():
    rows, _ = draw(focus=0)
    lv = {l for _, l, t in levels(rows) if not t.startswith(("Chapter Two", "a tired"))}
    assert len(lv) == 2, lv                                 # heard, and still to come


if __name__ == "__main__":
    import sys
    fails = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("ok  ", name)
            except AssertionError as e:
                fails += 1
                print("FAIL", name, e)
    sys.exit(1 if fails else 0)
