"""Tests for the settings menu (src/tui.sh): every setting changes, and is saved.

Run with:  python3 tests/test_settings.py   (or pytest)

Each setting is changed the way → does in the menu (setting_change ID 1), in
bash, and the settings file is read back. A setting the menu lists but can't
change fails here.
"""
import os
import re
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "src")

DEFAULTS = dict(KOKORO_VOICE="af_heart", KOKORO_BLEND="", KOKORO_SPEED="1.0", KOKORO_LANG="en-us",
                KOKORO_OUT="kokoro.wav", KOKORO_TUI="1", KOKORO_THEME="auto", KOKORO_FOCUS="1",
                KOKORO_WORDS="1", KOKORO_WORDS_OFFSET_MS="0", KOKORO_NOTIFY="1", KOKORO_AUTOSERVE="1",
                KOKORO_IDLE_MINUTES="30", KOKORO_PORT="8880")
# setting -> (what it changes, its value after one step right)
EXPECTED = {
    "speed": ("KOKORO_SPEED", "1.1"),
    "lang": ("KOKORO_LANG", "en-gb"),
    "theme": ("KOKORO_THEME", "dark"),
    "offset": ("KOKORO_WORDS_OFFSET_MS", "20"),
    "idle": ("KOKORO_IDLE_MINUTES", "60"),
    "blendamt": ("KOKORO_BLEND", "bf_emma:40"),
    "KOKORO_TUI": ("KOKORO_TUI", "0"),
    "KOKORO_FOCUS": ("KOKORO_FOCUS", "0"),
    "KOKORO_WORDS": ("KOKORO_WORDS", "0"),
    "KOKORO_NOTIFY": ("KOKORO_NOTIFY", "0"),
    "KOKORO_AUTOSERVE": ("KOKORO_AUTOSERVE", "0"),
}
# Opened with Enter rather than changed with → (a picker, a prompt or an action).
BY_ENTER = {"voice", "blend", "out", "port", "reset", "edit"}


def menu_ids():
    with open(os.path.join(SRC, "tui.sh"), encoding="utf-8") as f:
        block = re.search(r"^TUI_SETTINGS=\((.*?)^\)", f.read(), re.S | re.M).group(1)
    return [m.group(1) for m in re.finditer(r'^\s*"([A-Za-z_]+)\|', block, re.M)]


def change(setting, step=1):
    """Run setting_change SETTING STEP; return the settings file afterwards, as a dict."""
    with tempfile.TemporaryDirectory() as home:
        conf = os.path.join(home, "config")
        with open(conf, "w") as f:
            f.write("".join(f"{k}={v}\n" for k, v in DEFAULTS.items()))
        script = f"""
set -u
now_ms() {{ NOW=0; }}
{" ".join(f"{k}={v!r}" for k, v in DEFAULTS.items())}
KOKORO_BLEND=bf_emma:30
. {SRC}/theme.sh; THEME_ON=1; . {SRC}/tui.sh
CONF={conf!r}
setting_change {setting} {step}
"""
        subprocess.run(["bash", "-c", script], capture_output=True, text=True, check=True,
                       env={"PATH": os.environ["PATH"], "HOME": home})
        with open(conf) as f:
            return dict(line.rstrip("\n").split("=", 1) for line in f if "=" in line)


def test_every_setting_in_the_menu_can_be_changed():
    ids = menu_ids()
    assert set(ids) == set(EXPECTED) | BY_ENTER, set(ids) ^ (set(EXPECTED) | BY_ENTER)


def test_each_setting_changes_and_is_saved():
    for setting, (var, want) in EXPECTED.items():
        got = change(setting).get(var)
        assert got == want, (setting, var, got, want)


def test_left_goes_the_other_way():
    assert change("speed", -1)["KOKORO_SPEED"] == "0.9"
    assert change("lang", -1)["KOKORO_LANG"] == "cmn"                  # (round to the last)
    assert change("theme", -1)["KOKORO_THEME"] == "light"


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
