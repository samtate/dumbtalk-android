#!/usr/bin/env bash
# Keep signing secrets outside the checkout; never enable shell tracing here.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DUMBTALK_VERSION:?Set DUMBTALK_VERSION, for example 0.1.0-alpha.1}"
[[ "$DUMBTALK_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]] || { echo 'Invalid version' >&2; exit 1; }
signing_env="${DUMBTALK_SIGNING_ENV:-${XDG_CONFIG_HOME:-$HOME/.config}/dumbtalk/release.env}"
if [[ -f "$signing_env" ]]; then
    set -a
    source "$signing_env"
    set +a
fi
: "${DUMBTALK_KEYSTORE:?Configure the local release keystore}"
: "${DUMBTALK_KEYSTORE_PASSWORD:?Configure the keystore password}"
: "${DUMBTALK_KEY_ALIAS:?Configure the release alias}"
: "${DUMBTALK_KEY_PASSWORD:?Configure the key password}"
[[ -f "$DUMBTALK_KEYSTORE" ]] || { echo 'Release keystore not found' >&2; exit 1; }
export DUMBTALK_KEYSTORE DUMBTALK_KEYSTORE_PASSWORD DUMBTALK_KEY_ALIAS DUMBTALK_KEY_PASSWORD
export DUMBTALK_VERSION
export DUMBTALK_BUILD_NUMBER="${DUMBTALK_BUILD_NUMBER:-$(date +%s)}"
[[ "$DUMBTALK_BUILD_NUMBER" =~ ^[0-9]+$ ]] && (( DUMBTALK_BUILD_NUMBER > 0 && DUMBTALK_BUILD_NUMBER <= 2100000000 )) || { echo 'Invalid Android version code' >&2; exit 1; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
apksigner="$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name apksigner -type f | sort -V | tail -1)"
[[ -x "$apksigner" ]] || { echo 'Android SDK apksigner not found' >&2; exit 1; }
./gradlew :app:clean :core:testDebugUnitTest :app:testDebugUnitTest \
    :app:assembleDebug :app:assembleDemo :app:assembleRelease \
    --max-workers=3 -Dorg.gradle.jvmargs=-Xmx2500m --console=plain
python3 scripts/check-apk-credentials.py \
    app/build/outputs/apk/debug/*.apk app/build/outputs/apk/demo/*.apk app/build/outputs/apk/release/*.apk
"$apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
output=app/build/outputs/local-release
mkdir -p "$output"
filename="dumbtalk-$DUMBTALK_VERSION-$DUMBTALK_BUILD_NUMBER.apk"
cp app/build/outputs/apk/release/app-release.apk "$output/$filename"
(cd "$output" && sha256sum "$filename" > "$filename.sha256")
echo "Verified local release: $output/$filename"
