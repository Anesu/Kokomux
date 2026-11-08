"""Tests for reading web pages (src/engine.py page_text, with trafilatura).

Run with:  python3 tests/test_web.py   (or pytest). Needs trafilatura; skipped
without it. Pages are made up here, so no network is needed.
"""
import os
import sys

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "src"))
import engine  # noqa: E402

try:
    import trafilatura  # noqa: F401
except ImportError:
    trafilatura = None

ARTICLE = " ".join(["The road left the village at first light and climbed into the hills."] * 6)
PAGE = f"""<html><head><title>The Long Road - Walking Blog</title></head><body>
<nav><a href="/">Home</a> <a href="/about">About</a> <a href="/archive">Archive</a></nav>
<article><h1>The Long Road</h1>
<p>{ARTICLE}</p>
<h2>Crossing the river</h2>
<p>{ARTICLE.replace("village", "town")}<sup>[3]</sup></p>
<ul><li>Pack light</li><li>Start early</li></ul>
<h2>Related posts</h2>
<ul><li><a href="/a">A walk in the rain</a></li><li><a href="/b">Ten best boots</a></li></ul>
</article>
<footer>Copyright 2026. Subscribe to the newsletter.</footer></body></html>"""


def needs_trafilatura(fn):
    def wrapper():
        if trafilatura is None:
            return                                          # (needs trafilatura)
        fn()
    wrapper.__name__ = fn.__name__
    return wrapper


@needs_trafilatura
def test_the_article_and_nothing_around_it():
    title, text = engine.page_text(PAGE, "https://example.com/long-road")
    lines = text.split("\n")
    assert title == "The Long Road"                         # (trafilatura picks the article's own title)
    assert lines[0] == "The Long Road."                     # the heading, with a pause
    assert "Crossing the river." in lines
    assert "Pack light" in lines and "Start early" in lines  # list items, without their bullets
    assert "Home" not in text and "Copyright" not in text   # no menus or footer
    assert "A walk in the rain" not in text                 # stops at "Related posts"
    assert "<sup>" not in text


@needs_trafilatura
def test_old_pages_laid_out_with_line_breaks():
    paras = [f"Paragraph {n} says something worth hearing about the long road ahead." * 3 for n in range(5)]
    page = "<html><head><title>Essay</title></head><body><table><tr><td><font>" + \
        "<br><br>".join(paras) + "</font></td></tr></table></body></html>"
    title, text = engine.page_text(page, "https://example.com/essay")
    assert [p for p in paras if p in text.split("\n")] == paras   # each its own paragraph


@needs_trafilatura
def test_a_page_with_nothing_to_read():
    try:
        engine.page_text("<html><body><nav><a href='/'>Home</a></nav></body></html>", "https://example.com/")
    except engine.JobError:
        return
    raise AssertionError("an empty page was accepted")


if __name__ == "__main__":
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
