# Squelch — push-to-talk speech-to-text on your GPU

Hold **Menu**, speak, release — the transcription appears at your cursor in
well under a second. Tap **Menu** for a settings popup. Fully on-device,
no cloud, no accounts.

- **Fast GPU inference**: Parakeet TDT 0.6B v3 (English default, ~0.2 s for
  11 s of audio) and Whisper large-v3-turbo (other-languages fallback),
  both resident in VRAM via Vulkan — NVIDIA and AMD both work
- **Mic-array beamforming**: adaptive delay-and-sum across your camera/mic
  channels, high-pass, diffraction EQ, noise gate, normalization
- **Layout-proof output**: pastes via clipboard + Shift+Insert, so Colemak
  (or any layout) never mangles dictated text
- **Text cleanup**: filler words (um/uh/ah…) removed, number words over ten
  (optionally all) written as digits, custom dictionary with AI-suggested
  mishearings via OpenRouter
- **Game mode**: models unload automatically while a fullscreen app runs,
  reload after; only one engine resident at a time
- **Analytics**: all-time words/WPM/latency plus last-60-minutes WPM and a
  7-day table, in the tap popup and a fullscreen dashboard
- **Everything survives reboot** via systemd user services

Tested on EndeavourOS / KDE Plasma 6 / Wayland, AMD RX 5700, Sony
PlayStation Eye 4-mic array, kanata. PipeWire required; KWin fullscreen
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
| Download models, autostart services, API key | settings → Setup (no terminal needed) |
| Dictionary: `squelch WORD [mishearings…]` | AI suggests what it sounds like when an OpenRouter key is configured (`~/.config/squelch/openrouter.key`, chmod 600); `--no-ai` skips that |
| Dictionary editor | settings → Dictionary tab (Add/Suggest/Save) |
| Analytics dashboard | settings → Analytics tab, or glance line on the tap card |

Other languages: switch to Whisper in the popup (auto language
detect). Game fullscreen unloads models automatically; dictating then shows
one notification instead of failing silently.

Every utterance is archived for future training: raw 4-channel audio,
cleaned mono, and the transcript go to `/mnt/raid1/aidan/squelch/` (when
writable; otherwise queued locally and uploaded later). Adaptation is fully
on-device — frequent words you actually say are mined into your Whisper
vocabulary prompt over time. No gradients leave your machine, ever.

## Layout

- `src/squelch-ptt` — push-to-talk daemon (record → beamform → transcribe → paste)
- `src/squelch-parakeet-server` — resident Parakeet server (ctypes, port 1894)
- `src/squelch-tray` — tray applet, tap popup, dictionary editor, analytics
- `src/squelch-vram` — supervisor: one engine at a time, unload when gaming
- `src/squelch` — dictionary CLI · `src/squelch-setup-models` — model downloads
- `systemd/` — user units · `kanata/snippet.kbd` — Menu tap-hold wiring

Runtime state lives in `~/.config/squelch/` (engine, filters, dictionary,
stats, key). Models in `~/.local/share/whisper.cpp/models` and
`~/.local/share/parakeet`.

## Uninstall

```
yay -R squelch
rm -rf ~/.config/squelch ~/.local/share/whisper.cpp ~/.local/share/parakeet
```

## License

MIT — see LICENSE.
