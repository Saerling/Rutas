import { Easing, FadeIn, FadeInDown, FadeOut, LinearTransition } from "react-native-reanimated";

import { personalization } from "@/lib/personalize";

/** Mochy-style motion: things arrive on a soft spring and leave quickly. Speed follows Personalización; 0 turns it all off. */
const settle = Easing.bezier(0.22, 1, 0.36, 1);
const speed = () => personalization().speed;
const ms = (base: number) => Math.round(base / Math.max(0.1, speed()));

export const motion = {
  /** Content arriving: a short spring up and a fade. */
  enter: (delay = 0) =>
    speed() === 0 ? undefined : FadeInDown.delay(ms(delay)).springify().duration(ms(520)).dampingRatio(0.82).withInitialValues({ transform: [{ translateY: 14 }], opacity: 0 }),
  /** A whole surface appearing, like a photo. */
  appear: (delay = 0) => (speed() === 0 ? undefined : FadeIn.delay(ms(delay)).duration(ms(460)).easing(settle)),
  get leave() {
    return speed() === 0 ? undefined : FadeOut.duration(ms(180));
  },
  /** Rows changing place in a list. */
  get layout() {
    return speed() === 0 ? undefined : LinearTransition.springify().duration(ms(380)).dampingRatio(0.85);
  },
  /** Milliseconds for hand-made animations, scaled by the chosen speed. */
  ms,
};
