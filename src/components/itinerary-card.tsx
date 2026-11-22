import * as Haptics from "expo-haptics";
import { router } from "expo-router";
import { PressableScale } from "pressto";
import { StyleSheet, View } from "react-native";

import { useT } from "@/i18n";
import { clock, money } from "@/lib/format";
import { signal, useAccent, usePalette, useRadius } from "@/theme/colors";
import { radius, size, space } from "@/theme/tokens";
import { estimateFare, type Itinerary } from "@/transit/plan";

import { Dot } from "./dot";
import { Icon, icons } from "./icon";
import { RouteBadge } from "./route-badge";
import { Text } from "./text";

/** One way to get there, read the way Moovit shows it: how long, the chain of buses, when you leave and arrive, and how much you walk. */
export type Strength = "fastest" | "fewestTransfers" | "leastWalking";

/** What each option does best among the ones shown, like Vía tags its routes. Only a real difference earns a tag. */
export function strengths(all: Itinerary[]): Map<string, Strength[]> {
  const out = new Map<string, Strength[]>(all.map((it) => [it.id, []]));
  if (all.length < 2) return out;
  const best = (score: (it: Itinerary) => number, tag: Strength) => {
    const values = all.map(score);
    const min = Math.min(...values);
    if (values.every((v) => v === min)) return;
    const winners = all.filter((it) => score(it) === min);
    if (winners.length === 1) out.get(winners[0].id)!.push(tag);
  };
  best((it) => Date.parse(it.end), "fastest");
  best((it) => it.transfers, "fewestTransfers");
  best((it) => Math.round(it.walkMetres / 100), "leastWalking");
  return out;
}

export function ItineraryCard({ it, fastest, tags = [] }: { it: Itinerary; fastest?: boolean; tags?: Strength[] }) {
  const p = usePalette();
  const r = useRadius();
  const { accent } = useAccent();
  const t = useT();
  const duration = t.plan.duration(it.minutes);
  const transfers = it.legs.some((l) => l.mode === "transit") ? t.plan.transfers(it.transfers) : t.plan.walkOnly;
  const walk = t.plan.walk(it.walkMetres);
  const window = t.plan.window(clock(it.start), clock(it.end));
  const firstRide = it.legs.find((l) => l.mode === "transit");
  const fare = estimateFare(it);

  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={[...tags.map((k) => t.plan.tags[k]), t.plan.option(duration, transfers, walk, window)].join(". ")}
      onPress={() => {
        void Haptics.selectionAsync();
        router.push({ pathname: "/trip/[id]", params: { id: it.id } });
      }}
      style={[styles.card, { borderRadius: r.xl, backgroundColor: p.surface, borderColor: fastest ? accent : p.separator, borderWidth: fastest ? size.line.rule : size.line.hair }]}>
      {tags.length > 0 && (
        <View style={styles.tags} importantForAccessibility="no-hide-descendants">
          {tags.map((k) => (
            <View key={k} style={[styles.tag, { borderColor: k === "fastest" ? accent : p.separator }]}>
              <Text variant="eyebrow" tone={k === "fastest" ? "default" : "muted"}>
                {t.plan.tags[k]}
              </Text>
            </View>
          ))}
        </View>
      )}
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
          {fare ? `${transfers} · ${walk} · ${t.plan.fare(money(fare))}` : `${transfers} · ${walk}`}
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
  tags: { flexDirection: "row", flexWrap: "wrap", gap: space[1.5] },
  tag: { paddingHorizontal: space[2], paddingVertical: space[0.5], borderRadius: radius.pill, borderWidth: size.line.hair },
});
