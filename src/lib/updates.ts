import * as Application from "expo-application";
import Storage from "expo-sqlite/kv-store";
import { useEffect } from "react";
import { Alert, Linking, Platform } from "react-native";

import { t } from "@/i18n";

/**
 * Updates come from the GitHub Releases of the repo, the same way as the other apps of this author:
 * every push to main builds a signed APK tagged v0.1.<build>, and the app compares that build number with its own.
 * The APK is signed with the same key every time, so Android installs it over the current one and keeps the rider's data.
 */
const REPO = "Saerling/Rutas";
const LAST_CHECK = "updates.lastCheck.v1";
const DAY = 24 * 60 * 60_000;

export interface Release {
  build: number;
  tag: string;
  apk: string;
  notes: string;
}

export function currentBuild(): number {
  return Number(Application.nativeBuildVersion ?? 0) || 0;
}

export async function latestRelease(): Promise<Release | null> {
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { headers: { Accept: "application/vnd.github+json" } });
    if (!res.ok) return null;
    const json = (await res.json()) as { tag_name: string; body?: string; assets: { name: string; browser_download_url: string }[] };
    const apk = json.assets.find((a) => a.name.endsWith(".apk"));
    const build = Number(json.tag_name.split(".").pop());
    if (!apk || !Number.isFinite(build)) return null;
    return { build, tag: json.tag_name, apk: apk.browser_download_url, notes: json.body ?? "" };
  } catch {
    return null;
  }
}

/** Asks GitHub for a newer build and offers it. Quiet when there is nothing new, unless the rider asked. */
export async function checkForUpdate(asked = false): Promise<void> {
  if (Platform.OS !== "android") return;
  Storage.setItemSync(LAST_CHECK, String(Date.now()));
  const release = await latestRelease();
  const m = t();
  if (!release) {
    if (asked) Alert.alert(m.updates.failed);
    return;
  }
  if (release.build <= currentBuild()) {
    if (asked) Alert.alert(m.updates.upToDate);
    return;
  }
  Alert.alert(m.updates.available(release.tag), release.notes.slice(0, 400) || m.updates.body, [
    { text: m.updates.later, style: "cancel" },
    { text: m.updates.install, onPress: () => void Linking.openURL(release.apk) },
  ]);
}

/** Once a day at most, when the app opens. */
export function useUpdateCheck() {
  useEffect(() => {
    const last = Number(Storage.getItemSync(LAST_CHECK) ?? 0);
    if (Date.now() - last < DAY) return;
    const timer = setTimeout(() => void checkForUpdate(), 3_000);
    return () => clearTimeout(timer);
  }, []);
}
