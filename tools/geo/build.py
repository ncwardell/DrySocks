#!/usr/bin/env python3
"""Builds the offline geolocation data the globe view uses.

Everything is looked up on the phone: sending each address you connect to to an online
geolocation service would leak exactly what DrySocks exists to hide.

Inputs (downloaded by fetch.sh):
  dbip-country-lite.csv.gz   DB-IP IP to Country Lite, CC BY 4.0, https://db-ip.com
  ne_50m_admin_0_countries.geojson (country centres; 1:110m drops Singapore, Hong Kong…)
  ne_110m_land.geojson (outlines; coarse is plenty on a phone)   Natural Earth, public domain

Outputs, in app/src/main/assets/geo/:
  countries.txt   one line per country index: CC<TAB>lon<TAB>lat<TAB>name
  ipv4.bin        big-endian records (start u32, country u8), sorted by start
  ipv6.bin        big-endian records (start's top 64 bits u64, country u8), sorted
  land.bin        polylines: u16 count, then count × (lon f32, lat f32), repeated
"""
import csv, gzip, ipaddress, json, struct, sys, os

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)

# Country centres from Natural Earth's label points, which sit inside the country (a plain
# centroid of, say, the US or France lands somewhere unhelpful).
ne = json.load(open(os.path.join(src, "ne_50m_admin_0_countries.geojson")))
centres = {}
for f in ne["features"]:
    p = f["properties"]
    cc = p.get("ISO_A2_EH") if p.get("ISO_A2") in (None, "-99") else p.get("ISO_A2")
    if cc and cc != "-99":
        centres[cc] = (p["LABEL_X"], p["LABEL_Y"], p.get("NAME") or cc)

# Places Natural Earth folds into another country (France's overseas regions) or codes
# differently (Taiwan), placed by hand at their main island or capital.
EXTRA = {
    "TW": (121.0, 23.7, "Taiwan"), "RE": (55.5, -21.1, "Réunion"), "GI": (-5.35, 36.14, "Gibraltar"),
    "YT": (45.15, -12.8, "Mayotte"), "MQ": (-61.0, 14.64, "Martinique"), "GP": (-61.55, 16.25, "Guadeloupe"),
    "GF": (-53.0, 4.0, "French Guiana"), "TK": (-171.8, -9.2, "Tokelau"), "BV": (3.4, -54.4, "Bouvet Island"),
    "BQ": (-68.3, 12.2, "Caribbean Netherlands"), "UM": (-162.0, 5.9, "US Outlying Islands"),
    "SJ": (16.0, 78.0, "Svalbard"), "CC": (96.87, -12.16, "Cocos Islands"), "CX": (105.7, -10.45, "Christmas Island"),
    "EU": (10.0, 50.0, "Europe"), "AP": (105.0, 35.0, "Asia-Pacific"),
}
for cc, v in EXTRA.items():
    centres.setdefault(cc, v)

codes, index = [], {}
def idx(cc):
    if cc not in index:
        index[cc] = len(codes)
        codes.append(cc)
    return index[cc]
idx("ZZ")  # unknown / reserved

v4, v6 = [], []
with gzip.open(os.path.join(src, "dbip-country-lite.csv.gz"), "rt") as fh:
    for start, _end, cc in csv.reader(fh):
        a = ipaddress.ip_address(start)
        i = idx(cc)
        if a.version == 4:
            if not v4 or v4[-1][1] != i:  # adjacent ranges of one country merge into one
                v4.append((int(a), i))
        else:
            top = int(a) >> 64
            if not v6 or v6[-1][1] != i:
                if v6 and v6[-1][0] == top:
                    v6[-1] = (top, i)  # finer than /64: keep the later one
                else:
                    v6.append((top, i))

with open(os.path.join(out, "ipv4.bin"), "wb") as fh:
    for s, i in v4:
        fh.write(struct.pack(">IB", s, i))
with open(os.path.join(out, "ipv6.bin"), "wb") as fh:
    for s, i in v6:
        fh.write(struct.pack(">QB", s, i))
with open(os.path.join(out, "countries.txt"), "w") as fh:
    for cc in codes:
        lon, lat, name = centres.get(cc, (0.0, 0.0, cc))
        fh.write(f"{cc}\t{lon:.3f}\t{lat:.3f}\t{name}\n")

# Land outlines as polylines, rounded to 0.1°, which is finer than a phone can show.
land = json.load(open(os.path.join(src, "ne_110m_land.geojson")))
lines = []
for f in land["features"]:
    g = f["geometry"]
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    for poly in polys:
        for ring in poly:
            lines.append([(round(x, 1), round(y, 1)) for x, y in ring])
with open(os.path.join(out, "land.bin"), "wb") as fh:
    for ring in lines:
        fh.write(struct.pack(">H", len(ring)))
        for x, y in ring:
            fh.write(struct.pack(">ff", x, y))

missing = [c for c in codes if c not in centres and c != "ZZ"]  # plotted at 0,0 if any remain
print(f"{len(v4)} v4 ranges, {len(v6)} v6 ranges, {len(codes)} countries "
      f"({len(missing)} without a Natural Earth centre: {' '.join(missing[:12])}…), {len(lines)} land rings")
