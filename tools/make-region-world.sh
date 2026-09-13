#!/usr/bin/env bash
#
# Regenerate the bundled world basemap: app/src/main/assets/region-world.pmtiles
#
# This is the only archive that ships inside the APK, and it is the reason the map is never blank
# on first run — including a first run in a dead spot, which for this app is not a hypothetical.
# Everything else is acquired at runtime by RegionAcquisition.
#
# Requires the `pmtiles` CLI: https://github.com/protomaps/go-pmtiles/releases
#
# Source: the Source Cooperative mirror of the Protomaps v4 planet, NOT build.protomaps.com.
# Protomaps' own docs say "hotlinking to these downloads are discouraged" and their daily bucket
# keeps only a week of builds. See docs/region-acquisition.md.
#
# Measured sizes, z0-N over the whole world:
#
#   z0-3   2.4 MB   continents and coastline only
#   z0-4   6.0 MB   <- shipped: country shapes, major water, principal roads
#   z0-5  14.9 MB   too much APK for what it adds over the country tier
#
# Zoom is the only knob. Raising it makes the APK bigger; lowering it makes the pre-download map
# coarser. The country tier (z0-9, acquired on arrival) closes the gap either way.

set -euo pipefail

PLANET="${PLANET:-https://data.source.coop/protomaps/openstreetmap/v4.pmtiles}"
MAXZOOM="${MAXZOOM:-4}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/region-world.pmtiles"

command -v pmtiles >/dev/null 2>&1 || { echo "pmtiles CLI not on PATH" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
tmp="$OUT.part"
rm -f "$tmp"

echo "cutting z0-$MAXZOOM of the world from $PLANET"
pmtiles extract "$PLANET" "$tmp" --maxzoom "$MAXZOOM" --overfetch 0.2

# Never ship an archive that has not been read back. MapLibre's PMTiles source aborts the whole
# process on one it cannot parse, so a truncated asset would be a bundled crash.
pmtiles show "$tmp" >/dev/null
mv "$tmp" "$OUT"

pmtiles show "$OUT" | head -8
ls -la "$OUT"
