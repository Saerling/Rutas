import { respond } from "@/server/respond";
import { conTomTom, incidentes } from "@/server/tomtom";

export function GET() {
  return respond(async () => ({ source: conTomTom() ? "tomtom" : null, incidents: await incidentes() }), 120);
}
