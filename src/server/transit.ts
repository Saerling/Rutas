import { humanizeAlert } from "@/transit/alerts";
import { dedupeRoutes, describeRoute } from "@/transit/codes";
import type { Alert, Arrival, Bus, Feed, Health, IncomingBus, LiveBus, Nearby, Traffic, TrafficCell, TrafficLevel, Vehicles, RouteDetail, RouteRef, SearchResult, Stop, StopDetail } from "@/transit/types";

import type { AlertaRt, Vehiculo } from "./gtfsrt";
import { conTomTom } from "./tomtom";
import { alertas, decodificar, enVia, faltan, marchas, metros, tramoEntre, normalizar, pasadasHoy, posiciones, red, relojBogota, rumbo, ubicar, type Patron, type Red, type Ubicado } from "./red";

/**
 * Todo sale de los datos abiertos de TransMilenio: el GTFS (índice "red") y sus feeds de GTFS Realtime.
 * Sin Croma, sin créditos. Las llegadas en vivo se calculan con la posición del bus y los tiempos del horario.
 */

const VIEJO_S = 300;
const iso = (s: number) => new Date(s * 1000).toISOString().replace(/\.\d+Z$/, "Z");

function ref(rd: Red, i: number): RouteRef {
  const r = rd.rutas[i];
  return describeRoute({ id: r[0], shortName: r[1], longName: r[2], system: r[3] });
}

function rutasDe(rd: Red, parada: number): RouteRef[] {
  const pats = rd.porParada.get(parada) ?? [];
  return dedupeRoutes([...new Set(pats.map((p) => p.r))].map((i) => ref(rd, i))).sort((a, b) => a.code.localeCompare(b.code, "es", { numeric: true }));
}

function parada(rd: Red, i: number, desde?: { lat: number; lon: number }): Stop {
  const p = rd.paradas[i];
  return {
    id: p[0], name: p[1], kind: p[4] ? "station" : "stop", lat: p[2], lon: p[3], accessible: !!p[5],
    distanceM: desde ? Math.round(metros(desde.lat, desde.lon, p[2], p[3])) : null,
    routes: rutasDe(rd, i),
  };
}

/** Paraderos que un pasajero reconoce: estaciones y paradas sueltas, no los vagones de cada estación. */
function visible(rd: Red, i: number): boolean {
  const p = rd.paradas[i];
  return !p[6] && (rd.porParada.get(i)?.length ?? 0) > 0;
}

export async function nearby(lat: number, lon: number): Promise<Nearby> {
  const rd = await red();
  const cerca: [number, number][] = [];
  rd.paradas.forEach((p, i) => {
    const d = metros(lat, lon, p[2], p[3]);
    if (d <= 700 && visible(rd, i)) cerca.push([d, i]);
  });
  cerca.sort((a, b) => a[0] - b[0]);
  return { stops: cerca.slice(0, 25).map(([, i]) => parada(rd, i, { lat, lon })) };
}

export async function search(query: string): Promise<SearchResult> {
  const rd = await red();
  const q = normalizar(query);
  if (!q) return { stops: [], routes: [] };
  const palabras = q.split(" ");
  const stops: [number, number][] = [];
  rd.nombresNorm.forEach((n, i) => {
    if (!visible(rd, i) || !palabras.every((w) => n.includes(w))) return;
    stops.push([n.startsWith(q) ? 0 : n.includes(q) ? 1 : 2, i]);
  });
  stops.sort((a, b) => a[0] - b[0] || rd.paradas[a[1]][1].length - rd.paradas[b[1]][1].length);
  const qc = q.replace(/ /g, "");
  const routes = dedupeRoutes(
    rd.rutas.map((_, i) => ref(rd, i)).filter((r) => r.aliases.some((a) => normalizar(a).replace(/ /g, "").startsWith(qc)) || normalizar(r.name).includes(q)),
  )
    .sort((a, b) => Number(!a.aliases.some((x) => normalizar(x) === q)) - Number(!b.aliases.some((x) => normalizar(x) === q)) || a.code.length - b.code.length)
    .slice(0, 15);
  return { stops: stops.slice(0, 15).map(([, i]) => parada(rd, i)), routes };
}

