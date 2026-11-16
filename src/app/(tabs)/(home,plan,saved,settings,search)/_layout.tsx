import { TabStack } from "@/components/tab-stack";
import { useT } from "@/i18n";

/** A stop or a route opened from a link still has its tab's first screen under it, so there is always a way back. */
export const unstable_settings = {
  anchor: "index",
  plan: { anchor: "plan" },
  saved: { anchor: "saved" },
  settings: { anchor: "settings" },
  search: { anchor: "search" },
};

/** Every tab is a stack of its own, and a stop or a route opens inside it: the tabs stay in reach however deep the rider goes. */
export default function Layout({ segment }: { segment: string }) {
  const tabs = useT().tabs;
  if (segment === "(plan)") return <TabStack root="plan" title={tabs.plan} />;
  if (segment === "(saved)") return <TabStack root="saved" title={tabs.saved} />;
  if (segment === "(settings)") return <TabStack root="settings" title={tabs.settings} />;
  if (segment === "(search)") return <TabStack root="search" title={tabs.search} />;
  return <TabStack root="index" />;
}
