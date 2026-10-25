#!/usr/bin/env bash
# Downloads the TDLib build pinned in tdlib.properties (published by the
# TDLib workflow as a release of this repo) into svc-telegram/tdlib. Uses the
# gh CLI when it's logged in, otherwise a plain download (public repo only).
set -euo pipefail
cd "$(dirname "$0")/.."
source <(sed 's/ *= */=/' tdlib.properties)
target=svc-telegram/tdlib
if [ -f "$target/.version" ] && [ "$(cat "$target/.version")" = "$TDLIB_VERSION" ]; then
  echo "TDLib $TDLIB_VERSION already present"
  exit 0
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
repo=${TDLIB_REPO:-samtate/dumbtalk-android}
if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  gh release download "tdlib-$TDLIB_VERSION" --repo "$repo" --pattern "tdlib-$TDLIB_VERSION.zip" --dir "$tmp"
else
  curl -fL --retry 3 -o "$tmp/tdlib-$TDLIB_VERSION.zip" \
    "https://github.com/$repo/releases/download/tdlib-$TDLIB_VERSION/tdlib-$TDLIB_VERSION.zip"
fi
rm -rf "$target"
mkdir -p "$target"
python3 -m zipfile -e "$tmp/tdlib-$TDLIB_VERSION.zip" "$tmp"
mkdir -p "$target/libs"
cp -r "$tmp/tdlib/java" "$target/"
# The app ships arm64 only.
cp -r "$tmp/tdlib/libs/arm64-v8a" "$target/libs/"
echo "$TDLIB_VERSION" > "$target/.version"
echo "TDLib $TDLIB_VERSION ready in $target"
