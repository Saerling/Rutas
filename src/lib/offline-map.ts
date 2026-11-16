import { OfflineManager, type OfflinePack } from "@maplibre/maplibre-react-native";
import { useCallback, useEffect, useState } from "react";

import { MAP_STYLES } from "@/components/transit-map.types";

/** Bogotá and Soacha, with the portals at the edges: what a rider needs with no signal. */
const BOGOTA_BOUNDS: [number, number, number, number] = [-74.25, 4.45, -73.98, 4.85];
const MIN_ZOOM = 10;
const MAX_ZOOM = 16;

export type OfflineState =
  | { status: "loading" }
  | { status: "none" }
  | { status: "downloading"; percent: number }
  | { status: "ready"; bytes: number }
  | { status: "failed" };

const name = (style: "light" | "dark") => `bogota-${style}`;

async function packs(): Promise<OfflinePack[]> {
  const all = await OfflineManager.getPacks();
  return all.filter((p) => typeof p.metadata?.name === "string" && String(p.metadata.name).startsWith("bogota-"));
}

/**
 * Downloads the light and dark maps of Bogotá so the map keeps working in the subway of a station, a tunnel or with no data.
 * The packs live in MapLibre's own database on the phone; the map uses them on its own when the network is gone.
 */
export function useOfflineMap() {
  const [state, setState] = useState<OfflineState>({ status: "loading" });

  const refresh = useCallback(async () => {
    try {
      const found = await packs();
      if (found.length === 0) return setState({ status: "none" });
      const statuses = await Promise.all(found.map((p) => p.status()));
      const done = statuses.every((s) => s.state === "complete");
      const bytes = statuses.reduce((sum, s) => sum + s.completedResourceSize, 0);
      const percent = statuses.reduce((sum, s) => sum + s.percentage, 0) / Math.max(1, statuses.length);
      setState(done ? { status: "ready", bytes } : { status: "downloading", percent });
      if (!done) found.forEach((p) => void p.resume());
    } catch {
      setState({ status: "none" });
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => void refresh(), 0);
    return () => clearTimeout(timer);
  }, [refresh]);

  const download = useCallback(async () => {
    setState({ status: "downloading", percent: 0 });
    OfflineManager.setTileCountLimit(20_000);
    OfflineManager.setProgressEventThrottle(500);
    const progress = new Map<string, number>();
    try {
      for (const style of ["light", "dark"] as const) {
        await OfflineManager.createPack(
          { mapStyle: MAP_STYLES[style], bounds: BOGOTA_BOUNDS, minZoom: MIN_ZOOM, maxZoom: MAX_ZOOM, metadata: { name: name(style) } },
          (pack, status) => {
            progress.set(pack.id, status.percentage);
            const percent = [...progress.values()].reduce((a, b) => a + b, 0) / 2;
            if (status.state === "complete" && progress.size === 2 && percent >= 99.9) void refresh();
            else setState({ status: "downloading", percent });
          },
          () => setState({ status: "failed" }),
        );
      }
    } catch {
      setState({ status: "failed" });
    }
  }, [refresh]);

  const remove = useCallback(async () => {
    const found = await packs();
    await Promise.all(found.map((p) => OfflineManager.deletePack(p.id)));
    setState({ status: "none" });
  }, []);

  return { state, download, remove };
}
