import { NativeTabs } from "expo-router/native-tabs";
import { useT } from "@/i18n";
import { useCardShortcut } from "@/lib/tullave";
import { usePalette } from "@/theme/colors";
import { fonts } from "@/theme/tokens";

export default function TabsLayout() {
  const p = usePalette();
  const t = useT();
  useCardShortcut();
  return (
    <NativeTabs
      tintColor={p.ink}
      minimizeBehavior="onScrollDown"
      labelStyle={{ fontFamily: fonts[600] }}
      backgroundColor={process.env.EXPO_OS === "android" ? p.background : undefined}
      indicatorColor={p.fillStrong}
      labelVisibilityMode="labeled">
      <NativeTabs.Trigger name="(home)">
        <NativeTabs.Trigger.Label>{t.tabs.nearby}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon src={require("@/assets/images/tabs/tab-nearby.png")} renderingMode="template" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="(plan)">
        <NativeTabs.Trigger.Label>{t.tabs.plan}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: "arrow.triangle.turn.up.right.diamond", selected: "arrow.triangle.turn.up.right.diamond.fill" }} md="directions" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="(saved)">
        <NativeTabs.Trigger.Label>{t.tabs.saved}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: "star", selected: "star.fill" }} md="star" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="(settings)">
        <NativeTabs.Trigger.Label>{t.tabs.settings}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf={{ default: "gearshape", selected: "gearshape.fill" }} md="settings" />
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="(search)" role="search">
        <NativeTabs.Trigger.Label>{t.tabs.search}</NativeTabs.Trigger.Label>
        <NativeTabs.Trigger.Icon sf="magnifyingglass" md="search" />
      </NativeTabs.Trigger>
    </NativeTabs>
  );
}
