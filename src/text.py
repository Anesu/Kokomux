"""kokomux: text made speakable, and split into parts. Runs inside the container.

normalise() tidies what's meant for the eye (ligatures, footnote marks, links,
dashes, Roman numerals in headings, abbreviations and units), and split_text()
cuts the result into parts of whole sentences, each with the pause after it.
Pure Python: the tests import it bare.
"""
import re

MAX_CHUNK = 360  # characters per part: whole sentences, under Kokoro's 510 sounds


# Pauses between parts, in seconds. Inside a part Kokoro pauses 0.25 s at a
# full stop; between parts the same, longer after a paragraph and a chapter,
# in golden-ratio steps (0.25 × φ² and × φ³).
PAUSE = {"sentence": 0.25, "paragraph": 0.65, "chapter": 1.06}

# --- tidying text before it's spoken -----------------------------------------
# Kokoro reads what it's given, so things meant for the eye are made speakable
# first: ligatures, footnote marks, links, markdown, dashes, Roman numerals in
# headings, ALL-CAPS headings, and (in English) abbreviations and units. The
# reader shows the tidied text, so what's lit is what's said.

_INVISIBLE = dict.fromkeys(map(ord, "­​‌‍⁠﻿"))
_LIGATURES = {"ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl", "ﬃ": "ffi", "ﬄ": "ffl",
              "ﬅ": "st", "ﬆ": "st", "Ĳ": "IJ", "ĳ": "ij"}
_ROMAN = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1000}
_HEADING_WORDS = (r"Chapter|Part|Book|Volume|Vol\.|Act|Scene|Section|Canto|Letter|Appendix|"
                  r"Chapitre|Partie|Livre|Capítulo|Capitulo|Parte|Libro|Capitolo|Kapitel|Teil|Buch")
# "Chapter IV" or "CHAPTER IV", where the numeral ends the phrase ("the part I liked" stays)
_HEADING_RE = re.compile(r"\b((?:" + "|".join(f"{w}|{w.upper()}" for w in _HEADING_WORDS.split("|")) +
                         r"))[ \t]+([IVXLCDM]+)(?=[ \t]*(?:\n|$|[.:,;)–—-])|[ \t]+[A-Z\"“'‘])", re.M)
_TITLES = {"Mr": "Mister", "Mrs": "Missus", "Ms": "Miz", "Dr": "Doctor", "Prof": "Professor",
           "St": "Saint", "Gen": "General", "Capt": "Captain", "Lt": "Lieutenant",
           "Sgt": "Sergeant", "Col": "Colonel", "Rev": "Reverend", "Hon": "Honourable"}
_PHRASES = [(r"\be\.[ \t]?g\.", "for example"), (r"\bi\.[ \t]?e\.", "that is"),
            (r"\betc\.", "etcetera."), (r"\bvs\.?(?=[ \t])", "versus"), (r"\bapprox\.", "approximately"),
            (r"\bNo\.(?=[ \t]*\d)", "number"), (r"\bFig\.(?=[ \t]*\d)", "figure"),
            (r"\bpp\.(?=[ \t]*\d)", "pages"), (r"\bp\.(?=[ \t]*\d)", "page")]
# unit: (singular, plural), spoken after a number; (American, British) spellings
_UNITS = [("km/h", ("kilometer per hour", "kilometers per hour"), ("kilometre per hour", "kilometres per hour")),
          ("mph", ("mile per hour", "miles per hour"), None),
          ("km", ("kilometer", "kilometers"), ("kilometre", "kilometres")),
          ("cm", ("centimeter", "centimeters"), ("centimetre", "centimetres")),
          ("mm", ("millimeter", "millimeters"), ("millimetre", "millimetres")),
          ("kg", ("kilogram", "kilograms"), None),
          ("mg", ("milligram", "milligrams"), None),
          ("ml", ("milliliter", "milliliters"), ("millilitre", "millilitres")),
          ("lbs?", ("pound", "pounds"), None),
          ("ft", ("foot", "feet"), None),
          ("GB", ("gigabyte", "gigabytes"), None), ("MB", ("megabyte", "megabytes"), None),
          ("KB", ("kilobyte", "kilobytes"), None), ("kB", ("kilobyte", "kilobytes"), None)]


