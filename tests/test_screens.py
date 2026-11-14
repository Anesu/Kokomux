"""Tests for the full-screen screens (src/tui.sh), driven like a person would.

Run with:  python3 tests/test_screens.py   (or pytest)

Each test runs the real `kokoro` command in a pseudo-terminal of a given size,
with a stand-in phone (a temporary $PREFIX and $HOME, no container), types
keys, and checks that the screen did what it should and that kokoro neither
crashed nor left the terminal unusable (taps reported, keys unechoed).

Home, settings and the voice picker need no model, so those run anywhere. The
reader needs Kokoro: its tests run when dev/rig.sh has made .dev/ (the model,
and a proot-distro stand-in) and mpv and socat are installed; sound goes
nowhere (mpv --ao=null).
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
RIG = os.path.join(HERE, "..", ".dev")
HAS_RIG = (os.path.isfile(os.path.join(RIG, "app", "models", "voices-v1.0.bin")) and
           os.access(os.path.join(RIG, "bin", "proot-distro"), os.X_OK) and
           bool(shutil.which("mpv")) and bool(shutil.which("socat")))
STORY = ("The road left the village at first light. It climbed into the hills, where the fields gave way "
         "to heather.\n\nFor three days she walked without meeting anyone. She slept in the lee of stone walls. "
         "On the fourth morning a cart came over the ridge.\n\nThe driver offered her a ride as far as the river, "
         "and after a moment's thought she accepted. They crossed the bridge at dusk.\n")
UP, DOWN, RIGHT, LEFT, ESC, ENTER = "\033[A", "\033[B", "\033[C", "\033[D", "\033", "\r"
PGUP, PGDN = "\033[5~", "\033[6~"


class Phone:
    """A stand-in phone: kokoro and its screens in a temporary $PREFIX, no container."""

    def __init__(self, rig=False):
        """rig: with Kokoro, from dev/rig.sh's .dev/ (the container's /tmp is this machine's /tmp)."""
        self.dir = tempfile.mkdtemp(prefix="kokomux-test.")
        self.prefix, self.home = os.path.join(self.dir, "prefix"), os.path.join(self.dir, "home")
        self.rig = rig
        share = os.path.join(self.prefix, "share", "kokomux")
        for d in (share, os.path.join(self.prefix, "bin"), self.home):
            os.makedirs(d)
        if rig:
            os.symlink("/tmp", os.path.join(self.prefix, "tmp"))
        else:
            os.makedirs(os.path.join(self.prefix, "tmp"))
        for f in ("theme.sh", "tui.sh", "player.sh", "commands.sh"):
            shutil.copy(os.path.join(SRC, f), share)
        with open(os.path.join(SRC, "kokoro"), encoding="utf-8") as f:
            body = f.read().split("\n", 1)[1]
        self.write("prefix/bin/kokoro", "#!/usr/bin/env bash\n" + body, mode=0o755)
        if not rig:
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

    def run(self, args, keys=(), rows=40, cols=60, wait=0.25, start=1.2, end=0.6, env=None):
        """Run `kokoro ARGS` on a ROWS×COLS terminal and type KEYS, START seconds in and
        then WAIT seconds apart (a key can also be a number: that many seconds' pause).
        Returns (exit status, or None if it's still running after END; everything it wrote)."""
        path = f"{self.prefix}/bin:{os.path.join(RIG, 'bin') + ':' if self.rig else ''}{os.environ['PATH']}"
        env = dict(PATH=path, PREFIX=self.prefix, HOME=self.home, TERM="xterm-256color", LANG="C.UTF-8",
                   KOKORO_AUTOSERVE="0", KOKORO_NOTIFY="0", KOKORO_MPV_ARGS="--ao=null", **(env or {}))
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

        alive = pump(start)
        for k in keys:
            if not alive:
                break
            if isinstance(k, (int, float)):
                alive = pump(k)
                continue
            os.write(fd, k.encode())
            alive = pump(wait)
        alive = alive and pump(end)
        if alive:
            os.kill(pid, 9)
        _, status = os.waitpid(pid, 0)
        os.close(fd)
        return (None if alive else os.waitstatus_to_exitcode(status)), out.decode("utf-8", "replace")

    def bookmarks(self):
        d = os.path.join(self.home, ".local", "share", "kokomux", "bookmarks")
        out = {}
        for f in os.listdir(d) if os.path.isdir(d) else []:
            if f.endswith(".mark"):
                with open(os.path.join(d, f), encoding="utf-8") as fh:
                    out[f] = dict(line.split("=", 1) for line in fh.read().splitlines() if "=" in line)
        return out

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


