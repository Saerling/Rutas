import { conTomTom, incidentes } from "@/server/tomtom";

/** Incidentes de TomTom. La CDN de Vercel guarda la respuesta 20 min: así el cupo mensual (2 500) alcanza. */
export async function GET() {
  try {
    const data = { source: conTomTom() ? "tomtom" : null, incidents: await incidentes() };
    return Response.json(data, { headers: { "Cache-Control": "public, max-age=600", "Vercel-CDN-Cache-Control": "max-age=1200, stale-while-revalidate=3600" } });
  } catch {
    return Response.json({ source: conTomTom() ? "tomtom" : null, incidents: [] }, { headers: { "Cache-Control": "public, max-age=60, s-maxage=120" } });
  }
}
