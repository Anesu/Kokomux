# kokomux: runs inside the "kokoro" Debian container (as root), one phase
# per call, from install.sh:  bash setup.sh apt|pip|models|tidy
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
export PIP_PROGRESS_BAR=off
APP=/opt/kokoro
SRC=/tmp/kokoro-setup

case "${1:-}" in
  apt)
    # Skip docs, man pages and translations for everything apt installs.
    mkdir -p /etc/dpkg/dpkg.cfg.d
    cat > /etc/dpkg/dpkg.cfg.d/01-kokomux-nodoc <<'CFG'
path-exclude=/usr/share/doc/*
path-include=/usr/share/doc/*/copyright
path-exclude=/usr/share/man/*
path-exclude=/usr/share/info/*
path-exclude=/usr/share/locale/*
path-include=/usr/share/locale/locale.alias
CFG
    apt-get update
    apt-get install -y --no-install-recommends python3 python3-venv ca-certificates
    ;;
  pip)   # setup.sh pip [standard|dictionary]: dictionary also keeps misaki for English
    mkdir -p "$APP"
    [ -x "$APP/venv/bin/python" ] || python3 -m venv "$APP/venv"
    "$APP/venv/bin/pip" install --upgrade pip
    # pypdf reads PDFs.
    "$APP/venv/bin/pip" install --upgrade kokoro-onnx soundfile pypdf | tee /tmp/kokoro-setup/.pip
    # Before 0.8, Whistle (cactus-compute) timed the words; Kokoro does that itself now.
    # It and the packages only it needed are removed (about 54 MB).
    # (tqdm, click, anyio and h11 stay when the pronunciation dictionary is installed: spaCy needs them.)
    shared="tqdm click anyio h11"
    compgen -G "$APP/venv/lib/python3*/site-packages/misaki" >/dev/null && shared=""
    # shellcheck disable=SC2086
    "$APP/venv/bin/pip" uninstall -y cactus-compute pillow huggingface-hub hf-xet fsspec filelock \
      pyyaml httpcore2 httpx2 truststore $shared 2>/dev/null || true
    if [ "${2:-}" = dictionary ]; then   # the same packages as kokoro --pronunciation dictionary
      "$APP/venv/bin/pip" install --upgrade "misaki>=0.7.4,<0.8" num2words "spacy>=3.8,<3.9" \
        "en_core_web_sm @ https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0-py3-none-any.whl" \
        | tee -a /tmp/kokoro-setup/.pip
    fi
    grep -q '^Successfully installed' /tmp/kokoro-setup/.pip || printf 'up to date' > "$SRC/.note"
    cp "$SRC/engine.py" "$SRC/words.py" "$APP/"
    rm -f "$APP/speak.py"   # from older versions
    ;;
  models)   # setup.sh models [standard|high]: high also keeps the full-precision model
    quality=${2:-standard}
    if [ -s "$APP/models/kokoro-v1.0-timestamped.int8.onnx" ] && [ -s "$APP/models/voices-v1.0.bin" ] &&
       { [ "$quality" != high ] || [ -s "$APP/models/kokoro-v1.0-timestamped.fp32.onnx" ]; }; then
      printf 'already downloaded' > "$SRC/.note"
    fi
    "$APP/venv/bin/python" -u "$APP/engine.py" download --quality "$quality"
    ;;
  tidy)
    apt-get clean
    rm -rf /var/lib/apt/lists/* /root/.cache/pip /usr/share/man/* /usr/share/info/*
    rm -rf /root/.cache/cactus /root/.cache/cactus-needle /root/.cache/huggingface/hub/models--Cactus-Compute--*   # old Whistle files
    find /usr/share/doc -mindepth 2 ! -name copyright -delete 2>/dev/null || true
    ;;
  *)
    echo "usage: setup.sh apt|pip|models|tidy" >&2; exit 2 ;;
esac
