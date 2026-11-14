#!/usr/bin/env python3
"""Wheels the phone's Python needs that PyPI doesn't have in a form Chaquopy installs.

    python wheels.py OUTDIR      (the build runs it with the build machine's Python 3.10)

- docopt 0.6.2 is only a source package, and Chaquopy installs wheels only.
  num2words wants it installed (misaki never runs it), so its wheel is built here.
- trafilatura 2.3.1, htmldate 1.11.0, dateparser 1.5.0 and lxml_html_clean
  0.4.6 are the versions the container reads web pages with. They ask for lxml
  6 and a 2024 regex, which Chaquopy has no builds of, but they work with its
  lxml 5.3.0 and regex 2023.10.3 (tests/test_web.py, test_novel.py and
  test_books.py pass with them). So their wheels are given here as
  VERSION+chaquopy, asking for those instead; requirements.txt pins them.

Every download is checked against its hash.
"""
import base64
import hashlib
import io
import os
import subprocess
import sys
import urllib.request
import zipfile

PYPI = "https://files.pythonhosted.org/packages/"
LABEL = "chaquopy"

# name, version, wheel, sha256, (what its METADATA asks for -> what it gets)
RELABEL = [
    ("trafilatura", "2.3.1",
     "f5/20/e95d135c417aceaa3d34d1b4dff9aceb85e98800480eca1b033933717169/trafilatura-2.3.1-py3-none-any.whl",
     "f86bad2ee36f82884e14dee84c83aae0f0ac0127da5c65764e82f3846716b822",
     [("Requires-Dist: lxml>=6.1.3", "Requires-Dist: lxml>=5.3.0")]),
    ("htmldate", "1.11.0",
     "42/f8/6139fc95b889f44e9a4b373778f10315ade81b395a6298adf3c08415de64/htmldate-1.11.0-py3-none-any.whl",
     "2a5a0d80cce38d3f986a64bb9ab64ba03e5953159b38fd78a841492131ead348",
     [("Requires-Dist: lxml>=6.1.3", "Requires-Dist: lxml>=5.3.0")]),
    ("dateparser", "1.5.0",
     "7f/58/73739bc1e168f309aeb8cc29b5866a134da8dbd4f69af947fc93462f1e33/dateparser-1.5.0-py3-none-any.whl",
     "7c4e088f25458f57db391510035af0878439a6df71d159318dcdb0a7553932e3",
     [("Requires-Dist: regex>=2024.9.11", "Requires-Dist: regex>=2023.10.3")]),
    ("lxml_html_clean", "0.4.6",
     "f5/36/4898b5e3951635846ed0e445e042fadf02b7c5e811a9e43da5b3557bc8d2/lxml_html_clean-0.4.6-py3-none-any.whl",
     "cc037270ba500a65e7235409dcddcae8ef37300f26dea826b61d917bf0236880",
     [("Requires-Dist: lxml>=6.1.1", "Requires-Dist: lxml>=5.3.0")]),
]
DOCOPT = "docopt==0.6.2 --hash=sha256:49b3a825280bd66b3aa83585ef59c4a8c82f2c8a522dbe754a8bc8d08c85c491\n"


def download(path, sha256):
    """A file from PyPI, checked against its hash."""
    with urllib.request.urlopen(PYPI + path, timeout=60) as r:
        data = r.read()
    if hashlib.sha256(data).hexdigest() != sha256:
        sys.exit(f"{path.rsplit('/', 1)[-1]}: the download doesn't match its hash")
    return data


def record_hash(data):
    """A file's hash as a wheel's RECORD writes it."""
    return "sha256=" + base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()


def relabel(wheel, name, version, swaps, outdir):
    """The wheel as NAME-VERSION+chaquopy, with its requirements swapped."""
    local = f"{version}+{LABEL}"
    old, new = f"{name}-{version}.dist-info/", f"{name}-{local}.dist-info/"
    rows = []
    path = os.path.join(outdir, f"{name}-{local}-py3-none-any.whl")
    with zipfile.ZipFile(io.BytesIO(wheel)) as zin, zipfile.ZipFile(path + ".tmp", "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            inner = new + item.filename[len(old):] if item.filename.startswith(old) else item.filename
            if inner == new + "RECORD":
                continue
            if inner == new + "METADATA":
                text = data.decode("utf-8")
                text = text.replace(f"\nVersion: {version}\n", f"\nVersion: {local}\n", 1)
                for want, have in swaps:
                    if want not in text:
                        sys.exit(f"{name} {version}: its METADATA doesn't say {want!r}")
                    text = text.replace(want, have)
                data = text.encode("utf-8")
            zout.writestr(inner, data)
            rows.append(f"{inner},{record_hash(data)},{len(data)}")
        rows.append(f"{new}RECORD,,")
        zout.writestr(new + "RECORD", "\n".join(rows) + "\n")
    os.replace(path + ".tmp", path)


def main():
    outdir = os.path.abspath(sys.argv[1])
    os.makedirs(outdir, exist_ok=True)
    for name, version, path, sha256, swaps in RELABEL:
        if not os.path.exists(os.path.join(outdir, f"{name}-{version}+{LABEL}-py3-none-any.whl")):
            relabel(download(path, sha256), name, version, swaps, outdir)
    if not any(f.startswith("docopt-0.6.2-") for f in os.listdir(outdir)):
        req = os.path.join(outdir, "docopt.txt")
        with open(req, "w") as fh:
            fh.write(DOCOPT)
        subprocess.run([sys.executable, "-m", "pip", "wheel", "--quiet", "--no-deps", "--require-hashes",
                        "-r", req, "-w", outdir], check=True)
        os.remove(req)


if __name__ == "__main__":
    main()
