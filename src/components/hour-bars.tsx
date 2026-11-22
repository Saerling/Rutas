import { useMemo } from "react";
import { StyleSheet, View } from "react-native";
import Animated from "react-native-reanimated";

import { useT } from "@/i18n";
import { useAccessibility } from "@/lib/accessibility";
import { usePalette } from "@/theme/colors";
import { motion } from "@/theme/motion";
import { layout, radius, size, space } from "@/theme/tokens";

import { Text } from "./text";

/** Counts departures ("HH:MM") per hour of the day, keeping only the hours the route runs. */
export function perHour(departures: string[]): { hour: number; count: number }[] {
  const counts = new Map<number, number>();
  for (const d of departures) {
    const hour = Number(d.slice(0, 2));
    if (Number.isFinite(hour)) counts.set(hour, (counts.get(hour) ?? 0) + 1);
  }
  const hours = [...counts.keys()].sort((a, b) => a - b);
  if (!hours.length) return [];
  const out = [];
  for (let h = hours[0]; h <= hours[hours.length - 1]; h++) out.push({ hour: h, count: counts.get(h) ?? 0 });
  return out;
}

const hh = (h: number) => `${String(h).padStart(2, "0")}:00`;

/**
 * When the route leaves most often, from today's published timetable. One bar per hour; the busiest is solid.
 * Screen readers hear one sentence with the busiest hour instead of a row of bars.
 */
export function HourBars({ departures }: { departures: string[] }) {
  const p = usePalette();
  const t = useT();
  const { reduceMotion } = useAccessibility();
  const bars = useMemo(() => perHour(departures), [departures]);
  if (bars.length < 2) return null;

  const max = Math.max(...bars.map((b) => b.count));
  const peak = bars.find((b) => b.count === max)!;
  const now = new Date().getHours();
  const label = `${t.route.busiest}. ${t.route.peak(hh(peak.hour), peak.count)}. ${t.route.byHour}`;

  return (
    <View accessible accessibilityRole="summary" accessibilityLabel={label} style={styles.wrap}>
      <Text variant="title">{t.route.busiest}</Text>
      <View style={styles.chart}>
        {bars.map((b, i) => (
          <Animated.View
            key={b.hour}
            entering={reduceMotion ? undefined : motion.enter(Math.min(i, 12) * 20)}
            style={[
              styles.bar,
              {
                height: Math.max(size.line.rule, Math.round((b.count / max) * layout.chartHeight)),
                backgroundColor: b === peak ? p.ink : b.hour === now ? p.muted : p.separator,
              },
            ]}
          />
        ))}
      </View>
      <View style={styles.axis}>
        <Text variant="caption" tone="faint" tabular>
          {hh(bars[0].hour)}
        </Text>
        <Text variant="caption" tone="muted" tabular>
          {t.route.peak(hh(peak.hour), peak.count)}
        </Text>
        <Text variant="caption" tone="faint" tabular>
          {hh(bars[bars.length - 1].hour)}
        </Text>
      </View>
      <Text variant="caption" tone="faint">
        {t.route.byHour}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space[2] },
  chart: { flexDirection: "row", alignItems: "flex-end", gap: space[0.5], height: layout.chartHeight },
  bar: { flex: 1, borderRadius: radius.xs, borderCurve: "continuous" },
  axis: { flexDirection: "row", justifyContent: "space-between", gap: space[2], flexWrap: "wrap" },
});
