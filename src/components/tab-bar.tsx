import { BlurView } from "expo-blur";
import * as Haptics from "expo-haptics";
import type { TabTriggerSlotProps } from "expo-router/ui";
import { useEffect, type ComponentRef, type ReactNode, type Ref, type RefObject } from "react";
import { Platform, Pressable, StyleSheet, View } from "react-native";
import Animated, { useAnimatedStyle, useSharedValue, withSpring, withTiming } from "react-native-reanimated";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { useAccessibility } from "@/lib/accessibility";
import { usePersonalization } from "@/lib/personalize";
import { useAccent, usePalette, useRadius } from "@/theme/colors";
import { motion } from "@/theme/motion";
import { layout, size, space } from "@/theme/tokens";

import { Icon, type IconName } from "./icon";
import { Text } from "./text";

/**
 * The bottom bar, Mochy style: a floating pill of frosted glass over the content, with the active tab lifted on an accent
 * pill that springs in. With glass off, less transparency or more contrast, it turns into a solid surface.
 */
export function TabBar({ children, behind }: { children: ReactNode; behind: RefObject<ComponentRef<typeof View> | null> }) {
  const p = usePalette();
  const r = useRadius();
  const insets = useSafeAreaInsets();
  const { glass } = usePersonalization();
  const { reduceTransparency } = useAccessibility();
  const frosted = glass && !reduceTransparency && !p.high;
  return (
    <View pointerEvents="box-none" style={[styles.dock, { paddingBottom: Math.max(insets.bottom, space[2]) + space[1] }]}>
      <View style={[styles.bar, { borderRadius: r.sheet, borderColor: p.border, backgroundColor: frosted ? "transparent" : p.elevated, boxShadow: p.shadow }]}>
        {frosted && (
          <>
            <BlurView
              style={StyleSheet.absoluteFill}
              intensity={60}
              tint={p.dark ? "dark" : "light"}
              blurMethod="dimezisBlurViewSdk31Plus"
              blurTarget={Platform.OS === "android" ? behind : undefined}
            />
            <View style={[StyleSheet.absoluteFill, { backgroundColor: p.elevated, opacity: p.dark ? 0.55 : 0.6 }]} />
          </>
        )}
        {children}
      </View>
    </View>
  );
}

type TabButtonProps = TabTriggerSlotProps & { icon: IconName; label: string; ref?: Ref<ComponentRef<typeof View>> };

export function TabButton({ icon, label, isFocused, onPress, ...rest }: TabButtonProps) {
  const p = usePalette();
  const r = useRadius();
  const { accent, onAccent } = useAccent();
  const { reduceMotion } = useAccessibility();
  const on = useSharedValue(isFocused ? 1 : 0);

  useEffect(() => {
    on.value = reduceMotion ? withTiming(isFocused ? 1 : 0, { duration: 0 }) : withSpring(isFocused ? 1 : 0, { duration: motion.ms(420), dampingRatio: 0.7 });
  }, [isFocused, on, reduceMotion]);

  const pill = useAnimatedStyle(() => ({ opacity: on.value, transform: [{ scaleX: 0.6 + 0.4 * on.value }, { scaleY: 0.8 + 0.2 * on.value }] }));
  const lift = useAnimatedStyle(() => ({ transform: [{ translateY: -1.5 * on.value }, { scale: 1 + 0.06 * on.value }] }));

  return (
    <Pressable
      {...rest}
      accessibilityRole="tab"
      accessibilityLabel={label}
      accessibilityState={{ selected: !!isFocused }}
      onPress={(e) => {
        if (!isFocused) void Haptics.selectionAsync();
        onPress?.(e);
      }}
      style={styles.tab}>
      <Animated.View style={[StyleSheet.absoluteFill, styles.pillBox]}>
        <Animated.View style={[styles.pill, { backgroundColor: accent, borderRadius: r.pill }, pill]} />
      </Animated.View>
      <Animated.View style={[styles.inner, lift]}>
        <Icon name={icon} color={isFocused ? onAccent : p.muted} size="md" />
        <Text variant="micro" color={isFocused ? onAccent : p.muted} numberOfLines={1}>
          {label}
        </Text>
      </Animated.View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  dock: { position: "absolute", left: 0, right: 0, bottom: 0, paddingHorizontal: layout.gutter },
  bar: {
    flexDirection: "row",
    overflow: "hidden",
    borderWidth: StyleSheet.hairlineWidth,
    borderCurve: "continuous",
    padding: space[1.5],
    gap: space[1],
  },
  tab: { flex: 1, minHeight: size.control.lg, alignItems: "center", justifyContent: "center" },
  pillBox: { padding: space[0.5] },
  pill: { flex: 1, borderCurve: "continuous" },
  inner: { alignItems: "center", gap: space[0.5] },
});
