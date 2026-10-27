# Squelch — push-to-talk speech-to-text on your GPU

Hold **Menu**, speak, release — the transcription appears at your cursor in
well under a second. Tap **Menu** for a settings popup. Fully on-device,
no cloud, no accounts.

- **Fast GPU inference**: Parakeet TDT 0.6B v3 (English default, ~0.2 s for
  11 s of audio) and Whisper large-v3-turbo (other-languages fallback),
  via Vulkan — NVIDIA and AMD both work. The selected engine stays
  resident in VRAM; the other one is unloaded
- **Mic-array beamforming**: adaptive delay-and-sum across your mic's
  channels, high-pass, diffraction EQ, noise gate, normalization
- **Layout-proof output**: pastes via clipboard + Shift+Insert, so Colemak
  (or any layout) never mangles dictated text. In browsers it sends Ctrl+V
  instead, since web editors like Word online ignore Shift+Insert
- **Text cleanup**: filler words (um/uh/ah…) removed, number words over ten
  (optionally all) written as digits, custom dictionary with AI-suggested
  mishearings via OpenRouter
- **Game mode**: models unload once a game has been fullscreen for 10 s and
  reload after. Browsers, video players, the lock screen and Spectacle
  going fullscreen don't count
- **Analytics**: all-time words/WPM/latency plus last-60-minutes WPM and a
  7-day table, in the tap popup and a fullscreen dashboard
- **Everything survives reboot** via systemd user services

Tested on EndeavourOS / KDE Plasma 6 / Wayland, AMD RX 5700,
Logitech C920 webcam (stereo mic), kanata. PipeWire required; KWin fullscreen
detection and tray applet assume Plasma, other Wayland compositors need
small tweaks.

## Install (Arch)

```
yay -S squelch
squelch-setup-models     # downloads ~1.2 GB of models, resumable
```

Then wire up a trigger key — Menu by default (needs [kanata](https://github.com/jtroo/kanata)) —
see [kanata/snippet.kbd](kanata/snippet.kbd), validate with
`kanata --check`, restart kanata — and start everything:

```
systemctl --user enable --now squelch-ptt squelch-tray squelch-vram squelch-ydotoold
```

Focus a text field, hold your trigger key, speak, release.

## Usage

| Action | How |
|---|---|
| Dictate | hold trigger key, speak, release |
| Settings (engine, trigger, insertion, cleanup, dictionary, analytics, setup) | tap trigger, double-tap for Settings, or tray menu |
| Engine switch | settings popup, General tab, or right-click tray icon |
| Trigger key (Menu/Caps Lock/Scroll Lock) | settings → General (rewrites kanata binding) |
| Insertion (paste/type/clipboard-only) | settings → General |
| Speech models (per-engine file) | settings → Setup |
| Microphone (default: PipeWire's default source) | settings → Setup |
| Download models, autostart services, API key | settings → Setup (no terminal needed) |
| Dictionary: `squelch WORD [mishearings…]` | AI suggests what it sounds like when an OpenRouter key is configured (`~/.config/squelch/openrouter.key`, chmod 600); `--no-ai` skips that |
| Dictionary editor | settings → Dictionary tab (Add/Suggest/Remove) |
| Analytics dashboard | settings → Analytics tab, or glance line on the tap card |

Other languages: switch to Whisper in the popup (auto language
detect). Game fullscreen unloads models automatically; dictating then shows
one notification instead of failing silently. So do failed pastes and
transcriptions.

A dictation can run up to 2 minutes. Holding longer keeps the first 2
minutes and tells you it was cut (Parakeet's VRAM use grows with clip
length, so a stuck key can't balloon it).

Optional archive for future training: put a directory in
`~/.config/squelch/archive` (e.g. `echo ~/squelch-archive >
~/.config/squelch/archive`) and every utterance's raw audio, cleaned mono
and transcript go there. If the directory is unreachable (a NAS that's
away), the last 100 utterances wait in `~/.config/squelch/spool` and upload
later. No file, no archive. Adaptation is fully on-device either way:
frequent words you actually say are mined into your Whisper vocabulary
prompt over time. No gradients leave your machine, ever.

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
- `polkit/` — lets wheel users' squelch-ptt start the mic-reset unit · `kanata/snippet.kbd` — Menu tap-hold wiring

Runtime state lives in `~/.config/squelch/` (engine, filters, dictionary,
mic, archive, stats, key). Models in `~/.local/share/whisper.cpp/models` and
`~/.local/share/parakeet`.

## Uninstall

```
yay -R squelch
rm -rf ~/.config/squelch ~/.local/share/whisper.cpp ~/.local/share/parakeet
```

## License

MIT — see LICENSE.
