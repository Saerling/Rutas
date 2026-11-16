import { humanizeAlert } from "@/transit/alerts";
import { dedupeRoutes, describeRoute } from "@/transit/codes";
import type { Alert, Arrival, Bus, Feed, Health, IncomingBus, Nearby, RouteDetail, RouteRef, SearchResult, Stop, StopDetail } from "@/transit/types";

import type { AlertaRt, Vehiculo } from "./gtfsrt";
import { alertas, decodificar, faltan, metros, normalizar, pasadasHoy, posiciones, red, relojBogota, rumbo, ubicar, type Patron, type Red } from "./red";

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

function aBus(rd: Red, v: Vehiculo): Bus {
  const u = ubicar(rd, v);
  let bearing: number | null = null;
  let nextStop = nombreParada(rd, v.paradaId);
  if (u && u.k + 1 < u.patron.s.length) {
    const a = rd.paradas[u.patron.s[u.k]], b = rd.paradas[u.patron.s[u.k + 1]];
    bearing = Math.round(rumbo(a[2], a[3], b[2], b[3]));
    nextStop = rd.paradas[publica(rd, u.patron.s[u.k + 1])][1];
  }
  const toward = u ? rd.paradas[publica(rd, u.patron.s[u.patron.s.length - 1])][1] : null;
  return { id: v.id, lat: v.lat, lon: v.lon, nextStop, plate: v.placa || null, reportedAt: iso(v.ts), bearing, label: v.etiqueta || null, toward };
}

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
