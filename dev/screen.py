#!/usr/bin/env python3
r"""Run a command in a pseudo-terminal and save its last screen as a picture.

The terminal answers the colour queries kokoro sends (OSC 10/11) and DA1 as
Termux would, types keys on a schedule, then draws the screen with pyte to
HTML, and to PNG with Playwright (dev/shot.js). Use it to look at a screen
after changing one.

  python3 dev/screen.py --out /tmp/reader.png --cols 56 --rows 40 \
      --keys '3:j;;1:\x1b[C' -- dev/kokoro -p -f story.txt

--keys: "seconds:keys" items, split by ';;'; escapes like \x1b and \r work.
--bg/--fg: the terminal's colours (hex), e.g. --bg fafafa --fg 202020 for light.
Needs: pip install pyte (and node with playwright, for the PNG).
"""
import argparse, codecs, fcntl, html, os, pty, select, struct, subprocess, termios, time
import pyte

ap = argparse.ArgumentParser()
ap.add_argument("--bg", default="16161e")
ap.add_argument("--fg", default="e0e0e0")
ap.add_argument("--no-osc", action="store_true", help="terminal ignores OSC 10/11")
ap.add_argument("--cols", type=int, default=100)
ap.add_argument("--rows", type=int, default=32)
ap.add_argument("--keys", default="")
ap.add_argument("--wait", type=float, default=2.0, help="seconds after the last key")
ap.add_argument("--out", required=True)
ap.add_argument("--scale", type=float, default=1)
ap.add_argument("--truecolor", type=int, default=1)
ap.add_argument("cmd", nargs=argparse.REMAINDER)
a = ap.parse_args()
cmd = a.cmd[1:] if a.cmd and a.cmd[0] == "--" else a.cmd

class BCEScreen(pyte.Screen):
    """Erasing fills with the current background, as real terminals do (BCE)."""
    def erase_in_display(self, how=0, *args, **kwargs):
        rows = {0: range(self.cursor.y + 1, self.lines), 1: range(self.cursor.y)}.get(how, range(self.lines))
        for y in rows:
            for x in range(self.columns):
                self.buffer[y][x] = self.cursor.attrs
        if how in (0, 1):
            self.erase_in_line(how)

    def erase_in_line(self, how=0, private=False):
        xs = {0: range(self.cursor.x, self.columns), 1: range(0, self.cursor.x + 1)}.get(how, range(self.columns))
        for x in xs:
            self.buffer[self.cursor.y][x] = self.cursor.attrs


screen = BCEScreen(a.cols, a.rows)
stream = pyte.ByteStream(screen)

pid, fd = pty.fork()
if pid == 0:
    env = dict(os.environ, TERM="xterm-256color", LANG="C.UTF-8")
    env.pop("COLORTERM", None); env.pop("TERMUX_VERSION", None)
    if a.truecolor:
        env["COLORTERM"] = "truecolor"
    os.execvpe(cmd[0], cmd, env)
fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", a.rows, a.cols, 0, 0))


def rgb4(h):
    return "/".join(h[i:i + 2] * 2 for i in (0, 2, 4))


keys = []
t = 0.0
for item in filter(None, a.keys.split(";;")):
    d, k = item.split(":", 1)
    t += float(d)
    keys.append((t, codecs.decode(k, "unicode_escape").encode("utf-8", "surrogateescape")))
start = time.time()
end = (keys[-1][0] if keys else 0) + a.wait
tail = b""
while time.time() - start < end:
    now = time.time() - start
    while keys and keys[0][0] <= now:
        os.write(fd, keys.pop(0)[1])
    r, _, _ = select.select([fd], [], [], 0.02)
    if fd in r:
        try:
            data = os.read(fd, 65536)
        except OSError:
            break
        if not data:
            break
        buf = tail + data
        if not a.no_osc:
            if b"\x1b]11;?" in buf:
                os.write(fd, f"\x1b]11;rgb:{rgb4(a.bg)}\x1b\\".encode())
            if b"\x1b]10;?" in buf:
                os.write(fd, f"\x1b]10;rgb:{rgb4(a.fg)}\x1b\\".encode())
        if b"\x1b[c" in buf:
            os.write(fd, b"\x1b[?62;22c")
        answered = any(q in buf for q in (b"\x1b]11;?", b"\x1b]10;?", b"\x1b[c"))
        tail = b"" if answered else buf[-16:]
        stream.feed(data)

NAMED = {"black": "000000", "red": "cd0000", "green": "00cd00", "brown": "cdcd00", "blue": "0000ee",
         "magenta": "cd00cd", "cyan": "00cdcd", "white": "e5e5e5"}


def col(c, default):
    if c == "default":
        return default
    if c in NAMED:
        return NAMED[c]
    return c


rows = []
for y in range(a.rows):
    line = screen.buffer[y]
    out = []
    for x in range(a.cols):
        ch = line[x]
        fg, bg = col(ch.fg, a.fg), col(ch.bg, a.bg)
        if ch.reverse:
            fg, bg = bg, fg
        data = ch.data or " "
        if data == "":
            continue
        style = f"color:#{fg};background:#{bg}"
        if ch.bold:
            style += ";font-weight:700"
        out.append(f'<span style="{style}">{html.escape(data)}</span>')
    rows.append("".join(out))
page = f"""<!doctype html><meta charset=utf-8><style>
body{{margin:0;background:#{a.bg}}}
pre{{margin:0;padding:14px 16px;font:15px/1.35 'JetBrains Mono','DejaVu Sans Mono',monospace;background:#{a.bg};color:#{a.fg};display:inline-block}}
span{{display:inline-block;width:1ch;white-space:pre}}
</style><pre>{chr(10).join(rows)}</pre>"""
htmlf = a.out.rsplit(".", 1)[0] + ".html"
open(htmlf, "w").write(page)
subprocess.run(["node", os.path.join(os.path.dirname(os.path.abspath(__file__)), "shot.js"), os.path.abspath(htmlf), os.path.abspath(a.out), str(a.scale)], check=True)
try:
    os.kill(pid, 9)
except OSError:
    pass
print("wrote", a.out)
