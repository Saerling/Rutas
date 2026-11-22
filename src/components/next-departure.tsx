import { useEffect, useState } from "react";
import { StyleSheet, View } from "react-native";

import { useT } from "@/i18n";
import { clock, spokenCode, spokenWhen } from "@/lib/format";
import { signal } from "@/theme/colors";
import { radius, space } from "@/theme/tokens";
import type { Leg } from "@/transit/plan";

import { Dot } from "./dot";
import { Glass } from "./glass";
import { Minutes } from "./minutes";
import { RouteBadge } from "./route-badge";
import { Text } from "./text";

function useNow(ms: number) {
  const [now, set] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => set(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

/**
 * The one thing a rider holding the bar needs at a glance: which bus is next and how long until it leaves.
 * It floats over the map so the timeline below can stay where the rider scrolled it.
 */
export function NextDeparture({ leg }: { leg: Leg }) {
  const t = useT();
  const now = useNow(15_000);
  const minutes = Math.max(0, Math.round((Date.parse(leg.start) - now) / 60_000));
  const code = leg.code ?? "";
  const basis = leg.live ? t.plan.live : t.plan.scheduled;
  const label = t.plan.trip.nextLabel(spokenCode(code), leg.from.name, spokenWhen(minutes), basis);

  return (
    <Glass style={styles.card}>
      <View accessible accessibilityLabel={label} accessibilityLiveRegion="polite" style={styles.row}>
        <RouteBadge code={code || "?"} system={leg.system ?? "other"} />
        <View style={styles.text}>
          <Text variant="eyebrow" tone="faint">
            {t.plan.trip.next}
          </Text>
          <Text variant="headline">{t.plan.trip.leavesFrom(leg.from.name)}</Text>
          <View style={styles.meta}>
            <Dot color={leg.live ? signal.live : signal.late} pulse={leg.live} />
            <Text variant="caption" tone="muted" tabular>
              {`${basis} · ${clock(leg.start)}`}
            </Text>
          </View>
        </View>
        <Minutes value={minutes} size="md" />
      </View>
    </Glass>
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: radius.lg, borderCurve: "continuous" },
  row: { flexDirection: "row", alignItems: "center", gap: space[3], padding: space[3] },
  text: { flex: 1, gap: space[0.5] },
  meta: { flexDirection: "row", alignItems: "center", gap: space[1.5], flexWrap: "wrap" },
});
