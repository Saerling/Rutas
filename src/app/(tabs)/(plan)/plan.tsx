import * as Haptics from "expo-haptics";
import * as WebBrowser from "expo-web-browser";
import { useDeferredValue, useEffect, useMemo, useState } from "react";
import { Pressable, ScrollView, StyleSheet, TextInput, View } from "react-native";
import Animated from "react-native-reanimated";
import { toast } from "sonner-native";

import { Chip } from "@/components/chip";
import { Icon, icons, type IconName } from "@/components/icon";
import { ItineraryCard, strengths } from "@/components/itinerary-card";
import { SkeletonList } from "@/components/skeleton";
import { Empty } from "@/components/state";
import { Text } from "@/components/text";
import { useT } from "@/i18n";
import { announce } from "@/lib/accessibility";
import { clock } from "@/lib/format";
import { useLocation } from "@/lib/location";
import { remember, setSlot, useMyPlaces, usePlaces, usePlan, type MyPlace } from "@/lib/trip";
import { usePalette } from "@/theme/colors";
import { motion } from "@/theme/motion";
import { fonts, layout, radius, size, space, type } from "@/theme/tokens";
import type { Place } from "@/transit/plan";

type End = "from" | "to";
/** What the search field is filling: one end of the trip, or a saved slot. */
type Editing = End | "home" | "work" | null;

const LATER = [15, 30, 60];
/** Parts of the day a rider plans ahead for: the morning commute, midday, and the evening rush home. */
const SLOTS = { morning: 7, afternoon: 13, evening: 18 } as const;
type Slot = keyof typeof SLOTS;

/** The next time each part of the day starts: today if it is still ahead, otherwise tomorrow. */
function slotTimes(now: Date): { slot: Slot; at: string; tomorrow: boolean }[] {
  return (Object.keys(SLOTS) as Slot[]).map((slot) => {
    const at = new Date(now);
    at.setHours(SLOTS[slot], 0, 0, 0);
    const tomorrow = at.getTime() <= now.getTime() + 15 * 60_000;
    if (tomorrow) at.setDate(at.getDate() + 1);
    return { slot, at: at.toISOString(), tomorrow };
  });
}
const SOURCES = "https://transitous.org/sources/";

