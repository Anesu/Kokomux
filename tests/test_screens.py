"""Tests for the full-screen screens (src/tui.sh), driven like a person would.

Run with:  python3 tests/test_screens.py   (or pytest)

Each test runs the real `kokoro` command in a pseudo-terminal of a given size,
with a stand-in phone (a temporary $PREFIX and $HOME, no container), types
keys, and checks that the screen did what it should and that kokoro neither
crashed nor left the terminal unusable (taps reported, keys unechoed). The
screens here need no model, so these run anywhere.
"""
import fcntl
import os
import pty
import re
import select
import shutil
import struct
import tempfile
import termios
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "src")
UP, DOWN, RIGHT, LEFT, ESC, ENTER = "\033[A", "\033[B", "\033[C", "\033[D", "\033", "\r"


class Phone:
    """A stand-in phone: kokoro and its screens in a temporary $PREFIX, no container."""

    def __init__(self):
        self.dir = tempfile.mkdtemp(prefix="kokomux-test.")
        self.prefix, self.home = os.path.join(self.dir, "prefix"), os.path.join(self.dir, "home")
        share = os.path.join(self.prefix, "share", "kokomux")
        for d in (share, os.path.join(self.prefix, "bin"), os.path.join(self.prefix, "tmp"), self.home):
            os.makedirs(d)
        for f in ("theme.sh", "tui.sh", "player.sh"):
            shutil.copy(os.path.join(SRC, f), share)
        with open(os.path.join(SRC, "kokoro"), encoding="utf-8") as f:
            body = f.read().split("\n", 1)[1]
        self.write("prefix/bin/kokoro", "#!/usr/bin/env bash\n" + body, mode=0o755)
        self.write("prefix/bin/proot-distro", "#!/bin/sh\nexit 1\n", mode=0o755)   # no container here

    def write(self, rel, text, mode=0o644):
        path = os.path.join(self.dir, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        os.chmod(path, mode)
        return path

    def config(self):
        try:
            with open(os.path.join(self.home, ".config", "kokoro", "config"), encoding="utf-8") as f:
                return dict(line.split("=", 1) for line in f.read().splitlines() if "=" in line)
        except OSError:
            return {}

    def run(self, args, keys=(), rows=40, cols=60, wait=0.25):
        """Run `kokoro ARGS` on a ROWS×COLS terminal, type KEYS (each WAIT seconds apart).
        Returns (exit status, or None if it's still running; everything it wrote)."""
        env = dict(PATH=f"{self.prefix}/bin:{os.environ['PATH']}", PREFIX=self.prefix, HOME=self.home,
                   TERM="xterm-256color", LANG="C.UTF-8", KOKORO_AUTOSERVE="0", KOKORO_NOTIFY="0")
        pid, fd = pty.fork()
        if pid == 0:
            os.execvpe("kokoro", ["kokoro", *args], env)
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        out = bytearray()

        def pump(secs):
            end = time.time() + secs
            while time.time() < end:
                if select.select([fd], [], [], 0.02)[0]:
                    try:
                        data = os.read(fd, 65536)
                    except OSError:
                        return False
                    if not data:
                        return False
                    out.extend(data)
                    if b"\033]11;?" in data:   # answer the colour query as Termux does
                        os.write(fd, b"\033]11;rgb:1616/1616/1e1e\033\\\033]10;rgb:e0e0/e0e0/e0e0\033\\\033[?62;22c")
            return True

        alive = pump(1.2)
        for k in keys:
            if not alive:
                break
            os.write(fd, k.encode())
            alive = pump(wait)
        alive = alive and pump(0.6)
        if alive:
            os.kill(pid, 9)
        _, status = os.waitpid(pid, 0)
        os.close(fd)
        return (None if alive else os.waitstatus_to_exitcode(status)), out.decode("utf-8", "replace")

    def close(self):
        shutil.rmtree(self.dir, ignore_errors=True)


def plain(out):
    return re.sub(r"\033(\[[0-9;?<>]*[A-Za-z~]|\][^\033\a]*(\033\\|\a)|[()][0-9A-Z])", "", out)


def ended_cleanly(status, out):
    """It quit by itself, without a bash error, and gave the terminal back (taps off, normal screen)."""
    errors = [ln for ln in plain(out).replace("\r", "\n").split("\n")
              if re.search(r"(line \d+: |unbound variable|syntax error|command not found)", ln)]
    assert not errors, errors
    assert status == 0, f"exit status {status}"
    assert out.rfind("\033[?1000l") > out.rfind("\033[?1000h"), "taps are still reported to kokoro"
    assert out.rfind("\033[?1049l") > out.rfind("\033[?1049h"), "still on the full screen"


# ---------------------------------------------------------------- settings

def test_settings_scroll_down_and_back_up_on_a_short_screen():
    """With the keyboard up the screen is short: the list scrolls, both ways."""
    p = Phone()
    try:
        status, out = p.run(["--settings"], keys=[DOWN] * 22 + [UP] * 22 + ["q"], rows=16, cols=50, wait=0.12)
        ended_cleanly(status, out)
    finally:
        p.close()


def test_settings_change_and_save():
    p = Phone()
    try:
        # Down to Speed (Voice, Blend with, Blend amount, Speed), one step faster, then quit.
        status, out = p.run(["--settings"], keys=[DOWN, DOWN, DOWN, RIGHT, "q"])
        ended_cleanly(status, out)
        assert p.config().get("KOKORO_SPEED") == "1.1"
    finally:
        p.close()


def test_settings_arrows_on_every_row_and_past_the_ends():
    """← and → on each setting, then → far past the end of its range: nothing crashes."""
    p = Phone()
    try:
        keys = []
        for row in range(17):                     # every setting, in the order the menu lists them
            if row == 1:
                keys += [LEFT, RIGHT, ESC, DOWN]  # (→ on "Blend with" opens the voice picker; Esc is back)
            else:
                keys += [LEFT, RIGHT] + [RIGHT] * 3 + [DOWN]
        status, out = p.run(["--settings"], keys=keys + ["q"], wait=0.08)
        ended_cleanly(status, out)
    finally:
        p.close()


def test_settings_speed_stops_at_two():
    p = Phone()
    try:
        status, out = p.run(["--settings"], keys=[DOWN] * 3 + [RIGHT] * 12 + ["q"], wait=0.1)
        ended_cleanly(status, out)
        assert p.config().get("KOKORO_SPEED") == "2.0"
    finally:
        p.close()


def test_voice_picker_filter_and_choose():
    p = Phone()
    try:
        status, out = p.run(["--settings"], keys=[ENTER, "b", "f", "_", "e", "m", ENTER, "q"])
        ended_cleanly(status, out)
        assert p.config().get("KOKORO_VOICE") == "bf_emma"
        assert p.config().get("KOKORO_LANG") == "en-gb"      # a voice brings its own language
    finally:
        p.close()


# ---------------------------------------------------------------- home

def test_home_opens_and_quits():
    p = Phone()
    try:
        status, out = p.run([], keys=["q"])
        ended_cleanly(status, out)
        assert "Bookshelf" in plain(out)
    finally:
        p.close()


def test_home_settings_and_back():
    p = Phone()
    try:
        status, out = p.run([], keys=["s", DOWN, "q", "q"], rows=20, cols=44)
        ended_cleanly(status, out)
        assert "Settings" in plain(out)
    finally:
        p.close()


def test_home_lists_a_bookmark():
    p = Phone()
    try:
        p.write("home/.local/share/kokomux/bookmarks/0123456789abcdef.mark",
                "part=3\nparts=10\ntitle=A Long Story\nkind=file\nsource=/nowhere\n"
                "voice=af_heart\nspeed=1.0\nlang=en-us\n")
        status, out = p.run([], keys=["q"])
        ended_cleanly(status, out)
        assert "A Long Story" in plain(out) and "20%" in plain(out)
    finally:
        p.close()


if __name__ == "__main__":
    import sys
    failed = 0
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_")]
    for name, fn in tests:
        try:
            fn()
            print(f"ok    {name}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL  {name}: {e}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
