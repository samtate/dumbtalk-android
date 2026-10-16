#!/usr/bin/env bash
# Downloads the TDLib build pinned in tdlib.properties (published by the
# TDLib workflow) into svc-telegram/tdlib. Needs the gh CLI, logged in.
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
gh release download "tdlib-$TDLIB_VERSION" --pattern "tdlib-$TDLIB_VERSION.zip" --dir "$tmp"
rm -rf "$target"
mkdir -p "$target"
python3 -m zipfile -e "$tmp/tdlib-$TDLIB_VERSION.zip" "$tmp"
cp -r "$tmp/tdlib/java" "$tmp/tdlib/libs" "$target/"
echo "$TDLIB_VERSION" > "$target/.version"
echo "TDLib $TDLIB_VERSION ready in $target"
