#!/usr/bin/env python3
"""Reference speech for the engine's tests (android/engine), made the Termux way.

Runs engine.py as the container does (misaki for the sounds, kokoro-onnx 0.6.1
with the fp32 timestamped model) on a few sentences, and writes golden.json:
each sentence's sounds, the length of its speech, when each sound is spoken,
and its loudness every 0.1 s. Run it with the rig's Python, after dev/rig.sh:

    .dev/app/venv/bin/python android/tools/golden.py
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, ".dev", "app"))   # engine.py, words.py and models/, as in the container
import engine  # noqa: E402

CASES = [
    ("Nobody in the village remembered who had built it, or why it ran inland.", "af_heart", 1.0, "en-us"),
    ("Dr. Smith paid $42.50 for 3 books on 5th Avenue.", "am_adam", 1.3, "en-us"),
    ("“Wait,” she said. “Is that the road?” He didn't answer; the wind had turned.", "bf_emma", 1.0, "en-gb"),
    # Long enough that its sounds go to the model in two batches.
    ("The road left the village at first light and climbed into the hills, where the fields gave way to "
     "heather and the heather to bare stone. For three days she walked without meeting anyone, sleeping "
     "in the lee of stone walls and waking to the sound of sheep, and on the fourth she came over a ridge "
     "and saw the sea below her, grey and enormous, and the little harbour town she had been told about. "
     "Its roofs were slate and its walls were white, and smoke rose from every chimney into the still air. "
     "She sat on a rock and ate the last of her bread, and watched the boats come in on the evening tide.",
     "bm_george", 0.9, "en-gb"),
]


def envelope(samples, step=2400):
    """Loudness (RMS) in each 0.1 s."""
    import numpy as np
    return [round(float(np.sqrt(np.mean(samples[i:i + step] ** 2))), 5) for i in range(0, len(samples), step)]


def main():
    k = engine.tts()
    out = []
    for text, voice, speed, lang in CASES:
        text = engine.normalise(text, lang)
        ps, _ = engine.dictionary_phonemes(engine.g2p_for(lang), text)
        samples, rate, timings = k.create_timed(ps, voice=voice, speed=speed, lang=lang, is_phonemes=True)
        out.append({"text": text, "voice": voice, "speed": speed, "lang": lang, "phonemes": ps,
                    "samples": len(samples),
                    "timings": [[t.phoneme, round(t.start, 4), round(t.end, 4)] for t in timings],
                    "envelope": envelope(samples)})
        print(f"{voice} {speed}: {len(ps)} sounds, {len(samples) / rate:.2f} s", file=sys.stderr)
    path = os.path.join(os.path.dirname(__file__), "golden.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=0)
        fh.write("\n")


if __name__ == "__main__":
    main()
