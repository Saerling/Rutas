import * as Haptics from "expo-haptics";
import { router } from "expo-router";
import { PressableScale } from "pressto";
import { StyleSheet, View } from "react-native";

import { useT } from "@/i18n";
import { clock } from "@/lib/format";
import { signal, usePalette } from "@/theme/colors";
import { radius, size, space } from "@/theme/tokens";
import type { Itinerary } from "@/transit/plan";

import { Dot } from "./dot";
import { Icon, icons } from "./icon";
import { RouteBadge } from "./route-badge";
import { Text } from "./text";

/** One way to get there, read the way Moovit shows it: how long, the chain of buses, when you leave and arrive, and how much you walk. */
export function ItineraryCard({ it, fastest }: { it: Itinerary; fastest?: boolean }) {
  const p = usePalette();
  const t = useT();
  const duration = t.plan.duration(it.minutes);
  const transfers = it.legs.some((l) => l.mode === "transit") ? t.plan.transfers(it.transfers) : t.plan.walkOnly;
  const walk = t.plan.walk(it.walkMetres);
  const window = t.plan.window(clock(it.start), clock(it.end));
  const firstRide = it.legs.find((l) => l.mode === "transit");

  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={t.plan.option(duration, transfers, walk, window)}
      onPress={() => {
        void Haptics.selectionAsync();
        router.push({ pathname: "/trip/[id]", params: { id: it.id } });
      }}
      style={[styles.card, { backgroundColor: p.surface, borderColor: fastest ? p.ink : p.separator }]}>
      <View style={styles.top}>
        <Text variant="title" tabular>
          {duration}
        </Text>
        <Text variant="caption" tone="muted" tabular>
          {window}
        </Text>
      </View>

      <View style={styles.chain} importantForAccessibility="no-hide-descendants">
        {it.legs.map((l, i) => (
          <View key={i} style={styles.link}>
            {i > 0 && <Icon name={icons.chevron} color={p.faint} size="xs" />}
            {l.mode === "transit" && l.code ? (
              <RouteBadge code={l.code} system={l.system ?? "other"} size="sm" />
            ) : (
              <View style={styles.walk}>
                <Icon name={icons.walk} color={p.muted} size="sm" />
                <Text variant="caption" tone="muted" tabular>
                  {l.minutes}
                </Text>
              </View>
            )}
          </View>
        ))}
      </View>

      <View style={styles.meta}>
        {firstRide && (
          <View style={styles.live}>
            {it.live && <Dot color={signal.live} />}
            <Text variant="caption" tone={it.live ? "default" : "muted"}>
              {`${t.plan.trip.leaves(clock(firstRide.start))} · ${it.live ? t.plan.live : t.plan.scheduled}`}
            </Text>
          </View>
        )}
        <Text variant="caption" tone="muted">
          {`${transfers} · ${walk}`}
        </Text>
      </View>
    </PressableScale>
  );
}

const styles = StyleSheet.create({
  card: { padding: space[4], gap: space[3], borderRadius: radius.lg, borderWidth: size.line.hair, borderCurve: "continuous" },
  top: { flexDirection: "row", justifyContent: "space-between", alignItems: "baseline", gap: space[2] },
  chain: { flexDirection: "row", flexWrap: "wrap", alignItems: "center", gap: space[1] },
  link: { flexDirection: "row", alignItems: "center", gap: space[1] },
  walk: { flexDirection: "row", alignItems: "center", gap: space[0.5] },
  meta: { gap: space[1] },
  live: { flexDirection: "row", alignItems: "center", gap: space[1.5] },
});
