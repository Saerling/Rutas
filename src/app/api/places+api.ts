import { places } from "@/server/planner";
import { bad, respond } from "@/server/respond";
import { BOGOTA } from "@/transit/plan-area";

export function GET(request: Request) {
  const q = new URL(request.url).searchParams;
  const text = q.get("q")?.trim() ?? "";
  if (text.length < 2 || text.length > 80) return bad("q must be 2 to 80 characters.");
  const lat = Number(q.get("lat"));
  const lon = Number(q.get("lon"));
  const near = Number.isFinite(lat) && Number.isFinite(lon) && lat !== 0 ? { lat, lon } : BOGOTA;
  return respond(() => places(text, near), 600);
}
