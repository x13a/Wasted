/**
 * eSIM country selector (WAI-ARIA tabs). Without JS every country panel is
 * shown in full; with JS only the selected country is revealed.
 */
for (const root of document.querySelectorAll<HTMLElement>('[data-esim]')) {
  const tabs = Array.from(root.querySelectorAll<HTMLButtonElement>('[data-country]'));
  const panels = Array.from(root.querySelectorAll<HTMLElement>('[data-panel]'));

  const select = (index: number, focus = false) => {
    const i = (index + tabs.length) % tabs.length;
    tabs.forEach((tab, n) => {
      const on = n === i;
      tab.setAttribute('aria-selected', String(on));
      tab.tabIndex = on ? 0 : -1;
    });
    const code = tabs[i]?.dataset.country;
    panels.forEach((panel) => (panel.hidden = panel.dataset.panel !== code));
    if (focus) tabs[i]?.focus();
  };

  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => select(i));
    tab.addEventListener('keydown', (e) => {
      const keys: Record<string, number> = { ArrowRight: i + 1, ArrowDown: i + 1, ArrowLeft: i - 1, ArrowUp: i - 1, Home: 0, End: tabs.length - 1 };
      const next = keys[e.key];
      if (next === undefined) return;
      e.preventDefault();
      select(next, true);
    });
  });

  select(0);
}
