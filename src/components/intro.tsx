import { useEffect, useState } from "react";
import { StyleSheet } from "react-native";
import Animated, { FadeIn, FadeOut } from "react-native-reanimated";
import Svg, { Circle } from "react-native-svg";

import { useAccessibility } from "@/lib/accessibility";
import { usePersonalization } from "@/lib/personalize";
import { useAccent, usePalette } from "@/theme/colors";
import { motion } from "@/theme/motion";
import { size } from "@/theme/tokens";

import { MARK_DOTS } from "./rutas-mark";

const AnimatedCircle = Animated.createAnimatedComponent(Circle);

/** Opening animation, thinking-orbs style: the dots of the route light up one after the other, then the app shows through. */
export function Intro() {
  const p = usePalette();
  const { accent } = useAccent();
  const { intro } = usePersonalization();
  const { reduceMotion } = useAccessibility();
  const [shown, setShown] = useState(intro && !reduceMotion);

  useEffect(() => {
    if (!shown) return;
    const timer = setTimeout(() => setShown(false), motion.ms(1100));
    return () => clearTimeout(timer);
  }, [shown]);

  if (!shown) return null;
  const step = motion.ms(55);
  return (
    <Animated.View pointerEvents="none" exiting={FadeOut.duration(motion.ms(320))} style={[StyleSheet.absoluteFill, styles.center, { backgroundColor: p.background }]}>
      <Svg width={size.tile.xl * 2} height={size.tile.xl * 2} viewBox="0 0 1024 1024">
        <AnimatedCircle entering={FadeIn.duration(motion.ms(200))} cx={262} cy={762} r={56} fill="none" stroke={p.text} strokeWidth={26} />
        {MARK_DOTS.map(([x, y, r, o], i) => (
          <AnimatedCircle key={i} entering={FadeIn.delay(step * (i + 1)).duration(motion.ms(220))} cx={x} cy={y} r={r} fill={p.text} opacity={o} />
        ))}
        <AnimatedCircle entering={FadeIn.delay(step * (MARK_DOTS.length + 2)).duration(motion.ms(260))} cx={762} cy={262} r={82} fill={accent} />
      </Svg>
    </Animated.View>
  );
}

const styles = StyleSheet.create({ center: { alignItems: "center", justifyContent: "center" } });
