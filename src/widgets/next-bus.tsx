import type { NextBusWidget } from "./next-bus.types";

/** The web has no home screen widget; updates go nowhere. */
const widget: NextBusWidget = {
  updateSnapshot() {},
  updateTimeline() {},
};

export default widget;