function aAlerta(rd: Red, a: AlertaRt): Alert {
  const routes = a.rutas.flatMap((id) => {
    const i = rd.rutaIdx.get(id);
    return i === undefined ? [] : [rd.rutas[i][1]];
  });
  const h = humanizeAlert({ effect: a.efecto, description: a.detalle || a.titulo, routes });
  return { id: a.id, effect: a.efecto, title: h.title, detail: h.detail, places: h.places, note: h.note, routes, until: a.hasta ? iso(a.hasta) : null };
}

async function alertasDe(rd: Red, filtro: { paradas?: Set<string>; ruta?: string }): Promise<Alert[]> {
  const todas = await alertas().catch(() => [] as AlertaRt[]);
  return todas
    .filter((a) => (filtro.ruta ? a.rutas.includes(filtro.ruta) : a.paradas.some((p) => filtro.paradas?.has(p))))
    .slice(0, 6)
    .map((a) => aAlerta(rd, a));
}

function feedDe(ts: number): Feed {
  return { asOf: iso(ts), live: Date.now() / 1000 - ts <= VIEJO_S };
}

/** Un vagón se muestra como su estación: el pasajero busca "Héroes", no "Héroes A - 4 ó 6". */
function publica(rd: Red, k: number): number {
  const padre = rd.paradas[k][6];
  return padre ? (rd.paradaIdx.get(padre) ?? k) : k;
}

function paradaCorta(rd: Red, k: number) {
  const p = rd.paradas[publica(rd, k)];
  return { id: p[0], name: p[1], lat: p[2], lon: p[3] };
}

function nombreParada(rd: Red, id: string): string | null {
  const i = rd.paradaIdx.get(id);
  return i === undefined ? null : rd.paradas[publica(rd, i)][1];
}

/** [u]: el recorrido ya calculado (ubicar() actualiza el rastro del bus, así que no se llama dos veces). */
function aBus(rd: Red, v: Vehiculo, u: Ubicado | null = ubicar(rd, v)): Bus {
  let bearing: number | null = null;
  let nextStop = nombreParada(rd, v.paradaId);
  let lat = v.lat, lon = v.lon;
  if (u) {
    // Sobre la vía: posición pegada a la calle y rumbo de la calle (no la recta entre paraderos).
    const via = enVia(u.patron, v.lat, v.lon);
    if (via) { lat = via.lat; lon = via.lon; bearing = via.rumbo; }
    if (u.k + 1 < u.patron.s.length) {
      nextStop = rd.paradas[publica(rd, u.patron.s[u.k + 1])][1];
      if (bearing === null) {
        const a = rd.paradas[u.patron.s[u.k]], b = rd.paradas[u.patron.s[u.k + 1]];
        bearing = Math.round(rumbo(a[2], a[3], b[2], b[3]));
      }
    }
  } else {
    // Sin recorrido seguro: el rumbo de lo que acaba de moverse, si se movió.
    const m = marchas.get(v.id);
    if (m && m.ts === v.ts && metros(m.desde[1], m.desde[0], v.lat, v.lon) > 30) bearing = Math.round(rumbo(m.desde[1], m.desde[0], v.lat, v.lon));
  }
  const toward = u ? rd.paradas[publica(rd, u.patron.s[u.patron.s.length - 1])][1] : null;
  return { id: v.id, lat: round5(lat), lon: round5(lon), nextStop, plate: v.placa || null, reportedAt: iso(v.ts), bearing, label: v.etiqueta || null, toward };
}

const round5 = (x: number) => Math.round(x * 1e5) / 1e5;