def test_settings_mouse_wheel_and_esc():
    wheel_down = "\033[<65;20;10M"
    p = Phone()
    try:
        status, out = p.run(["--settings"], keys=[wheel_down] * 3 + [RIGHT, ESC])   # down to Speed, faster
        ended_cleanly(status, out)
        assert p.config().get("KOKORO_SPEED") == "1.1"
    finally:
        p.close()


def test_voice_picker_backspace_and_esc():
    p = Phone()
    try:
        # A filter with no match, mended with Backspace, then Esc: back without choosing.
        status, out = p.run(["--settings"], keys=[ENTER, "z", "x", "\177", ESC, "q"])
        ended_cleanly(status, out)
        assert 'No voice matches "zx"' in plain(out)
        assert "KOKORO_VOICE" not in p.config()
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


def shelf(p, n):
    """N books in progress, the first heard most recently; returns their bookmark files."""
    marks = []
    for k in range(n):
        m = p.write(f"home/.local/share/kokomux/bookmarks/{k + 1:016x}.mark",
                    f"part={k + 2}\nparts=20\ntitle=Book {k + 1}\nkind=file\nsource=/nowhere\n"
                    "voice=af_heart\nspeed=1.0\nlang=en-us\n")
        os.utime(m, (time.time() - k * 60, time.time() - k * 60))
        marks.append(m)
    return marks


def test_home_shelf_scrolls_to_every_book():
    p = Phone()
    try:
        shelf(p, 12)
        status, out = p.run([], keys=[PGDN] * 4 + ["q"], rows=22, cols=50)   # (three fit at once)
        ended_cleanly(status, out)
        assert "Book 1 " in plain(out) and "Book 12" in plain(out)   # (only some fit at once)
        assert "of 12" in plain(out)
    finally:
        p.close()


def test_home_removes_a_book_after_asking():
    p = Phone()
    try:
        marks = shelf(p, 3)
        cache = p.write("prefix/tmp/kokomux-cache/0000000000000002/x/0001.wav", "")
        status, out = p.run([], keys=[DOWN, "x", "n", "x", "y", "q"])
        ended_cleanly(status, out)
        assert "off the shelf?" in plain(out)
        assert [os.path.exists(m) for m in marks] == [True, False, True]   # asked twice, removed once
        assert not os.path.exists(os.path.dirname(os.path.dirname(cache)))  # with its kept audio
    finally:
        p.close()


def test_home_removes_a_book_with_a_tap_on_its_cross():
    p = Phone()
    try:
        marks = shelf(p, 2)
        status, out = p.run([], keys=["q"])
        y = int(re.search(r"\033\[(\d+);\d+H(?:(?!\033\[\d+;\d+H).)*?Book 2", out).group(1))   # Book 2's row
        status, out = p.run([], keys=[click(55, y), "y", "q"])                # its ✕: the column before the edge
        ended_cleanly(status, out)
        assert [os.path.exists(m) for m in marks] == [True, False]
    finally:
        p.close()


def test_home_finished_books():
    p = Phone()
    try:
        p.write("home/.local/share/kokomux/finished",
                "2026-11-02\tWar and Peace\tfile\t/nowhere\taf_heart\t1.0\ten-us\n"
                "2026-11-10\tThe Hobbit\turl\thttps://example.org/hobbit\tbf_emma\t1.2\ten-gb\n")
        status, out = p.run([], keys=["h", ESC, "q"])
        ended_cleanly(status, out)
        assert "2 heard to the end" in plain(out)
        assert "War and Peace" in plain(out) and "2026-11-10" in plain(out)
    finally:
        p.close()


# ---------------------------------------------------------------- the reader (needs dev/rig.sh)

def needs_rig(fn):
    def wrapper():
        if HAS_RIG:
            fn()
    wrapper.__name__ = fn.__name__
    return wrapper


def click(x, y):
    return f"\033[<0;{x};{y}M"


