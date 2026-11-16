#!/usr/bin/env python3
"""Arma el índice de la red de TransMilenio/SITP a partir del GTFS oficial.

Lo corre GitHub Actions cada día y lo publica como red.json.gz en la versión "red" del repo.
El servidor lo carga una vez y con él responde paraderos, rutas, horarios y búsquedas sin Croma.

Uso: python3 scripts/red.py salida.json.gz [GTFS.zip]
"""
import csv, gzip, io, json, math, statistics, sys, urllib.request, zipfile
from collections import defaultdict

BUCKET = "https://storage.googleapis.com/storage/v1/b/gtfs-estaticos/o?fields=items(name,updated,size)&maxResults=1000"
SISTEMA = {"1": "troncal", "2": "alimentador", "6": "dual", "3": "zonal_urbano", "4": "zonal_complementario", "5": "zonal_especial", "7": "transmicable"}


def gtfs_reciente() -> zipfile.ZipFile:
    """El archivo diario a veces sale sin los viajes entre semana: se usa el más nuevo que esté completo."""
    items = json.load(urllib.request.urlopen(BUCKET, timeout=60))["items"]
    items = sorted((i for i in items if i["name"].endswith(".zip")), key=lambda i: i["updated"], reverse=True)
    for i in items[:10]:
        z = zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(f"https://storage.googleapis.com/gtfs-estaticos/{i['name']}", timeout=600).read()))
        servicios = {r["service_id"] for r in leer(z, "trips.txt")}
        print(i["name"], sorted(servicios), file=sys.stderr)
        if {"1", "2", "4"} <= servicios:
            z.nombre = i["name"]
            return z
    raise SystemExit("Ningún GTFS reciente trae servicio entre semana, sábado y domingo")


def leer(z, nombre):
    with z.open(nombre) as f:
        yield from csv.DictReader(io.TextIOWrapper(f, "utf-8-sig"))


def seg(h: str) -> int:
    a, b, c = h.split(":")
    return int(a) * 3600 + int(b) * 60 + int(c)


def rdp(p, eps):
    if len(p) < 3:
        return p
    (x1, y1), (x2, y2) = p[0], p[-1]
    dx, dy = x2 - x1, y2 - y1
    n = math.hypot(dx, dy) or 1e-12
    i, dm = 0, 0.0
    for k in range(1, len(p) - 1):
        d = abs(dy * p[k][0] - dx * p[k][1] + x2 * y1 - y2 * x1) / n
        if d > dm:
            i, dm = k, d
    if dm > eps:
        return rdp(p[: i + 1], eps)[:-1] + rdp(p[i:], eps)
    return [p[0], p[-1]]


def polilinea(puntos):
    """Codificación de Google (precisión 5), lat/lon."""
    sal, plat, plon = [], 0, 0
    for lat, lon in puntos:
        for v, prev in ((round(lat * 1e5), plat), (round(lon * 1e5), plon)):
            d = v - prev
            d = ~(d << 1) if d < 0 else d << 1
            while d >= 0x20:
                sal.append(chr((0x20 | (d & 0x1F)) + 63)); d >>= 5
            sal.append(chr(d + 63))
        plat, plon = round(lat * 1e5), round(lon * 1e5)
    return "".join(sal)


def main():
    salida = sys.argv[1]
    z = zipfile.ZipFile(sys.argv[2]) if len(sys.argv) > 2 else gtfs_reciente()
    agencia_ruta, rutas = {}, []
    for r in leer(z, "routes.txt"):
        agencia_ruta[r["route_id"]] = len(rutas)
        rutas.append([r["route_id"], r["route_short_name"].strip(), r["route_long_name"].strip(), SISTEMA.get(r["agency_id"], "other"), r.get("route_color") or ""])

    paradas, idx = [], {}
    padre = {}
    for s in leer(z, "stops.txt"):
        idx[s["stop_id"]] = len(paradas)
        paradas.append([s["stop_id"], " ".join(s["stop_name"].split()), round(float(s["stop_lat"]), 6), round(float(s["stop_lon"]), 6),
                        1 if s["location_type"] == "1" else 0, 1 if s.get("wheelchair_boarding") == "1" else 0, s.get("parent_station") or ""])
        if s.get("parent_station"):
            padre[s["stop_id"]] = s["parent_station"]

    viajes = {t["trip_id"]: t for t in leer(z, "trips.txt")}
    frec = defaultdict(list)
    for f in leer(z, "frequencies.txt"):
        frec[f["trip_id"]].append((seg(f["start_time"]), seg(f["end_time"]), int(f["headway_secs"])))

    secuencias = defaultdict(list)
    for st in leer(z, "stop_times.txt"):
        if st["trip_id"] in viajes:
            secuencias[st["trip_id"]].append((int(st["stop_sequence"]), st["stop_id"], seg(st["departure_time"] or st["arrival_time"])))

    patrones, clave_patron = [], {}
    tiempos = defaultdict(list)
    for tid, seq in secuencias.items():
        seq.sort()
        t = viajes[tid]
        clave = (t["route_id"], tuple(s for _, s, _ in seq))
        if clave not in clave_patron:
            clave_patron[clave] = len(patrones)
            patrones.append({"r": agencia_ruta[t["route_id"]], "s": [idx[s] for _, s, _ in seq], "shape": t["shape_id"], "h": t.get("trip_headsign") or "", "d": defaultdict(list)})
        p = clave_patron[clave]
        inicio = seq[0][2]
        if len(tiempos[p]) < 25:
            tiempos[p].append([x - inicio for _, _, x in seq])
        salidas = [inicio] if tid not in frec else [x for a, b, h in frec[tid] for x in range(a, b, max(h, 60))]
        patrones[p]["d"][t["service_id"]].extend(salidas)

    formas = defaultdict(list)
    usadas = {p["shape"] for p in patrones}
    for s in leer(z, "shapes.txt"):
        if s["shape_id"] in usadas:
            formas[s["shape_id"]].append((int(s["shape_pt_sequence"]), float(s["shape_pt_lat"]), float(s["shape_pt_lon"])))

    for i, p in enumerate(patrones):
        p["t"] = [int(statistics.median(c)) for c in zip(*tiempos[i])]
        f = [(la, lo) for _, la, lo in sorted(formas.get(p.pop("shape"), []))]
        if not f:
            f = [(paradas[k][2], paradas[k][3]) for k in p["s"]]
        p["p"] = polilinea(rdp(f, 0.00006))
        p["d"] = {k: sorted(set(v)) for k, v in p["d"].items()}

    servicios = defaultdict(lambda: {"dias": [0] * 7, "mas": [], "menos": []})
    dias = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
    for c in leer(z, "calendar.txt"):
        servicios[c["service_id"]]["dias"] = [int(c[d]) for d in dias]
    for c in leer(z, "calendar_dates.txt"):
        servicios[c["service_id"]]["mas" if c["exception_type"] == "1" else "menos"].append(c["date"])

    red = {"version": getattr(z, "nombre", sys.argv[-1]), "rutas": rutas, "paradas": paradas, "patrones": patrones, "servicios": servicios}
    with gzip.open(salida, "wt", encoding="utf-8") as f:
        json.dump(red, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(rutas)} rutas, {len(paradas)} paradas, {len(patrones)} patrones", file=sys.stderr)


if __name__ == "__main__":
    main()
