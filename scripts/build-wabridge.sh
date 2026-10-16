#!/usr/bin/env bash
# Builds the WhatsApp Go bridge (bridge/whatsapp) into
# svc-whatsapp/libs/wabridge.aar with gomobile. Needs Go 1.26+, ANDROID_HOME
# and ANDROID_NDK_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p svc-whatsapp/libs
cd bridge/whatsapp
# Use the gomobile/gobind matching the x/mobile version pinned in go.mod.
mobile=$(go list -m -f '{{.Version}}' golang.org/x/mobile)
go install "golang.org/x/mobile/cmd/gomobile@$mobile" "golang.org/x/mobile/cmd/gobind@$mobile"
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile bind -target android/arm64 -androidapi 29 -javapkg dev.dumbtalk.whatsapp.go \
  -trimpath -ldflags="-s -w" -o ../../svc-whatsapp/libs/wabridge.aar .
rm -f ../../svc-whatsapp/libs/wabridge-sources.jar
echo "Built svc-whatsapp/libs/wabridge.aar"
