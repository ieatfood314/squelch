# Notes for coding agents

The README covers what squelch does and its file layout. This file covers where the work happens and how it ships.

## Where the work happens
- **The repo lives on nas** at `~/repos/squelch` (T3 project "squelch" on nas). regolith's old `~/src/squelch` clone is retired; don't work there.
- **The desktop app runs on regolith and mars, not nas.** nas has no mic and no squelch install (it is Mint, not Arch), so test desktop behaviour on regolith over `ssh regolith` (`wake-regolith` on nas first if it's asleep). Aidan uses regolith, so don't pop windows up or steal focus while he's on it.
- **The sync hub runs on nas** (`squelch-sync-server` user unit, a copy of `src/squelch-sync-server` at `~/.local/bin/squelch-sync-server`, served as https://squelch.dwelf-sirius.ts.net). When that file changes: `cp src/squelch-sync-server ~/.local/bin/ && systemctl --user restart squelch-sync-server`.
- **Android builds on nas** with `~/.local/android-toolchain` and the signing key in `~/.config/squelch-android/`. Gradle uses about 3 GB; `release.sh` stops its daemon when done, so do the same after any other Gradle run (with `JAVA_HOME=~/.local/android-toolchain/jdk17`, since nas has no system Java).

## Shipping the desktop app (AUR)
Squelch ships as the AUR package `squelch`, built from GitHub tag tarballs. Never hand-copy files into `/usr`; that drifted from the repo before 0.6.0. Releases are cut from main, so merge a feature branch before tagging; 0.8.0 shipped without the `live-typing` branch that regolith had been trying. To try a branch on regolith, stop the squelch-ptt service first: a second squelch-ptt run by hand next to it answers every hold too, which doubles every dictation (this happened 2026-11-07 to 11-08). Run from `~/repos/squelch` on nas:

1. Commit to main and push.
2. Bump `pkgver` in PKGBUILD (reset `pkgrel=1`), `git tag -a vX.Y.Z -m "Squelch X.Y.Z"`, push the tag.
3. Put `curl -sL https://github.com/ieatfood314/squelch/archive/refs/tags/vX.Y.Z.tar.gz | sha256sum` into PKGBUILD's first sha256sum. nas has no makepkg, so make `.SRCINFO` on regolith:
   `ssh regolith 'd=$(mktemp -d); cd $d; cat > PKGBUILD; makepkg --printsrcinfo; rm -rf $d' < PKGBUILD > .SRCINFO`
   Commit "X.Y.Z checksums", push.
4. AUR: only regolith's ssh key can push to `ssh://aur@aur.archlinux.org/squelch.git`. On regolith, clone it into a temp dir, copy in PKGBUILD and .SRCINFO (scp them from nas), commit, push.
5. Install on regolith: `makepkg` in a temp dir (builds whisper.cpp with Vulkan, several minutes), then `sudo -n pacman -U --noconfirm` on the package. `systemctl --user daemon-reload` and restart squelch-vram, squelch-ptt, squelch-tray and squelch-ydotoold, plus squelch-parakeet when `squelch-parakeet-server` changed (squelch-ptt and the server talk over HTTP, and typing as you speak needs the 0.9.0 server).
6. Verify on regolith: `pacman -Q squelch`, `cmp` each `/usr/bin/squelch*` against the tag's `src/`, and the services' journals.
7. mars (laptop, often offline) also runs squelch. On regolith, copy step 5's `squelch-X.Y.Z-1-x86_64.pkg.tar.zst` (in the makepkg temp dir) to `/mnt/nas/Aidan/squelch-pkg/`, which is `/mnt/RAID1/Aidan/squelch-pkg/` on nas and `/mnt/nas/Aidan/squelch-pkg/` on mars, and queue an idempotent `sudo -n pacman -U` plus step 5's restarts with the fleet skill's `when-online mars - squelch-X.Y.Z`. Don't build on mars.

regolith also pulls AUR releases through yay on its own, so a reinstall of the same version is expected.

## Shipping Android
1. `cd android && ./release.sh X.Y.Z <versionCode>`. versionCode must go up every release (0.2.0 = 2).
2. Tag `android-vX.Y.Z` (never `vX.Y.Z`, which is the desktop/AUR namespace), push it, then `gh release create android-vX.Y.Z android/build/squelch-X.Y.Z.apk`.
3. Obtainium on halley tracks the GitHub releases. Only Android gets GitHub releases; desktop is tags only, so Obtainium never sees an APK-less latest release.

## Testing dictation without a mic or an unlocked screen
`STT_DRY_RUN=1` makes squelch-ptt log what it would type. Never let it type for real while regolith's screen is locked: the keys go into the lock screen. Live typing's rule (`live_settled` in squelch-ptt) was tuned by replaying the archive in `/mnt/RAID1/Aidan/squelch/raw/` in 0.5 s steps through a second `squelch-parakeet-server` (`SQUELCH_PARAKEET_PORT=1895`) and comparing what it would type with the full-clip transcript; redo that before changing its numbers.

## Mic on regolith
regolith uses a Logitech C920 (046d:0892, stereo) since 2026-10-23, picked in `~/.config/squelch/mic`. regolith's default source is easyeffects_source, so don't delete that file. The PS Eye it replaced wedged after suspend and only a replug fixed it; don't suggest going back to it or adding software resets for it. The "too quiet" gate is QUIET_PEAK=3000 / QUIET_RMS=500 in squelch-ptt, set from the C920's idle floor (peak about 1700, rms 350) rather than real speech, so if short dictations get dropped as too quiet, lower those first.
