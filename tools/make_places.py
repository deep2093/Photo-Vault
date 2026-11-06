"""Builds app/src/main/assets/places/cities.tsv from GeoNames (CC BY 4.0) for Places (7B-1).

Inputs (downloaded by CI): cities15000.txt, admin1CodesASCII.txt, countryInfo.txt.
Output, one city per line: name, region, country, latitude, longitude, population.
"""
import sys

cities, admin1, countries, out = sys.argv[1:5]

country = {}
with open(countries, encoding="utf-8") as f:
    for line in f:
        if line.startswith("#"):
            continue
        p = line.rstrip("\n").split("\t")
        if len(p) > 4:
            country[p[0]] = p[4]

region = {}
with open(admin1, encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) > 1:
            region[p[0]] = p[1]

rows = 0
with open(cities, encoding="utf-8") as f, open(out, "w", encoding="utf-8") as o:
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) < 15:
            continue
        # Parts of a city (PPLX) and historical places (PPLH) are left out: places are whole towns and cities.
        if p[7] in ("PPLX", "PPLH", "PPLQ", "PPLW"):
            continue
        name = p[1].replace("\t", " ")
        cc = p[8]
        o.write("\t".join([
            name,
            region.get(cc + "." + p[10], ""),
            country.get(cc, cc),
            "%.4f" % float(p[4]),
            "%.4f" % float(p[5]),
            p[14] or "0",
        ]) + "\n")
        rows += 1
print("cities:", rows)
if rows < 20000:
    sys.exit("too few cities")
