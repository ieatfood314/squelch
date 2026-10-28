# Squelch — push-to-talk speech-to-text on your GPU

Hold **Menu**, speak, release — the transcription appears at your cursor in
well under a second. Tap **Menu** for a settings popup. Fully on-device,
no cloud, no accounts.

- **Fast GPU inference**: Parakeet TDT 0.6B (default, ~0.2 s for 11 s of
  audio; v3 multilingual, or v2 if you only dictate English, which is a
  little more accurate) and Whisper large-v3-turbo (other-languages
  fallback), via Vulkan — NVIDIA and AMD both work. The selected engine
  stays resident in VRAM; the other one is unloaded
- **Mic-array beamforming**: adaptive delay-and-sum across your mic's
  channels, high-pass, diffraction EQ, noise gate, normalization
- **Layout-proof output**: pastes via clipboard + Shift+Insert, so Colemak
  (or any layout) never mangles dictated text. In browsers it sends Ctrl+V
  instead, since web editors like Word online ignore Shift+Insert
- **Text cleanup**: filler words (um/uh/ah…) removed, number words over ten
  (optionally all) written as digits, custom dictionary with AI-suggested
  mishearings via OpenRouter, and mishearings learned from your own
  dictation
- **Game mode**: models unload once a game has been fullscreen for 10 s and
  reload after. Browsers, video players, the lock screen and Spectacle
  going fullscreen don't count
- **Analytics**: all-time words/WPM/latency plus last-60-minutes WPM and a
  7-day table, in the tap popup and a fullscreen dashboard
- **Everything survives reboot** via systemd user services

Tested on EndeavourOS / KDE Plasma 6 / Wayland, AMD RX 5700,
Logitech C920 webcam (stereo mic), with and without kanata. PipeWire required; KWin fullscreen
detection and tray applet assume Plasma, other Wayland compositors need
small tweaks.

## Install (Arch)

```
yay -S squelch
squelch-setup-models     # downloads ~1.2 GB of models, resumable
```

That gets the two default models. Smaller and larger ones are in
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

Squelch reads the trigger key straight from your keyboard, so your user
needs to be in the `input` group (log out and back in after):

```
sudo usermod -aG input $USER
```

Then start everything:

```
systemctl --user enable --now squelch-ptt squelch-tray squelch-vram squelch-ydotoold
```

Focus a text field, hold Menu, speak, release.

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
| Settings (engine, trigger, insertion, cleanup, appearance, dictionary, analytics, setup) | tap trigger, double-tap for Settings, or tray menu |
| Engine switch | settings popup, General tab, or right-click tray icon |
| Trigger key (any key) | settings → General → Change, then press the key |
| Insertion (paste/type/clipboard-only) | settings → General |
| Accent color, background opacity, text size, font | settings → Appearance |
| Speech models (picking one that isn't downloaded yet downloads it) | settings → Setup |
| Microphone (default: PipeWire's default source) | settings → Setup |
| Download models, autostart services, API key | settings → Setup (no terminal needed) |
| Dictionary: `squelch WORD [mishearings…]` | AI suggests what it sounds like when an OpenRouter key is configured (`~/.config/squelch/openrouter.key`, chmod 600); `--no-ai` skips that |
| Dictionary editor | settings → Dictionary tab (Add/Suggest/Remove) |
| Learned mishearings | settings → Dictionary tab, under Possible mishearings (Add/Ignore) |
| Analytics dashboard | settings → Analytics tab, or glance line on the tap card |

Other languages: switch to Whisper in the popup (auto language
detect). Game fullscreen unloads models automatically; dictating then shows
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

## Layout

- `src/squelch-ptt` — push-to-talk daemon (record → beamform → transcribe → paste)
- `src/squelch-parakeet-server` — resident Parakeet server (ctypes, port 1894)
- `src/squelch-tray` — tray applet, tap popup, dictionary editor, analytics
- `src/squelch-vram` — supervisor: one engine at a time, unload when gaming
- `src/squelch` — dictionary CLI · `src/squelch-setup-models` — model downloads
- `src/squelch-mic-reset` — root helper: power-cycle a USB mic's port
- `systemd/` — user units · `systemd/system/` — mic-reset template unit
- `polkit/` — lets wheel users' squelch-ptt start the mic-reset unit · `kanata/snippet.kbd` — optional kanata tap-hold for the trigger

Runtime state lives in `~/.config/squelch/` (engine, filters, dictionary,
mic, hotkey, archive, vram.json, stats, key). Models in `~/.local/share/whisper.cpp/models` and
`~/.local/share/parakeet`.

## Uninstall

```
yay -R squelch
rm -rf ~/.config/squelch ~/.local/share/whisper.cpp ~/.local/share/parakeet
```

## License

MIT — see LICENSE.
