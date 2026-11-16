import { keepPreviousData, useQuery } from "@tanstack/react-query";
import * as Location from "expo-location";
import Storage from "expo-sqlite/kv-store";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";

import { metresBetween } from "@/transit/path";
import type { Itinerary, Leg, Place, PlanResult } from "@/transit/plan";

import { t } from "@/i18n";

import { ApiError, queryClient } from "./api";

/** Like the rest of the app, coordinates leave the phone rounded to about a block. */
const round = (n: number) => n.toFixed(3);

async function get<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: { Accept: "application/json" } });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as { error?: { code?: string } } | null;
    throw new ApiError(res.status, body?.error?.code ?? `http_${res.status}`);
  }
  return res.json() as Promise<T>;
}

export interface PlanOptions {
  /** ISO time to leave at (or arrive by); now when null. */
  time: string | null;
  arriveBy: boolean;
  wheelchair: boolean;
  fewerTransfers: boolean;
}

type Point = { lat: number; lon: number };

/** MOTIS calls the two ends START and END; the rider knows them as "your location" and the place they picked. */
function named(plan: PlanResult, fromName: string, toName: string): PlanResult {
  const fix = (n: string) => (n === "START" ? fromName : n === "END" ? toName : n);
  const each = (it: Itinerary): Itinerary => ({
    ...it,
    legs: it.legs.map((l) => ({ ...l, from: { ...l.from, name: fix(l.from.name) }, to: { ...l.to, name: fix(l.to.name) } })),
  });
  return { itineraries: plan.itineraries.map(each), walk: plan.walk && each(plan.walk) };
}

export function usePlan(from: (Point & { name?: string }) | null, to: (Point & { name?: string }) | null, options: PlanOptions) {
  const params = from && to && {
    fromLat: round(from.lat),
    fromLon: round(from.lon),
    toLat: round(to.lat),
    toLon: round(to.lon),
    ...(options.time ? { time: options.time } : {}),
    ...(options.arriveBy ? { arriveBy: "1" } : {}),
    ...(options.wheelchair ? { wheelchair: "1" } : {}),
    ...(options.fewerTransfers ? { maxTransfers: "1" } : {}),
  };
  return useQuery({
    queryKey: ["plan", params],
    queryFn: async () => named(await get<PlanResult>(`/api/plan?${new URLSearchParams(params || {})}`), from?.name ?? t().plan.here, to?.name ?? t().plan.to),
    enabled: !!params,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
  });
}

export function usePlaces(text: string, near: Point | null) {
  const q = text.trim();
  const at = near ? `&lat=${round(near.lat)}&lon=${round(near.lon)}` : "";
  return useQuery({
    queryKey: ["places", q.toLowerCase(), at],
    queryFn: () => get<Place[]>(`/api/places?q=${encodeURIComponent(q)}${at}`),
    enabled: q.length >= 2,
    placeholderData: keepPreviousData,
    staleTime: 30 * 60_000,
  });
}

/** The itinerary the rider opened, found among the plans already on the phone, so the trip screen never has to ask again. */
export function knownItinerary(id: string | undefined): Itinerary | undefined {
  if (!id) return undefined;
  for (const [, data] of queryClient.getQueriesData<PlanResult>({ queryKey: ["plan"] })) {
    const hit = data?.itineraries.find((i) => i.id === id) ?? (data?.walk?.id === id ? data.walk : undefined);
    if (hit) return hit;
  }
  return trips.get(id);
}

/** Itineraries that were started stay reachable even after the plan query is gone. */
const trips = new Map<string, Itinerary>();
export function keepItinerary(it: Itinerary) {
  trips.set(it.id, it);
}

// Home, work and recent places, kept only on the phone.

export interface MyPlace extends Place {
  slot: "home" | "work" | "recent";
}

const KEY = "places.v1";
const listeners = new Set<() => void>();

function read(): MyPlace[] {
  try {
    return JSON.parse(Storage.getItemSync(KEY) ?? "[]") as MyPlace[];
  } catch {
    return [];
  }
}

let snapshot = read();

function write(next: MyPlace[]) {
  snapshot = next;
  Storage.setItemSync(KEY, JSON.stringify(next));
  listeners.forEach((l) => l());
}

