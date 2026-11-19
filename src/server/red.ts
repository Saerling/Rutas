import { CromaError } from "./croma";
import { leerFeed, leerViajes, type AlertaRt, type Vehiculo, type ViajeRt } from "./gtfsrt";

/**
 * La red de TransMilenio/SITP sin intermediarios: el índice que arma scripts/red.py con el GTFS oficial
 * (publicado en la versión "red" del repo) y los feeds abiertos de GTFS Realtime de TransMilenio.
 */

const RED_URL = process.env.RED_URL ?? "https://github.com/Saerling/Rutas/releases/download/red/red.json.gz";
const RT = process.env.TRANSMI_RT ?? "https://gtfs.transmilenio.gov.co/";
const RT_TTL_MS = 15_000;
const BOGOTA_MS = -5 * 3600_000;

type RutaCruda = [id: string, corto: string, largo: string, sistema: string, color: string];
type ParadaCruda = [id: string, nombre: string, lat: number, lon: number, estacion: 0 | 1, accesible: 0 | 1, padre: string];

export interface Patron {
  i: number;
  r: number;
  s: number[];
  t: number[];
  p: string;
  d: Record<string, number[]>;
}

interface RedCruda {
  version: string;
  rutas: RutaCruda[];
  paradas: ParadaCruda[];
  patrones: Omit<Patron, "i">[];
  servicios: Record<string, { dias: number[]; mas: string[]; menos: string[] }>;
}

export interface Red {
  version: string;
  rutas: RutaCruda[];
  paradas: ParadaCruda[];
  patrones: Patron[];
  servicios: RedCruda["servicios"];
  rutaIdx: Map<string, number>;
  paradaIdx: Map<string, number>;
  hijos: Map<number, number[]>;
  porRuta: Map<number, Patron[]>;
  porParada: Map<number, Patron[]>;
  nombresNorm: string[];
}

let cargando: Promise<Red> | null = null;
let cargadaEn = 0;

