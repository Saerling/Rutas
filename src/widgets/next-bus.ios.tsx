import { HStack, Spacer, Text, VStack } from "@expo/ui/swift-ui";
import {
  containerBackground,
  font,
  foregroundStyle,
  lineLimit,
  padding,
} from "@expo/ui/swift-ui/modifiers";
import { createWidget, type WidgetEnvironment } from "expo-widgets";
import { PlatformColor } from "react-native";

import type { NextBusProps, NextBusWidget } from "./next-bus.types";

/**
 * Tu paradero guardado en la pantalla de inicio: sus próximas rutas y de dónde sale cada hora.
 * Widget rules: no hooks, no imports or module values inside the function.
 */
function NextBus(props: NextBusProps, environment: WidgetEnvironment) {
  "widget";
  const rows =
    environment.widgetFamily === "systemSmall"
      ? props.rows.slice(0, 2)
      : props.rows.slice(0, 3);
  return (
    <VStack
      alignment="leading"
      spacing={6}
      modifiers={[
        containerBackground(PlatformColor("systemBackground"), "widget"),
        padding({ all: 2 }),
      ]}
    >
      <Text modifiers={[font({ size: 13, weight: "semibold" }), lineLimit(2)]}>
        {props.stop}
      </Text>
      {rows.length === 0 ? (
        <Text
          modifiers={[
            font({ size: 12 }),
            foregroundStyle(PlatformColor("secondaryLabel")),
          ]}
        >
          {props.empty}
        </Text>
      ) : (
        rows.map((r) => (
          <HStack key={r.code} spacing={6}>
            <Text
              modifiers={[
                font({ size: 13, weight: "bold", design: "monospaced" }),
              ]}
            >
              {r.code}
            </Text>
            <Spacer />
            <Text
              modifiers={[
                font({ size: 13, weight: "medium" }),
                foregroundStyle(PlatformColor("label")),
              ]}
            >
              {r.when}
            </Text>
          </HStack>
        ))
      )}
      <Spacer />
      <Text
        modifiers={[
          font({ size: 11 }),
          foregroundStyle(PlatformColor("secondaryLabel")),
          lineLimit(1),
        ]}
      >
        {props.footer}
      </Text>
    </VStack>
  );
}

const widget: NextBusWidget = createWidget("NextBus", NextBus, {
  stop: "Rutas",
  rows: [],
  footer: "",
  empty: "",
});
export default widget;