export async function stopDetail(stopId: string): Promise<StopDetail | null> {
  const rd = await red();
  const i = rd.paradaIdx.get(stopId);
  if (i === undefined) return null;
  const objetivo = new Set([i, ...(rd.hijos.get(i) ?? [])]);
  const ids = new Set([...objetivo].map((k) => rd.paradas[k][0]));
  const [pos, avisos] = await Promise.all([posiciones().catch(() => null), alertasDe(rd, { paradas: ids })]);
  const ahora = Date.now() / 1000;
  const feed = pos ? feedDe(pos.ts) : { asOf: iso(ahora), live: false };
  const patrones = rd.porParada.get(i) ?? [];
  const llegadas: (Arrival & { _s: number })[] = [];
  const conVivo = new Set<number>();

  // En vivo: buses de las rutas que paran aquí, ubicados en su recorrido antes de esta parada.
  if (pos && feed.live) {
    for (const r of new Set(patrones.map((p) => p.r))) {
      for (const v of pos.porRuta.get(rd.rutas[r][0]) ?? []) {
        if (ahora - v.ts > VIEJO_S) continue;
        const u = ubicar(rd, v);
        if (!u) continue;
        const j = u.patron.s.findIndex((s, x) => x > u.k && objetivo.has(s));
        if (j < 0) continue;
        const en = v.ts + faltan(u, j) - ahora;
        if (en < -90 || en > 90 * 60) continue;
        const rr = ref(rd, r);
        conVivo.add(r);
        llegadas.push({
          routeId: rr.id, code: rr.code, aliases: rr.aliases, name: rr.name, system: rr.system,
          minutes: Math.max(0, Math.round(en / 60)), at: iso(ahora + Math.max(0, en)), basis: "estimated", delaySeconds: null,
          vehicle: v.etiqueta || null, plate: v.placa || null, _s: en,
        });
      }
    }
  }

  // Del horario: rutas sin buses ubicados (o todas, si el feed está caído), próximas 90 minutos.
  const { seg } = relojBogota();
  for (const p of patrones) {
    if (conVivo.has(p.r)) continue;
    const j = p.s.findIndex((s, x) => x < p.s.length - 1 && objetivo.has(s));
    if (j < 0) continue;
    const rr = ref(rd, p.r);
    for (const h of pasadasHoy(rd, p, j).filter((h) => h >= seg - 60 && h <= seg + 90 * 60).slice(0, 4)) {
      const en = h - seg;
      llegadas.push({
        routeId: rr.id, code: rr.code, aliases: rr.aliases, name: rr.name, system: rr.system,
        minutes: Math.max(0, Math.round(en / 60)), at: iso(ahora + en), basis: "scheduled", delaySeconds: null, vehicle: null, plate: null, _s: en,
      });
    }
  }
  llegadas.sort((a, b) => a._s - b._s);
  const vistos = new Set<string>();
  const arrivals = llegadas
    .filter((a) => { const k = `${a.code}|${a.vehicle ?? a.at}`; if (vistos.has(k)) return false; vistos.add(k); return true; })
    .slice(0, 30)
    .map(({ _s, ...a }) => a);

  const p = rd.paradas[i];
  const sirven = new Set(patrones.map((x) => rd.rutas[x.r][0]));
  const incoming: IncomingBus[] = pos && feed.live
    ? pos.vehiculos
        .filter((v) => sirven.has(v.rutaId) && ahora - v.ts <= VIEJO_S && metros(p[2], p[3], v.lat, v.lon) <= 3000)
        .slice(0, 40)
        .map((v) => { const rr = ref(rd, rd.rutaIdx.get(v.rutaId)!); return { ...aBus(rd, v), code: rr.code, system: rr.system }; })
    : [];

  return { feed, stop: parada(rd, i), arrivals, incoming, alerts: avisos };
}

