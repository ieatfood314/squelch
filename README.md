# Squelch — push-to-talk speech-to-text on your GPU

Hold **Menu**, speak, release — the transcription appears at your cursor in
well under a second. (With [Nullspace](#nullspace-on-the-desktop) the same
key also captures the screen and takes voice notes.) Optionally the words are typed while you speak. Tap
**Menu** for a settings popup. Transcription is fully on-device, with no
cloud account needed; optional AI polishing uses an endpoint you configure.

- **Fast GPU inference**: Parakeet TDT 0.6B (default, ~0.2 s for 11 s of
  audio; v3 multilingual, or v2 if you only dictate English, which is a
  little more accurate) and Whisper large-v3-turbo (other-languages
  fallback), via Vulkan — NVIDIA, AMD and Intel (integrated too) work. The selected engine
  stays resident in VRAM; the other one is unloaded
- **Mic-array beamforming**: adaptive delay-and-sum across your mic's
  channels, high-pass, diffraction EQ, noise gate, normalization
- **Type as you speak** (Parakeet): while you hold the key, the audio so
  far is transcribed twice a second, and each word is typed once the model
  is sure of it: recent passes all agree on it, a second of speech has
  followed it, and its confidence is 0.9 or more. Typed words are never
  taken back, and the rest arrives on release. Replayed over 160 real
  dictations, 136 came out word for word what you get with it off, and
  0.6% of words differed beyond punctuation
- **A pill while it works**: level bars at the bottom of the screen while
  it listens, a ripple while it transcribes. It never takes focus or clicks
- **Layout-proof output**: pastes via clipboard + Shift+Insert, or Ctrl+V
  in browsers, since web editors like Word online ignore Shift+Insert.
  Type mode goes through your active keyboard layout (read from Plasma and
  libxkbcommon), so Colemak or a layout switched mid-session types
  correctly; characters it has no key for are pasted
- **Text cleanup**: filler words (um/uh/ah…) removed, number words over ten
  (optionally all) written as digits, spoken part numbers joined
  ("seven four HCT two seven three" → 74HCT273), optional Canadian-English
  spelling (colour, favour), custom dictionary with AI-suggested
  mishearings via OpenRouter, and mishearings learned from your own
  dictation. Optional AI tidying cuts repeated phrases, false starts and
  self-corrections without summarizing or rewriting your words
- **Game mode**: models unload once a game has been fullscreen for 10 s and
  reload after. Browsers, video players, the lock screen and Spectacle
  going fullscreen don't count
- **Analytics**: all-time words/WPM/latency plus last-60-minutes WPM and a
  7-day table, in the tap popup and a fullscreen dashboard
- **Sync**: dictionary and analytics shared between your computers and
  your phone (through [Nullspace](#phone)) with a small hub you run
  yourself ([Sync](#sync))
- **Everything survives reboot** via systemd user services

Tested on EndeavourOS / KDE Plasma 6 / Wayland, AMD RX 5700,
Logitech C920 webcam (stereo mic), with and without kanata, and on a
laptop with Intel Alder Lake graphics and its built-in mic. PipeWire required; KWin fullscreen
detection and tray applet assume Plasma, other Wayland compositors need
small tweaks.

## Phone

Squelch's dictation on Android lives in
[Nullspace](https://github.com/ieatfood314/nullspace), a separate app:
hold a key (the Essential key on Nothing phones), speak, let go, and the
text lands in the field you're in, decoded on the phone with the same
Parakeet model. Nullspace also catches screenshots, voice notes and
reminders, and works on its own.

Together they share what you've taught them. Point Nullspace at your
Squelch [sync hub](#sync) and you get:

- **One dictionary.** Words you add on the phone or a computer, and the
  mishearings they fix, reach every device, deletions included.
- **One set of analytics.** The tray's Analytics tab and the phone's
  settings both add up every device.
- **One training archive** (if your hub keeps one, see below). The phone's
  dictation audio and the edits you make afterwards land in the same
  archive as your computers', ready for fine-tuning.

To set it up, on the phone: Nullspace → Settings → **Words sync → Set up**,
paste your hub's address (the same one as `~/.config/squelch/sync`, like
`https://squelch.your-tailnet.ts.net`) and tap **Save**. It syncs on Wi-Fi
only, when you open the app and around each dictation. To also send the
phone's recordings to your archive, turn on **Keep recordings** below it.

`android/` here is the old Squelch for Android, no longer updated.

## Nullspace on the desktop

Nullspace has a Linux app too (AUR `nullspace`) with Squelch's engine
inside: the `nullspace` package replaces this one, and everything you see
says Nullspace. Squelch stays for when you want dictation and nothing else.
With Nullspace running, the trigger key does what the phone's Essential key
does:

| Press | Does |
|---|---|
| Hold | Dictation, as always |
| Tap | Captures the screen and its text into your Nullspace list; a card shows it, and holding the key while it shows adds a voice note about it |
| Tap, then hold | A voice note |
| Double tap | Opens Nullspace |

Nullspace's tray menu has a **Dictation** entry that opens these settings.

How they talk: Nullspace listens on `$XDG_RUNTIME_DIR/nullspace-<uid>`.
squelch-ptt sends it `capture`, `open`, `note-start {"about": …}` and
`note-end {"about": …, "text": …}` (a voice note's transcript is handed
over instead of typed), and reads `nullspace-<uid>-card` for the
screenshot a hold would be a note about.

## Install (Arch)

```
yay -S squelch
```

Then open **Squelch** from the app menu. Settings opens on Setup with a
Get started list: keyboard access (adds you to the `input` group, restart
after), the speech models (~1.2 GB), autostart, and the trigger key.
Focus a text field, hold the trigger key, speak, release.

From a terminal instead:

```
sudo usermod -aG input $USER
```

```
squelch-setup-models
```

```
systemctl --user enable --now squelch-ptt squelch-tray squelch-vram squelch-ydotoold
```

The `input` group is needed because Squelch reads the trigger key
straight from your keyboard and types through uinput.

Setup gets the two default models. Smaller and larger ones are in
Settings → Setup, or `squelch-setup-models --list`:

| Engine | Model | Size | For |
|---|---|---|---|
| Parakeet | Multilingual (default) | 638 MB | English and 24 other European languages (v3) |
| Parakeet | Multilingual small | 396 MB | GPUs with little VRAM; same model, 4-bit |
| Parakeet | English | 628 MB | English only, a little more accurate at it (v2) |
| Parakeet | English small | 408 MB | GPUs with little VRAM; same model, 5-bit |
| Whisper | Turbo (default) | 547 MB | any language |
| Whisper | Small | 181 MB | weak GPUs |
| Whisper | Large | 1.0 GB | best accuracy outside English, slower |

ggml-org only publishes v3, so the English (v2) files come from a
community conversion for the same whisper.cpp engine
([JoaoZaokk/parakeet-tdt-0.6b-v2-ggml](https://huggingface.co/JoaoZaokk/parakeet-tdt-0.6b-v2-ggml)),
pinned by SHA-256.

### Trigger key

Any key works: Settings → General → Change, then press it. Read straight
from the keyboard, the key also keeps doing its normal job (Caps Lock
still toggles caps), so pick one you don't otherwise use, like Menu,
Scroll Lock, Pause or F13–F24. To stop the key's own action too, bind it
with [kanata](https://github.com/jtroo/kanata) instead: add the tap-hold
from [kanata/snippet.kbd](kanata/snippet.kbd), check it with
`kanata --check` and restart kanata. Once that binding is in your kanata
config, Change moves it to the new key for you.

## Usage

| Action | How |
|---|---|
| Dictate | hold trigger key, speak, release |
| Settings (engine, trigger, insertion, cleanup, appearance, dictionary, analytics, setup) | tap trigger, double-tap for Settings, Squelch in the app menu, or tray menu |
| Engine switch | settings popup, General tab, or right-click tray icon |
| Trigger key (any key) | settings → General → Change, then press the key |
| Insertion (paste/type/clipboard-only), type as you speak | settings → General |
| Dictation language (automatic or your own), regional spelling, filler words, numbers, AI tidying | settings → General → Cleanup |
| Accent color, background opacity, text size, font | settings → Appearance |
| Speech models (picking one that isn't downloaded yet downloads it) | settings → Setup |
| Microphone (default: PipeWire's default source) | settings → Setup |
| Download models, autostart services, keyboard access | settings → Setup (no terminal needed; until it's all done, the app menu opens Setup with a Get started list) |
| Dictionary: `squelch WORD [mishearings…]` | AI suggests what it sounds like when an OpenRouter key is configured (`~/.config/squelch/openrouter.key`, chmod 600); `--no-ai` skips that |
| Dictionary editor | settings → Dictionary tab (Add/Suggest/Remove) |
| OpenRouter key | settings → Dictionary tab; the button shows Set or Not set |
| Suggestion fallback | optional `~/.config/squelch/llm-fallback.json`, `{"url": "https://host/v1", "key_file": "~/path/to/key", "model": "id"}`: any OpenAI-compatible endpoint, tried when OpenRouter fails or no key is set |
| Learned mishearings | settings → Dictionary tab, under Possible mishearings (Add/Ignore) |
| Analytics dashboard | settings → Analytics tab, or glance line on the tap card |

Other languages: Parakeet Multilingual detects 25 languages automatically;
Whisper supports 100. Whisper auto-detects by default, or choose a language
name or tag such as `fr-CA` under Settings → General → Cleanup → Language.
Custom words can correct spelling in any language. Game fullscreen unloads models automatically; dictating then shows
one notification instead of failing silently. So do failed pastes and
transcriptions.

How long one dictation can run depends on free VRAM. Parakeet's working
memory grows with the square of clip length (about 0.5 GB for 2 min,
1.6 GB for 4 min, 4.6 GB for 6 min), so at each press Squelch allows the
longest clip that still leaves the VRAM headroom free (Settings → Setup,
default 1 GB; the longest dictation it allows right now is shown next to
it). On an 8 GB card that's about 6 minutes. After a long clip the server
reloads (0.2 s) to hand that memory back. Whisper works in 30 s windows,
so it only has a 15-minute stuck-key ceiling. Holding past the cap keeps
the first part and tells you it was cut.

Optional archive for future training: put a directory in
`~/.config/squelch/archive` (e.g. `echo ~/squelch-archive >
~/.config/squelch/archive`) and every utterance's raw audio, cleaned mono
and transcript go there. If the directory is unreachable (a NAS that's
away), the last 100 utterances wait in `~/.config/squelch/spool` and upload
later. No file, no archive. Adaptation is fully on-device either way:
frequent words you actually say are mined into your Whisper vocabulary
prompt over time. And when a transcript has something that looks and
sounds like one of your dictionary words ("QubitTrint" for qBittorrent),
Squelch learns it as a mishearing. If it isn't a real word, it's added
straight away and you get a notification. If it is made of real words
("Cache OS"), it waits under Possible mishearings in the Dictionary tab
for you to add or ignore. Dictionary words that are plain English are
left alone, so ordinary speech never gets rewritten. Both need
`aspell-en`. No gradients leave your machine, ever.

Squelch also notices what you do to a dictation afterwards, so the archive
can pair each clip with the words you meant. Until you move on (another
dictation, Enter, switching windows, the box emptied by sending, or two
idle minutes) it watches the text it put in, and then appends a line to
`~/.config/squelch/corrections.jsonl` (and `corrections.jsonl` in the
archive, next to the audio, by the same `id`): the text as it went in, as
you left it, and what kind of change it was. Swapping a word for one that
sounds like it ("Regulath" → "Regolith"), deleting an "um" or a word heard
twice, or fixing punctuation is a `fix`, and the edited text becomes the
clip's `label`. Adding, dropping or rewording words is a `rewrite`, you
changing your mind rather than Squelch mishearing, so that clip gets
`"train": false` and no label, and a model trained on `label`s never learns
words you didn't say. An AI-tidied clip is marked `"tidied": true` and isn't
a training label either: its audio still contains the repeats and false
starts that were cut. Your later corrections are still recorded. Clips whose
start was cut off (below) aren't trained on either, nor are ones where it
lost track of the text (`"unsure": true`).

It sees the text through accessibility (AT-SPI, which Squelch switches on),
so it catches every edit, mouse ones included, in Qt, GTK and Firefox-based
apps. Chromium and Electron apps (Chrome, VS Code, Obsidian…) only share
the text in a page when started with `--force-renderer-accessibility`.
Add the flag to an app's launcher (for a Flathub Obsidian, a line in
`~/.var/app/md.obsidian.Obsidian/config/obsidian/user-flags.conf`) to get
the same there; it costs the app a little memory and CPU. Elsewhere it
follows the keys
you press: letters, Backspace, Delete and the arrows. A click moves the
cursor somewhere it can't see, so the keys after a click are placed when
you're done: where the selected text sits (double-clicks and drags), or
else the one place where they turn a word into a real word that sounds
like it (`"placed": "guess"`). If no place stands out, it stops there and
the clip isn't trained on. Needs `python-gobject` for the accessibility
part and `aspell-en` for placing.

If you were already talking when you pressed the key, the clip reaches
back to the pause before you started, up to 5 seconds (the mic stays
open, so the words are there). If you'd been talking even longer, the
start is gone, and an LLM guesses the missing words from the rest and the
text before the cursor (`llm-fast.json` if you made one, else the
`llm-fallback.json` endpoint, or OpenRouter). Parakeet reads a clipped
word as whichever whole word fits the sentence, so the guess may replace
or drop the first word heard, and is told when Parakeet was unsure of it.
It waits up to 0.6 s for that, and only for those clips; past that the
text goes in as heard. `llm-fast.json` has the same format as
`llm-fallback.json` and is for an endpoint that answers well inside that,
such as Groq (`https://api.groq.com/openai/v1`) with `qwen/qwen3.8-27b`.

With **Tidy repeats and false starts with AI** on in Settings → General →
Cleanup, dictations of at least eight words also get the same tidy pass as
Nullspace: repeated words and phrases, abandoned sentence starts and
self-corrections ("on Tuesday, no, Wednesday") are cut, and punctuation and
capitals are fixed. It uses `llm-fast.json`, `llm-fallback.json` or your
OpenRouter key, in that order; with none configured, nothing changes. Only
the dictation's text is sent, never its audio. A guard rejects an answer that
adds or changes too many words, cuts too much or grows the text, so the
transcript goes in as heard instead. Guessing a cut-off start and tidying
share a 1-second limit (the guess gets at most 0.6 s of it); after that the
text goes in as heard.
Turn the checkbox off to skip this pass. Dictations whose words were already
typed live are left alone, since live words are never taken back.

Music (any MPRIS player) pauses while you hold the trigger and resumes on
release, so it never bleeds into the mic.

### Mic

Pick the mic in Settings → Setup (stored in `~/.config/squelch/mic`); with
none picked, Squelch records from PipeWire's default source. A stereo
webcam mic like the Logitech C920 gets beamformed. Squelch checks the mic
after every resume and whenever a recording comes back short. If a USB mic
is dead, it power-cycles that device's USB port (`squelch-mic-reset@ID`, a
root oneshot that only touches USB audio devices; polkit lets members of
`wheel` start it without a password) and notifies you whether that
worked. Cutting port power needs a hub with per-port power switching; on
ports without it you get a "replug it" notification. `squelch-ptt
--probe-mic` checks the mic by hand, `--reset-mic` forces a reset.

Holding the trigger unmutes a muted mic, including the real mic behind a
virtual source like EasyEffects or echo-cancel, and tells you it did. A
mute it can't undo (a hardware switch) gets a "Mic is muted"
notification, and a virtual source with no mic feeding it gets "No mic
behind …".

### Sync

Your dictionary and analytics can follow you between computers and
[Nullspace](#phone) on your phone. One machine runs the hub, which every
device can reach (it has no login, so keep it on a private network such as
Tailscale):

```
systemctl --user enable --now squelch-sync-server
```

It listens on 127.0.0.1:4615 and keeps its data in
`~/.local/share/squelch-sync`; put it behind HTTPS on your private network,
for example `tailscale serve --bg 4615`. Then on each computer, write the
hub's address to `~/.config/squelch/sync` and turn on the sync units:

```
echo https://squelch.your-tailnet.ts.net > ~/.config/squelch/sync
```

```
systemctl --user enable --now squelch-sync.timer squelch-sync.path
```

The computer syncs whenever its dictionary or stats change, and every 3
minutes to pick up everyone else's. On the phone, put the same address
under Nullspace's Words sync. Words added or removed anywhere reach every
device, deletions included, and the Analytics tab adds up every device
(with a per-device line once there's more than one). `squelch-sync --status`
shows when it last synced.

To collect phones' recordings too, name a training archive folder in
`~/.config/squelch/archive` on the hub's machine (or start it with
`--archive DIR`). Without one, phones keep their clips and try again later.

#### The hub's API

For anyone building another client (Nullspace is one). No auth; JSON in
and out.

| Endpoint | Body | Answer |
|---|---|---|
| `GET /v1/status` | | `{"squelch-sync": 1, words, devices}` (per device: `name`, `events`, `last`) |
| `POST /v1/sync` | `device` (a stable id), `name`, `dictionary: {mine, base}` (your entries now, and as of your last sync; entries are `{term, also_match: [...]}`), `stats: {events, have}` (new dictation events, and the newest `ts` you hold per other device) | `dictionary` (merged three ways), `stats: {pushed, devices}` (other devices' events after what you have) |
| `POST /v1/archive` | one clip: `id`, `ts`, `device`, `engine`, `audio_s`, `text` (as heard), `cut` (the mic opened mid-word), `wav` (16 kHz mono WAV, base64) | `{ok}`, or 404 when the hub keeps no archive |
| `POST /v1/corrections` | `device`, `items`: per dictation `id`, `ts`, `app`, `text` (as inserted), `edited` (what you left it as), `heard`, `cut`, `end` (why the watch ended), and optionally `before` (300 characters of what was in the box), `guess` (a guessed start for a cut-off clip) and `tidied` (an LLM cut repeats from it, so it isn't used as a label) | `{ok, saved}` |

## Layout

- `src/squelch-ptt` — push-to-talk daemon (record → beamform → transcribe → paste)
- `src/squelch-parakeet-server` — resident Parakeet server (ctypes, port 1894)
- `src/squelch-tray` — tray applet, tap popup, dictionary editor, analytics
- `src/squelch-vram` — supervisor: one engine at a time, unload when gaming
- `src/squelch` — dictionary CLI · `src/squelch-setup-models` — model downloads
- `src/squelch-sync` — dictionary and analytics sync · `src/squelch-sync-server` — the hub it syncs with
- `src/squelch-mic-reset` — root helper: power-cycle a USB mic's port
- `systemd/` — user units · `systemd/system/` — mic-reset template unit
- `polkit/` — lets wheel users' squelch-ptt start the mic-reset unit · `kanata/snippet.kbd` — optional kanata tap-hold for the trigger
- `android/` — the old Android app, now [Nullspace](https://github.com/ieatfood314/nullspace)

Runtime state lives in `~/.config/squelch/` (engine, filters, dictionary,
mic, hotkey, archive, vram.json, stats, sync, key; other devices' stats in stats.d/). Models in `~/.local/share/whisper.cpp/models` and
`~/.local/share/parakeet`.

## Uninstall

```
yay -R squelch
rm -rf ~/.config/squelch ~/.local/share/whisper.cpp ~/.local/share/parakeet
```

## License

MIT — see LICENSE.
