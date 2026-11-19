/**
 * Lector mínimo de GTFS Realtime (protobuf) para los feeds abiertos de TransMilenio, sin dependencias.
 * Solo decodifica los campos que usa la app: posiciones de buses y alertas.
 */

class Lector {
  pos = 0;
  constructor(readonly b: Uint8Array) {}
  get fin() { return this.pos >= this.b.length; }
  varint(): number {
    let r = 0, m = 1, x: number;
    do { x = this.b[this.pos++]; r += (x & 0x7f) * m; m *= 128; } while (x & 0x80);
    return r;
  }
  bytes(): Uint8Array { const n = this.varint(); const s = this.b.subarray(this.pos, this.pos + n); this.pos += n; return s; }
  /** int32 con signo (los negativos vienen en 10 bytes). */
  entero(): number {
    let r = 0n, m = 0n, x: number;
    do { x = this.b[this.pos++]; r |= BigInt(x & 0x7f) << m; m += 7n; } while (x & 0x80);
    return Number(BigInt.asIntN(32, r));
  }
  float(): number { const v = new DataView(this.b.buffer, this.b.byteOffset + this.pos, 4).getFloat32(0, true); this.pos += 4; return v; }
  saltar(tipo: number) {
    if (tipo === 0) this.varint();
    else if (tipo === 1) this.pos += 8;
    else if (tipo === 2) { const n = this.varint(); this.pos += n; }
    else if (tipo === 5) this.pos += 4;
    else throw new Error(`protobuf: tipo ${tipo}`);
  }
}

const texto = new TextDecoder();
const str = (b: Uint8Array) => texto.decode(b);

function campos(b: Uint8Array, cada: (campo: number, tipo: number, l: Lector) => void) {
  const l = new Lector(b);
  while (!l.fin) {
    const k = l.varint();
    const antes = l.pos;
    cada(k >>> 3, k & 7, l);
    if (l.pos === antes) l.saltar(k & 7);
  }
}

export interface Vehiculo {
  id: string;
  tripId: string;
  rutaId: string;
  lat: number;
  lon: number;
  secuencia: number;
  paradaId: string;
  ts: number;
  etiqueta: string;
  placa: string;
  /** Sin GPS: ubicado por horario en el recorrido [patron] (índice), tramo k con avance f. */
  estimado?: { patron: number; k: number; f: number };
}

export interface AlertaRt {
  id: string;
  efecto: string;
  titulo: string;
  detalle: string;
  rutas: string[];
  paradas: string[];
  hasta: number | null;
}

const EFECTOS = ["", "no_service", "reduced_service", "significant_delays", "detour", "additional_service", "modified_service", "other_effect", "unknown_effect", "stop_moved"];

function traduccion(b: Uint8Array): string {
  let t = "";
  campos(b, (c, _, l) => { if (c === 1) campos(l.bytes(), (c2, _2, l2) => { if (c2 === 1 && !t) t = str(l2.bytes()); }); });
  return t;
}

