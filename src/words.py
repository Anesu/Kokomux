"""Word timings for spoken lines: which word is being said, and when.

Runs inside the "kokoro" Debian container, next to engine.py.

Kokoro reports how long it spends on every sound it makes (the timestamped
model's "durations" output, read by kokoro-onnx as per-phoneme timings). Each
word of the line is phonemised on its own, and its sounds are lined up against
the sounds Kokoro actually spoke, so a word takes exactly the times of the
phonemes it became. No speech recogniser is needed, and it works in every
language Kokoro speaks.

A line's result has the shape {v, engine, confidence, words: [{s, e, a, b, p}]}:
s/e are the start and end in ms, a/b the character range in the line's text,
and p the confidence (the share of the word's sounds that lined up).
"""
import re

LINE_WORDS_VERSION = 2
# Below this, too few of a line's sounds lined up to trust: it gets no highlight
# rather than a wrong one.
WORDS_MIN_CONFIDENCE = 0.35


def empty_result(engine):
    """Timings for a line that has none usable: nothing is lit."""
    return {"v": LINE_WORDS_VERSION, "engine": engine, "confidence": 0, "words": []}


def player_rows(text, data):
    """A line's timings in the form the bash player reads (stream/NNNN.words): a
    "confidence" line, then one "start end a b" row per word, with a/b the word's
    UTF-8 byte range in the line. Below WORDS_MIN_CONFIDENCE there are no rows,
    so nothing is lit."""
    rows = [f"confidence\t{data.get('confidence', 0)}"]
    if data.get("confidence", 0) >= WORDS_MIN_CONFIDENCE:
        for w, (a, b) in zip(data["words"], byte_ranges(text, data["words"])):
            rows.append(f"{w['s']}\t{w['e']}\t{a}\t{b}")
    return "\n".join(rows) + "\n"


def byte_ranges(text, words):
    """The same words as UTF-8 byte ranges, which is what the bash player slices by."""
    out, pos, byte = [], 0, 0
    for w in words:
        byte += len(text[pos:w["a"]].encode("utf-8"))
        a = byte
        byte += len(text[w["a"]:w["b"]].encode("utf-8"))
        pos = w["b"]
        out.append((a, byte))
    return out


# ---------------------------------------------------------------- Kokoro's own timings

# Not sounds: spaces, stress marks and the punctuation espeak passes through.
_SILENT = set(" ˈˌ;:,.!?¡¿—…\"«»“”()'-")
# Stripped from the ends of a token to find the word to highlight (any script).
_EDGE_PUNCT = "\"'“”‘’«»()[]{}<>.,;:!?¡¿…—–-、。，！？：；（）「」『』।॥"
# Split points inside a token for scripts written without spaces.
_CJK_BREAK = re.compile(r"(?<=[、。，！？：；])")


def _tokens(text):
    """The words to highlight: whitespace-separated tokens (also split after CJK
    punctuation), with punctuation trimmed from their ends, as (start, end)."""
    out = []
    for m in re.finditer(r"\S+", text):
        pos = m.start()
        for part in _CJK_BREAK.split(m.group()):
            core = part.strip(_EDGE_PUNCT)
            if core:
                a = pos + part.index(core)
                out.append((a, a + len(core)))
            pos += len(part)
    return out


def words_from_timings(text, timings, phonemize_many):
    """Word timings for a line from Kokoro's own per-phoneme timings.

    Each word is phonemised on its own, and its sounds are lined up against the
    sounds Kokoro actually spoke (stress marks, spaces and punctuation aside), so
    a word takes the times of exactly the phonemes it became. This survives
    espeak running words together in a sentence ("in the" -> one spoken word)
    and expanding numbers ("1999" -> three). Confidence is the share of sounds
    that lined up. Returns the same shape as the cache: {v, engine, confidence, words}."""
    import difflib

    tokens = _tokens(text)
    spoken = [t for t in timings if t.phoneme not in _SILENT]
    if not tokens or not spoken:
        return empty_result("kokoro")
    per = phonemize_many([text[a:b] for a, b in tokens])
    mine, owner = [], []                       # each token's sounds, end to end
    for i, p in enumerate(per):
        for ch in p:
            if ch not in _SILENT:
                mine.append(ch)
                owner.append(i)
    said = [t.phoneme for t in spoken]
    match = [None] * len(mine)                 # the spoken sound each of mine lined up with
    blocks = difflib.SequenceMatcher(None, mine, said, autojunk=False).get_matching_blocks()
    for blk in blocks:
        for k in range(blk.size):
            match[blk.a + k] = blk.b + k
    matched = sum(m is not None for m in match)
    confidence = round(matched / max(len(mine), len(said)), 3) if mine else 0.0
    words, floor = [], 0
    for i, (a, b) in enumerate(tokens):
        hits = [match[k] for k in range(len(mine)) if owner[k] == i and match[k] is not None]
        if not hits:
            continue                           # nothing of it lined up: leave it unlit
        start, end = spoken[min(hits)].start, spoken[max(hits)].end
        s_ms = max(floor, round(start * 1000))
        e_ms = max(s_ms + 1, round(end * 1000))
        words.append({"s": s_ms, "e": e_ms, "a": a, "b": b, "p": confidence})
        floor = e_ms
    return {"v": LINE_WORDS_VERSION, "engine": "kokoro", "confidence": confidence, "words": words}
