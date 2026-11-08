"""Tests for reading books: EPUB, PDF and plain text (src/engine.py).

Run with:  python3 tests/test_books.py   (or pytest). The PDF test needs pypdf.
"""
import os
import sys
import tempfile
import zipfile

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "src"))
import engine  # noqa: E402

CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

OPF = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Long Road</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c2" href="text/chapter%202.xhtml" media-type="application/xhtml+xml"/>
    <item id="c1" href="text/chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="nav" linear="no"/><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>"""

CHAPTER = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>{t}</title></head>
<body><h1>{t}</h1><p>{p}</p><p class="footnote"><sup>1</sup> skipped?</p></body></html>"""


def make_epub(path, encrypted=False):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/epub+zip")
        z.writestr("META-INF/container.xml", CONTAINER)
        if encrypted:
            z.writestr("META-INF/encryption.xml", "<encryption><EncryptedData/></encryption>")
        z.writestr("OEBPS/content.opf", OPF)
        z.writestr("OEBPS/nav.xhtml", CHAPTER.format(t="Contents", p="Chapter one. Chapter two."))
        z.writestr("OEBPS/text/chapter1.xhtml", CHAPTER.format(t="Chapter One", p="The road began at the sea."))
        z.writestr("OEBPS/text/chapter 2.xhtml", CHAPTER.format(t="Chapter Two", p="It ended in the hills."))


def job_with(filename, data):
    jobdir = tempfile.mkdtemp()
    with open(os.path.join(jobdir, filename), "wb") as fh:
        fh.write(data)
    return jobdir


def test_epub_chapters_in_spine_order():
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "book.epub")
        make_epub(path)
        title, chapters = engine.read_epub(path, "book.epub")
    assert title == "The Long Road"
    assert [h for h, _ in chapters] == ["Chapter One", "Chapter Two"]   # titled by their headings
    assert "The road began" in chapters[0][1]
    assert "It ended in the hills." in chapters[1][1]  # href with an escaped space
    assert all("Contents" not in text for _, text in chapters)   # non-linear nav is skipped


def test_drm_epub_is_refused():
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "book.epub")
        make_epub(path, encrypted=True)
        try:
            engine.read_epub(path, "book.epub")
            raise AssertionError("expected a JobError")
        except engine.JobError as e:
            assert "DRM" in str(e)


def test_pdf_title_and_joined_lines():
    import importlib.util
    if importlib.util.find_spec("pypdf") is None:
        print("  (pypdf not installed: skipped)")
        return
    title, chapters = engine.read_pdf(os.path.join(HERE, "fixtures", "caravan.pdf"), "caravan.pdf")
    text = chapters[0][1]
    assert title == "The Caravan" and len(chapters) == 1
    assert "every traveller kept one eye on the" in text      # a wrapped line, rejoined
    assert "\n1\n" not in f"\n{text}\n" and "\n2\n" not in f"\n{text}\n"   # page numbers gone
    assert "By noon the camels refused to walk." in text


def test_read_source_detects_the_kind_of_file():
    with tempfile.TemporaryDirectory() as d:
        epub = os.path.join(d, "b.epub")
        make_epub(epub)
        data = open(epub, "rb").read()
    title, chapters = engine.read_source(job_with("in.bin", data), {"file": "in.bin", "name": "b.epub"})
    assert title == "The Long Road" and len(chapters) == 2 and "road began" in chapters[0][1]
    title, chapters = engine.read_source(job_with("in.txt", "Plain words.".encode()), {"name": "notes.txt"})
    assert (title, chapters) == ("notes.txt", [("notes.txt", "Plain words.")])


def test_binary_junk_is_refused():
    jobdir = job_with("in.txt", bytes(range(256)) * 20)
    try:
        engine.read_source(jobdir, {})
        raise AssertionError("expected a JobError")
    except engine.JobError as e:
        assert "doesn't look like text" in str(e)


def test_input_file_name_cannot_escape_the_job_folder():
    try:
        engine.read_source(tempfile.mkdtemp(), {"file": "../etc/passwd"})
        raise AssertionError("expected a JobError")
    except engine.JobError:
        pass


if __name__ == "__main__":
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    failed = 0
    for name, fn in tests:
        try:
            fn()
            print(f"ok    {name}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL  {name}: {e}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
