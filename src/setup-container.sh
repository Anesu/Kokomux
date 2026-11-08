# kokomux: runs inside the "kokoro" Debian container (as root), from install.sh.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
APP=/opt/kokoro
SRC=/tmp/kokoro-setup

echo "--> apt packages"
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

echo "--> Python packages (the slow part)"
mkdir -p "$APP"
[ -x "$APP/venv/bin/python" ] || python3 -m venv "$APP/venv"
"$APP/venv/bin/pip" install --upgrade pip
"$APP/venv/bin/pip" install --upgrade kokoro-onnx soundfile

cp "$SRC/engine.py" "$APP/engine.py"
rm -f "$APP/speak.py"   # from older versions

echo "--> model files"
"$APP/venv/bin/python" "$APP/engine.py" download

echo "--> tidying up"
apt-get clean
rm -rf /var/lib/apt/lists/* /root/.cache/pip /usr/share/man/* /usr/share/info/*
find /usr/share/doc -mindepth 2 ! -name copyright -delete 2>/dev/null || true
