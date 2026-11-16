import Constants from "expo-constants";
import { router } from "expo-router";
import * as WebBrowser from "expo-web-browser";
import type { ReactNode } from "react";
import { ScrollView, StyleSheet, Switch, View } from "react-native";

import { Button } from "@/components/button";
import { Chip } from "@/components/chip";
import { FeedStatus } from "@/components/feed-status";
import { Glass } from "@/components/glass";
import { Text } from "@/components/text";
import { LANGUAGES, setLanguage, useLanguageChoice, useT, type Language, type LanguageChoice } from "@/i18n";
import { useHealth } from "@/lib/api";
import { setAppearance, setContrast, useAppearance, useContrast, type Appearance, type Contrast } from "@/lib/appearance";
import { useOfflineMap } from "@/lib/offline-map";
import { ACCENTS, SPEEDS, setPersonalization, usePersonalization, type Accent, type Corners } from "@/lib/personalize";
import { checkForUpdate, currentBuild } from "@/lib/updates";
import { useAccent, usePalette, useRadius } from "@/theme/colors";
import { layout, radius, size, space } from "@/theme/tokens";

const REPO = "https://github.com/Saerling/Rutas";
const APPEARANCES: Appearance[] = ["system", "light", "dark"];
const CONTRASTS: Contrast[] = ["system", "high"];
const ACCENT_KEYS: Accent[] = [...(Object.keys(ACCENTS) as Accent[]), "mono"];
const CORNERS: Corners[] = ["continuas", "redondas", "cuadradas"];
const CHOICES: LanguageChoice[] = ["system", ...(Object.keys(LANGUAGES) as Language[])];

