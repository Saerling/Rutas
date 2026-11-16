import { plan } from "@/server/planner";
import { bad, respond } from "@/server/respond";

const coord = (v: string | null) => (v != null && /^-?\d{1,3}(\.\d{1,6})?$/.test(v) ? Number(v) : null);

export function GET(request: Request) {
  const q = new URL(request.url).searchParams;
  const [fromLat, fromLon, toLat, toLon] = ["fromLat", "fromLon", "toLat", "toLon"].map((k) => coord(q.get(k)));
  if (fromLat == null || fromLon == null || toLat == null || toLon == null) return bad("fromLat, fromLon, toLat and toLon are required.");
  const time = q.get("time");
  if (time && Number.isNaN(Date.parse(time))) return bad("time must be an ISO date.");
  const transfers = q.get("maxTransfers");
  return respond(
    () =>
      plan({
        from: { lat: fromLat, lon: fromLon },
        to: { lat: toLat, lon: toLon },
        time: time ?? undefined,
        arriveBy: q.get("arriveBy") === "1",
        wheelchair: q.get("wheelchair") === "1",
        cursor: q.get("cursor")?.slice(0, 500) || undefined,
        maxTransfers: transfers != null && /^\d$/.test(transfers) ? Number(transfers) : undefined,
      }),
    30,
  );
}
