"""Tests for src/theme.sh: the colours, on dark and light terminals.

Run with:  python3 tests/test_theme.py   (or pytest)

The palette is made from the terminal's own background, so these check it
against the backgrounds of popular terminal themes: text, accents and
secondary text must stay readable (WCAG contrast ratios: text 7:1, accents 4.5:1), and the greys must
follow the golden-ratio ladder. The terminal is asked for its colours over a
pty, the way a real one answers.
"""
import os
import pty
import re
import select
import subprocess
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
THEME = os.path.join(HERE, "..", "src", "theme.sh")
PHI = (1 + 5 ** 0.5) / 2

DARK = ["000000", "121218", "16161e", "1e1e1e", "282a36", "282828", "002b36", "2e3440", "303446"]
LIGHT = ["ffffff", "fafafa", "fdf6e3", "fbf1c7", "eff1f5", "e8e8e8"]
TOKENS = "R B D PAST LINE GHOST ACC ACC2 GRN RED YEL HL SURF SEL PSEL BASE THEME_MODE THEME_PAINT THEME_FG THEME_BG"


def palette(term_bg=None, want="auto", screen=False, truecolor=1, env=None):
    """Run theme_apply in bash and return its colours."""
    with tempfile.TemporaryDirectory() as home:
        script = f". {THEME!r}; " + (f"THEME_TERM_BG={term_bg}; " if term_bg else "") + \
            f"theme_apply {'screen' if screen else ''}; for v in {TOKENS}; do printf '%s=%q\\n' \"$v\" \"${{!v}}\"; done"
        e = {"PATH": os.environ["PATH"], "HOME": home, "KOKORO_THEME": want, "KOKORO_TRUECOLOR": str(truecolor)}
        e.update(env or {})
        out = subprocess.run(["bash", "-c", script], env=e, capture_output=True, text=True, check=True).stdout
    vals = {}
    for line in out.splitlines():
        k, v = line.split("=", 1)
        if v.startswith("$'"):
            v = v[2:-1].replace("\\E", "\\x1b").encode().decode("unicode_escape")
        vals[k] = "" if v == "''" else v
    return vals


def rgb(seq, kind="38"):
    """The colour an SGR escape sets, as (r, g, b)."""
    m = re.search(kind + r";2;(\d+);(\d+);(\d+)m", seq)
    assert m, repr(seq)
    return tuple(int(x) for x in m.groups())


def hexrgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lum(c):
    def lin(v):
        v /= 255
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = map(lin, c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    a, b = lum(a), lum(b)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)


def check_readable(bg):
    p = palette(bg)
    B, fg = hexrgb(bg), hexrgb(p["THEME_FG"])
    assert p["THEME_MODE"] == ("light" if bg in LIGHT else "dark")
    assert p["THEME_BG"] == bg                       # the greys are made from the real background
    assert contrast(fg, B) >= 7, ("text", bg)               # WCAG AAA
    for k in ("ACC", "ACC2", "GRN", "RED", "YEL"):  # accents are used as text
        assert contrast(rgb(p[k]), B) >= 4.5, (k, bg, contrast(rgb(p[k]), B))
    assert contrast(rgb(p["D"]), B) >= 4.4, ("secondary text", bg)
    assert contrast(rgb(p["PAST"]), B) >= 3, ("heard text", bg)
    hl_bg, hl_fg = rgb(p["HL"], "48"), rgb(p["HL"], "38")
    assert contrast(hl_fg, hl_bg) >= 4.5, ("the lit word", bg)
    assert contrast(rgb(p["SURF"], "48"), B) >= 1.25, ("buttons stand out", bg)
    for surface in ("SURF", "SEL", "PSEL"):          # what's drawn on buttons, panels and selections
        s = rgb(p[surface], "48")
        assert contrast(fg, s) >= 5, (surface, bg)          # above WCAG AA
        for k in ("ACC", "ACC2", "D"):
            assert contrast(rgb(p[k]), s) >= 2.8, (k, "on", surface, bg, contrast(rgb(p[k]), s))


def test_readable_on_dark_terminals():
    for bg in DARK:
        check_readable(bg)


def test_readable_on_light_terminals():
    for bg in LIGHT:
        check_readable(bg)


def test_greys_follow_the_golden_ratio():
    out = subprocess.run(["bash", "-c", f". {THEME!r}; echo \"${{THEME_STEPS[*]}}\""],
                         capture_output=True, text=True, check=True).stdout.split()
    steps = [int(x) / 1000 for x in out]
    for n, s in enumerate(steps):
        assert abs(s - PHI ** -n) < 0.002, (n, s)
    # and each level is further from the background than the one before
    for bg in ("16161e", "fafafa"):
        p, B = palette(bg), hexrgb(bg)
        order = [rgb(p["SURF"], "48"), rgb(p["GHOST"]), rgb(p["LINE"]), rgb(p["PAST"]), rgb(p["D"])]
        ratios = [contrast(c, B) for c in order]
        assert ratios == sorted(ratios), (bg, ratios)