export function useMyPlaces(): MyPlace[] {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => snapshot,
  );
}

export function setSlot(slot: "home" | "work", place: Place) {
  write([...snapshot.filter((p) => p.slot !== slot), { ...place, slot }]);
}

export function clearSlot(slot: "home" | "work") {
  write(snapshot.filter((p) => p.slot !== slot));
}

export function remember(place: Place) {
  const recent = snapshot.filter((p) => p.slot === "recent" && p.id !== place.id).slice(0, 7);
  write([...snapshot.filter((p) => p.slot !== "recent"), { ...place, slot: "recent" }, ...recent]);
}

// Live guidance while riding: where the rider is along the trip and when to get off.

/** Close enough to a stop to say "this one". */
const AT_STOP_M = 120;
/** Close enough to the stop before the rider's own to warn them: the next one is theirs. */
const NEAR_M = 250;

export type Guidance =
  | { state: "waiting" }
  | { state: "denied" }
  | { state: "walking"; leg: number; metresLeft: number }
  | { state: "riding"; leg: number; stopsLeft: number; getOffNext: boolean }
  | { state: "arrived" };

function stopsLeft(leg: Leg, at: [number, number]): number {
  const all = [...leg.stops, leg.to];
  let nearest = 0;
  let best = Infinity;
  all.forEach((s, i) => {
    const d = metresBetween(at, [s.lon, s.lat]);
    if (d < best) {
      best = d;
      nearest = i;
    }
  });
  // Past the nearest stop when it is behind: count from the one after.
  const passed = best < AT_STOP_M ? nearest + 1 : nearest;
  return Math.max(0, all.length - passed);
}

/**
 * Follows the rider through the trip with the phone open, the way Moovit does: which leg they are on, how many stops are left,
 * and a nudge when the next stop is theirs. Positions stay on the phone; nothing is sent anywhere.
 */
export function useGuidance(it: Itinerary | undefined, on: boolean, onGetOffSoon: (leg: Leg) => void): Guidance {
  const [guidance, setGuidance] = useState<Guidance>({ state: "waiting" });
  const warned = useRef(new Set<number>());
  const notify = useRef(onGetOffSoon);
  useEffect(() => {
    notify.current = onGetOffSoon;
  }, [onGetOffSoon]);

  useEffect(() => {
    if (!it || !on) return;
    let sub: Location.LocationSubscription | null = null;
    let cancelled = false;
    void (async () => {
      const { granted } = await Location.requestForegroundPermissionsAsync();
      if (!granted) {
        setGuidance({ state: "denied" });
        return;
      }
      sub = await Location.watchPositionAsync({ accuracy: Location.Accuracy.High, distanceInterval: 25, timeInterval: 5_000 }, (pos) => {
        if (cancelled) return;
        const at: [number, number] = [pos.coords.longitude, pos.coords.latitude];
        const last = it.legs[it.legs.length - 1];
        if (metresBetween(at, [last.to.lon, last.to.lat]) < AT_STOP_M) {
          setGuidance({ state: "arrived" });
          return;
        }
        // The current leg is the first one whose end is still ahead of the rider.
        const index = it.legs.findIndex((l) => metresBetween(at, [l.to.lon, l.to.lat]) > AT_STOP_M && Date.parse(l.end) > Date.now() - 10 * 60_000);
        const i = index === -1 ? it.legs.length - 1 : index;
        const leg = it.legs[i];
        if (leg.mode !== "transit") {
          setGuidance({ state: "walking", leg: i, metresLeft: Math.round(metresBetween(at, [leg.to.lon, leg.to.lat])) });
          return;
        }
        const left = stopsLeft(leg, at);
        const prev = leg.stops[leg.stops.length - 1];
        const getOffNext = left <= 1 || (!!prev && metresBetween(at, [prev.lon, prev.lat]) < NEAR_M);
        if (getOffNext && !warned.current.has(i)) {
          warned.current.add(i);
          notify.current(leg);
        }
        setGuidance({ state: "riding", leg: i, stopsLeft: left, getOffNext });
      });
      if (cancelled) sub.remove();
    })();
    return () => {
      cancelled = true;
      sub?.remove();
    };
  }, [it, on]);

  return on ? guidance : { state: "waiting" };
}