export function normalizar(t: string): string {
  return t.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

async function descargar(): Promise<Red> {
  const res = await fetch(RED_URL, { signal: AbortSignal.timeout(20_000) });
  if (!res.ok) throw new CromaError("red_unavailable", `No se pudo bajar la red (${res.status}).`, 503);
  const cruda = (await new Response(res.body!.pipeThrough(new DecompressionStream("gzip"))).json()) as RedCruda;
  const patrones = cruda.patrones.map((p, i) => ({ ...p, i }));
  const red: Red = {
    ...cruda,
    patrones,
    rutaIdx: new Map(cruda.rutas.map((r, i) => [r[0], i])),
    paradaIdx: new Map(cruda.paradas.map((p, i) => [p[0], i])),
    hijos: new Map(),
    porRuta: new Map(),
    porParada: new Map(),
    nombresNorm: cruda.paradas.map((p) => normalizar(p[1])),
  };
  cruda.paradas.forEach((p, i) => {
    const padre = p[6] ? red.paradaIdx.get(p[6]) : undefined;
    if (padre !== undefined) red.hijos.set(padre, [...(red.hijos.get(padre) ?? []), i]);
  });
  for (const p of patrones) {
    red.porRuta.set(p.r, [...(red.porRuta.get(p.r) ?? []), p]);
    for (const s of new Set(p.s)) {
      red.porParada.set(s, [...(red.porParada.get(s) ?? []), p]);
      const padre = cruda.paradas[s][6] ? red.paradaIdx.get(cruda.paradas[s][6]) : undefined;
      if (padre !== undefined) red.porParada.set(padre, [...new Set([...(red.porParada.get(padre) ?? []), p])]);
    }
  }
  return red;
}

/** Una sola descarga por instancia; se refresca cada 12 h para tomar el GTFS del día. */
export function red(): Promise<Red> {
  if (!cargando || Date.now() - cargadaEn > 12 * 3600_000) {
    const anterior = cargando;
    cargadaEn = Date.now();
    cargando = descargar().catch((e) => {
      cargando = anterior;
      cargadaEn = 0;
      if (anterior) return anterior;
      throw e;
    });
  }
  return cargando;
}

// ───────────────────────── Tiempo real ─────────────────────────

interface Foto<T> { en: number; dato: Promise<T> }
const fotos = new Map<string, Foto<unknown>>();

function rt<T>(archivo: string, ttl: number, leer: (b: Uint8Array) => T): Promise<T> {
  const f = fotos.get(archivo) as Foto<T> | undefined;
  if (f && Date.now() - f.en < ttl) return f.dato;
  const dato = fetch(RT + archivo, { signal: AbortSignal.timeout(10_000), headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 15) Rutas/0.2 (+https://github.com/Saerling/Rutas)", Accept: "*/*" } })
    .then(async (r) => {
      if (!r.ok) {
        const cuerpo = (await r.text().catch(() => "")).slice(0, 160).replace(/\s+/g, " ");
        console.error("[rt]", archivo, r.status, r.headers.get("server"), cuerpo);
        throw new CromaError("feed_unavailable", `TransMilenio respondió ${r.status}.`, 502);
      }
      return leer(new Uint8Array(await r.arrayBuffer()));
    })
    .catch((e) => {
      if (f) return f.dato; // mejor el último que nada; la app ve la hora del dato
      throw e;
    });
  fotos.set(archivo, { en: Date.now(), dato });
  return dato;
}

export interface Posiciones { ts: number; vehiculos: Vehiculo[]; porRuta: Map<string, Vehiculo[]> }

/** Rastro corto por bus en esta instancia: dice hacia dónde va cuando la ida y la vuelta van por la misma vía. */
const rastro = new Map<string, { lat: number; lon: number; ts: number }>();

/**
 * Velocidad real de cada bus: el feed no la trae, así que se compara cada reporte con el anterior del mismo bus
 * (en esta instancia). Sirve para ver trancones y para animar el bus en el mapa. `desde` es dónde estaba antes.
 */
export interface Marcha { kmh: number; desde: [number, number]; ts: number }
const ultimoReporte = new Map<string, { lat: number; lon: number; ts: number }>();
export const marchas = new Map<string, Marcha>();

function crudas(): Promise<Posiciones> {
  return rt("positions.pb", RT_TTL_MS, (b) => {
    const { ts, vehiculos } = leerFeed(b);
    for (const v of vehiculos) {
      const antes = ultimoReporte.get(v.id);
      if (antes && v.ts > antes.ts) {
        const dt = v.ts - antes.ts;
        const d = metros(antes.lat, antes.lon, v.lat, v.lon);
        // Saltos imposibles (GPS que brinca, cambio de bus) no cuentan.
        if (dt >= 10 && dt <= 300 && d / dt < 25) marchas.set(v.id, { kmh: Math.round((d / dt) * 3.6 * 10) / 10, desde: [antes.lon, antes.lat], ts: v.ts });
      }
      if (!antes || v.ts > antes.ts) ultimoReporte.set(v.id, { lat: v.lat, lon: v.lon, ts: v.ts });
    }
    return { ts, vehiculos, porRuta: new Map() };
  });
}

/** Buses con GPS reciente por debajo de esto = el feed de posiciones está caído y se completa con el horario. */
const POCOS_GPS = 150;

/**
 * Posiciones de los buses. Si TransMilenio deja de publicar el GPS (pasa: positions.pb queda casi vacío), cada bus
 * que tripupdates.pb dice que va en viaje se ubica sobre su recorrido según la hora de salida y el retraso.
 */
export async function posiciones(): Promise<Posiciones> {
  const p = await crudas();
  const ahora = Date.now() / 1000;
  let vehiculos = p.vehiculos;
  let ts = p.ts;
  const frescos = vehiculos.filter((v) => ahora - v.ts <= 300);
  if (frescos.length < POCOS_GPS) {
    const [rd, vj] = await Promise.all([red(), rt("tripupdates.pb", 30_000, leerViajes).catch(() => null)]);
    if (vj) {
      const conGps = new Set(frescos.map((v) => v.id));
      vehiculos = [...frescos, ...estimados(rd, vj.viajes, ahora).filter((v) => !conGps.has(v.id))];
      ts = Math.max(ts, vj.ts);
    }
  }
  const porRuta = new Map<string, Vehiculo[]>();
  for (const v of vehiculos) porRuta.set(v.rutaId, [...(porRuta.get(v.rutaId) ?? []), v]);
  return { ts, vehiculos, porRuta };
}

const segDe = (h: string) => { const [a, b, c] = h.split(":").map(Number); return a * 3600 + b * 60 + (c || 0); };

/** Dónde va cada bus en viaje según su horario: en qué tramo y cuánto lleva, pegado a la vía. */
function estimados(rd: Red, viajes: ViajeRt[], ahora: number): Vehiculo[] {
  const out: Vehiculo[] = [];
  const svcs = new Map<string, Set<string>>();
  for (const v of viajes) {
    const ri = rd.rutaIdx.get(v.rutaId);
    if (ri === undefined || v.fecha.length !== 8) continue;
    const y = +v.fecha.slice(0, 4), m = +v.fecha.slice(4, 6), d = +v.fecha.slice(6, 8);
    const sal = segDe(v.inicio);
    const base = Date.UTC(y, m - 1, d) / 1000 - BOGOTA_MS / 1000;
    const e = ahora - (base + sal) - v.retraso;
    if (e < 0 || e > 4 * 3600) continue;
    let activos = svcs.get(v.fecha);
    if (!activos) { activos = serviciosDe(rd, v.fecha, (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7); svcs.set(v.fecha, activos); }
    // El recorrido: el que tiene una salida a esa hora (o la más cercana, para los viajes agregados).
    let patron: Patron | null = null, mejor = 1800;
    for (const p of rd.porRuta.get(ri) ?? []) {
      if (e > p.t[p.t.length - 1] + 600) continue;
      for (const [svc, salidas] of Object.entries(p.d)) {
        if (!activos.has(svc)) continue;
        for (const x of salidas) { const dd = Math.abs(x - sal); if (dd < mejor) { mejor = dd; patron = p; } }
      }
    }
    if (!patron) continue;
    const t = patron.t;
    let k = 0;
    while (k < t.length - 2 && t[k + 1] <= e) k++;
    if (e >= t[t.length - 1]) continue; // ya llegó
    const f = t[k + 1] > t[k] ? Math.min(1, Math.max(0, (e - t[k]) / (t[k + 1] - t[k]))) : 0;
    const A = rd.paradas[patron.s[k]], B = rd.paradas[patron.s[k + 1]];
    let lat = A[2] + (B[2] - A[2]) * f, lon = A[3] + (B[3] - A[3]) * f;
    const ea = enVia(patron, A[2], A[3], 150), eb = enVia(patron, B[2], B[3], 150);
    const tramo = ea && eb ? tramoEntre(patron, ea, eb, 50_000) : null;
    if (tramo && tramo.length > 1) {
      const largos = tramo.slice(1).map((q, i) => metros(tramo[i][1], tramo[i][0], q[1], q[0]));
      let falta = largos.reduce((a, b) => a + b, 0) * f;
      for (let i = 0; i < largos.length; i++) {
        if (falta <= largos[i] || i === largos.length - 1) {
          const g = largos[i] > 0 ? Math.min(1, falta / largos[i]) : 0;
          lon = tramo[i][0] + (tramo[i + 1][0] - tramo[i][0]) * g; lat = tramo[i][1] + (tramo[i + 1][1] - tramo[i][1]) * g;
          break;
        }
        falta -= largos[i];
      }
    }
    out.push({
      id: v.vehiculo, tripId: v.tripId, rutaId: v.rutaId, lat, lon, secuencia: k + 2, paradaId: B[0],
      ts: Math.floor(ahora / 15) * 15, etiqueta: v.etiqueta, placa: v.placa, estimado: { patron: patron.i, k, f },
    });
  }
  return out;
}

export function alertas(): Promise<AlertaRt[]> {
  return rt("alerts.pb", 60_000, (b) => {
    const ahora = Date.now() / 1000;
    return leerFeed(b).alertas.filter((a) => a.hasta === null || a.hasta > ahora);
  });
}

// ───────────────────────── Geometría y estimación ─────────────────────────

const RAD = Math.PI / 180;
const MLAT = 111_320;
const MLON = 111_320 * Math.cos(4.65 * RAD);

export function metros(la1: number, lo1: number, la2: number, lo2: number): number {
  return Math.hypot((la1 - la2) * MLAT, (lo1 - lo2) * MLON);
}

function aSegmento(px: number, py: number, ax: number, ay: number, bx: number, by: number): { d: number; f: number } {
  const dx = bx - ax, dy = by - ay;
  const l2 = dx * dx + dy * dy;
  const f = l2 > 0 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2)) : 0;
  return { d: Math.hypot(px - (ax + f * dx), py - (ay + f * dy)), f };
}

export function rumbo(la1: number, lo1: number, la2: number, lo2: number): number {
  return (Math.atan2((lo2 - lo1) * MLON, (la2 - la1) * MLAT) / RAD + 360) % 360;
}

const difAngulo = (a: number, b: number) => Math.abs(((a - b + 540) % 360) - 180);

export interface Ubicado { patron: Patron; k: number; f: number }

/**
 * En qué recorrido va el bus y por qué tramo (de la parada k a la k+1, con avance f).
 * Primero con la parada y secuencia que reporta el feed; si no cuadra con la posición, por geometría,
 * usando el rastro para separar ida de vuelta.
 */
export function ubicar(rd: Red, v: Vehiculo): Ubicado | null {
  const ri = rd.rutaIdx.get(v.rutaId);
  if (ri === undefined) return null;
  if (v.estimado) return { patron: rd.patrones[v.estimado.patron], k: v.estimado.k, f: v.estimado.f };
  const patrones = rd.porRuta.get(ri) ?? [];
  const px = v.lon * MLON, py = v.lat * MLAT;
  const P = rd.paradas;

  const previo = rastro.get(v.id);
  let mov: number | null = null;
  if (previo && metros(previo.lat, previo.lon, v.lat, v.lon) > 40) mov = rumbo(previo.lat, previo.lon, v.lat, v.lon);
  if (!previo || metros(previo.lat, previo.lon, v.lat, v.lon) > 40 || v.ts - previo.ts > 420) rastro.set(v.id, { lat: v.lat, lon: v.lon, ts: v.ts });

  const candidatos: { d: number; patron: Patron; k: number; f: number; g: number; s: number }[] = [];
  const reportada = rd.paradaIdx.get(v.paradaId);
  for (const patron of patrones) {
    for (let k = 0; k < patron.s.length - 1; k++) {
      const a = P[patron.s[k]], b = P[patron.s[k + 1]];
      const { d, f } = aSegmento(px, py, a[3] * MLON, a[2] * MLAT, b[3] * MLON, b[2] * MLAT);
      if (d > 120) continue;
      // El feed dice "voy hacia la parada X": preferir el tramo que termina ahí y con la misma secuencia.
      const s = (patron.s[k + 1] === reportada ? 0 : 1) + (k + 2 === v.secuencia || k + 1 === v.secuencia ? 0 : 0.5);
      candidatos.push({ d, patron, k, f, g: rumbo(a[2], a[3], b[2], b[3]), s });
    }
  }
  let lista = candidatos;
  if (mov !== null) {
    const enSentido = lista.filter((c) => difAngulo(c.g, mov!) <= 70);
    if (enSentido.length) lista = enSentido;
  }
  if (!lista.length) return null;
  lista.sort((a, b) => a.s - b.s || a.d - b.d);
  const mejor = lista[0];
  if (mov === null && mejor.s >= 1) {
    // Sin rumbo ni parada que lo confirme: si los empatados van en sentidos opuestos, no adivinar.
    const cerca = lista.filter((c) => c.d <= mejor.d + 25);
    if (cerca.some((c) => difAngulo(c.g, mejor.g) > 70)) return null;
  }
  return { patron: mejor.patron, k: mejor.k, f: mejor.f };
}

/** Segundos que le faltan al bus (desde su reporte) para llegar a la parada j del recorrido. */
export function faltan(u: Ubicado, j: number): number {
  const t = u.patron.t;
  const k1 = Math.min(u.k + 1, t.length - 1);
  return (1 - u.f) * (t[k1] - t[u.k]) + (t[j] - t[k1]);
}

// ───────────────────────── Horario ─────────────────────────

/** Fecha y segundos del día en Bogotá. */
export function relojBogota(ms = Date.now()): { fecha: string; dia: number; seg: number } {
  const d = new Date(ms + BOGOTA_MS);
  const fecha = d.toISOString().slice(0, 10).replaceAll("-", "");
  return { fecha, dia: (d.getUTCDay() + 6) % 7, seg: d.getUTCHours() * 3600 + d.getUTCMinutes() * 60 + d.getUTCSeconds() };
}

export function serviciosDe(rd: Red, fecha: string, dia: number): Set<string> {
  const activos = new Set<string>();
  for (const [id, s] of Object.entries(rd.servicios)) {
    if (s.menos.includes(fecha)) continue;
    if (s.dias[dia] || s.mas.includes(fecha)) activos.add(id);
  }
  return activos;
}

/** Horas (segundos desde la medianoche de hoy) a las que el recorrido pasa por la parada j, hoy. */
export function pasadasHoy(rd: Red, patron: Patron, j: number, ms = Date.now()): number[] {
  const hoy = relojBogota(ms);
  const ayer = relojBogota(ms - 86_400_000);
  const sHoy = serviciosDe(rd, hoy.fecha, hoy.dia);
  const sAyer = serviciosDe(rd, ayer.fecha, ayer.dia);
  const out: number[] = [];
  for (const [svc, salidas] of Object.entries(patron.d)) {
    if (sHoy.has(svc)) for (const x of salidas) out.push(x + patron.t[j]);
    if (sAyer.has(svc)) for (const x of salidas) if (x + patron.t[j] >= 86_400) out.push(x + patron.t[j] - 86_400);
  }
  return out.sort((a, b) => a - b);
}

export function decodificar(p: string): [number, number][] {
  const out: [number, number][] = [];
  let i = 0, lat = 0, lon = 0;
  while (i < p.length) {
    for (const k of [0, 1]) {
      let r = 0, s = 0, b: number;
      do { b = p.charCodeAt(i++) - 63; r |= (b & 0x1f) << s; s += 5; } while (b >= 0x20);
      const d = r & 1 ? ~(r >> 1) : r >> 1;
      if (k === 0) lat += d; else lon += d;
    }
    out.push([lon / 1e5, lat / 1e5]);
  }
  return out;
}

// ───────────────────────── Sobre la vía ─────────────────────────

const trazos = new Map<number, [number, number][]>();
function trazo(p: Patron): [number, number][] {
  let t = trazos.get(p.i);
  if (!t) { t = decodificar(p.p); trazos.set(p.i, t); }
  return t;
}

/** Un punto pegado al trazado de un recorrido: dónde queda, hacia dónde apunta la vía y en qué tramo va. */
export interface EnVia { lat: number; lon: number; rumbo: number; i: number; f: number; d: number }

/**
 * Pega un punto GPS a la vía por donde pasa el recorrido (el GPS de los buses cae a veces sobre las manzanas) y da
 * el rumbo de la calle en ese punto, no el de la recta entre dos paraderos. [cerca] limita la búsqueda alrededor
 * de un tramo ya conocido.
 */
export function enVia(p: Patron, lat: number, lon: number, maxM = 60): EnVia | null {
  const t = trazo(p);
  if (t.length < 2) return null;
  const px = lon * MLON, py = lat * MLAT;
  let mejor: EnVia | null = null;
  for (let i = 0; i < t.length - 1; i++) {
    const a = t[i], b = t[i + 1];
    const { d, f } = aSegmento(px, py, a[0] * MLON, a[1] * MLAT, b[0] * MLON, b[1] * MLAT);
    if (d > maxM || (mejor && d >= mejor.d)) continue;
    if (a[0] === b[0] && a[1] === b[1]) continue;
    mejor = { lat: a[1] + (b[1] - a[1]) * f, lon: a[0] + (b[0] - a[0]) * f, rumbo: Math.round(rumbo(a[1], a[0], b[1], b[0])), i, f, d };
  }
  return mejor;
}

/** El pedazo de trazado entre dos puntos pegados a la vía, en orden de recorrido ([lon, lat]). Null si va hacia atrás o es muy largo. */
export function tramoEntre(p: Patron, a: EnVia, b: EnVia, maxM = 1500): [number, number][] | null {
  if (b.i < a.i || (b.i === a.i && b.f < a.f)) return null;
  const t = trazo(p);
  const out: [number, number][] = [[a.lon, a.lat], ...t.slice(a.i + 1, b.i + 1), [b.lon, b.lat]];
  let largo = 0;
  for (let k = 1; k < out.length; k++) largo += metros(out[k - 1][1], out[k - 1][0], out[k][1], out[k][0]);
  return largo <= maxM ? out : null;
}
