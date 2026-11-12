# Squelch for Android

**Moved:** this app is now part of [Uplink](https://github.com/ieatfood314/uplink),
with the same dictation. This folder is no longer updated.

Hold a key, speak, let go: the text lands in whatever field you're typing in.
Same idea as desktop Squelch, on the phone's CPU, offline.

- **Models:** Parakeet TDT 0.6B v2 (English, the default), Parakeet v3
  (English and 24 other European languages) or Parakeet 110M (English, about
  five times quicker, less accurate), all through
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and downloaded from
  Hugging Face when you pick them. Any other sherpa-onnx transducer on Hugging
  Face works too: type its `user/name` under Model (it needs `encoder*.onnx`,
  `decoder*.onnx`, `joiner*.onnx` and `tokens.txt`; int8 files are preferred).
- **Speed:** on a Dimensity 7300 (CMF Phone 2 Pro) 7.4 s of speech decodes
  in about 0.65 s. Finished sentences are decoded while you're still talking,
  so after you let go only the last one is left: 25 s of speech came out
  0.28 s after release.
- **Trigger key:** any key the phone reports, set from the app. Defaults to
  the Essential key on Nothing and CMF phones (Linux key 250, which Android
  has no keycode for).
- **Typing:** through an accessibility service. Plain fields get the text
  set directly at the cursor, so the clipboard is left alone. Rich web
  editors (Gmail, Docs) get it pasted, which keeps their formatting.
- **Cleanup:** filler words (um, uh…) removed and your dictionary applied,
  the same as desktop Squelch. Words lists and edits it.
- **Sync:** with a hub address set (see [Sync](../README.md#sync)), your
  words and analytics are shared with desktop Squelch on your computers. The
  phone syncs when you open the app, when a dictation starts and when it ends.
- **Analytics:** words, WPM, transcription time and a 7-day table, for this
  phone and every synced device, the same numbers as the desktop dashboard.
- **Memory:** the model takes about 1 GB of RAM. When Android runs low it's
  unloaded, and the next dictation loads it again while you talk (about 3 s).

## Install

Get the APK from [Releases](https://github.com/ieatfood314/squelch/releases)
(or add the repo to Obtainium), open Squelch, then:

1. **Accessibility:** Turn on, then enable Squelch.
2. **Microphone:** Allow.
3. **Model:** pick one (English is the usual choice).
4. **Sync** (optional): your hub's address, e.g. `squelch.your-tailnet.ts.net`.

Focus a text field, hold the Essential key, speak, let go.

### The Essential key on Nothing OS

Nothing's own handler sees the key before any app can stop it, so it
still opens Essential Space (tap) or the recorder (hold). Turn those apps off
over adb:

```
adb shell pm disable-user --user 0 com.nothing.ntessentialspace
```

```
adb shell pm disable-user --user 0 com.nothing.ntessentialrecorder
```

Essential Voice (hold while the keyboard is up) has its own switches:

```
adb shell settings put secure nt_essential_voice_action_hold 2
```

```
adb shell settings put secure nt_essential_voice_action_double_click 2
```

To undo, `pm enable` the two apps and set both settings back to `0` and `1`.

## Build

JDK 17 and the Android SDK (platform 35). The sherpa-onnx AAR is fetched and
checksum-checked on the first build.

```
./gradlew assembleDebug
```

Signed release: `./release.sh 0.2.0 2` (needs `~/.config/squelch-android/`
with `android.jks` and `android.pass`).

Debug builds include a `Bench` receiver for timing decodes on the device
without a mic, e.g.
`adb shell am broadcast -n ca.heeney.squelch/.Bench --es wav files/x.wav --ez stream true`.
