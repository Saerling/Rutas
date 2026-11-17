import { CromaError } from "./croma";

/**
 * Tráfico de TomTom (plan gratis MENSUAL: 200 000 mosaicos y 2 500 consultas de incidentes, sin tarjeta). Solo se usa si el servidor
 * tiene la variable TOMTOM_KEY; si no, la app sigue con el tráfico medido por los buses.
 * Los mosaicos de flujo pasan por aquí para que la clave no viaje en la app.
 */

const KEY = () => process.env.TOMTOM_KEY ?? "";
export const conTomTom = () => KEY().length > 0;

/** Bogotá y alrededores (lon, lat). */
const BBOX = "-74.25,4.45,-73.98,4.84";

export async function mosaico(z: number, x: number, y: number, oscuro: boolean): Promise<Response> {
  if (!conTomTom()) return new Response(null, { status: 404 });
  const estilo = oscuro ? "relative0-dark" : "relative0";
  const r = await fetch(`https://api.tomtom.com/traffic/map/4/tile/flow/${estilo}/${z}/${x}/${y}.png?key=${KEY()}&tileSize=256`, { signal: AbortSignal.timeout(8000) });
  if (!r.ok) return new Response(null, { status: r.status === 429 ? 429 : 502 });
  return new Response(await r.arrayBuffer(), { headers: { "Content-Type": "image/png", "Cache-Control": "public, max-age=600, s-maxage=600, stale-while-revalidate=1800" } });
}

export interface Incidente {
  id: string;
  kind: "trancon" | "accidente" | "cierre" | "obra" | "peligro" | "otro";
  title: string;
  from: string | null;
  to: string | null;
  /** Segundos de retraso que causa. */
  delay: number | null;
  /** 0 desconocido, 1 leve, 2 moderado, 3 grave, 4 indefinido (cierres). */
  magnitude: number;
  lat: number;
  lon: number;
  line: [number, number][];
}

const TIPO: Record<number, Incidente["kind"]> = { 1: "accidente", 2: "peligro", 3: "peligro", 4: "peligro", 5: "peligro", 6: "trancon", 7: "cierre", 8: "cierre", 9: "obra", 10: "peligro", 11: "peligro", 14: "peligro" };

let cache: { en: number; dato: Promise<Incidente[]> } | null = null;

export function incidentes(): Promise<Incidente[]> {
  if (!conTomTom()) return Promise.resolve([]);
  // El plan gratis es MENSUAL (2 500 consultas): una cada 20 min son ~2 200 al mes. La CDN de Vercel guarda lo mismo.
  if (cache && Date.now() - cache.en < 20 * 60_000) return cache.dato;
  const anterior = cache;
  const campos = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,events{description},from,to,delay}}}";
  const dato = fetch(`https://api.tomtom.com/traffic/services/5/incidentDetails?key=${KEY()}&bbox=${BBOX}&fields=${encodeURIComponent(campos)}&language=es-ES&timeValidityFilter=present`, { signal: AbortSignal.timeout(10_000) })
    .then(async (r) => {
      if (!r.ok) throw new CromaError("traffic_unavailable", `TomTom respondió ${r.status}.`, 502);
      const j = (await r.json()) as { incidents?: { geometry: { type: string; coordinates: unknown }; properties: { id: string; iconCategory: number; magnitudeOfDelay: number; events?: { description: string }[]; from?: string; to?: string; delay?: number } }[] };
      return (j.incidents ?? []).map((i): Incidente => {
        const g = i.geometry;
        const line = (g.type === "LineString" ? (g.coordinates as [number, number][]) : [g.coordinates as [number, number]]).map(([x, y]) => [Math.round(x * 1e5) / 1e5, Math.round(y * 1e5) / 1e5] as [number, number]);
        const mid = line[Math.floor(line.length / 2)];
        const p = i.properties;
        return {
          id: p.id, kind: TIPO[p.iconCategory] ?? "otro", title: p.events?.map((e) => e.description).join(" · ") || "Incidente",
          from: p.from ?? null, to: p.to ?? null, delay: p.delay ?? null, magnitude: p.magnitudeOfDelay ?? 0, lat: mid[1], lon: mid[0], line,
        };
      }).sort((a, b) => b.magnitude - a.magnitude || (b.delay ?? 0) - (a.delay ?? 0));
    })
    .catch((e) => { if (anterior) return anterior.dato; throw e; });
  cache = { en: Date.now(), dato };
  return dato;
}
