/**
 * Scroll reveal as progressive enhancement: without JS, everything is visible.
 * Elements already on screen are marked visible before the `js` class is applied,
 * so nothing flickers on load.
 */
const items = Array.from(document.querySelectorAll<HTMLElement>('[data-reveal]'));
const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

if (!reduce && 'IntersectionObserver' in window) {
  const vh = window.innerHeight;
  for (const el of items) {
    if (el.getBoundingClientRect().top < vh) el.classList.add('is-visible');
  }
  document.documentElement.classList.add('js');

  const io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          io.unobserve(entry.target);
        }
      }
    },
    { rootMargin: '0px 0px -8% 0px', threshold: 0.08 },
  );
  for (const el of items) if (!el.classList.contains('is-visible')) io.observe(el);
}

// Header elevation on scroll.
const header = document.querySelector<HTMLElement>('[data-header]');
if (header) {
  const update = () => header.classList.toggle('is-scrolled', window.scrollY > 12);
  update();
  window.addEventListener('scroll', update, { passive: true });
}
