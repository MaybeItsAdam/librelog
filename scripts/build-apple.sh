#!/usr/bin/env bash
# Builds the Rust core for every Apple target and packages it as
# apple/DailylogCore.xcframework, with the UniFFI Swift bindings in
# apple/Sources/DailylogCore. Run after any change to core/.
#
#   scripts/build-apple.sh            release build (what the apps ship)
#   PROFILE=debug scripts/build-apple.sh
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
core="$root/core"
out="$root/apple"
profile=${PROFILE:-release}
flag=$([ "$profile" = release ] && echo --release || true)
lib=libdailylog_core.a

export IPHONEOS_DEPLOYMENT_TARGET=17.0
export MACOSX_DEPLOYMENT_TARGET=14.0

targets=(aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin x86_64-apple-darwin)
for t in "${targets[@]}"; do
  echo "› $t"
  cargo build -q $flag --manifest-path "$core/Cargo.toml" --target "$t" --lib
done

# Swift bindings, generated from the compiled library's metadata.
host_dylib="$core/target/aarch64-apple-darwin/$profile/libdailylog_core.dylib"
gen=$(mktemp -d)
# (library mode reads `cargo metadata`, so it must run inside core/)
(cd "$core" && cargo run -q --features bindgen --bin uniffi-bindgen -- \
  generate --library "$host_dylib" --language swift --out-dir "$gen")

mkdir -p "$out/Sources/DailylogCore"
cp "$gen/dailylog_core.swift" "$out/Sources/DailylogCore/DailylogCore.swift"

# One headers dir per slice, with a module map Xcode can find.
headers="$gen/headers"
mkdir -p "$headers"
cp "$gen/dailylog_coreFFI.h" "$headers/"
cp "$gen/dailylog_coreFFI.modulemap" "$headers/module.modulemap"

# The Mac slice is universal (Apple silicon + Intel).
mac="$gen/macos"
mkdir -p "$mac"
lipo -create \
  "$core/target/aarch64-apple-darwin/$profile/$lib" \
  "$core/target/x86_64-apple-darwin/$profile/$lib" \
  -output "$mac/$lib"

rm -rf "$out/DailylogCore.xcframework"
xcodebuild -create-xcframework \
  -library "$core/target/aarch64-apple-ios/$profile/$lib" -headers "$headers" \
  -library "$core/target/aarch64-apple-ios-sim/$profile/$lib" -headers "$headers" \
  -library "$mac/$lib" -headers "$headers" \
  -output "$out/DailylogCore.xcframework" >/dev/null

rm -rf "$gen"
echo "✓ $out/DailylogCore.xcframework"