export function leerFeed(buf: Uint8Array): { ts: number; vehiculos: Vehiculo[]; alertas: AlertaRt[] } {
  let ts = 0;
  const vehiculos: Vehiculo[] = [];
  const alertas: AlertaRt[] = [];
  campos(buf, (c, _, l) => {
    if (c === 1) campos(l.bytes(), (c2, _2, l2) => { if (c2 === 3) ts = l2.varint(); });
    else if (c === 2) {
      let id = "";
      campos(l.bytes(), (c2, _2, l2) => {
        if (c2 === 1) id = str(l2.bytes());
        else if (c2 === 4) {
          const v: Vehiculo = { id, tripId: "", rutaId: "", lat: 0, lon: 0, secuencia: 0, paradaId: "", ts: 0, etiqueta: "", placa: "" };
          campos(l2.bytes(), (c3, _3, l3) => {
            if (c3 === 1) campos(l3.bytes(), (c4, _4, l4) => { if (c4 === 1) v.tripId = str(l4.bytes()); else if (c4 === 5) v.rutaId = str(l4.bytes()); });
            else if (c3 === 2) campos(l3.bytes(), (c4, t4, l4) => { if (c4 === 1 && t4 === 5) v.lat = l4.float(); else if (c4 === 2 && t4 === 5) v.lon = l4.float(); });
            else if (c3 === 3) v.secuencia = l3.varint();
            else if (c3 === 5) v.ts = l3.varint();
            else if (c3 === 7) v.paradaId = str(l3.bytes());
            else if (c3 === 8) campos(l3.bytes(), (c4, _4, l4) => {
              if (c4 === 1) v.id = str(l4.bytes()); else if (c4 === 2) v.etiqueta = str(l4.bytes()); else if (c4 === 3) v.placa = str(l4.bytes());
            });
          });
          if (v.lat && v.lon) vehiculos.push(v);
        } else if (c2 === 5) {
          const a: AlertaRt = { id, efecto: "unknown_effect", titulo: "", detalle: "", rutas: [], paradas: [], hasta: null };
          campos(l2.bytes(), (c3, _3, l3) => {
            if (c3 === 1) campos(l3.bytes(), (c4, _4, l4) => { if (c4 === 2) { const h = l4.varint(); a.hasta = Math.max(a.hasta ?? 0, h); } });
            else if (c3 === 5) campos(l3.bytes(), (c4, _4, l4) => {
              if (c4 === 2) { const r = str(l4.bytes()); if (!a.rutas.includes(r)) a.rutas.push(r); }
              else if (c4 === 5) { const p = str(l4.bytes()); if (!a.paradas.includes(p)) a.paradas.push(p); }
            });
            else if (c3 === 7) a.efecto = EFECTOS[l3.varint()] ?? "unknown_effect";
            else if (c3 === 10) a.titulo = traduccion(l3.bytes());
            else if (c3 === 11) a.detalle = traduccion(l3.bytes());
          });
          alertas.push(a);
        }
      });
    }
  });
  return { ts, vehiculos, alertas };
}

/** Un viaje en curso según tripupdates.pb: no trae posición, solo cuándo salió y cuánto va retrasado. */
export interface ViajeRt {
  tripId: string;
  rutaId: string;
  inicio: string;
  fecha: string;
  retraso: number;
  vehiculo: string;
  etiqueta: string;
  placa: string;
}

export function leerViajes(buf: Uint8Array): { ts: number; viajes: ViajeRt[] } {
  let ts = 0;
  const viajes: ViajeRt[] = [];
  campos(buf, (c, _, l) => {
    if (c === 1) campos(l.bytes(), (c2, _2, l2) => { if (c2 === 3) ts = l2.varint(); });
    else if (c === 2) campos(l.bytes(), (c2, _2, l2) => {
      if (c2 !== 3) return;
      const v: ViajeRt = { tripId: "", rutaId: "", inicio: "", fecha: "", retraso: 0, vehiculo: "", etiqueta: "", placa: "" };
      campos(l2.bytes(), (c3, t3, l3) => {
        if (c3 === 1) campos(l3.bytes(), (c4, _4, l4) => {
          if (c4 === 1) v.tripId = str(l4.bytes()); else if (c4 === 2) v.inicio = str(l4.bytes());
          else if (c4 === 3) v.fecha = str(l4.bytes()); else if (c4 === 5) v.rutaId = str(l4.bytes());
        });
        else if (c3 === 3) campos(l3.bytes(), (c4, _4, l4) => {
          if (c4 === 1) v.vehiculo = str(l4.bytes()); else if (c4 === 2) v.etiqueta = str(l4.bytes()); else if (c4 === 3) v.placa = str(l4.bytes());
        });
        else if (c3 === 5 && t3 === 0) v.retraso = l3.entero();
      });
      if (v.rutaId && v.inicio && v.fecha && v.vehiculo) viajes.push(v);
    });
  });
  return { ts, viajes };
}
