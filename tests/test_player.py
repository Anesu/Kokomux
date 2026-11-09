"""Tests for the player (src/player.sh): a new speed heard at once, and the sleep timer's fade.

Run with:  python3 tests/test_player.py   (or pytest)

player.sh is loaded in bash with stand-ins for mpv (its commands are logged),
the engine and the screen.
"""
import os
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "src")

STANDINS = """
set -euo pipefail
ui=inline; in_line=1; gen_pid=""; voice=af_heart; start=1; was_parts=0
. {src}/player.sh
mpv_send() { printf '%s\\n' "$1" >> "$PREFIX/mpv.log"; }
write_job() { printf 'start=%s\\nspeed=%s\\n' "$start" "$speed" > "$tmpd/job"; }
run_engine() { :; }
bookmark_save() { echo "$1 $speed" > "$PREFIX/bookmark"; }
now_ms() { NOW=1000000; }
"""


def bash(script, prefix):
    os.makedirs(os.path.join(prefix, "tmp"), exist_ok=True)
    full = STANDINS.replace("{src}", SRC) + script
    r = subprocess.run(["bash", "-c", full], capture_output=True, text=True,
                       env={"PATH": os.environ["PATH"], "PREFIX": prefix, "HOME": prefix})
    assert r.returncode == 0, r.stderr
    return r.stdout


def listen(prefix, parts, at, speed="1.2"):
    """Parts 1..5 made at 1.0x, parts 3-5 in mpv's playlist, AT playing; then a new SPEED."""
    return bash(f"""
tmpd="$(mktemp -d "$PREFIX/tmp/kokoro.XXXXXX")"; mkdir "$tmpd/stream"; old=$tmpd
for n in 1 2 3 4 5; do for e in txt words wav; do echo "$n" > "$tmpd/stream/000$n.$e"; done; done
echo "5 {parts}" > "$tmpd/progress"; echo hello > "$tmpd/in.txt"
speed=1.0
for n in 3 4 5; do mpv_queue "$n" "$tmpd/stream/000$n.wav"; done
speed={speed}
speed_now {at}
echo "old=$old"; echo "tmpd=$tmpd"; echo "n=$MPV_N part=${{MPV_PART[0]}} idx=${{MPV_IDX[{at}]}} spd=${{MPV_SPD[0]}}"
""", prefix)


def fields(out):
    return dict(line.split("=", 1) for line in out.splitlines() if "=" in line and " " not in line.split("=", 1)[0])


def test_a_new_speed_plays_at_once_and_the_rest_is_made_again():
    with tempfile.TemporaryDirectory() as d:
        out = listen(d, parts=9, at=4)
        f = fields(out)
        log = open(os.path.join(d, "mpv.log")).read().splitlines()
        # The part being heard: faster in mpv, pitch kept; the parts queued after it go.
        assert '"set_property","file-local-options/speed",1.2000' in log, log
        assert '"playlist-clear"' in log
        assert "n=1 part=4 idx=0 spd=1.0" in out, out
        # The rest is made again, from the next part, at the new speed, in a new folder.
        assert f["tmpd"] != f["old"] and not os.path.exists(f["old"])
        assert open(os.path.join(f["tmpd"], "job")).read() == "start=5\nspeed=1.2\n"
        kept = sorted(os.listdir(os.path.join(f["tmpd"], "stream")))
        assert kept == [f"000{n}.{e}" for n in (1, 2, 3, 4) for e in ("txt", "wav", "words")], kept
        assert open(os.path.join(f["tmpd"], "in.txt")).read() == "hello\n"
        assert open(os.path.join(d, "bookmark")).read() == "4 1.2\n"


def test_a_new_speed_on_the_last_part_only_changes_the_tempo():
    with tempfile.TemporaryDirectory() as d:
        f = fields(listen(d, parts=5, at=5, speed="0.8"))
        assert f["tmpd"] == f["old"]                                    # nothing more to make
        assert '"set_property","file-local-options/speed",0.8000' in open(os.path.join(d, "mpv.log")).read()


def sleep_vol(prefix, setup, part):
    return int(bash(setup + f"\nsleep_vol {part}; echo $VOL", prefix).strip())


def test_the_sleep_timer_fades_out_over_its_last_seconds():
    with tempfile.TemporaryDirectory() as d:
        at = lambda ms: sleep_vol(d, f"SLEEP=30 SLEEP_AT=$(( 1000000 + {ms} ))", 1)   # noqa: E731
        assert at(60000) == 100                     # a minute to go: full
        assert at(13000) == 100                     # the fade starts here
        assert at(6500) == 50
        assert at(0) == 0 and at(-500) == 0
        assert sleep_vol(d, "SLEEP=off", 1) == 100


def test_end_of_chapter_fades_out_the_chapters_last_part():
    with tempfile.TemporaryDirectory() as d:
        ch = "SLEEP=chapter CH_START=(1 4) CH_TITLE=(One Two) MPV_DUR=10.000000 MPV_T=3.500000"
        assert sleep_vol(d, ch, 3) == 50            # 6.5 s left of the chapter's last part
        assert sleep_vol(d, ch, 2) == 100           # not the last part


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
