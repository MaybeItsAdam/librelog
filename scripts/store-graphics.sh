#!/usr/bin/env bash
# Renders the store graphics into fastlane's layout:
#   design/store/feature-graphic.svg -> Play featureGraphic.png (1024 x 500)
#   design/icon/play-store-512.png   -> Play icon.png
# Needs rsvg-convert (brew install librsvg). The fonts come from apple/Fonts.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/fastlane/metadata/android/en-GB/images"
mkdir -p "$out"
conf=$(mktemp -d)
trap 'rm -rf "$conf"' EXIT
system_conf=$(ls /opt/homebrew/etc/fonts/fonts.conf /usr/local/etc/fonts/fonts.conf /etc/fonts/fonts.conf 2>/dev/null | head -1 || true)
cat > "$conf/fonts.conf" <<XML
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <include ignore_missing="yes">$system_conf</include>
  <dir>$root/apple/Fonts</dir>
  <cachedir>$conf/cache</cachedir>
</fontconfig>
XML
FONTCONFIG_FILE="$conf/fonts.conf" rsvg-convert -w 1024 -h 500 "$root/design/store/feature-graphic.svg" -o "$out/featureGraphic.png"
cp "$root/design/icon/play-store-512.png" "$out/icon.png"
echo "wrote $out/featureGraphic.png and $out/icon.png"