export default function Plan() {
  const p = usePalette();
  const t = useT();
  const { state: here } = useLocation();
  const mine = useMyPlaces();
  const home = mine.find((m) => m.slot === "home");
  const work = mine.find((m) => m.slot === "work");
  const recent = mine.filter((m) => m.slot === "recent");

  // A null origin means "where I am".
  const [from, setFrom] = useState<Place | null>(null);
  const [to, setTo] = useState<Place | null>(null);
  const [editing, setEditing] = useState<Editing>("to");
  const [text, setText] = useState("");
  const query = useDeferredValue(text);
  const origin = useMemo(() => from ?? (here.status === "ready" ? { lat: here.lat, lon: here.lon } : null), [from, here]);

  const [later, setLater] = useState<number | Slot>(0);
  const [slots, setSlots] = useState(() => slotTimes(new Date()));
  const [arriveBy, setArriveBy] = useState(false);
  const [wheelchair, setWheelchair] = useState(false);
  const [fewerTransfers, setFewerTransfers] = useState(false);
  // The departure time is fixed when the rider picks it, so the plan does not shift under them as the clock ticks.
  const [time, setTime] = useState<string | null>(null);

  const plan = usePlan(editing ? null : origin, editing ? null : to, { time, arriveBy, wheelchair, fewerTransfers });
  const found = usePlaces(editing ? query : "", origin);

  useEffect(() => {
    if (plan.isError) announce(t.plan.failed.title);
    else if (plan.data) announce(plan.data.itineraries.length ? `${t.plan.results}: ${plan.data.itineraries.length}` : t.plan.none.title);
  }, [plan.data, plan.isError, t]);

  const pick = (place: Place) => {
    void Haptics.selectionAsync();
    if (editing === "home" || editing === "work") {
      setSlot(editing, place);
      toast.success(t.plan.saved(editing === "home" ? t.plan.home : t.plan.work));
      setEditing("to");
    } else {
      remember(place);
      if (editing === "from") setFrom(place);
      else setTo(place);
      setEditing(null);
    }
    setText("");
  };

  const goTo = (place: MyPlace | undefined, slot: "home" | "work") => {
    if (!place) {
      setEditing(slot);
      setText("");
      return;
    }
    void Haptics.selectionAsync();
    setTo(place);
    setEditing(null);
  };

  const swap = () => {
    void Haptics.selectionAsync();
    if (!to) return;
    const there = to;
    // Swapping "where I am" makes it the destination; only a real place can be.
    if (from) setTo(from);
    else if (here.status === "ready") setTo({ id: "here", name: t.plan.here, area: null, kind: "place", lat: here.lat, lon: here.lon });
    setFrom(there);
  };

  const pickTime = (minutes: number) => {
    void Haptics.selectionAsync();
    setLater(minutes);
    setTime(minutes ? new Date(Date.now() + minutes * 60_000).toISOString() : null);
  };

  const pickSlot = (slot: Slot) => {
    void Haptics.selectionAsync();
    const fresh = slotTimes(new Date());
    setSlots(fresh);
    setLater(slot);
    setTime(fresh.find((x) => x.slot === slot)!.at);
  };

  const fromLabel = from?.name ?? t.plan.here;
  const sorted = plan.data ? [...plan.data.itineraries].sort((a, b) => Date.parse(a.end) - Date.parse(b.end)) : [];
  const tags = strengths(sorted);

  return (
    <ScrollView contentInsetAdjustmentBehavior="automatic" keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag" contentContainerStyle={styles.content}>
      <View style={[styles.ends, { backgroundColor: p.surface }]}>
        <EndRow icon={icons.location} label={t.plan.from} value={fromLabel} active={editing === "from"} onPress={() => setEditing(editing === "from" ? null : "from")} />
        <View style={[styles.rule, { backgroundColor: p.separator }]} />
        <EndRow icon={icons.pin} label={t.plan.to} value={to?.name ?? t.plan.title} active={editing === "to"} onPress={() => setEditing(editing === "to" ? null : "to")} />
        {to && (
          <Pressable accessibilityRole="button" accessibilityLabel={t.plan.swap} onPress={swap} hitSlop={space[3]} style={[styles.swap, { backgroundColor: p.background, borderColor: p.separator }]}>
            <Icon name={icons.swap} color={p.text} size="sm" />
          </Pressable>
        )}
      </View>

      {editing && (
        <Animated.View entering={motion.appear()} style={styles.section}>
          <View style={[styles.field, { backgroundColor: p.surface }]}>
            <Icon name={icons.search} color={p.text} size="md" weight="bold" />
            <TextInput
              autoFocus
              value={text}
              onChangeText={setText}
              placeholder={editing === "home" ? t.plan.addHome : editing === "work" ? t.plan.addWork : t.plan.placeholder}
              placeholderTextColor={p.faint}
              autoCorrect={false}
              returnKeyType="search"
              accessibilityLabel={t.plan.placeholder}
              style={[styles.input, { color: p.text }]}
            />
            {text.length > 0 && (
              <Pressable accessibilityRole="button" accessibilityLabel={t.common.clear} onPress={() => setText("")} hitSlop={space[3]}>
                <Icon name={icons.close} color={p.faint} size="md" />
              </Pressable>
            )}
          </View>

          {query.trim().length < 2 ? (
            <View>
              {editing === "from" && here.status === "ready" && (
                <PlaceRow
                  icon={icons.location}
                  name={t.plan.here}
                  onPress={() => {
                    setFrom(null);
                    setEditing(null);
                  }}
                />
              )}
              {(editing === "from" || editing === "to") && (
                <>
                  <PlaceRow icon={icons.home} name={home?.name ?? t.plan.addHome} area={home ? t.plan.home : null} onPress={() => (home ? pick(home) : goTo(undefined, "home"))} />
                  <PlaceRow icon={icons.work} name={work?.name ?? t.plan.addWork} area={work ? t.plan.work : null} onPress={() => (work ? pick(work) : goTo(undefined, "work"))} />
                </>
              )}
              {recent.length > 0 && (
                <Text variant="eyebrow" tone="faint" accessibilityRole="header" style={styles.label}>
                  {t.plan.recent}
                </Text>
              )}
              {recent.map((r) => (
                <PlaceRow key={r.id} icon={icons.history} name={r.name} area={r.area} onPress={() => pick(r)} />
              ))}
            </View>
          ) : found.isFetching && !found.data ? (
            <SkeletonList rows={4} />
          ) : found.data && found.data.length === 0 ? (
            <Empty title={t.plan.noPlaces(query.trim())} />
          ) : (
            found.data?.map((place) => (
              <PlaceRow key={place.id} icon={place.kind === "stop" ? icons.station : icons.pin} name={place.name} area={place.area} onPress={() => pick(place)} />
            ))
          )}
        </Animated.View>
      )}

      {!editing && !to && (
        <View style={styles.shortcuts}>
          <Shortcut icon={icons.home} label={home ? t.plan.home : t.plan.addHome} onPress={() => goTo(home, "home")} />
          <Shortcut icon={icons.work} label={work ? t.plan.work : t.plan.addWork} onPress={() => goTo(work, "work")} />
        </View>
      )}

      {!editing && to && (
        <>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
            <Chip label={t.plan.now} selected={later === 0} onPress={() => pickTime(0)} />
            {LATER.map((m) => (
              <Chip key={m} label={t.plan.inMinutes(m)} selected={later === m} onPress={() => pickTime(m)} />
            ))}
            {slots.map(({ slot, at, tomorrow }) => {
              const label = t.plan.slot((tomorrow ? t.plan.tomorrowSlots : t.plan.slots)[slot], clock(at));
              return <Chip key={slot} label={label} selected={later === slot} onPress={() => pickSlot(slot)} />;
            })}
            {later !== 0 && <Chip label={t.plan.arriveBy} selected={arriveBy} onPress={() => setArriveBy(!arriveBy)} />}
            <Chip label={t.plan.fewerTransfers} selected={fewerTransfers} onPress={() => setFewerTransfers(!fewerTransfers)} />
            <Chip label={t.plan.accessible} selected={wheelchair} onPress={() => setWheelchair(!wheelchair)} />
          </ScrollView>

          {!origin && <Empty title={t.plan.needOrigin} />}
          {origin && plan.isFetching && !plan.data && <SkeletonList rows={3} />}
          {plan.isError && <Empty title={t.plan.failed.title} body={t.plan.failed.body} action={t.common.retry} onAction={() => void plan.refetch()} />}
          {plan.data && sorted.length === 0 && !plan.data.walk && <Empty title={t.plan.none.title} body={t.plan.none.body} />}

          {plan.data && (sorted.length > 0 || plan.data.walk) && (
            <View style={styles.results}>
              <Text variant="eyebrow" tone="faint" accessibilityRole="header">
                {t.plan.results}
              </Text>
              {sorted.map((it, i) => (
                <Animated.View key={it.id} entering={motion.enter(Math.min(i, 5) * 60)}>
                  <ItineraryCard it={it} fastest={i === 0} tags={tags.get(it.id)} />
                </Animated.View>
              ))}
              {plan.data.walk && <ItineraryCard it={plan.data.walk} />}
              <Pressable accessibilityRole="link" accessibilityLabel={t.plan.credit} onPress={() => void WebBrowser.openBrowserAsync(SOURCES)}>
                <Text variant="caption" tone="faint">
                  {t.plan.credit.split(". ")[0]}
                </Text>
              </Pressable>
            </View>
          )}
        </>
      )}
    </ScrollView>
  );
}