def test_mixing_is_exact():
    out = subprocess.run(["bash", "-c", f". {THEME!r}; theme_mix 000000 ffffff 618; echo $REPLY; "
                          f"theme_mix ffffff 000000 382; echo $REPLY; theme_mix 102030 102030 500; echo $REPLY"],
                         capture_output=True, text=True, check=True).stdout.split()
    assert out == ["9e9e9e", "9e9e9e", "102030"]


def test_an_unknown_terminal_gets_the_dark_palette_and_its_own_reference_colours():
    p = palette()
    assert p["THEME_MODE"] == "dark" and p["THEME_PAINT"] == "0"
    assert p["THEME_BG"] == "16151d"


def test_colorfgbg_says_light():
    assert palette(env={"COLORFGBG": "0;15"})["THEME_MODE"] == "light"
    assert palette(env={"COLORFGBG": "15;default;0"})["THEME_MODE"] == "dark"


def test_a_chosen_theme_paints_its_own_background_only_on_screens_and_only_when_needed():
    p = palette("000000", want="light", screen=True)    # light, on Termux's black
    assert p["THEME_MODE"] == "light" and p["THEME_PAINT"] == "1"
    assert rgb(p["BASE"], "48") == hexrgb("fbfaf8")
    assert p["R"].startswith("\033[0m") and p["R"].endswith(p["BASE"])   # a reset returns to the canvas
    p = palette("fafafa", want="light", screen=True)    # already light: nothing to paint
    assert p["THEME_PAINT"] == "0" and p["BASE"] == "" and p["R"] == "\033[0m"
    p = palette("000000", want="light")                 # plain output follows the terminal
    assert p["THEME_MODE"] == "dark" and p["THEME_PAINT"] == "0"
    p = palette(None, want="light")                     # ...unless we know nothing about it
    assert p["THEME_MODE"] == "light" and p["THEME_PAINT"] == "0"


def test_256_colour_fallback():
    out = subprocess.run(["bash", "-c", f". {THEME!r}; THEME_TRUE=0; for c in 000000 ffffff 808080 ff0000 5f87af 16161e; "
                          f"do theme_sgr 38 $c; echo $REPLY; done"],
                         capture_output=True, text=True, check=True).stdout.split()
    assert out == ["38;5;16", "38;5;231", "38;5;244", "38;5;196", "38;5;67", "38;5;234"]
    p = palette("fafafa", truecolor=0)
    assert all(";2;" not in p[k] for k in ("D", "ACC", "HL", "SEL")) and "38;5;" in p["ACC"]


def test_no_colour():
    out = subprocess.run(["bash", "-c", f". {THEME!r}; theme_off; echo \"[$R$D$ACC$HL$SEL]\""],
                         capture_output=True, text=True, check=True).stdout.strip()
    assert out == "[]"


# ---------------------------------------------------------------- asking the terminal

def ask(reply_osc=True, digits=4, bg="fbfaf8", fg="1c1b22"):
    """Run theme_detect on a pty that answers like a terminal. Returns (output, seconds)."""
    with tempfile.TemporaryDirectory() as home:
        pid, fd = pty.fork()
        if pid == 0:
            os.execve("/bin/bash", ["bash", "-c", f". {THEME!r}; theme_detect; echo \"rc=$? bg=$THEME_TERM_BG fg=$THEME_TERM_FG\"; "
                                    f"cat \"$THEME_CACHE\" 2>/dev/null || echo nocache"],
                      {"PATH": os.environ["PATH"], "HOME": home})

        def col(h):
            return "/".join(h[i:i + 2] * (digits // 2) if digits >= 2 else h[i] for i in (0, 2, 4))
        out, buf, start = b"", b"", time.time()
        while time.time() - start < 5:
            r, _, _ = select.select([fd], [], [], 0.05)
            if fd not in r:
                continue
            try:
                data = os.read(fd, 4096)
            except OSError:
                break
            if not data:
                break
            out += data
            buf += data
            if b"\033[c" in buf:                   # answer every query in one go, then DA1
                ans = ""
                if reply_osc:
                    ans += f"\033]11;rgb:{col(bg)}\033\\\033]10;rgb:{col(fg)}\007"
                os.write(fd, (ans + "\033[?62;22c").encode())
                buf = b""
        os.waitpid(pid, 0)
        return out.decode(errors="replace"), time.time() - start


def test_asking_the_terminal_for_its_colours():
    out, _ = ask()
    assert "rc=0 bg=fbfaf8 fg=1c1b22" in out
    assert "fbfaf8 1c1b22" in out.split("rc=0")[1]           # remembered for kokoro's plain output


def test_short_colour_replies():
    out, _ = ask(digits=2, bg="16161e", fg="e0e0e0")
    assert "rc=0 bg=16161e fg=e0e0e0" in out


def test_a_terminal_that_does_not_know_osc_11_costs_no_waiting():
    out, secs = ask(reply_osc=False)
    assert "rc=1 bg= fg=" in out and "nocache" in out
    assert secs < 1.5, secs                                   # DA1 ends the wait at once


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
