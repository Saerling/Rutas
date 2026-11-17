import { bad, respond } from "@/server/respond";
import { vehicles } from "@/server/transit";

const inBogota = (lat: number, lon: number) => lat > 3.5 && lat < 5.5 && lon > -75 && lon < -73;

export function GET(request: Request) {
  const params = new URL(request.url).searchParams;
  const lat = Number(params.get("lat"));
  const lon = Number(params.get("lon"));
  const radius = Math.min(Math.max(Number(params.get("radius")) || 1500, 200), 4000);
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || !inBogota(lat, lon)) return bad("lat and lon must be a point in Bogotá.");
  return respond(() => vehicles(lat, lon, radius), 5);
}
