export const SYSTEMS = [
  "troncal",
  "dual",
  "zonal_urbano",
  "alimentador",
  "zonal_complementario",
  "zonal_especial",
  "transmicable",
  "other",
] as const;

export type System = (typeof SYSTEMS)[number];

export type Basis = "predicted" | "estimated" | "scheduled";

export interface Feed {
  asOf: string;
  live: boolean;
}

export interface RouteRef {
  id: string;
  feedCode: string;
  code: string;
  aliases: string[];
  name: string;
  system: System;
  variant: string | null;
}

export interface Stop {
  id: string;
  name: string;
  kind: "station" | "stop";
  lat: number;
  lon: number;
  accessible: boolean;
  distanceM: number | null;
  routes: RouteRef[];
}

export interface Arrival {
  routeId: string | null;
  code: string;
  aliases: string[];
  name: string;
  system: System;
  minutes: number | null;
  at: string | null;
  basis: Basis;
  delaySeconds: number | null;
  vehicle: string | null;
  /** The bus's plate, to find it on the map. Absent from servers before 0.0.5. */
  plate?: string | null;
}

export interface Alert {
  id: string;
  effect: string;
  /** Spanish sentences, kept for app versions that predate translations. */
  title: string;
  detail: string;
  /** Where it happens, as stop names. Two places mean "between", one means "near". */
  places?: string[];
  /** TransMilenio's own words when it names no stops. Shown as written, like stop names. */
  note?: string | null;
  routes: string[];
  until: string | null;
}

export interface Bus {
  id: string;
  lat: number;
  lon: number;
  nextStop: string | null;
  plate: string | null;
  reportedAt: string | null;
  /** Hacia dónde va, en grados (0 = norte), según el tramo del recorrido en que está. */
  bearing?: number | null;
  /** Último paradero del recorrido en que va. */
  toward?: string | null;
  label?: string | null;
}

export interface IncomingBus extends Bus {
  code: string;
  system: System;
}

export interface StopDetail {
  feed: Feed;
  stop: Stop;
  arrivals: Arrival[];
  incoming: IncomingBus[];
  alerts: Alert[];
}

export interface RouteDetail {
  feed: Feed;
  route: RouteRef & {
    firstDeparture: string | null;
    lastDeparture: string | null;
    tripsPerWeek: number | null;
  };
  path: [number, number][];
  /** Every way the route runs, the busiest first: its line and its stops in order. Absent from servers before 0.0.5. */
  patterns?: { path: [number, number][]; stops: string[] }[];
  /** Cada sentido o variante: hacia dónde va, sus paradas, su trazo y las salidas de hoy desde la primera parada. */
  variants?: { toward: string; stops: { id: string; name: string; lat: number; lon: number }[]; path: [number, number][]; departures: string[] }[];
  stops: { id: string; name: string; lat: number; lon: number }[];
  buses: Bus[];
  alerts: Alert[];
}

/** Un bus en vía cerca de ti, con su ruta y la velocidad a la que va (si hay dos reportes seguidos). */
export interface LiveBus extends Bus {
  routeId: string;
  code: string;
  system: System;
  kmh?: number | null;
  /** Dónde estaba en el reporte anterior [lon, lat]: el tramo que acaba de recorrer. */
  from?: [number, number] | null;
  /** El tramo recorrido desde el reporte anterior, siguiendo la calle ([lon, lat]). */
  trail?: [number, number][] | null;
}

export type TrafficLevel = "fluido" | "moderado" | "lento";

/** Cómo va el tráfico en una zona, medido con la velocidad de los buses SITP (no los troncales: van por su carril). */
export interface TrafficCell {
  lat: number;
  lon: number;
  kmh: number;
  n: number;
  level: TrafficLevel;
  /** El paradero más cercano, para decir dónde es. */
  near: string;
}

export interface Vehicles {
  feed: Feed;
  buses: LiveBus[];
  traffic: { kmh: number | null; n: number; level: TrafficLevel | null; slow: TrafficCell[] };
}

export interface Traffic {
  feed: Feed;
  cells: TrafficCell[];
  kmh: number | null;
  n: number;
  /** Si el servidor tiene tráfico de TomTom (mosaicos en /api/tiles e incidentes en /api/incidents). */
  tomtom?: boolean;
}

export interface Nearby {
  stops: Stop[];
}

export interface SearchResult {
  stops: Stop[];
  routes: RouteRef[];
}

export interface Health {
  feed: Feed;
}
