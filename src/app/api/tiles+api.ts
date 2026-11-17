import { mosaico } from "@/server/tomtom";

/** Mosaico de flujo de tráfico de TomTom: /api/tiles?z=14&x=4823&y=7911&dark=1 */
export async function GET(request: Request) {
  const q = new URL(request.url).searchParams;
  const z = Number(q.get("z")), x = Number(q.get("x")), y = Number(q.get("y"));
  if (![z, x, y].every(Number.isInteger) || z < 8 || z > 18) return new Response(null, { status: 400 });
  return mosaico(z, x, y, q.get("dark") === "1").catch(() => new Response(null, { status: 502 }));
}
