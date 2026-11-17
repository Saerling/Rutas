import { CromaError } from "./croma";
import { normalizar, red } from "./red";

/**
 * Boletines oficiales de TransMilenio (transmilenio.gov.co): lo mismo que publican en su canal de WhatsApp y en X
 * (cambios de recorrido, cierres de vagones, rutas nuevas), leído de su página pública. Se guarda 15 min.
 */

const BASE = "https://www.transmilenio.gov.co";
const SECCIONES = ["/comunicaciones/noticias-de-transmilenio/boletines-informativos", "/comunicaciones/noticias-de-transmilenio/comunicados-oficiales"];
const TTL = 15 * 60_000;
const SERVICIO = /\b(cierre|cierra|desvio|ruta|rutas|recorrido|trazado|vagon|estacion|portal|paradero|parada|servicio|operacion|horario|obra|obras|evento|manifestacion|movilizacion|bloqueo|cable|alimentador)\b/;

export interface Boletin {
  id: string;
  title: string;
  summary: string;
  date: string | null;
  url: string;
  image: string | null;
  /** Códigos de ruta que menciona (los que existen en la red). */
  routes: string[];
  /** Estaciones o portales que menciona. */
  stations: string[];
}

const MESES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"];
const texto = (h: string) => h.replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#0?39;/g, "'").replace(/\s+/g, " ").trim();

function fecha(t: string): string | null {
  const m = /([a-záéíóú]+)\s+(\d{1,2})\s+de\s+(\d{4})/i.exec(t);
  if (!m) return null;
  const mes = MESES.indexOf(m[1].toLowerCase());
  if (mes < 0) return null;
  return `${m[3]}-${String(mes + 1).padStart(2, "0")}-${m[2].padStart(2, "0")}`;
}

async function seccion(ruta: string): Promise<Omit<Boletin, "routes" | "stations">[]> {
  const r = await fetch(BASE + ruta, { signal: AbortSignal.timeout(12_000), headers: { "User-Agent": "Rutas/0.2 (+https://github.com/Saerling/Rutas)" } });
  if (!r.ok) throw new CromaError("news_unavailable", `TransMilenio respondió ${r.status}.`, 502);
  const html = await r.text();
  const out: Omit<Boletin, "routes" | "stations">[] = [];
  for (const a of html.split("<article").slice(1)) {
    const href = /href="([^"]+)"/.exec(a)?.[1];
    const title = texto(/<h2[^>]*>([\s\S]*?)<\/h2>/.exec(a)?.[1] ?? "");
    if (!href || !title) continue;
    const summary = texto(/<div class="text-sm leading-6[^"]*">([\s\S]*?)<\/div>/.exec(a)?.[1] ?? "");
    const cuando = texto(/fa-calendar-alt[^>]*><\/i>([\s\S]*?)<\/div>/.exec(a)?.[1] ?? "");
    const img = /<img[^>]+src="([^"]+)"/.exec(a)?.[1] ?? null;
    out.push({ id: href.split("/").pop()!, title, summary, date: fecha(cuando), url: href.startsWith("http") ? href : BASE + href, image: img });
  }
  return out;
}

let cache: { en: number; dato: Promise<Boletin[]> } | null = null;

export function boletines(): Promise<Boletin[]> {
  if (cache && Date.now() - cache.en < TTL) return cache.dato;
  const anterior = cache;
  const dato = (async () => {
    const [rd, ...listas] = await Promise.all([red(), ...SECCIONES.map((s) => seccion(s).catch(() => []))]);
    const todos = listas.flat();
    if (!todos.length) throw new CromaError("news_unavailable", "No se pudieron leer los boletines.", 502);
    const codigos = new Set(rd.rutas.flatMap((r) => expandir(r[1])));
    const estaciones = [...new Set(rd.paradas.filter((p) => p[4] && !p[6]).map((p) => p[1]))].map((n) => [n, normalizar(n)] as const).filter(([, n]) => n.length >= 5);
    const vistos = new Set<string>();
    return todos
      .filter((b) => !vistos.has(b.id) && vistos.add(b.id))
      .sort((a, b) => (b.date ?? "").localeCompare(a.date ?? ""))
      .slice(0, 30)
      .map((b) => {
        const t = `${b.title} ${b.summary}`;
        const routes = [...new Set((t.toUpperCase().match(/\b[A-Z]{1,2}\d{1,3}[A-Z]?\b|\b\d{1,2}-\d{1,2}\b/g) ?? []).filter((c) => codigos.has(c)))];
        const n = ` ${normalizar(t)} `;
        const stations = estaciones.filter(([, e]) => n.includes(` ${e} `)).map(([e]) => e).slice(0, 6);
        return { ...b, routes, stations };
      })
      // Solo lo que cambia el servicio: fuera avisos legales, contratación y notas institucionales.
      .filter((b) => b.routes.length || b.stations.length || SERVICIO.test(normalizar(`${b.title} ${b.summary}`)));
  })().catch((e) => { if (anterior) return anterior.dato; throw e; });
  cache = { en: Date.now(), dato };
  return dato;
}

/** DK211 es D211 y K211; los demás códigos son ellos mismos. */
export function expandir(code: string): string[] {
  const c = code.trim().toUpperCase();
  const m = /^([A-Z])([A-Z])(\d{2,4}[A-Z]?)$/.exec(c);
  return m ? [c, m[1] + m[3], m[2] + m[3]] : [c];
}
