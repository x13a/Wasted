function addRipple(button, clientX, clientY) {
  const rect = button.getBoundingClientRect();
  const size = Math.max(rect.width, rect.height) * 2;
  const x = clientX ?? rect.left + rect.width / 2;
  const y = clientY ?? rect.top + rect.height / 2;
  const ripple = document.createElement("span");
  ripple.className = "ripple";
  ripple.setAttribute("aria-hidden", "true");
  ripple.style.width = `${size}px`;
  ripple.style.height = `${size}px`;
  ripple.style.left = `${x - rect.left - size / 2}px`;
  ripple.style.top = `${y - rect.top - size / 2}px`;
  button.append(ripple);
  ripple.addEventListener("animationend", () => ripple.remove(), { once: true });
  setTimeout(() => ripple.remove(), 700);
}

export function installRipple() {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

  document.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    const button = event.target.closest("button");
    if (!button || button.disabled) return;
    addRipple(button, event.clientX, event.clientY);
  });

  document.addEventListener("click", (event) => {
    if (event.detail !== 0) return;
    const button = event.target.closest("button");
    if (!button || button.disabled) return;
    addRipple(button);
  });
}