function horaTexto(s: number): string {
  const h = Math.floor(s / 3600) % 24, m = Math.floor((s % 3600) / 60);
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

export async function routeDetail(routeId: string): Promise<RouteDetail | null> {
  const rd = await red();
  const r = rd.rutaIdx.get(routeId);
  if (r === undefined) return null;
  const pats = [...(rd.porRuta.get(r) ?? [])].sort((a, b) => cuenta(b) - cuenta(a));
  const main = pats[0];
  if (!main) return null;
  const hoy = pasadasHoy(rd, main, 0);
  const semana = pats.reduce((n, p) => n + Object.entries(p.d).reduce((m, [svc, v]) => m + v.length * (rd.servicios[svc]?.dias.reduce((a, b) => a + b, 0) ?? 0), 0), 0);
  const [pos, avisos] = await Promise.all([posiciones().catch(() => null), alertasDe(rd, { ruta: routeId })]);
  const ahora = Date.now() / 1000;
  return {
    feed: pos ? feedDe(pos.ts) : { asOf: iso(ahora), live: false },
    route: { ...ref(rd, r), firstDeparture: hoy.length ? horaTexto(hoy[0]) : null, lastDeparture: hoy.length ? horaTexto(hoy[hoy.length - 1]) : null, tripsPerWeek: semana || null },
    path: decodificar(main.p),
    patterns: pats.slice(0, 4).map((p) => ({ path: decodificar(p.p), stops: p.s.map((k) => rd.paradas[k][0]) })),
    variants: sentidos(rd, pats),
    stops: main.s.map((k) => paradaCorta(rd, k)),
    buses: (pos?.porRuta.get(routeId) ?? []).filter((v) => ahora - v.ts <= VIEJO_S).map((v) => aBus(rd, v)),
    alerts: avisos,
  };
}

/** Un recorrido por destino (el que más sale hoy), con sus salidas de hoy desde la primera parada. */
function sentidos(rd: Red, pats: Patron[]) {
  const conHoy = pats.map((p) => ({ p, hoy: pasadasHoy(rd, p, 0) })).sort((a, b) => b.hoy.length - a.hoy.length || cuenta(b.p) - cuenta(a.p));
  const vistos = new Set<string>();
  const out = [];
  for (const { p, hoy } of conHoy) {
    const toward = rd.paradas[publica(rd, p.s[p.s.length - 1])][1];
    if (vistos.has(toward) || (!hoy.length && out.length)) continue;
    vistos.add(toward);
    out.push({ toward, stops: p.s.map((k) => paradaCorta(rd, k)), path: decodificar(p.p), departures: [...new Set(hoy.map(horaTexto))] });
    if (out.length === 4) break;
  }
  return out;
}

function cuenta(p: Patron): number {
  return Object.values(p.d).reduce((n, v) => n + v.length, 0);
}

/** Novedades activas de toda la red, sin repetir las que dicen lo mismo. */
export async function allAlerts(): Promise<{ alerts: Alert[] }> {
  const rd = await red();
  const vistas = new Map<string, Alert>();
  for (const a of await alertas()) {
    const x = aAlerta(rd, a);
    const k = `${x.title}|${x.detail}`;
    const prev = vistas.get(k);
    if (prev) prev.routes = [...new Set([...prev.routes, ...x.routes])];
    else vistas.set(k, x);
  }
  return { alerts: [...vistas.values()].slice(0, 80) };
}

export async function health(): Promise<Health> {
  const pos = await posiciones();
  return { feed: feedDe(pos.ts) };
}

// ───────────────────────── Buses en vivo y tráfico ─────────────────────────

const MIXTO = new Set(["zonal_urbano", "zonal_complementario", "zonal_especial", "alimentador", "other"]);
/** Un bus quieto entre dos reportes casi siempre está parqueado (patio, terminal o parada larga), no en un trancón. */
const enMarcha = (b: LiveBus) => b.kmh != null && b.kmh >= 1.5 && MIXTO.has(b.system);
const nivel = (kmh: number): TrafficLevel => (kmh >= 15 ? "fluido" : kmh >= 8 ? "moderado" : "lento");
const mediana = (v: number[]) => { const s = [...v].sort((a, b) => a - b); return s.length ? s[s.length >> 1] : null; };

function vivoDe(rd: Red, v: Vehiculo): LiveBus | null {
  const ri = rd.rutaIdx.get(v.rutaId);
  if (ri === undefined) return null;
  const r = ref(rd, ri);
  const m = marchas.get(v.id);
  // En las primeras paradas el bus suele estar esperando turno en la terminal: su velocidad no dice nada del tráfico.
  // Y si no está sobre su recorrido, está en el patio.
  const u = ubicar(rd, v);
  const fresca = m && m.ts === v.ts && v.secuencia > 2 && u ? m : null;
  // El tramo que acaba de recorrer, siguiendo la calle (sirve para pintar el tráfico y animar el bus por la vía).
  let trail: [number, number][] | null = null;
  if (u && m && m.ts === v.ts) {
    const a = enVia(u.patron, m.desde[1], m.desde[0]), b = enVia(u.patron, v.lat, v.lon);
    if (a && b) trail = tramoEntre(u.patron, a, b)?.map(([x, y]) => [round5(x), round5(y)] as [number, number]) ?? null;
  }
  return { ...aBus(rd, v, u), routeId: v.rutaId, code: r.code, system: r.system, kmh: fresca?.kmh ?? null, from: fresca?.desde ?? null, trail };
}

/** El paradero visible más cercano a un punto, para nombrar una zona. */
function cercano(rd: Red, lat: number, lon: number): string {
  let mejor = "", d = Infinity;
  rd.paradas.forEach((p, i) => {
    if (Math.abs(p[2] - lat) > 0.006 || Math.abs(p[3] - lon) > 0.006 || p[6]) return;
    const x = metros(lat, lon, p[2], p[3]);
    if (x < d && visible(rd, i)) { d = x; mejor = p[1]; }
  });
  return mejor;
}

/** Celdas de ~650 m con la mediana de velocidad de los buses en tráfico mixto (mínimo 3 buses). */
function celdas(rd: Red, buses: LiveBus[], conNombre: (c: { lat: number; lon: number }) => boolean): TrafficCell[] {
  const G = 0.006;
  const grupos = new Map<string, { lat: number[]; lon: number[]; kmh: number[] }>();
  for (const b of buses) {
    if (!enMarcha(b)) continue;
    const k = `${Math.round(b.lat / G)}|${Math.round(b.lon / G)}`;
    const g = grupos.get(k) ?? { lat: [], lon: [], kmh: [] };
    g.lat.push(b.lat); g.lon.push(b.lon); g.kmh.push(b.kmh!);
    grupos.set(k, g);
  }
  const out: TrafficCell[] = [];
  for (const g of grupos.values()) {
    if (g.kmh.length < 3) continue;
    const kmh = mediana(g.kmh)!;
    const c = { lat: g.lat.reduce((a, b) => a + b) / g.lat.length, lon: g.lon.reduce((a, b) => a + b) / g.lon.length };
    out.push({ lat: Math.round(c.lat * 1e5) / 1e5, lon: Math.round(c.lon * 1e5) / 1e5, kmh: Math.round(kmh), n: g.kmh.length, level: nivel(kmh), near: conNombre(c) ? cercano(rd, c.lat, c.lon) : "" });
  }
  return out;
}

/** Todos los buses en vía en un radio, con su velocidad y cómo va el tráfico ahí. */
export async function vehicles(lat: number, lon: number, radio: number): Promise<Vehicles> {
  const [rd, pos] = await Promise.all([red(), posiciones()]);
  const ahora = Date.now() / 1000;
  const cerca: [number, LiveBus][] = [];
  for (const v of pos.vehiculos) {
    if (ahora - v.ts > VIEJO_S) continue;
    const d = metros(lat, lon, v.lat, v.lon);
    if (d > radio) continue;
    const b = vivoDe(rd, v);
    if (b) cerca.push([d, b]);
  }
  cerca.sort((a, b) => a[0] - b[0]);
  const buses = cerca.slice(0, 200).map(([, b]) => b);
  const mixtos = buses.filter(enMarcha).map((b) => b.kmh!);
  const kmh = mixtos.length >= 3 ? mediana(mixtos) : null;
  const slow = celdas(rd, buses, () => true).filter((c) => c.level !== "fluido").sort((a, b) => a.kmh - b.kmh).slice(0, 5);
  return { feed: feedDe(pos.ts), buses, traffic: { kmh: kmh == null ? null : Math.round(kmh), n: mixtos.length, level: kmh == null ? null : nivel(kmh), slow } };
}

/** Cómo va el tráfico en toda Bogotá, por zonas. */
export async function traffic(): Promise<Traffic> {
  const [rd, pos] = await Promise.all([red(), posiciones()]);
  const ahora = Date.now() / 1000;
  const buses = pos.vehiculos.filter((v) => ahora - v.ts <= VIEJO_S).map((v) => vivoDe(rd, v)).filter((b): b is LiveBus => b != null);
  const cells = celdas(rd, buses, () => false);
  // Solo se nombran las zonas lentas: buscar el paradero de cada celda es lo más caro.
  for (const c of cells) if (c.level === "lento") c.near = cercano(rd, c.lat, c.lon);
  const todas = buses.filter(enMarcha).map((b) => b.kmh!);
  const kmh = todas.length >= 10 ? mediana(todas) : null;
  return { feed: feedDe(pos.ts), cells, kmh: kmh == null ? null : Math.round(kmh), n: todas.length, tomtom: conTomTom() };
}