_TITLE_RE = re.compile(r"\b(" + "|".join(_TITLES) + r")\.(?=[ \t]+[A-Z])")
_UNIT_WORDS = {u: (us, gb) for u, us, gb in _UNITS}
_UNIT_RE = re.compile(r"(?<![\w.])(\d+(?:[.,]\d+)?)[ \t]?(" + "|".join(u for u, _, _ in _UNITS) + r")\b")


def roman(s):
    """A Roman numeral's value, or None if it isn't one ("MMXXIV" -> 2024)."""
    s = s.upper()
    if not s or not re.fullmatch(r"M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})", s):
        return None
    total = 0
    for i, ch in enumerate(s):
        v = _ROMAN[ch]
        total += -v if i + 1 < len(s) and _ROMAN[s[i + 1]] > v else v
    return total


def _caps_heading(line):
    """ "CHAPTER ONE: THE ROAD" -> "Chapter One: The Road" (only whole lines in capitals)."""
    letters = [c for c in line if c.isalpha()]
    words = re.findall(r"[^\W\d_]{2,}", line)
    if len(letters) < 4 or len(words) < 2 or not all(c.isupper() for c in letters):
        return line
    return re.sub(r"[^\W\d_]+", lambda m: m.group(0)[0] + m.group(0)[1:].lower(), line)


def _english(text, lang):
    text = _TITLE_RE.sub(lambda m: _TITLES[m.group(1)], text)   # titles before a name: "Dr. Smith"
    for pat, rep in _PHRASES:
        text = re.sub(pat, rep, text)
    text = re.sub(r"(?<=\d)[ \t]?([ap])\.[ \t]?m\.", lambda m: f" {m.group(1).upper()}M" +   # 5 p.m. -> 5 PM
                  ("." if re.match(r"[ \t]+[\"“]?[A-Z]|[ \t]*(?:\n|$)", m.string[m.end():]) else ""),
                  text, flags=re.I)
    text = re.sub(r"(?<=\d)[ \t]?°[ \t]?C\b", " degrees Celsius", text)
    text = re.sub(r"(?<=\d)[ \t]?°[ \t]?F\b", " degrees Fahrenheit", text)
    text = re.sub(r"(?<=\d)[ \t]?°", " degrees", text)
    british = lang == "en-gb"

    def unit(m):
        us, gb = _UNIT_WORDS[m.group(2) if m.group(2) not in ("lb", "lbs") else "lbs?"]
        one, many = gb if british and gb else us
        return f"{m.group(1)} {one if m.group(1) == '1' else many}"
    text = _UNIT_RE.sub(unit, text)
    text = re.sub(r"(?<=[ \t])&(?=[ \t])", "and", text)
    return text


