import { z } from "zod";

import { decodePolyline, guessSystem, totalWalk, type Itinerary, type Leg, type Place, type PlanResult } from "@/transit/plan";

import { cached, CromaError } from "./croma";

/**
 * Trip planning and place search come from Transitous (MOTIS over the open GTFS of TransMilenio and SITP), a free community service.
 * It asks every app to send who it is, to keep its code open, and to stay well under its daily limits; the shared cache below helps with that.
 * Set PLANNER_URL to point at your own MOTIS or a self-hosted copy when the app grows.
 */
const BASE = process.env.PLANNER_URL ?? "https://api.transitous.org";
const AGENT = "Rutas/0.1 (+https://github.com/Saerling/Rutas)";
const TIMEOUT_MS = 12_000;

async function motis<S extends z.ZodType>(path: string, params: Record<string, string>, schema: S): Promise<z.infer<S>> {
  let res: Response;
  try {
    res = await fetch(`${BASE}${path}?${new URLSearchParams(params)}`, {
      headers: { "User-Agent": AGENT, Accept: "application/json" },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (error) {
    const slow = error instanceof DOMException && error.name === "TimeoutError";
    throw new CromaError(slow ? "timeout" : "network", slow ? "The planner took too long." : "Could not reach the planner.", slow ? 504 : 502);
  }
  if (!res.ok) throw new CromaError(`planner_${res.status}`, res.statusText);
  const parsed = schema.safeParse(await res.json().catch(() => null));
  if (!parsed.success) throw new CromaError("unexpected_response", `Unexpected shape from ${path}.`);
  return parsed.data;
}

const point = z.object({
  name: z.string(),
  lat: z.number(),
  lon: z.number(),
  arrival: z.string().optional(),
  departure: z.string().optional(),
});

const leg = z.object({
  mode: z.string(),
  from: point,
  to: point,
  startTime: z.string(),
  endTime: z.string(),
  duration: z.number(),
  realTime: z.boolean().optional(),
  distance: z.number().optional(),
  routeShortName: z.string().optional(),
  routeType: z.number().optional(),
  headsign: z.string().optional(),
  intermediateStops: z.array(point).optional(),
  legGeometry: z.object({ points: z.string(), precision: z.number().optional() }).optional(),
  steps: z.array(z.object({ relativeDirection: z.string().optional(), distance: z.number().optional(), streetName: z.string().optional() })).optional(),
});

const itinerary = z.object({
  duration: z.number(),
  startTime: z.string(),
  endTime: z.string(),
  transfers: z.number(),
  legs: z.array(leg),
});

const planResponse = z.object({ itineraries: z.array(itinerary), direct: z.array(itinerary).optional() });

const match = z.object({
  type: z.string(),
  id: z.string(),
  name: z.string(),
  lat: z.number(),
  lon: z.number(),
  street: z.string().optional(),
  houseNumber: z.string().optional(),
  areas: z.array(z.object({ name: z.string(), adminLevel: z.number() })).optional(),
});

const stopOf = (p: z.infer<typeof point>) => ({ name: p.name, lat: p.lat, lon: p.lon, at: p.departure ?? p.arrival ?? null });

function toLeg(l: z.infer<typeof leg>): Leg {
  const mode = l.mode === "WALK" ? "walk" : l.mode === "BIKE" ? "bike" : "transit";
  const code = mode === "transit" ? (l.routeShortName ?? null) : null;
  return {
    mode,
    from: stopOf(l.from),
    to: stopOf(l.to),
    start: l.startTime,
    end: l.endTime,
    live: !!l.realTime,
    minutes: Math.max(1, Math.round(l.duration / 60)),
    metres: l.distance ?? null,
    code,
    system: mode === "transit" ? guessSystem(code, l.routeType ?? null) : null,
    headsign: l.headsign ?? null,
    stops: (l.intermediateStops ?? []).map(stopOf),
    path: l.legGeometry ? decodePolyline(l.legGeometry.points, l.legGeometry.precision ?? 6) : [],
    steps: (l.steps ?? [])
      .filter((s) => (s.distance ?? 0) >= 10)
      .map((s) => ({ street: s.streetName ?? "", metres: Math.round(s.distance ?? 0), turn: s.relativeDirection ?? "CONTINUE" })),
  };
}

function toItinerary(it: z.infer<typeof itinerary>, index: number): Itinerary {
  const legs = it.legs.map(toLeg);
  return {
    id: `${Date.parse(it.startTime)}-${index}`,
    start: it.startTime,
    end: it.endTime,
    minutes: Math.max(1, Math.round(it.duration / 60)),
    transfers: it.transfers,
    walkMetres: Math.round(totalWalk(legs)),
    live: legs.some((l) => l.mode === "transit" && l.live),
    legs,
  };
}

export interface PlanInput {
  from: { lat: number; lon: number };
  to: { lat: number; lon: number };
  time?: string;
  arriveBy?: boolean;
  wheelchair?: boolean;
  maxTransfers?: number;
}

const planCache = cached<PlanResult>(60_000);

export function plan(input: PlanInput): Promise<PlanResult> {
  const params: Record<string, string> = {
    fromPlace: `${input.from.lat},${input.from.lon}`,
    toPlace: `${input.to.lat},${input.to.lon}`,
    numItineraries: "5",
    directModes: "WALK",
    maxDirectTime: "2700",
    language: "es",
  };
  if (input.time) params.time = input.time;
  if (input.arriveBy) params.arriveBy = "true";
  if (input.wheelchair) params.pedestrianProfile = "WHEELCHAIR";
  if (input.maxTransfers != null) params.maxTransfers = String(input.maxTransfers);
  return planCache(JSON.stringify(params), async () => {
    const data = await motis("/api/v5/plan", params, planResponse);
    const direct = data.direct?.[0];
    return { itineraries: data.itineraries.map(toItinerary), walk: direct ? toItinerary(direct, 99) : null };
  });
}

const placeCache = cached<Place[]>(10 * 60_000);

/** Places in and around Bogotá that match what the rider typed, nearest to them first. */
export function places(text: string, near: { lat: number; lon: number }): Promise<Place[]> {
  const params = { text, place: `${near.lat},${near.lon}`, placeBias: "5", language: "es" };
  return placeCache(JSON.stringify(params), async () => {
    const found = await motis("/api/v1/geocode", params, z.array(match));
    return found.slice(0, 12).map((m) => {
      // The neighbourhood or locality says more to a rider than "Bogotá"; the city is the fallback.
      const areas = [...(m.areas ?? [])].sort((a, b) => b.adminLevel - a.adminLevel);
      const named = (a: { name: string }) => !/^UPZs /.test(a.name);
      const city = (areas.find((a) => a.adminLevel === 8 && named(a)) ?? areas.find((a) => a.adminLevel >= 6 && named(a)))?.name.replace(/^Localidad /, "") ?? null;
      const street = m.street ? [m.street, m.houseNumber].filter(Boolean).join(" ") : null;
      return {
        id: m.id,
        name: m.name,
        area: street && street !== m.name ? street : city,
        kind: m.type === "STOP" ? "stop" : m.type === "ADDRESS" ? "address" : "place",
        lat: m.lat,
        lon: m.lon,
      };
    });
  });
}
