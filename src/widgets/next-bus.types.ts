/** What the home-screen widget shows. Every word is prepared in the app, in the rider's language, because the widget cannot read src/i18n. */
export interface NextBusProps {
  /** The saved stop's name, as TransMilenio writes it. */
  stop: string;
  /** Up to three routes: code and when, e.g. { code: "K23", when: "4 min" }. */
  rows: { code: string; when: string }[];
  /** Where the times come from and how old they are: "En vivo · 6:42 p. m.". */
  footer: string;
  /** Shown when there is nothing saved yet. */
  empty: string;
}

export interface NextBusWidget {
  updateSnapshot(props: NextBusProps): void;
  updateTimeline(entries: { date: Date; props: NextBusProps }[]): void;
}
