import * as Haptics from "expo-haptics";
import { useKeepAwake } from "expo-keep-awake";
import { useLocalSearchParams } from "expo-router";
import { useCallback, useMemo, useState } from "react";
import { StyleSheet, View } from "react-native";
import { toast } from "sonner-native";

import { Button } from "@/components/button";
import { DetailScreen } from "@/components/detail-screen";
import { Dot } from "@/components/dot";
import { Icon, icons } from "@/components/icon";
import { RouteBadge } from "@/components/route-badge";
import { Empty } from "@/components/state";
import { Text } from "@/components/text";
import { TransitMap } from "@/components/transit-map";
import { useT } from "@/i18n";
import { announce } from "@/lib/accessibility";
import { clock, money } from "@/lib/format";
import { remindBefore } from "@/lib/reminders";
import { keepItinerary, knownItinerary, useGuidance, type Guidance } from "@/lib/trip";
import { signal, usePalette } from "@/theme/colors";
import { layout, radius, size, space } from "@/theme/tokens";
import { estimateFare, type Leg } from "@/transit/plan";

export default function TripScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const t = useT();
  const p = usePalette();
  const [it] = useState(() => knownItinerary(id));
  const [on, setOn] = useState(false);
  const [openedAt] = useState(() => Date.now());

  const warn = useCallback(
    (leg: Leg) => {
      void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
      const message = t.plan.trip.getOffSoon(leg.to.name);
      toast.warning(t.plan.trip.getOffNext, { description: message });
      announce(`${t.plan.trip.getOffNext}. ${message}`);
    },
    [t],
  );
  const guidance = useGuidance(it, on, warn);

  const path = useMemo(() => it?.legs.flatMap((l) => l.path) ?? [], [it]);
  const marks = useMemo(
    () =>
      it?.legs
        .filter((l) => l.mode === "transit")
        .flatMap((l, i) => [
          { id: `on-${i}`, name: l.from.name, kind: "stop" as const, lat: l.from.lat, lon: l.from.lon },
          { id: `off-${i}`, name: l.to.name, kind: "stop" as const, lat: l.to.lat, lon: l.to.lon },
        ]) ?? [],
    [it],
  );

  if (!it) return <Empty title={t.plan.trip.expired.title} body={t.plan.trip.expired.body} />;
  const last = it.legs[it.legs.length - 1];

  const fare = estimateFare(it);
  const firstRide = it.legs.find((l) => l.mode === "transit");
  const window = t.plan.window(clock(it.start), clock(it.end));

  const remind = async () => {
    void Haptics.selectionAsync();
    const at = await remindBefore(it, t.plan.reminderTitle, t.plan.reminderBody(firstRide?.code ?? last.to.name));
    if (at) toast.success(t.plan.reminded(clock(at.toISOString())));
  };


  const toggle = () => {
    void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    if (!on) keepItinerary(it);
    setOn(!on);
    announce(on ? t.plan.trip.end : t.plan.trip.guiding);
  };

  return (
    <DetailScreen
      map={
        <TransitMap
          center={{ lat: it.legs[0].from.lat, lon: it.legs[0].from.lon }}
          zoom={13}
          stops={marks}
          path={path}
          showUser
          system={it.legs.find((l) => l.system)?.system ?? undefined}
          padding={layout.mapInset}
        />
      }>
      <View style={styles.header}>
        <Text variant="display" tabular accessibilityRole="header">
          {t.plan.duration(it.minutes)}
        </Text>
        <Text variant="callout" tone="muted" tabular>
          {`${window} · ${t.plan.transfers(it.transfers)} · ${t.plan.walk(it.walkMetres)}`}
        </Text>
        {fare > 0 && (
          <Text variant="caption" tone="muted" accessibilityLabel={`${t.plan.fare(money(fare))}. ${t.plan.fareNote}`}>
            {`${t.plan.fare(money(fare))} · ${t.plan.fareNote}`}
          </Text>
        )}
      </View>

      <Button label={on ? t.plan.trip.end : t.plan.trip.start} variant={on ? "quiet" : "primary"} onPress={toggle} />
      {on && <Live guidance={guidance} legs={it.legs} />}
      {!on && Date.parse(it.start) - openedAt > 6 * 60_000 && <Button variant="secondary" height="md" label={t.plan.remind} onPress={() => void remind()} />}

      <View>
        <Text variant="title" style={styles.heading}>
          {t.plan.trip.steps}
        </Text>
        {it.legs.map((leg, i) => {
          const at = on && "leg" in guidance ? guidance.leg : on && guidance.state === "arrived" ? it.legs.length : -1;
          const prev = it.legs[i - 1];
          const gap = prev ? Math.round((Date.parse(leg.start) - Date.parse(prev.end)) / 60_000) : 0;
          return (
            <View key={i}>
              {gap > 1 && <Gap minutes={gap} />}
              <Step leg={leg} state={i === at ? "now" : i < at ? "done" : "next"} />
            </View>
          );
        })}
        <View style={styles.step}>
          <Text variant="caption" tone="muted" tabular style={styles.time}>
            {clock(last.end)}
          </Text>
          <View style={styles.rail}>
            <Icon name={icons.flagEnd} color={p.text} size="md" />
          </View>
          <Text variant="headline" style={styles.body}>
            {t.plan.trip.arrive(last.to.name)}
          </Text>
        </View>
      </View>
    </DetailScreen>
  );
}

