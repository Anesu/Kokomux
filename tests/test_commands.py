"""Tests for kokoro's command-line options: the queue, status, stop, and the
messages for what it can't do.

Run with:  python3 tests/test_commands.py   (or pytest)

Each runs the real `kokoro` on the stand-in phone from test_screens.py (no
container, no model needed).
"""
from test_screens import Phone, plain


def kokoro(p, *args):
    status, out = p.run(list(args), start=0.1, end=5)     # (they finish by themselves)
    return status, plain(out).replace("\r", "")


def test_queue_add_list_skip_clear():
    p = Phone()
    try:
        assert kokoro(p, "--queue", "clear")[0] == 0          # an empty queue: nothing to clear, no error
        assert kokoro(p, "--add", "First  thing\tto\nhear.")[0] == 0
        assert kokoro(p, "--add", "-u", "https://example.com/a")[0] == 0
        status, out = kokoro(p, "--queue", "list")
        assert status == 0 and " 1  First thing to hear.\n" in out and " 2  https://example.com/a" in out, out
        status, out = kokoro(p, "--queue", "skip")
        assert status == 0 and 'Dropped "First thing to hear."' in out, out
        assert "First" not in kokoro(p, "--queue", "list")[1]
        assert kokoro(p, "--queue", "clear")[0] == 0
        assert "Your queue is empty" in kokoro(p, "--queue", "list")[1]
    finally:
        p.close()


def test_a_copied_link_goes_in_as_a_page():
    p = Phone()
    try:
        kokoro(p, "--add", "  https://example.com/story  ")
        with open(f"{p.home}/.local/share/kokomux/queue/list", encoding="utf-8") as f:
            _, kind, src, title = f.read().rstrip("\n").split("\t")
        assert (kind, src, title) == ("url", "https://example.com/story", "https://example.com/story")
    finally:
        p.close()


def test_a_long_title_is_cut_at_a_character_not_a_byte():
    p = Phone()
    try:
        kokoro(p, "--add", "é" * 100)
        status, out = kokoro(p, "--queue", "list")
        assert status == 0 and " 1  " + "é" * 60 + "\n" in out, out
    finally:
        p.close()


def test_with_nothing_playing():
    p = Phone()
    try:
        for cmd in ("--stop", "--pause", "--next", "--again"):
            status, out = kokoro(p, cmd)
            assert status == 0 and "Nothing is playing" in out, (cmd, out)
    finally:
        p.close()


def test_status_and_version():
    p = Phone()
    try:
        status, out = kokoro(p, "--status")
        assert status == 0 and "Voice     af_heart" in out, out
        status, out = kokoro(p, "--version")
        assert status == 0 and out.startswith("kokomux "), out
    finally:
        p.close()


def test_what_it_cant_do_is_said_plainly():
    p = Phone()
    try:
        for args, says in ((["-v", "nope", "hi"], "isn't a voice name"),
                           (["-s", "fast", "hi"], "Speed must be a number"),
                           (["-o", "x.aiff", "hi"], 'Can\'t save as "x.aiff"'),
                           (["-f", "/nowhere"], "There's no file called"),
                           (["--queue", "shuffle"], "kokoro --queue plays the queue"),
                           (["--bogus"], "Unknown option --bogus")):
            status, out = kokoro(p, *args)
            assert status == 1 and says in out and "line " not in out, (args, out)
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
