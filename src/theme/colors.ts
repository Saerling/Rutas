import { useColorScheme } from "react-native";

import { radius as baseRadius } from "./tokens";

import { useAccessibility } from "@/lib/accessibility";
import { useContrast } from "@/lib/appearance";
import { ACCENTS, CORNER_SCALE, usePersonalization } from "@/lib/personalize";

import { DARK, HIGH_DARK, HIGH_LIGHT, LIGHT, type Palette as Colors } from "./palettes";

export { photo, signal, systems } from "./palettes";

export const palettes = { light: LIGHT, dark: DARK, highLight: HIGH_LIGHT, highDark: HIGH_DARK } as const;

export type Palette = Colors & { dark: boolean; high: boolean };

/** The colours for this moment: light or dark, and high contrast when the phone or the rider asks for it. */
export function usePalette(): Palette {
  const scheme = useColorScheme();
  const contrast = useContrast();
  const device = useAccessibility();
  const dark = scheme === "dark";
  const high = contrast === "high" || device.moreContrast;
  const { black } = usePersonalization();
  const base = high ? (dark ? HIGH_DARK : HIGH_LIGHT) : dark ? DARK : LIGHT;
  // Negro puro: for OLED screens, the background goes to true black and surfaces step up from it.
  const deep = dark && black && !high ? { background: "#000000", elevated: "#0D0D0D", surface: "#141414", separator: "#1F1F1F", border: "#1F1F1F" } : null;
  return { ...base, ...deep, dark, high };
}

/** The accent the rider picked, with the colour that reads on it. In high contrast or "mono" it is the ink itself. */
export function useAccent(): { accent: string; onAccent: string } {
  const p = usePalette();
  const { accent } = usePersonalization();
  if (accent === "mono" || p.high) return { accent: p.ink, onAccent: p.onInk };
  return { accent: ACCENTS[accent][p.dark ? "dark" : "light"], onAccent: p.dark ? "#0B0B0C" : "#FFFFFF" };
}

/** Radius tokens scaled by the corner style in Personalización. */
export function useRadius(): typeof baseRadius {
  const { corners } = usePersonalization();
  const k = CORNER_SCALE[corners];
  if (k === 1) return baseRadius;
  return Object.fromEntries(Object.entries(baseRadius).map(([key, v]) => [key, key === "pill" ? (corners === "cuadradas" ? baseRadius.md * k : v) : Math.round(v * k)])) as typeof baseRadius;
}
