import { useEffect } from "react";

import { useT, type Messages } from "@/i18n";
import { useStop } from "@/lib/api";
import { clock } from "@/lib/format";
import { useSaved, type SavedStop } from "@/lib/saved";
import type { StopDetail } from "@/transit/types";
import widget from "@/widgets/next-bus";
import type { NextBusProps } from "@/widgets/next-bus.types";

/** How many minutes ahead the iOS widget counts down on its own before it needs the app again. */
const COUNTDOWN = 15;

function when(t: Messages, minutes: number | null): string {
  if (minutes == null) return "–";
  return minutes <= 0 ? t.time.arriving : `${minutes} ${t.time.min}`;
}

/** The widget's words for one moment, `shift` minutes after the data was fetched. */
export function widgetProps(
  t: Messages,
  name: string | null,
  data: StopDetail | undefined,
  shift = 0,
): NextBusProps {
  const empty = t.widget.empty;
  if (!name) return { stop: "Rutas", rows: [], footer: "", empty };
  const firstOf = new Map<string, number | null>();
  for (const a of data?.arrivals ?? [])
    if (!firstOf.has(a.code)) firstOf.set(a.code, a.minutes);
  const rows = [...firstOf]
    .slice(0, 3)
    .map(([code, m]) => ({
      code,
      when: when(t, m == null ? null : Math.max(0, m - shift)),
    }));
  // The footer always says how old the times are, so a widget that has not refreshed in a while never passes for live.
  const footer = data
    ? `${data.feed.live ? t.feed.live : t.feed.scheduled} · ${clock(data.feed.asOf)}`
    : "";
  return { stop: name, rows, footer, empty: data ? t.widget.none : empty };
}

/** Keeps the home-screen widget showing the first saved stop. */
export function useWidgetSync() {
  const t = useT();
  const first = useSaved().find((s): s is SavedStop => s.type === "stop");
  const { data } = useStop(first?.id);
  const name = first ? (first.nickname ?? first.name) : null;

  useEffect(() => {
    try {
      widget.updateSnapshot(widgetProps(t, name, data));
      if (process.env.EXPO_OS === "ios" && data) {
        const start = Date.now();
        widget.updateTimeline(
          Array.from({ length: COUNTDOWN }, (_, k) => ({
            date: new Date(start + k * 60_000),
            props: widgetProps(t, name, data, k),
          })),
        );
      }
    } catch {
      // A build without the widget extension (Expo Go, old builds) has nothing to update.
    }
  }, [t, name, data]);
}
