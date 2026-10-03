#!/usr/bin/env bash
# Builds the Rust core for Android and generates its Kotlin bindings:
#
#   android/app/src/main/jniLibs/<abi>/libdailylog_core.so   (not committed)
#   android/app/src/main/java/uniffi/dailylog_core/          (committed)
#
# plus a host build in core/target/<host>/ that the JVM unit tests load through
# JNA. Run after any change to core/.
#
#   scripts/build-android.sh            release build (what the app ships)
#   PROFILE=debug scripts/build-android.sh
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
core="$root/core"
app="$root/android/app/src/main"
profile=${PROFILE:-release}
flag=$([ "$profile" = release ] && echo --release || true)

export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)
  export ANDROID_NDK_HOME
fi

# minSdk 28 matches android/app/build.gradle.kts.
echo "› android (arm64-v8a, armeabi-v7a, x86_64)"
(cd "$core" && cargo ndk -q -P 28 -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o "$app/jniLibs" build $flag --lib)

# The host library: JVM unit tests load it, and bindgen reads its metadata.
echo "› host"
cargo build -q $flag --manifest-path "$core/Cargo.toml" --lib
case "$(uname)" in
  Darwin) host_lib="$core/target/$profile/libdailylog_core.dylib" ;;
  *) host_lib="$core/target/$profile/libdailylog_core.so" ;;
esac

# Kotlin bindings (library mode reads `cargo metadata`, so run inside core/).
gen=$(mktemp -d)
(cd "$core" && cargo run -q --features bindgen --bin uniffi-bindgen -- \
  generate --library "$host_lib" --language kotlin --no-format --out-dir "$gen")
rm -rf "$app/java/uniffi"
mkdir -p "$app/java"
cp -R "$gen/uniffi" "$app/java/"
rm -rf "$gen"

echo "✓ jniLibs, Kotlin bindings, and host library at $host_lib"
