#!/usr/bin/env bash
# Generate Android launcher icons from one square source PNG, using macOS `sips`
# so there is no Python/Pillow dependency.
#
#   ./tools/make_icon.sh art/signalscope.png
#
# The source is a complete icon design with its own rounded-square frame, so the
# adaptive foreground insets it to the 66% safe zone over the app's dark surface;
# without that the launcher's own mask would clip the artwork's corners.
set -euo pipefail
SRC="${1:-art/signalscope.png}"

# Always generate from the ORIGINAL source, never from art/signalscope-512.png or
# -1024.png. Those are upscales kept for store and marketing use; downscaling from
# an interpolation is no sharper than downscaling from the original, and slightly
# softer. Nothing here upscales: the largest asset produced is a 285px inner
# artwork, below the 329px source.
RES="app/src/main/res"
BG="0E1116"   # matches the mockup's phone surface

legacy=(mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192)
adaptive=(mdpi:108:71 hdpi:162:107 xhdpi:216:143 xxhdpi:324:214 xxxhdpi:432:285)

for e in "${legacy[@]}"; do
  d="${e%%:*}"; px="${e##*:}"
  mkdir -p "$RES/mipmap-$d"
  sips -z "$px" "$px" "$SRC" --out "$RES/mipmap-$d/ic_launcher.png" >/dev/null
  cp "$RES/mipmap-$d/ic_launcher.png" "$RES/mipmap-$d/ic_launcher_round.png"
done

for e in "${adaptive[@]}"; do
  d="$(echo "$e" | cut -d: -f1)"; canvas="$(echo "$e" | cut -d: -f2)"; inner="$(echo "$e" | cut -d: -f3)"
  mkdir -p "$RES/mipmap-$d"
  sips -z "$inner" "$inner" "$SRC" --out "/tmp/_fg_$d.png" >/dev/null
  sips -p "$canvas" "$canvas" --padColor "$BG" "/tmp/_fg_$d.png" \
       --out "$RES/mipmap-$d/ic_launcher_foreground.png" >/dev/null
  rm -f "/tmp/_fg_$d.png"
done

mkdir -p "$RES/values" "$RES/mipmap-anydpi-v26"
cat > "$RES/values/ic_launcher_background.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#$BG</color>
</resources>
XML

for n in ic_launcher ic_launcher_round; do
cat > "$RES/mipmap-anydpi-v26/$n.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
XML
done

echo "icons written under $RES"