export default function Settings() {
  const t = useT();
  const health = useHealth();
  const appearance = useAppearance();
  const contrast = useContrast();
  const language = useLanguageChoice();
  const offline = useOfflineMap();
  const look = usePersonalization();
  const s = offline.state;

  return (
    <ScrollView contentInsetAdjustmentBehavior="automatic" contentContainerStyle={styles.content}>
      <Section title={t.personalize.title}>
        <Choices label={t.about.appearance.label}>
          {APPEARANCES.map((a) => (
            <Chip key={a} radio label={t.about.appearance[a]} accessibilityLabel={`${t.about.appearance[a]}, ${t.about.appearance.label}`} selected={appearance === a} onPress={() => setAppearance(a)} />
          ))}
        </Choices>
        <Choices label={t.personalize.accent}>
          {ACCENT_KEYS.map((a) => (
            <Chip key={a} radio label={t.personalize.accents[a]} selected={look.accent === a} onPress={() => setPersonalization({ accent: a })} />
          ))}
        </Choices>
        <Toggle label={t.personalize.black} body={t.personalize.blackBody} value={look.black} onChange={(black) => setPersonalization({ black })} />
        <Choices label={t.personalize.corners}>
          {CORNERS.map((c) => (
            <Chip key={c} radio label={t.personalize.cornerStyles[c]} selected={look.corners === c} onPress={() => setPersonalization({ corners: c })} />
          ))}
        </Choices>
        <Toggle label={t.personalize.glass} body={t.personalize.glassBody} value={look.glass} onChange={(glass) => setPersonalization({ glass })} />
        <Choices label={t.personalize.speed}>
          {SPEEDS.map((v) => (
            <Chip key={v} radio label={t.personalize.speedLabel(v)} selected={look.speed === v} onPress={() => setPersonalization({ speed: v })} />
          ))}
        </Choices>
        <Toggle label={t.personalize.intro} body={t.personalize.introBody} value={look.intro} onChange={(intro) => setPersonalization({ intro })} />
      </Section>

      <Section title={t.settings.offline.title}>
        <Text variant="callout" tone="muted">
          {t.settings.offline.body}
        </Text>
        {s.status === "downloading" && <Progress value={s.percent} label={t.settings.offline.downloading(Math.round(s.percent))} />}
        {s.status === "ready" && <Text variant="headline">{t.settings.offline.ready(`${Math.max(1, Math.round(s.bytes / 1_048_576))} MB`)}</Text>}
        {s.status === "failed" && (
          <Text variant="callout" tone="muted">
            {t.settings.offline.failed}
          </Text>
        )}
        {(s.status === "none" || s.status === "failed") && <Button label={t.settings.offline.download} onPress={() => void offline.download()} />}
        {(s.status === "ready" || s.status === "downloading") && <Button variant="quiet" height="md" label={t.settings.offline.remove} onPress={() => void offline.remove()} />}
      </Section>

      <Section title={t.settings.general}>
        <Choices label={t.about.language.label}>
          {CHOICES.map((c) => {
            const label = c === "system" ? t.about.language.system : LANGUAGES[c].name;
            return <Chip key={c} radio label={label} accessibilityLabel={`${label}, ${t.about.language.label}`} selected={language === c} onPress={() => setLanguage(c)} />;
          })}
        </Choices>
        <Choices label={t.about.contrast.label}>
          {CONTRASTS.map((c) => (
            <Chip key={c} radio label={t.about.contrast[c]} accessibilityLabel={`${t.about.contrast[c]}, ${t.about.contrast.label}`} selected={contrast === c} onPress={() => setContrast(c)} />
          ))}
        </Choices>
      </Section>

      <Section title={t.settings.app}>
        <FeedStatus feed={health.data?.feed} />
        {process.env.EXPO_OS === "android" && <Button label={t.updates.check} onPress={() => void checkForUpdate(true)} />}
        <Button role="link" variant="secondary" label={t.about.code} onPress={() => void WebBrowser.openBrowserAsync(REPO)} />
        <Button role="link" variant="quiet" height="md" label={t.about.privacy} onPress={() => router.push("/privacidad")} />
        <Text variant="caption" tone="faint">
          {t.settings.credits}
        </Text>
        <Text variant="caption" tone="faint">
          {t.about.version(`${Constants.expoConfig?.version ?? ""} (${currentBuild()})`)}
        </Text>
      </Section>
    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  const r = useRadius();
  return (
    <Glass style={[styles.card, { borderRadius: r.sheet }]}>
      <Text variant="eyebrow" tone="faint" accessibilityRole="header">
        {title}
      </Text>
      {children}
    </Glass>
  );
}

function Toggle({ label, body, value, onChange }: { label: string; body: string; value: boolean; onChange: (value: boolean) => void }) {
  const { accent } = useAccent();
  const p = usePalette();
  return (
    <View style={styles.toggle}>
      <View style={styles.toggleText}>
        <Text variant="headline">{label}</Text>
        <Text variant="caption" tone="muted">
          {body}
        </Text>
      </View>
      <Switch value={value} onValueChange={onChange} accessibilityLabel={label} trackColor={{ true: accent, false: p.fillStrong }} thumbColor={p.elevated} />
    </View>
  );
}

function Progress({ value, label }: { value: number; label: string }) {
  const p = usePalette();
  const { accent } = useAccent();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={label} accessibilityValue={{ min: 0, max: 100, now: Math.round(value) }} style={styles.group}>
      <View style={[styles.track, { backgroundColor: p.fillStrong }]}>
        <View style={[styles.fill, { backgroundColor: accent, width: `${Math.min(100, Math.max(2, value))}%` }]} />
      </View>
      <Text variant="caption" tone="muted" tabular>
        {label}
      </Text>
    </View>
  );
}

function Choices({ label, children }: { label: string; children: ReactNode }) {
  return (
    <View style={styles.group}>
      <Text variant="caption" tone="muted">
        {label}
      </Text>
      <View accessibilityRole="radiogroup" accessibilityLabel={label} style={styles.choices}>
        {children}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  content: { paddingHorizontal: layout.gutter, paddingTop: space[3], paddingBottom: layout.tabBarClearance, gap: space[4] },
  card: { padding: space[5], gap: space[3], borderRadius: radius.xl, borderCurve: "continuous" },
  group: { gap: space[2] },
  toggle: { flexDirection: "row", alignItems: "center", gap: space[3] },
  toggleText: { flex: 1, gap: space[0.5] },
  choices: { flexDirection: "row", flexWrap: "wrap", gap: space[2] },
  track: { height: size.dot.md, borderRadius: radius.pill, overflow: "hidden" },
  fill: { height: "100%", borderRadius: radius.pill },
});
