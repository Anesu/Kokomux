"""kokomux on Android: the text side, run by Chaquopy.

engine.py and words.py are copied in from src/ when the app is built, so the
phone tidies, splits and pronounces text with the same code the Termux version
runs: normalise, split_text, and misaki through g2p_for, with espeak-ng for
the words misaki doesn't know. Kokoro itself runs in Kotlin (kokomux.engine).
"""
import json
import os
import sys
import tempfile
import time
import types


def setup(espeak_library, espeak_data, tmp):
    """Point phonemizer at the app's espeak-ng and its data (once, before anything else)."""
    os.environ["TMPDIR"] = tmp
    tempfile.tempdir = tmp
    os.environ["JOBLIB_MULTIPROCESSING"] = "0"   # phonemizer's joblib: one process (Android has no semaphores for it)
    # phonemizer imports dlinfo, which needs glibc's dlinfo(); bionic has none.
    # It's only used for a library given by bare name, and this one has a path.
    stub = types.ModuleType("dlinfo")

    class DLInfo:
        def __init__(self, cdll):
            raise RuntimeError("dlinfo isn't available on Android")
    stub.DLInfo = DLInfo
    sys.modules.setdefault("dlinfo", stub)
    from phonemizer.backend.espeak.wrapper import EspeakWrapper
    EspeakWrapper.set_library(espeak_library)
    EspeakWrapper.set_data_path(espeak_data)


def parts(text, lang):
    """The parts a text is read in, as JSON: [[text, pause after it], ...]."""
    import engine
    return json.dumps(engine.split_text(engine.normalise(text, lang)))


def sounds(text, lang):
    """A part's sounds from misaki, as engine.py makes them."""
    import engine
    return engine.dictionary_phonemes(engine.g2p_for(lang), text)[0]


def warm(lang):
    """Load misaki and spaCy for this accent; returns the seconds it took."""
    t0 = time.time()
    sounds("Hello.", lang)
    return time.time() - t0


def check(expected):
    """Every case in pronunciation.json through misaki here, as JSON:
    {"cases": N, "seconds": S, "differ": [{lang, text, want, got}, ...]}."""
    cases = json.loads(expected)["cases"]
    t0 = time.time()
    differ = []
    for case in cases:
        got = sounds(case["text"], case["lang"])
        if got != case["sounds"]:
            differ.append({"lang": case["lang"], "text": case["text"], "want": case["sounds"], "got": got})
    return json.dumps({"cases": len(cases), "seconds": round(time.time() - t0, 2), "differ": differ})
