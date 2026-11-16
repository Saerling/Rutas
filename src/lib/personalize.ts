import Storage from "expo-sqlite/kv-store";
import { useSyncExternalStore } from "react";

/**
 * Personalización, the same set Shiori offers: accent colour, pure black, corners, glass and animation speed.
 * Everything here is a look the rider can switch off; none of it changes what the app does.
 */
export type Accent = "nord" | "verde" | "morado" | "naranja" | "rojo" | "mono";
export type Corners = "continuas" | "redondas" | "cuadradas";

export interface Personalization {
  accent: Accent;
  black: boolean;
  corners: Corners;
  glass: boolean;
  /** 0 turns every animation off; 1 is normal; 2 is twice as fast. */
  speed: number;
  intro: boolean;
}

export const SPEEDS = [0, 0.5, 0.75, 1, 1.25, 1.5, 2] as const;

const KEY = "personalize.v1";
const DEFAULTS: Personalization = { accent: "nord", black: false, corners: "continuas", glass: true, speed: 1, intro: true };
const listeners = new Set<() => void>();

function read(): Personalization {
  try {
    return { ...DEFAULTS, ...(JSON.parse(Storage.getItemSync(KEY) ?? "{}") as Partial<Personalization>) };
  } catch {
    return DEFAULTS;
  }
}

let current = read();

export function personalization(): Personalization {
  return current;
}

export function subscribePersonalization(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function usePersonalization(): Personalization {
  return useSyncExternalStore(subscribePersonalization, () => current);
}

export function setPersonalization(patch: Partial<Personalization>) {
  current = { ...current, ...patch };
  Storage.setItemSync(KEY, JSON.stringify(current));
  listeners.forEach((l) => l());
}

/** Accent colours from Nord's frost and aurora, one tone for light and one for dark, each readable with text on top. */
export const ACCENTS: Record<Exclude<Accent, "mono">, { light: string; dark: string }> = {
  nord: { light: "#4C6E99", dark: "#88C0D0" },
  verde: { light: "#4A7337", dark: "#A3BE8C" },
  morado: { light: "#7A5783", dark: "#B48EAD" },
  naranja: { light: "#9C5536", dark: "#D08770" },
  rojo: { light: "#A3414B", dark: "#D9848B" },
};

/** How much each corner style scales the radius tokens: Mochy's superellipse by default, rounder, or nearly square. */
export const CORNER_SCALE: Record<Corners, number> = { continuas: 1, redondas: 1.4, cuadradas: 0.35 };
