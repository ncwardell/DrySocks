#!/usr/bin/env bash
# Downloads this month's DB-IP country lite and Natural Earth, then rebuilds the assets.
# DB-IP publishes monthly; run this before a release.
set -euo pipefail
cd "$(dirname "$0")"
work=$(mktemp -d)
month=$(date +%Y-%m)
curl -fsSL -o "$work/dbip-country-lite.csv.gz" "https://download.db-ip.com/free/dbip-country-lite-$month.csv.gz"
for f in ne_50m_admin_0_countries ne_110m_land; do
  curl -fsSL -o "$work/$f.geojson" "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/$f.geojson"
done
python3 build.py "$work" ../../app/src/main/assets/geo
echo "$month" > ../../app/src/main/assets/geo/VERSION
rm -rf "$work"
