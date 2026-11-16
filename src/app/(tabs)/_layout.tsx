import { BlurTargetView } from "expo-blur";
import { TabList, TabSlot, TabTrigger, Tabs } from "expo-router/ui";
import { useRef, type ComponentRef } from "react";
import { StyleSheet, View } from "react-native";

import { icons } from "@/components/icon";
import { TabBar, TabButton } from "@/components/tab-bar";
import { useT } from "@/i18n";
import { useCardShortcut } from "@/lib/tullave";
import { usePalette } from "@/theme/colors";

/** Five tabs, with Ajustes in the corner like in Shiori. The routes are declared in a hidden list; the bar drawn on top is ours. */
const TABS = [
  { name: "(home)", href: "/", icon: icons.location, key: "nearby" },
  { name: "(plan)", href: "/plan", icon: icons.directions, key: "plan" },
  { name: "(search)", href: "/search", icon: icons.search, key: "search" },
  { name: "(saved)", href: "/saved", icon: icons.starFill, key: "saved" },
  { name: "(settings)", href: "/settings", icon: icons.settings, key: "settings" },
] as const;

export default function TabsLayout() {
  const p = usePalette();
  const t = useT();
  const behind = useRef<ComponentRef<typeof View>>(null);
  useCardShortcut();
  return (
    <Tabs style={{ flex: 1, backgroundColor: p.background }}>
      <BlurTargetView ref={behind} style={styles.fill}>
        <TabSlot />
      </BlurTargetView>
      <TabBar behind={behind}>
        {TABS.map((tab) => (
          <TabTrigger key={tab.name} name={tab.name} asChild>
            <TabButton icon={tab.icon} label={t.tabs[tab.key]} />
          </TabTrigger>
        ))}
      </TabBar>
      <TabList style={styles.hidden}>
        {TABS.map((tab) => (
          <TabTrigger key={tab.name} name={tab.name} href={tab.href as any} />
        ))}
      </TabList>
    </Tabs>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  hidden: { display: "none" },
});
