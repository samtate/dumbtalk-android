#!/usr/bin/env bash
# Builds the Signal bridge (bridge/signal, Rust on presage) for arm64 Android
# into svc-signal: the native library, Android's C++ runtime, the
# rustls-platform-verifier AAR and the UniFFI Kotlin bindings.
# Needs rustup (the toolchain is pinned in bridge/signal), cargo-ndk, cmake,
# ninja, clang/libclang, protoc and ANDROID_NDK_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
module=$root/svc-signal
ndk=${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}
toolchain=$(echo "$ndk"/toolchains/llvm/prebuilt/*)
export CMAKE_GENERATOR=${CMAKE_GENERATOR:-Ninja}
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--sysroot=$toolchain/sysroot -I$(echo "$toolchain"/lib/clang/*/include)"

cd bridge/signal
jni=$module/src/main/jniLibs
rm -rf "$jni" && mkdir -p "$jni"
cargo ndk -t arm64-v8a -P 29 -o "$jni" build --release --lib
# cargo-ndk also copies unrelated dylibs from the target directory.
find "$jni" -name '*.so' ! -name libsigbridge.so -delete
cp "$toolchain/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$jni/arm64-v8a/"

mkdir -p "$module/libs"
verifier=$(cargo metadata --format-version 1 --filter-platform aarch64-linux-android |
  python3 -c 'import json,sys; m=json.load(sys.stdin); print(next(p["manifest_path"] for p in m["packages"] if p["name"]=="rustls-platform-verifier-android"))')
find "$(dirname "$verifier")/maven" -name '*.aar' -exec cp {} "$module/libs/rustls-platform-verifier.aar" \;

bindings=$module/src/main/kotlin-generated
rm -rf "$bindings"
bindgen=${UNIFFI_BINDGEN:-}
if [ -z "$bindgen" ]; then
  cargo build --bin uniffi-bindgen
  bindgen=${CARGO_TARGET_DIR:-target}/debug/uniffi-bindgen
fi
"$bindgen" generate --library "$jni/arm64-v8a/libsigbridge.so" --language kotlin --out-dir "$bindings" --no-format
echo "Built svc-signal native library and bindings"
