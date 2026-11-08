# kokomux: Kokoro TTS on Termux

Natural-sounding offline text-to-speech on your Android phone. kokomux installs [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) in Termux and gives you a `kokoro` command that can read text, files, your clipboard and web pages, either out loud or into an audio file.

```sh
kokoro -p "Hello from my phone"
```

<p>
  <img src="docs/install-progress.png" width="32%" alt="The installer checking the phone and downloading the voice model">
  <img src="docs/install-done.png" width="32%" alt="The finished install with a quick-start card">
  <img src="docs/kokoro.png" width="32%" alt="Using the kokoro command: saving a file, playing text, an error message and the status screen">
</p>

While it reads aloud, kokomux shows the text and lights up each word as it's spoken:

<p><img src="docs/words.png" width="60%" alt="kokoro -p showing two lines of a story, with the word being spoken highlighted"></p>

## Install

You need **Termux from F-Droid or GitHub** (the Play Store version is outdated), a 64-bit phone, and about **700 MB** of free space. Then run this in Termux:

```sh
curl -fsSLO https://raw.githubusercontent.com/Anesu/kokomux/main/install.sh && bash install.sh
```

The installer checks your phone first (CPU, free space, connection), shows what it's going to do, and asks before starting. Then it works through seven steps with live progress, and keeps the phone awake until it's done. Installing takes 5–15 minutes. At the end it says hello out loud. Running it again is safe: it skips what's already done and updates the rest.

- `--yes` skips the question (for scripts).
- `--verbose` shows everything the installer runs.

If a step fails, the installer shows what went wrong and how to fix it ([example](docs/install-error.png)). The full log is kept at `~/.cache/kokomux/install.log`. If you stop it with Ctrl+C, nothing breaks: run it again and it carries on.

**Optional extras:**
- **Termux:API app** (from the same place you got Termux) plus `pkg install termux-api`, for reading the clipboard.
- **Termux:Widget app**, for home-screen buttons.

## Use

```sh
kokoro -p "Say this out loud"             # speak (starts after the first sentence)
kokoro "Save this"                        # save to kokoro.wav
kokoro -o note.mp3 "Smaller file"         # .wav .mp3 .ogg or .flac
kokoro -c -p                              # read the clipboard
kokoro -u https://example.com/article -p  # read a web page
kokoro -f story.txt -o story.mp3          # read a text file
cat notes.txt | kokoro -p                 # or pipe text in
kokoro --stop                             # stop playback
```

| Option | Meaning |
|---|---|
| `-p` | Play while it's being made. Nothing is saved unless you also give `-o`. |
| `-o FILE` | Save to FILE. The ending picks the format: `.wav`, `.mp3`, `.ogg` or `.flac`. |
| `-v NAME` | Voice, e.g. `af_heart`, `am_adam`, `bf_emma`, `bm_george` |
| `-s N` | Speed, 0.5–2.0 |
| `-l CODE` | Language: `en-us`, `en-gb`, `es`, `fr-fr`, `it`, `pt-br`, `hi`. Use a voice for that language too. |
| `-f FILE` | Read a text file |
| `-c` | Read the clipboard. A copied link reads that web page. |
| `-u URL` | Read a web page. Menus, sidebars, tables and references are skipped. |

**Voices:** the first two letters of a voice name tell you its accent and gender. `a` is American, `b` British, then `f` for female and `m` for male. Other languages have their own voices, such as `ef_dora` for Spanish and `ff_siwis` for French. `kokoro --list-voices` shows all 54.

**Saving where other apps can find it:** run `termux-setup-storage` once, then save to shared storage, e.g. `-o ~/storage/shared/Music/note.mp3`.

## Words light up as they're read

With `-p` on a terminal, each line appears as it starts playing. A soft highlight then moves from word to word in time with the voice: words already spoken are shown normally and upcoming words are dim. When a line finishes it stays on screen, so a long text leaves a transcript behind.

How it works:

1. **Recognise.** [Whistle](https://cactuscompute.com/blog/whistle), a small speech recogniser from Cactus Compute, listens to each line Kokoro renders and returns the words it heard, with start and end times. It runs on the CPU inside the Debian container, beside Kokoro, and starts with the line you're hearing.
2. **Match.** Whistle isn't a forced aligner, so its transcript won't exactly match the text. An edit-distance alignment pairs its words with the text's words in order. It handles numbers ("60" ↔ "sixty"), hyphenated words ("worn-out" ↔ "wornout"), and names it hears as several words ("Igdrasil" ↔ "Igg Dressel").
3. **Store.** Each line's result is cached as `<audio_hash>.words.json` in `/opt/kokoro/cache/words/` inside the container. Each entry holds `s`/`e` (start and end in ms), `a`/`b` (the character range in the line's text) and `p` (Whistle's confidence). The same line in the same voice is never transcribed twice.
4. **Highlight.** The player lights the word that matches the time since the line started playing. A line whose match scores below 0.35 gets no highlight rather than a wrong one, and is shown in plain text.

After the installer has fetched Whistle, highlighting works offline. Whistle transcribes English, Spanish, French and Italian among Kokoro's languages; lines in other languages (Hindi, Portuguese, Japanese, Mandarin) are shown without a highlight. To turn it off, set `KOKORO_WORDS=0`. If the highlight runs ahead of the voice on your phone, raise `KOKORO_WORDS_OFFSET_MS` (default 100); if it lags, lower it. `play-audio` doesn't report its position, so this offset covers its start-up delay.

## Use it from other apps

- **Share to Termux.** In any app, tap **Share → Termux**:
  - A **link** reads the web page aloud.
  - **Text or a text file** reads that aloud.

  The installer sets this up with `~/bin/termux-url-opener` and `~/bin/termux-file-editor`. If you already had your own versions of these files, it leaves them alone and tells you.
- **Home-screen buttons.** With Termux:Widget installed, add a Termux widget to the home screen. It lists **Kokoro read clipboard** and **Kokoro stop**. Copy some text, then tap the button.

## Settings

`kokoro --config` opens the settings file (`~/.config/kokoro/config`). There you can set your default voice, speed, language, file name, and how the background server behaves. For a single command, environment variables override the file:

```sh
KOKORO_VOICE=bm_george kokoro -p "Just this once"
```

## Speed: the background server

Starting Kokoro takes a few seconds: it has to start the Debian container and load the model. To avoid paying that every time, the first `kokoro` command starts a **background server** that keeps the model loaded. After that, each command starts almost instantly. The server stops by itself after 30 minutes without use.

```sh
kokoro --status        # is it running? what are my settings?
kokoro --serve         # start it now
kokoro --stop-server   # stop it and free its memory
```

If you'd rather not have it, set `KOKORO_AUTOSERVE=0` in the settings. Each command then loads Kokoro on its own, which is slower but uses no memory in between.

The server only listens on the phone itself (127.0.0.1). It needs a secret token that only Termux can read, so other apps can't use it.

## Update and uninstall

```sh
kokoro --update      # get the latest kokomux and Kokoro
kokoro --uninstall   # remove the container, the command, settings and helper scripts
```

## How it works

Kokoro's Python packages (`onnxruntime`, `espeakng-loader`, `soundfile`) only ship builds for standard Linux (glibc). Termux uses Android's own C library, so they can't run there directly. The installer therefore creates a small Debian container called `kokoro` with `proot-distro`, and runs Kokoro inside it. The parts:

- **`kokoro`** (Termux side): reads your text, starts or uses the server, and plays the audio with `play-audio`.
- **`engine.py`** (inside Debian): splits the text into sentences and runs Kokoro. With `-p` it hands back audio one chunk at a time, so playback starts after the first sentence.
- **`words.py`** (inside Debian): runs Whistle on each chunk in a separate thread and matches its words to the text, for the highlight.
- **Sharing files:** the two sides exchange text and audio through `$PREFIX/tmp`, which the container sees as `/tmp`.

The installer only keeps what's needed in the container. It skips documentation and man pages, and clears the apt and pip download caches after installing. The model is the 8-bit (int8) version, about 92 MB, plus about 28 MB of voices.

## Troubleshooting

- **`pkg` errors or mirror problems:** run `termux-change-repo`, pick another mirror, then `pkg upgrade`.
- **A download failed:** run the installer again. Model downloads pick up where they stopped.
- **`-c` hangs or fails:** you need both the Termux:API **app** and `pkg install termux-api`.
- **No sound:** check the media volume. Also make sure `play-audio` is installed (`pkg install play-audio`).
- **"Process completed (signal 9)", or the server keeps disappearing:** Android 12+ kills background processes it considers excessive. Search for "Termux phantom process killer" for the one-time fix.
- **Server problems:** `kokoro --stop-server` and try again. Its log is in `$PREFIX/tmp/kokoro-server/log`.

## Development

The files in `src/` are the source. `./build.sh` bundles them into the single `install.sh` that people download, so after editing anything in `src/`, run `./build.sh` and commit both.

| File | What it is |
|---|---|
| `src/kokoro` | the `kokoro` command (Termux side) |
| `src/engine.py` | Kokoro engine, server, web-page reader and model downloader (container side) |
| `src/words.py` | word timings: Whistle, the matching, and the cache (container side) |
| `tests/test_words.py` | tests for the matching: `python3 tests/test_words.py` |
| `src/setup-container.sh` | installs everything inside the container |
| `src/install.sh` | installer template (UI, checks, steps) |
| `VERSION` | the version stamped into both by `build.sh` |
