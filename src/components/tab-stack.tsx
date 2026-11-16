import { Stack } from "expo-router";

import { useAccessibility } from "@/lib/accessibility";
import { usePalette } from "@/theme/colors";
import { motion } from "@/theme/motion";
import { fonts } from "@/theme/tokens";

const DETAIL = { headerLargeTitle: false, headerTransparent: true, title: "" } as const;

/** The stack inside each tab: its first screen under a large Geist title, or bare when it has none, and the stops and routes opened from it. */
export function TabStack({ root, title }: { root: string; title?: string }) {
  const p = usePalette();
  const { reduceMotion } = useAccessibility();
  return (
    <Stack
      screenOptions={{
        // Mochy-like: the new screen slides in over a parallax of the old one; off when animations are off.
        animation: reduceMotion ? "none" : "ios_from_right",
        animationDuration: motion.ms(380),
        contentStyle: { backgroundColor: p.background },
        headerLargeTitle: true,
        headerTransparent: process.env.EXPO_OS === "ios",
        headerShadowVisible: false,
        headerBackButtonDisplayMode: "minimal",
        headerBackButtonMenuEnabled: false,
        headerLargeTitleStyle: { fontFamily: fonts[600], color: p.text },
        headerTitleStyle: { fontFamily: fonts[600], color: p.text },
      }}>
      <Stack.Screen name={root} options={title ? { title } : { headerShown: false, title: "Rutas" }} />
      <Stack.Screen name="stop/[id]" options={DETAIL} />
      <Stack.Screen name="route/[id]" options={DETAIL} />
      <Stack.Screen name="trip/[id]" options={DETAIL} />
    </Stack>
  );
}
