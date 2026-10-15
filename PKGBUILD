# Maintainer: Aidan Heeney <aidan.heeney@proton.me>
pkgname=squelch
pkgver=0.2.0
pkgrel=1
pkgdesc='Push-to-talk speech-to-text on GPU: Parakeet/Whisper, mic-array beamforming, Menu-key dictation'
arch=('x86_64')
url='https://github.com/ieatfood314/squelch'
license=('MIT')
depends=('python' 'python-evdev' 'python-numpy' 'python-pyqt6' 'ydotool'
         'wl-clipboard' 'libnotify' 'pipewire' 'vulkan-icd-loader' 'kanata')
makedepends=('git' 'cmake' 'vulkan-headers' 'spirv-headers')
optdepends=('python-huggingface_hub: alternative model downloads')
source=("$pkgname-$pkgver.tar.gz::$url/archive/refs/tags/v$pkgver.tar.gz"
        "whisper.cpp::git+https://github.com/ggml-org/whisper.cpp.git#commit=0c7be696371069e0baa2515ed7aa08d5a5473aa6")
sha256sums=('4fba2b85e03cfa38bf1b2f9b3d6f596e6f4ae30f039297f0b217bb50e4715f46'
            'SKIP')
options=('!debug')

build() {
  cmake -S whisper.cpp -B build -DGGML_VULKAN=ON -DCMAKE_BUILD_TYPE=Release
  cmake --build build --config Release -j"$(nproc)" \
    --target whisper-server whisper-cli parakeet-cli
}

package() {
  install -Dm755 build/bin/whisper-server build/bin/whisper-cli \
    build/bin/parakeet-cli -t "$pkgdir/usr/bin/"
  install -d "$pkgdir/usr/lib/"
  for f in build/bin/libwhisper.so* build/bin/libparakeet.so* \
           build/bin/libggml*.so*; do
    [ -e "$f" ] || continue
    cp -d "$f" "$pkgdir/usr/lib/$(basename "$f")"
  done
  chmod 755 "$pkgdir"/usr/lib/lib*.so*
  install -Dm755 src/squelch src/squelch-ptt src/squelch-parakeet-server \
    src/squelch-tray src/squelch-vram src/squelch-setup-models \
    -t "$pkgdir/usr/bin/"
  install -Dm644 systemd/*.service -t "$pkgdir/usr/lib/systemd/user/"
  install -Dm644 desktop/squelch.desktop -t \
    "$pkgdir/usr/share/applications/"
  install -Dm644 LICENSE -t "$pkgdir/usr/share/licenses/$pkgname/"
  install -Dm644 README.md kanata/snippet.kbd -t \
    "$pkgdir/usr/share/doc/$pkgname/"
}
