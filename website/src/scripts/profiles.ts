/** Profile switcher: tabs bring a profile layer to the front; gentle auto-cycle until the visitor interacts. */
for (const root of document.querySelectorAll<HTMLElement>('[data-profiles]')) {
  const tabs = Array.from(root.querySelectorAll<HTMLButtonElement>('[data-tab]'));
  const layers = Array.from(root.querySelectorAll<HTMLElement>('[data-layer]'));
  const ids = tabs.map((t) => t.dataset.tab ?? '');
  let current = 0;
  let timer: number | undefined;

  const show = (index: number) => {
    current = (index + ids.length) % ids.length;
    tabs.forEach((tab, i) => {
      const selected = i === current;
      tab.setAttribute('aria-pressed', String(selected));
    });
    layers.forEach((layer) => {
      const i = ids.indexOf(layer.dataset.layer ?? '');
      layer.dataset.pos = String((i - current + ids.length) % ids.length);
    });
  };

  const stop = () => {
    if (timer !== undefined) window.clearInterval(timer);
    timer = undefined;
  };

  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => {
      stop();
      show(i);
    });
    tab.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
      e.preventDefault();
      stop();
      show(current + (e.key === 'ArrowDown' ? 1 : -1));
      tabs[current]?.focus();
    });
  });

  show(0);

  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!reduce && 'IntersectionObserver' in window) {
    const io = new IntersectionObserver(([entry]) => {
      if (entry?.isIntersecting && timer === undefined && !root.dataset.touched) {
        timer = window.setInterval(() => show(current + 1), 3800);
      } else if (!entry?.isIntersecting) {
        stop();
      }
    });
    io.observe(root);
    root.addEventListener('pointerdown', () => (root.dataset.touched = '1'), { once: true });
    root.addEventListener('focusin', () => {
      root.dataset.touched = '1';
      stop();
    });
  }
}
