#!/usr/bin/env python3
"""The sounds engine.py makes for a set of sentences, to check the app's against.

    python pronunciation.py --write    the container's sounds -> app/src/main/assets/pronunciation.json
                                       (run with the rig's Python: .dev/app/venv/bin/python)
    python pronunciation.py            this Python's sounds, compared with that file

The sentences are the fixtures' English (tests/fixtures), the examples the
README gives for misaki, and the cases tests/test_text.py checks, each tidied
and split into parts as engine.py does, in American and British English.
The app runs the same comparison on the phone (Check pronunciation).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "src"))
import engine  # noqa: E402

ASSET = os.path.join(HERE, "..", "app", "src", "main", "assets", "pronunciation.json")
FIXTURES = os.path.join(ROOT, "tests", "fixtures")

SENTENCES = [
    # README: misaki picks the reading from the sentence
    "I read the book yesterday.", "Please record the record.", "Wind the clock.", "It cost $42.50.",
    # tests/test_text.py
    "I went home.", "The part I liked best.", "Henry VIII was king.", "NASA launched it.",
    "She paid $42.50 for 3 books.", "It's 3:30 now.", "Isn't it?", "Mix it well.",
    "CHAPTER IV: THE LONG ROAD", "Dr. Smith met Mrs. Jones at 5 p.m. on St. James Street.",
    "The map was ﬁne.² It works.[3]", "Wait — no -- yes – fine.",
    "From 1990–1995 it grew by 12 km, e.g. past the old mill, i.e. the far one, etc.",
    # words that read differently by sense or accent
    "They lead the way with a lead pipe.", "The dove dove into the bushes.",
    "I live near the stage where they play live music.", "She will present the present tomorrow.",
    "We must polish the Polish furniture.", "The bandage was wound around the wound.",
    "Schedule the tomato and the aluminium to the laboratory.",
    # names, numbers and punctuation
    "“Wait,” she said. “Is that the road?” He didn't answer; the wind had turned.",
    "On 4 July 1976, 1,250 people paid £3.99 each, 25% more than the 2nd time.",
    "Tolkien, J. R. R., wrote it in 1937; Hermione and Siobhan read it aloud.",
    "The road left the village at first light and climbed into the hills, where the fields gave way to "
    "heather. For three days she walked without meeting anyone, sleeping in the lee of stone walls and "
    "waking to the sound of sheep.",
]


def texts():
    """The fixtures' English, then the sentences above."""
    out = []
    with open(os.path.join(FIXTURES, "timings.json"), encoding="utf-8") as fh:
        out += [d["text"] for d in json.load(fh) if d["lang"].startswith("en")]
    try:
        out.append(engine.read_pdf(os.path.join(FIXTURES, "caravan.pdf"), "caravan")[1][0][1])
    except ImportError:
        pass   # no pypdf here: the file already has the PDF's parts
    return out + SENTENCES


def cases():
    """Every part of every text, in both accents, with engine.py's sounds for it."""
    out = []
    for lang in ("en-us", "en-gb"):
        g2p = engine.g2p_for(lang)
        for text in texts():
            for part, _ in engine.split_text(engine.normalise(text, lang)):
                out.append({"lang": lang, "text": part, "sounds": engine.dictionary_phonemes(g2p, part)[0]})
    return out


def espeak_ready():
    """phonemizer finds espeak through kokoro-onnx in the container; do the same here."""
    try:
        from kokoro_onnx.tokenizer import Tokenizer
        Tokenizer()
    except ImportError:
        from phonemizer.backend.espeak.wrapper import EspeakWrapper
        import espeakng_loader
        EspeakWrapper.set_library(espeakng_loader.get_library_path())
        EspeakWrapper.set_data_path(espeakng_loader.get_data_path())


def main():
    espeak_ready()
    if "--write" in sys.argv:
        import misaki
        import spacy
        made = cases()
        with open(ASSET, "w", encoding="utf-8") as fh:
            json.dump({"made_with": {"misaki": misaki.__version__, "spacy": spacy.__version__},
                       "cases": made}, fh, ensure_ascii=False, indent=0)
            fh.write("\n")
        print(f"{len(made)} parts written to {os.path.relpath(ASSET)}")
        return
    with open(ASSET, encoding="utf-8") as fh:
        want = json.load(fh)["cases"]
    differ = 0
    for case in want:
        got = engine.dictionary_phonemes(engine.g2p_for(case["lang"]), case["text"])[0]
        if got != case["sounds"]:
            differ += 1
            print(f"{case['lang']}: {case['text']}\n  engine.py: {case['sounds']}\n  here:      {got}")
    print(f"{len(want) - differ} of {len(want)} parts sound the same")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()
