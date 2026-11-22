import { Column, Row, Spacer, Text } from "@expo/ui/jetpack-compose";
import {
  background,
  fillMaxSize,
  fillMaxWidth,
  paddingAll,
  weight,
} from "@expo/ui/jetpack-compose/modifiers";
import { createWidget, type WidgetEnvironment } from "expo-widgets";

import type { NextBusProps, NextBusWidget } from "./next-bus.types";

/**
 * Tu paradero guardado en la pantalla de inicio: sus próximas rutas y de dónde sale cada hora.
 * Widget rules: no hooks, no imports or module values inside the function.
 */
function NextBus(props: NextBusProps, environment: WidgetEnvironment) {
  "widget";
  const dark = environment.colorScheme === "dark";
  const ink = dark ? "#FAFAFA" : "#09090B";
  const muted = dark ? "#A1A1AA" : "#52525B";
  return (
    <Column
      modifiers={[
        fillMaxSize(),
        background(dark ? "#09090B" : "#FDFCFC"),
        paddingAll(14),
      ]}
    >
      <Text
        color={ink}
        maxLines={2}
        style={{ fontSize: 14, fontWeight: "600" }}
      >
        {props.stop}
      </Text>
      {props.rows.length === 0 ? (
        <Text color={muted} style={{ fontSize: 13 }}>
          {props.empty}
        </Text>
      ) : (
        props.rows.slice(0, 3).map((r) => (
          <Row key={r.code} modifiers={[fillMaxWidth()]}>
            <Text
              color={ink}
              modifiers={[weight(1)]}
              style={{
                fontSize: 14,
                fontWeight: "700",
                fontFamily: "monospace",
              }}
            >
              {r.code}
            </Text>
            <Text color={ink} style={{ fontSize: 14, fontWeight: "500" }}>
              {r.when}
            </Text>
          </Row>
        ))
      )}
      <Spacer modifiers={[weight(1)]} />
      <Text color={muted} maxLines={1} style={{ fontSize: 12 }}>
        {props.footer}
      </Text>
    </Column>
  );
}

const widget: NextBusWidget = createWidget("NextBus", NextBus, {
  stop: "Rutas",
  rows: [],
  footer: "",
  empty: "",
});
export default widget;
