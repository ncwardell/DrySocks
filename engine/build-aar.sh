#!/usr/bin/env bash
# Builds app/libs/engine.aar from this module. Run inside `nix develop .#android`.
#
# arm64 is every phone this app targets; x86_64 is the emulator. 32-bit ARM is
# left out: no device that runs Android 8+ well needs it, and it doubles the size.
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_HOME:?run inside nix develop .#android}"
: "${ANDROID_NDK_HOME:?run inside nix develop .#android}"

# gomobile and gobind are pinned as tools in go.mod; install those exact
# versions somewhere private rather than trusting whatever is on PATH.
bin="$PWD/.bin"
GOBIN="$bin" go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$bin:$PATH"

mkdir -p ../app/libs
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi 26 \
  -javapkg com.sockschain.engine \
  -trimpath \
  -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' \
  -o ../app/libs/engine.aar \
  ./mobile

echo "wrote app/libs/engine.aar"
