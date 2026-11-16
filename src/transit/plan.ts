import type { System } from "./types";

/** A place the rider can travel from or to: an address, a stop or a point on the map. */
export interface Place {
  id: string;
  name: string;
  area: string | null;
  kind: "stop" | "address" | "place";
  lat: number;
  lon: number;
}

export interface LegStop {
  name: string;
  lat: number;
  lon: number;
  at: string | null;
}

export interface Leg {
  mode: "walk" | "transit" | "bike";
  from: LegStop;
  to: LegStop;
  start: string;
  end: string;
  /** True when the times come from live positions, false when they are the published timetable. */
  live: boolean;
  minutes: number;
  metres: number | null;
  code: string | null;
  system: System | null;
  headsign: string | null;
  /** Stops the bus passes between getting on and getting off, in order. */
  stops: LegStop[];
  path: [number, number][];
  /** For walks: the turn-by-turn instructions, already in plain words. */
  steps: { street: string; metres: number; turn: string }[];
}

export interface Itinerary {
  id: string;
  start: string;
  end: string;
  minutes: number;
  transfers: number;
  walkMetres: number;
  live: boolean;
  legs: Leg[];
}

export interface PlanResult {
  itineraries: Itinerary[];
  /** Walking all the way, when it is short enough to be worth offering. */
  walk: Itinerary | null;
}

/** Decodes a Google-style encoded polyline into [lon, lat] pairs. MOTIS sends them with 6 or 7 decimals. */
export function decodePolyline(encoded: string, precision = 6): [number, number][] {
  const factor = 10 ** precision;
  const out: [number, number][] = [];
  let index = 0;
  let lat = 0;
  let lon = 0;
  while (index < encoded.length) {
    for (const axis of [0, 1]) {
      let result = 0;
      let shift = 0;
      let byte: number;
      do {
        byte = encoded.charCodeAt(index++) - 63;
        result |= (byte & 0x1f) << shift;
        shift += 5;
      } while (byte >= 0x20 && index < encoded.length);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (axis === 0) lat += delta;
      else lon += delta;
    }
    out.push([lon / factor, lat / factor]);
  }
  return out;
}

/**
 * Which TransMilenio system a route belongs to, from how its code is written: troncales are a letter and up to two digits (B10, D24, 4),
 * alimentadores a pair of numbers (16-14), TransMiCable a gondola, and the rest of the lettered codes zonal SITP routes.
 */
export function guessSystem(code: string | null, routeType: number | null): System {
  if (routeType === 6) return "transmicable";
  if (!code) return "other";
  const c = code.trim().toUpperCase();
  if (/^\d{1,2}-\d{1,2}$/.test(c)) return "alimentador";
  if (/^([A-M]\d{1,2}|\d{1,2}|M\d{2,3})$/.test(c)) return "troncal";
  if (/^[A-Z]{1,2}\d{2,4}[A-Z]?(-[A-Z]{1,2}\d{2,4}[A-Z]?)?$/.test(c)) return "zonal_urbano";
  return "other";
}

export function totalWalk(legs: Leg[]): number {
  return legs.filter((l) => l.mode === "walk").reduce((sum, l) => sum + (l.metres ?? 0), 0);
}

/** The transit legs only, which is what a rider calls "the route": walk to the B10, then the 1-7. */
export const rides = (it: Itinerary) => it.legs.filter((l) => l.mode === "transit");

/** The single SITP fare for troncal, zonal and TransMiCable in 2026 (Decreto distrital, in force since 14 January 2026). */
export const FARE_COP = 3550;

/**
 * Roughly what the trip costs: one fare each time the rider pays to board. Troncal legs taken one after another are a single
 * entry, because changing buses inside a station is free; a zonal bus, a feeder exit or a new station entry pays again.
 * It is an estimate: TuLlave transfer discounts and subsidies are not counted.
 */
export function estimateFare(it: Itinerary): number {
  let entries = 0;
  let inStation = false;
  for (const leg of it.legs) {
    if (leg.mode !== "transit") {
      // A short walk between troncal legs is usually a corridor inside the station.
      if (inStation && (leg.metres ?? 0) > 150) inStation = false;
      continue;
    }
    const troncal = leg.system === "troncal" || leg.system === "dual";
    if (leg.system === "alimentador") continue; // feeders are free with a troncal trip
    if (troncal && inStation) continue;
    entries++;
    inStation = troncal;
  }
  return entries * FARE_COP;
}