@needs_rig
def test_reader_plays_to_the_end():
    p = Phone(rig=True)
    try:
        story = p.write("home/story.txt", STORY)
        status, out = p.run(["-p", "-f", story], start=40, end=0)       # (it quits by itself at the end)
        ended_cleanly(status, out)
        assert "Done" in plain(out)
        assert p.bookmarks() == {}                                   # heard to the end: nothing to come back to
        with open(os.path.join(p.home, ".local", "share", "kokomux", "finished"), encoding="utf-8") as f:
            done = f.read().split("\t")                               # and it's on the Finished list
        assert done[1:4] == ["story.txt", "file", story], done
    finally:
        p.close()


@needs_rig
def test_reader_keys_menu_and_stop_keeps_the_place():
    p = Phone(rig=True)
    try:
        story = p.write("home/story.txt", STORY)
        keys = [" ", 1, " ", RIGHT, 2, "+", 1, "t", "m", ESC, "q"]      # pause, play, next, faster, sleep, menu
        status, out = p.run(["-p", "-f", story], keys=keys, start=8, wait=0.6)
        ended_cleanly(status, out)
        marks = list(p.bookmarks().values())
        assert len(marks) == 1 and int(marks[0]["part"]) >= 2, marks
        assert marks[0]["speed"] == "1.1"
        assert "Stopped at part" in plain(out)
    finally:
        p.close()


@needs_rig
def test_reader_buttons_by_tap():
    p = Phone(rig=True)
    try:
        story = p.write("home/story.txt", STORY)
        # 56×40: the transport row is 38; Again, Pause and Next are 11 wide, 3 apart, centred.
        pause, next_ = click(28, 38), click(42, 38)
        status, out = p.run(["-p", "-f", story], keys=[pause, 1, pause, next_, 2, "q"], rows=40, cols=56,
                            start=8, wait=0.6)
        ended_cleanly(status, out)
        assert "Paused" in plain(out)
        assert int(list(p.bookmarks().values())[0]["part"]) >= 2
    finally:
        p.close()


@needs_rig
def test_reader_settings_from_the_menu_and_back():
    p = Phone(rig=True)
    try:
        story = p.write("home/story.txt", STORY)
        keys = ["m", ENTER, DOWN, DOWN, DOWN, RIGHT, "q", 1, "q"]       # Menu → Settings → speed + → back
        status, out = p.run(["-p", "-f", story], keys=keys, start=8, wait=0.5)
        ended_cleanly(status, out)
        assert p.config().get("KOKORO_SPEED") == "1.1"
    finally:
        p.close()


def make_epub(path, chapters):
    import zipfile
    page = '<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>{}</h1><p>{}</p></body></html>'
    items = "".join(f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(chapters)))
    spine = "".join(f'<itemref idref="c{i}"/>' for i in range(len(chapters)))
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/epub+zip")
        z.writestr("META-INF/container.xml", '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
                   '<rootfiles><rootfile full-path="b.opf"/></rootfiles></container>')
        z.writestr("b.opf", '<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">'
                   f'<metadata><dc:title>The Small Book</dc:title></metadata><manifest>{items}</manifest>'
                   f'<spine>{spine}</spine></package>')
        for i, (title, text) in enumerate(chapters):
            z.writestr(f"c{i}.xhtml", page.format(title, text))
    return path


@needs_rig
def test_reader_chapters_jump_and_continue():
    p = Phone(rig=True)
    try:
        long = " ".join(["The road went on over the hills and down to the sea."] * 3)
        book = make_epub(os.path.join(p.home, "book.epub"),
                         [("The Start", long), ("The River", long), ("The Town", long)])
        # The chapter list (c), down to the third, Enter; then stop.
        status, out = p.run(["-p", "-f", book], keys=["c", DOWN, DOWN, ENTER, 3, "q"], start=8, wait=0.5)
        ended_cleanly(status, out)
        assert "The Town" in plain(out)
        mark = list(p.bookmarks().values())[0]
        assert mark["title"] == "The Small Book" and int(mark["part"]) > 2, mark
        # kokoro --continue picks up there.
        status, out = p.run(["--continue"], keys=["q"], start=8)
        ended_cleanly(status, out)
        assert "The Town" in plain(out)
    finally:
        p.close()


@needs_rig
def test_inline_view_plays_to_the_end():
    p = Phone(rig=True)
    try:
        story = p.write("home/story.txt", STORY)
        status, out = p.run(["-p", "-f", story], start=40, end=0, env={"KOKORO_TUI": "0"})
        assert status == 0, status
        assert "Done" in plain(out) and "bridge at dusk" in plain(out)
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
