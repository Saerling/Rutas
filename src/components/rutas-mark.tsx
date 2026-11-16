import Svg, { Circle } from "react-native-svg";

/** Dots along an S from x, y with radius r and opacity o: the route between where you are and where you go. Same drawing as the app icon. */
export const MARK_DOTS: [number, number, number, number][] = [
  [393, 761, 18, 0.66],
  [449, 728, 20, 0.76],
  [484, 673, 22, 0.85],
  [501, 610, 23, 0.91],
  [509, 544, 24, 0.95],
  [515, 479, 24, 0.95],
  [523, 413, 23, 0.91],
  [541, 350, 22, 0.85],
  [576, 295, 20, 0.76],
  [632, 263, 18, 0.66]
,
];

/** The Rutas mark: a ring where you start, a dotted route, and an orb where you arrive. */
export function RutasMark({ size, color, accent }: { size: number; color: string; accent: string }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 1024 1024" accessible accessibilityRole="image" accessibilityLabel="Rutas">
      {MARK_DOTS.map(([x, y, r, o], i) => (
        <Circle key={i} cx={x} cy={y} r={r} fill={color} opacity={o} />
      ))}
      <Circle cx={262} cy={762} r={56} fill="none" stroke={color} strokeWidth={26} />
      <Circle cx={762} cy={262} r={82} fill={accent} />
    </Svg>
  );
}