/** Keeps the screen awake while guiding, the way a rider holds the phone on a bus. */
function Live({ guidance, legs }: { guidance: Guidance; legs: Leg[] }) {
  useKeepAwake();
  const p = usePalette();
  const t = useT();
  const line =
    guidance.state === "denied"
      ? t.plan.trip.denied
      : guidance.state === "waiting"
        ? t.plan.trip.waiting
        : guidance.state === "arrived"
          ? t.plan.trip.arrived
          : guidance.state === "walking"
            ? `${t.plan.trip.walkTo(legs[guidance.leg].to.name)} · ${t.plan.trip.metresLeft(guidance.metresLeft)}`
            : guidance.getOffNext
              ? `${t.plan.trip.getOffNext}: ${legs[guidance.leg].to.name}`
              : `${t.plan.trip.stopsLeft(guidance.stopsLeft)} · ${t.plan.trip.getOff(legs[guidance.leg].to.name)}`;
  const urgent = guidance.state === "riding" && guidance.getOffNext;
  return (
    <View accessibilityLiveRegion="polite" style={[styles.live, { backgroundColor: urgent ? p.ink : p.surface }]}>
      <Dot color={signal.live} pulse={guidance.state === "riding" || guidance.state === "walking"} />
      <Text variant="headline" tone={urgent ? "inverse" : "default"} style={styles.body}>
        {line}
      </Text>
    </View>
  );
}

/** The wait between getting off one bus and the next one leaving, like a free slot in a day plan. */
function Gap({ minutes }: { minutes: number }) {
  const p = usePalette();
  const t = useT();
  return (
    <View style={styles.step}>
      <View style={styles.time} />
      <View style={styles.rail}>
        <View style={[styles.line, { backgroundColor: p.separator }]} />
      </View>
      <View style={[styles.body, styles.meta]}>
        <Icon name={icons.clock} color={p.faint} size="xs" />
        <Text variant="caption" tone="faint">
          {t.plan.trip.wait(minutes)}
        </Text>
      </View>
    </View>
  );
}

type StepState = "done" | "now" | "next";

/** One row of the timeline: the clock on the left, the rail, and what to do. The leg you are on is a solid card. */
function Step({ leg, state }: { leg: Leg; state: StepState }) {
  const p = usePalette();
  const t = useT();
  const now = state === "now";
  const done = state === "done";
  const tone = now ? "inverse" : done ? "faint" : "default";
  const sub = now ? "inverse" : done ? "faint" : "muted";
  const status = now ? `${t.plan.trip.now}. ` : done ? `${t.plan.trip.done}. ` : "";
  const strike = done ? styles.struck : undefined;

  const node =
    leg.mode === "transit" ? (
      <RouteBadge code={leg.code ?? "?"} system={leg.system ?? "other"} size="sm" />
    ) : (
      <Icon name={icons.walk} color={now ? p.text : done ? p.faint : p.muted} size="md" />
    );

  const lines =
    leg.mode === "transit"
      ? [t.plan.trip.ride(leg.code ?? "", leg.headsign), `${leg.from.name} · ${t.plan.trip.leaves(clock(leg.start))} · ${leg.live ? t.plan.live : t.plan.scheduled}`, t.plan.trip.stops(leg.stops.length + 1), t.plan.trip.getOff(leg.to.name)]
      : [t.plan.trip.walkTo(leg.to.name), `${t.plan.duration(leg.minutes)} · ${t.plan.walk(Math.round(leg.metres ?? 0))}`];

  return (
    <View style={styles.step} accessible accessibilityLabel={`${clock(leg.start)}. ${status}${lines.join(". ")}`}>
      <Text variant="caption" tone={done ? "faint" : "muted"} tabular style={styles.time}>
        {clock(leg.start)}
      </Text>
      <View style={styles.rail}>
        {node}
        <View style={[styles.line, { backgroundColor: p.separator }]} />
      </View>
      <View style={[styles.body, now && [styles.card, { backgroundColor: p.ink }]]}>
        {now && (
          <Text variant="eyebrow" tone="inverse">
            {t.plan.trip.now}
          </Text>
        )}
        <Text variant="headline" tone={tone} style={strike}>
          {lines[0]}
        </Text>
        {leg.mode === "transit" ? (
          <>
            <View style={styles.meta}>
              {leg.live && !done && <Dot color={signal.live} />}
              <Text variant="caption" tone={sub}>
                {lines[1]}
              </Text>
            </View>
            <Text variant="caption" tone={sub}>
              {lines[2]}
            </Text>
            <Text variant="headline" tone={tone} style={strike}>
              {lines[3]}
            </Text>
          </>
        ) : (
          <>
            <Text variant="caption" tone={sub}>
              {lines[1]}
            </Text>
            {!done &&
              leg.steps.slice(0, 8).map((s, i) => (
                <Text key={i} variant="caption" tone={sub}>
                  {t.plan.trip.on(t.plan.trip.turn[s.turn] ?? t.plan.trip.turn.CONTINUE, s.street, s.metres)}
                </Text>
              ))}
          </>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  header: { gap: space[1] },
  heading: { marginBottom: space[2] },
  live: { flexDirection: "row", alignItems: "center", gap: space[3], padding: space[4], borderRadius: radius.lg, borderCurve: "continuous" },
  step: { flexDirection: "row", gap: space[3] },
  time: { width: layout.timeColumn, paddingTop: space[1] },
  rail: { width: size.tile.md, alignItems: "center", gap: space[1] },
  line: { flex: 1, width: size.line.rule, minHeight: space[4] },
  body: { flex: 1, gap: space[1], paddingBottom: space[4] },
  card: { padding: space[4], marginBottom: space[3], borderRadius: radius.lg, borderCurve: "continuous" },
  struck: { textDecorationLine: "line-through" },
  meta: { flexDirection: "row", alignItems: "center", gap: space[1.5], flexWrap: "wrap" },
});