function EndRow({ icon, label, value, active, onPress }: { icon: IconName; label: string; value: string; active: boolean; onPress: () => void }) {
  const p = usePalette();
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={`${label}: ${value}`} accessibilityState={{ selected: active }} onPress={onPress} style={styles.end}>
      <Icon name={icon} color={active ? p.ink : p.muted} size="md" />
      <View style={styles.endText}>
        <Text variant="caption" tone="faint">
          {label}
        </Text>
        <Text variant="headline">{value}</Text>
      </View>
    </Pressable>
  );
}

function PlaceRow({ icon, name, area, onPress }: { icon: IconName; name: string; area?: string | null; onPress: () => void }) {
  const p = usePalette();
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={area ? `${name}, ${area}` : name} onPress={onPress} style={styles.place}>
      <Icon name={icon} color={p.muted} size="md" />
      <View style={styles.endText}>
        <Text variant="headline">{name}</Text>
        {area && (
          <Text variant="caption" tone="muted">
            {area}
          </Text>
        )}
      </View>
    </Pressable>
  );
}

function Shortcut({ icon, label, onPress }: { icon: IconName; label: string; onPress: () => void }) {
  const p = usePalette();
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} onPress={onPress} style={[styles.shortcut, { backgroundColor: p.surface }]}>
      <Icon name={icon} color={p.text} size="lg" />
      <Text variant="headline">{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: { paddingBottom: layout.tabBarClearance, gap: space[3] },
  ends: { marginHorizontal: layout.gutter, marginTop: space[2], borderRadius: radius.lg, borderCurve: "continuous", paddingHorizontal: space[4] },
  end: { flexDirection: "row", alignItems: "center", gap: space[3], paddingVertical: space[3], paddingRight: size.control.md },
  endText: { flex: 1, gap: space[0.5] },
  rule: { height: size.line.hair, marginLeft: space[8] },
  swap: {
    position: "absolute",
    right: space[3],
    top: "50%",
    marginTop: -size.control.sm / 2,
    width: size.control.sm,
    height: size.control.sm,
    borderRadius: radius.pill,
    borderWidth: size.line.hair,
    alignItems: "center",
    justifyContent: "center",
  },
  section: { gap: space[1] },
  field: {
    marginHorizontal: layout.gutter,
    flexDirection: "row",
    alignItems: "center",
    gap: space[3],
    paddingHorizontal: space[5],
    minHeight: size.control.lg,
    borderRadius: radius.lg,
  },
  input: { flex: 1, fontFamily: fonts[400], fontSize: type.body.fontSize, paddingVertical: space[3] },
  label: { paddingHorizontal: layout.gutter, paddingTop: space[3], paddingBottom: space[1] },
  place: { flexDirection: "row", alignItems: "center", gap: space[3], paddingHorizontal: layout.gutter, paddingVertical: space[3], minHeight: size.control.md },
  shortcuts: { flexDirection: "row", gap: space[3], paddingHorizontal: layout.gutter },
  shortcut: { flex: 1, gap: space[2], padding: space[4], borderRadius: radius.lg, borderCurve: "continuous" },
  chips: { gap: space[2], paddingHorizontal: layout.gutter, alignItems: "center" },
  results: { gap: space[3], paddingHorizontal: layout.gutter },
});
