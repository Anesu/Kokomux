#!/usr/bin/env python3
"""Web novels in the app (app/src/main/python/kokomux_android.py) on this machine:
reading a chapter and the next, a novel's list of chapters, and searching for a
novel, against a made-up site served here and laid out as freewebnovel.com is
(its chapter pages are tests/test_novel.py's; an index page listing the latest
chapters and some of the rest; a search box that posts "searchkey").

Run with a Python that has trafilatura and certifi:  python test_novels.py
"""
import http.server
import json
import os
import sys
import threading
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path[:0] = [os.path.join(ROOT, "android", "app", "src", "main", "python"), os.path.join(ROOT, "src"),
                os.path.join(ROOT, "tests")]
import engine  # noqa: E402
import kokomux_android as app  # noqa: E402
import test_novel  # noqa: E402

engine.fetch = app.fetch
os.environ["no_proxy"] = os.environ["NO_PROXY"] = "127.0.0.1,localhost"

INDEX = """<!DOCTYPE html><html><head><title>The Long Road - Free Web Novel</title></head><body>
<div class="m-info"><h1 class="tit">The Long Road</h1>
<div class="m-newest1"><span>Latest:</span> <a href="/novel/the-long-road/chapter-3" class="con">Chapter 3: The River</a></div></div>
<div class="m-newest2"><ul id="idData">
<li><a href="/novel/the-long-road/chapter-1" class="con" title="Chapter 1: Departure">Chapter 1: Departure</a></li>
<li><a href="/novel/the-long-road/chapter-3" class="con" title="Chapter 3: The River">Chapter 3: The River</a></li>
</ul></div>
<div class="footer"><a href="/novel/another-tale/chapter-1">Another Tale</a> <a href="/genre/fantasy">Fantasy</a></div>
</body></html>"""

RESULTS = """<!DOCTYPE html><html><head><title>Search - Free Web Novel</title></head><body>
<div class="col-content"><div class="ss-custom">
<div class="li-row"><div class="li"><div class="pic"><a href="/novel/the-long-road"><img src="/a.jpg" alt="The Long Road"></a></div>
<div class="txt"><h3 class="tit"><a href="/novel/the-long-road" title="The Long Road">The Long Road</a></h3>
<div class="item"><span class="s1">Chapter:</span><a href="/novel/the-long-road/chapter-3" class="chapter" title="Chapter 3: The River">Chapter 3: The River</a></div></div></div></div>
<div class="li-row"><div class="li"><div class="txt"><h3 class="tit"><a href="/novel/the-short-road" title="The Short Road">The Short Road</a></h3>
<div class="item"><a href="/novel/the-short-road/chapter-120" class="chapter">Chapter 120</a></div></div></div></div>
</div></div><div class="footer"><a href="/sort/latest-novel">Latest</a></div></body></html>"""


class Site(test_novel.Site):
    asked = []

    def do_GET(self):
        if self.path.rstrip("/") == "/novel/the-long-road":
            return self.page(INDEX)
        return super().do_GET()

    def do_POST(self):
        form = urllib.parse.parse_qs(self.rfile.read(int(self.headers["Content-Length"])).decode())
        Site.asked.append((self.path, form))
        return self.page(RESULTS if self.path == "/search/" else "<html>Not found</html>", 200 if self.path == "/search/" else 404)

    def page(self, html, code=200):
        body = html.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Site)
threading.Thread(target=server.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{server.server_address[1]}"
NOVEL = f"{BASE}/novel/the-long-road"


def test_a_chapter_and_the_next():
    got = json.loads(app.chapter(f"{NOVEL}/chapter-2"))
    n = got["novel"]
    assert (n["novel"], n["label"], n["number"]) == ("The Long Road", "Chapter 2: The Ridge", 2), n
    assert n["novel_url"] == NOVEL and n["next"] == f"{NOVEL}/chapter-3" and n["prev"] == f"{NOVEL}/chapter-1"
    assert got["text"].startswith("Chapter 2: The Ridge.\n") and "paragraph 5 of chapter 2" in got["text"]
    assert "freewebnovel.com" not in got["text"] and "Comments" not in got["text"]
    last = json.loads(app.chapter(n["next"]))["novel"]
    assert last["number"] == 3 and last["next"] == f"{NOVEL}/chapter-4"          # guessed: there's no link yet
    assert json.loads(app.chapter(last["next"])) == {"none": True}               # and it isn't out


def test_read_says_when_a_page_is_a_novels_chapter():
    book = json.loads(app.read(f"{NOVEL}/chapter-1", "", "en-us"))
    assert book["title"] == "The Long Road" and book["chapters"][0][1] == "Chapter 1: Departure"
    assert book["novel"]["number"] == 1 and book["novel"]["next"] == f"{NOVEL}/chapter-2"


def test_the_list_of_chapters_fills_in_what_the_index_leaves_out():
    got = json.loads(app.toc(NOVEL, f"{NOVEL}/chapter-1"))
    assert got == [[1, "Chapter 1: Departure", f"{NOVEL}/chapter-1"], [2, "Chapter 2", f"{NOVEL}/chapter-2"],
                   [3, "Chapter 3: The River", f"{NOVEL}/chapter-3"]], got


def test_a_search_finds_novels_and_their_latest_chapters():
    app.SEARCH["test"] = (f"{BASE}/search/", "searchkey")
    got = json.loads(app.search("long road", "test"))
    assert Site.asked[-1] == ("/search/", {"searchkey": ["long road"]})
    assert got == [["The Long Road", NOVEL, "Chapter 3: The River"],
                   ["The Short Road", f"{BASE}/novel/the-short-road", "Chapter 120"]], got


if __name__ == "__main__":
    failed = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok    {name}")
            except Exception as e:  # noqa: BLE001
                failed += 1
                print(f"FAIL  {name}: {e!r}")
    sys.exit(1 if failed else 0)