def normalise(text, lang="en-us"):
    """Make text speakable (see above). Line breaks, which mark paragraphs, are kept.
    Each rule runs once over the whole text, so a long book stays quick."""
    text = text.translate(_INVISIBLE)
    for k, v in _LIGATURES.items():
        text = text.replace(k, v)
    text = re.sub(r"[¹²³⁰-⁹]+", "", text)                 # footnote marks¹²
    text = re.sub(r"\[(?:\d+(?:\s*[,–-]\s*\d+)*|[a-z]|note \d+|citation needed|clarification needed)\]", "", text)
    text = re.sub(r"\b(?:https?://|www\.)(?:www\.)?([\w-]+(?:\.[\w-]+)+)[^\s)\]]*?(?=[.,;:!?)\]]?(?:\s|$))",
                  r"\1", text)                                                         # links: their site
    text = re.sub(r"(?m)^[ \t]*(?:#{1,6}|>+|[-*+•·▪◦‣])[ \t]+", "", text)            # markdown marks, bullets
    text = re.sub(r"(?<![\w*])(\*\*\*|___|\*\*|__|\*|_)(?=\S)([^\n]+?)(?<=\S)\1(?![\w*])", r"\2", text)
    text = re.sub(r"(?<=\d)[ \t]?[–—][ \t]?(?=\d)", " to ", text)                     # 1990–1995
    text = re.sub(r"[ \t]*(?:—|–|--)[ \t]*", ", ", text)                             # dashes: a pause
    text = re.sub(r"([!?])\1+", r"\1", text)
    text = re.sub(r"(?m)^[ \t]+|[ \t]+$", "", text)
    text = re.sub(r"(?m)^([IVXLCDM]+)([.:]?)$",                                       # a heading: "IV."
                  lambda m: f"{roman(m.group(1))}{m.group(2) or '.'}" if roman(m.group(1)) else m.group(0), text)
    text = _HEADING_RE.sub(lambda m: f"{m.group(1)} {roman(m.group(2))}" if roman(m.group(2)) else m.group(0),
                           text)                                                       # "Chapter IV"
    text = re.sub(r"(?m)^[^\na-z]*[A-Z][^\na-z]*$", lambda m: _caps_heading(m.group(0)), text)
    if lang.startswith("en"):
        text = _english(text, lang)
    return re.sub(r"[ \t]{2,}", " ", text)


# --- splitting into parts ----------------------------------------------------
_ABBREV = re.compile(r"(?:\b(?:[A-Z]|Mr|Mrs|Ms|Dr|St|Prof|Sr|Sra|Jr|Mt|Gen|Capt|Lt|Sgt|Col|Rev|Hon|"
                     r"Inc|Ltd|Co|vs|etc|approx|No|Fig|M|Mme|Mlle|Hr|Fr|Dra|Sig|Sto)\.)$")
_SENTENCE_END = re.compile(r"(?<=[.!?…])[\"”’»)\]]*\s+")


def sentences(paragraph):
    """A paragraph's sentences, not broken after an initial or abbreviation ("J. R. R.", "Mme.")
    or before a word in lower case ("approx. five")."""
    out = []
    for piece in (p for p in re.split(r"((?<=[.!?…])[\"”’»)\]]*)\s+", paragraph)):
        if piece is None:
            continue
        if out and not out[-1].endswith(" ") and re.fullmatch(r"[\"”’»)\]]*", piece):
            out[-1] += piece + " "                  # closing quotes stay with their sentence
            continue
        if out and (_ABBREV.search(out[-1].rstrip()) or (piece[:1].islower())):
            out[-1] = out[-1].rstrip() + " " + piece
        else:
            out.append(piece)
    return [s.strip() for s in out if re.search(r"\w", s)]


def _clauses(sentence, limit):
    """A sentence longer than the limit, cut at commas, semicolons and colons."""
    parts, cur = [], ""
    for bit in re.split(r"(?<=[,;:])\s+", sentence):
        if cur and len(cur) + len(bit) + 1 > limit:
            parts.append(cur)
            cur = bit
        else:
            cur = f"{cur} {bit}" if cur else bit
    return parts + [cur] if cur else parts


def split_text(text):
    """Split into parts: (text, pause after it). The first part is short, so playback
    starts quickly; then whole sentences up to MAX_CHUNK characters. A part never
    runs across a paragraph (a line break), and each paragraph ends with a longer
    pause. The last part's pause is a paragraph's; the caller makes it a chapter's."""
    chunks = []
    for para in text.split("\n"):
        cur = ""
        for sent in sentences(para.strip()):
            for bit in (_clauses(sent, MAX_CHUNK) if len(sent) > 2 * MAX_CHUNK else [sent]):
                limit = 40 if not chunks else MAX_CHUNK
                if cur and len(cur) + 1 + len(bit) > limit:
                    chunks.append((cur, PAUSE["sentence"]))
                    cur = bit
                else:
                    cur = f"{cur} {bit}" if cur else bit
        if cur:
            chunks.append((cur, PAUSE["paragraph"]))
    return chunks
