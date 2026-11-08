# kokomux: Kokoro TTS on Termux

One script that installs [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) text-to-speech on an Android phone through Termux, and gives you a `kokoro` command.

## Why the Debian container?

Kokoro's Python packages (`onnxruntime`, `espeakng-loader`, `soundfile`) only ship builds for normal Linux (glibc). Termux uses Android's own C library, so they won't install there directly. The script sets up a small Debian container with `proot-distro`, where they install normally. You never have to go into the container yourself: the `kokoro` command handles it.

## Requirements

- Termux from **F-Droid** or GitHub. The Play Store version is outdated.
- A 64-bit phone (aarch64). Almost every phone from the last few years is one.
- About 1.5 GB of free space and a decent connection. The model is ~92 MB, the voices ~28 MB, and Debian plus the Python packages make up the rest.

## Install

In Termux, run:

```sh
curl -fsSLO https://raw.githubusercontent.com/Anesu/kokomux/main/install.sh && bash install.sh
```

Or clone the repo:

```sh
pkg install git
git clone https://github.com/Anesu/kokomux && bash kokomux/install.sh
```

It takes 5–15 minutes. When it finishes it plays `~/kokoro-test.wav`. You can run it again safely: it skips whatever is already done and updates the Python packages.

## Use

```sh
kokoro "Hello from my phone"              # writes kokoro.wav in the current folder
kokoro -p "Say it out loud"               # -p plays the result too
kokoro -o note.wav -v bf_emma "Hi there"  # choose the file name and the voice
kokoro -s 1.2 -f story.txt -o story.wav   # read from a text file, 1.2x speed
cat notes.txt | kokoro -o notes.wav       # or pipe text in
kokoro --list-voices
```

| Option | Meaning |
|---|---|
| `-o FILE` | Output WAV file (default `kokoro.wav`) |
| `-v NAME` | Voice (default `af_heart`) |
| `-s N` | Speed, 0.5–2.0 |
| `-l CODE` | Language (espeak code): `en-us`, `en-gb`, `es`, `fr-fr`, `it`, `pt-br`, `hi`. Pair it with a matching voice |
| `-f FILE` | Read text from a file |
| `-p` | Play when done (uses `play-audio`) |

The voice prefix tells you the accent and gender: `a` is American, `b` British, then `f` for female and `m` for male. For example `af_heart`, `am_adam`, `bf_emma`, `bm_george`. Other languages have their own voices, such as `ef_dora` for Spanish and `ff_siwis` for French; they're all in `--list-voices`.

To save audio where other apps can find it, run `termux-setup-storage` once and then write to `~/storage/shared/...`, for example `-o ~/storage/shared/Music/note.wav`.

## Speed

On a recent mid-range phone, the int8 model makes speech about as fast as real time or faster. Each run spends a few seconds starting the container and loading the model, so one long text is quicker than many short ones.

## Uninstall

```sh
proot-distro remove kokoro
rm $PREFIX/bin/kokoro ~/kokoro-test.wav
```

## Troubleshooting

- **`pkg` errors or mirror problems:** run `termux-change-repo`, pick a different mirror, then `pkg upgrade`.
- **Downloads fail partway through:** run `install.sh` again. Model downloads pick up where they left off.
- **`--play` makes no sound:** check the phone's media volume. You can also install the Termux:API app plus `pkg install termux-api`, and the script will use `termux-media-player` as a fallback.
- **"Process completed (signal 9)":** Android 12+ kills processes it considers "phantom." Search for "Termux phantom process killer" for the one-time fix, or keep inputs shorter.
