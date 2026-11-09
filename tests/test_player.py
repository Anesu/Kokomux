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
echo "rate=$MPV_RATE"; echo "rate_idx=$MPV_RATE_IDX"
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
        assert f["rate"] == "1200" and f["rate_idx"] == "0", f      # the highlight's clock runs 1.2× too
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


# ---------------------------------------------------------------- the clock the highlight follows

def clock(steps):
    """Run mpv_clock after each step (bash: set the clock and what mpv said); return each MS."""
    with tempfile.TemporaryDirectory() as d:
        body = "".join(f"{step}; mpv_clock; echo $MS\n" for step in steps)
        out = bash("now_ms() { NOW=$CLOCK; }; MPV_POS=0\n" + body, d)
        return [int(x) for x in out.split()]


def test_between_reports_the_clock_runs_on():
    # mpv said 1.000 s at t=10 000 ms; 80 ms later it's 1.080 s
    assert clock(["CLOCK=10000 MPV_T=1.000000 MPV_T_AT=10000", "CLOCK=10080"]) == [1000, 1080]


def test_it_never_runs_on_more_than_a_quarter_second():
    assert clock(["CLOCK=10000 MPV_T=1.000000 MPV_T_AT=10000", "CLOCK=12000"]) == [1000, 1250]


def test_with_no_report_yet_it_waits():
    assert clock(["CLOCK=10000 MPV_T=0 MPV_T_AT=0", "CLOCK=10300"]) == [0, 0]


def test_after_a_new_speed_it_runs_at_that_rate():
    # The part playing was sped up 1.5×: 100 ms of real time is 150 ms of it.
    assert clock(["CLOCK=10000 MPV_T=1.000000 MPV_T_AT=10000 MPV_RATE=1500 MPV_RATE_IDX=0",
                  "CLOCK=10100"]) == [1000, 1150]
    # ...but only for that entry: the next part plays at its own speed.
    assert clock(["CLOCK=10000 MPV_T=1.000000 MPV_T_AT=10000 MPV_RATE=1500 MPV_RATE_IDX=0 MPV_POS=1",
                  "CLOCK=10100"]) == [1000, 1100]


def test_a_report_a_little_behind_holds_it_still():
    got = clock(["CLOCK=10000 MPV_T=1.000000 MPV_T_AT=10000", "CLOCK=10090",
                 "MPV_T=1.050000 MPV_T_AT=10090", "CLOCK=10120"])
    assert got == [1000, 1090, 1090, 1090], got        # not back to 1050; then on from there
    assert clock(["CLOCK=10000 MPV_T=1.000000 MPV_T_AT=10000", "CLOCK=10090",
                  "MPV_T=1.050000 MPV_T_AT=10090", "CLOCK=10150"])[-1] == 1110


def test_a_seek_back_is_followed():
    assert clock(["CLOCK=10000 MPV_T=6.000000 MPV_T_AT=10000", "MPV_T=1.000000 MPV_T_AT=10000"]) == [6000, 1000]


def pump(lines, setup=""):
    with tempfile.TemporaryDirectory() as d:
        feed = "".join(f"{line}\n" for line in lines)
        out = bash(f"""now_ms() {{ NOW=5000; }}; {setup}
exec {{MPV_IN}}< <(printf '%s' '{feed}'); sleep 0.2; mpv_pump
echo "$MPV_POS $MPV_T $MPV_T_AT"
""", d)
        return out.split()


def test_a_new_part_starts_with_no_time_and_a_playlist_clear_keeps_it():
    on = pump(['{"event":"property-change","id":1,"name":"playback-time","data":4.2}',
               '{"event":"property-change","id":2,"name":"playlist-pos","data":1}'], "MPV_POS=0")
    assert on == ["1", "0", "0"], on                        # the next part: nothing said about it yet
    cleared = pump(['{"event":"property-change","id":1,"name":"playback-time","data":4.2}',
                    '{"event":"property-change","id":2,"name":"playlist-pos","data":0}'], "MPV_POS=2")
    assert cleared == ["0", "4.2", "5000"], cleared         # the same part, renumbered: its time stands


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
