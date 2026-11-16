import * as Notifications from "expo-notifications";

import type { Itinerary } from "@/transit/plan";

Notifications.setNotificationHandler({
  handleNotification: async () => ({ shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false }),
});

const LEAD_MS = 5 * 60_000;

/** A local notification five minutes before the trip starts. It lives on the phone; nothing is sent to a server. Returns when it will ring. */
export async function remindBefore(it: Itinerary, title: string, body: string): Promise<Date | null> {
  const at = new Date(Date.parse(it.start) - LEAD_MS);
  if (at.getTime() <= Date.now()) return null;
  const { granted } = await Notifications.requestPermissionsAsync();
  if (!granted) return null;
  await Notifications.scheduleNotificationAsync({
    content: { title, body },
    trigger: { type: Notifications.SchedulableTriggerInputTypes.DATE, date: at },
  });
  return at;
}
