export const $ = (selector) => document.querySelector(selector);

const TOAST_DURATION_MS = 4500;
let toastTimer = null;
let toastStartedAt = 0;
let toastRemainingMs = TOAST_DURATION_MS;
let toastVisible = false;
let toastPaused = false;
let toastWired = false;

function ensureToastWiring() {
  const element = $("#toast");
  const close = $("#toast-close");
  if (!element || !close || toastWired) return;
  toastWired = true;
  element.addEventListener("mouseenter", pauseToast);
  element.addEventListener("mouseleave", resumeToast);
  element.addEventListener("focusin", pauseToast);
  element.addEventListener("focusout", resumeToast);
  close.addEventListener("click", (event) => {
    event.stopPropagation();
    hideToast();
  });
}

function hideToast() {
  clearTimeout(toastTimer);
  toastTimer = null;
  toastVisible = false;
  toastPaused = false;
  const element = $("#toast");
  if (element) element.hidden = true;
}

function pauseToast() {
  if (!toastVisible || toastPaused || toastTimer === null) return;
  toastRemainingMs = Math.max(0, toastRemainingMs - (Date.now() - toastStartedAt));
  clearTimeout(toastTimer);
  toastTimer = null;
  toastPaused = true;
}

function resumeToast() {
  if (!toastVisible || !toastPaused) return;
  toastPaused = false;
  if (toastRemainingMs <= 0) {
    hideToast();
    return;
  }
  toastStartedAt = Date.now();
  toastTimer = setTimeout(hideToast, toastRemainingMs);
}

export function toast(message, tone = "info") {
  ensureToastWiring();
  const element = $("#toast");
  const label = $("#toast-message");
  if (!element || !label) return;
  label.textContent = message;
  element.dataset.tone = message ? tone : "";
  element.hidden = false;
  toastVisible = true;
  toastRemainingMs = TOAST_DURATION_MS;
  toastPaused = false;
  clearTimeout(toastTimer);
  toastTimer = null;
  // Keep a newly-appeared notification paused if the pointer is already over it.
  if (element.matches(":hover")) {
    toastPaused = true;
    return;
  }
  toastStartedAt = Date.now();
  toastTimer = setTimeout(hideToast, TOAST_DURATION_MS);
}

export function showMessage(selector, message, tone = "info") {
  const element = $(selector);
  if (!element) return;
  element.textContent = message;
  element.dataset.tone = message ? tone : "";
}

export function clearMessage(selector) {
  showMessage(selector, "");
}

export async function copyText(value) {
  const text = String(value ?? "");
  if (!text) throw new Error("Nothing to copy.");
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch (_) {
      // Browser permission can be denied; fall through to the legacy copy path.
    }
  }

  const field = document.createElement("textarea");
  field.value = text;
  field.setAttribute("readonly", "");
  field.style.position = "fixed";
  field.style.opacity = "0";
  document.body.append(field);
  field.select();
  try {
    if (!document.execCommand("copy")) throw new Error("Copy failed.");
  } finally {
    field.remove();
  }
}

const busyLabels = new WeakMap();

/**
 * Dialog forms default to implicit submission through their first submit button, which is
 * Cancel. Make Enter run the intended primary action instead and never close with lost input.
 */
export function wireDialogEnterKeys() {
  for (const dialog of document.querySelectorAll("dialog")) {
    const form = dialog.querySelector('form[method="dialog"]');
    if (!form) continue;
    form.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" || event.isComposing || event.keyCode === 229) return;
      const target = event.target;
      if (!(target instanceof HTMLInputElement)) return;
      if (["checkbox", "radio", "hidden", "file", "button", "submit", "reset"].includes(target.type)) {
        return;
      }
      const primary = form.querySelector('button[value="ok"]');
      if (!primary) return;
      event.preventDefault();
      if (!primary.disabled) primary.click();
    });
  }
}

export function setBusy(button, busy, busyLabel = "Working…") {
  if (!button) return;
  const label = button.querySelector(".button-label") ?? button;
  if (busy) {
    if (!busyLabels.has(button)) busyLabels.set(button, label.textContent);
    button.disabled = true;
    label.textContent = busyLabel;
  } else {
    button.disabled = false;
    const original = busyLabels.get(button);
    if (original !== undefined) label.textContent = original;
  }
}

/**
 * Promise-based confirmation dialog. When typedPhrase is provided the Confirm button stays
 * disabled until the phrase is typed exactly (Force Reset safety requirement).
 */
export function confirmAction({
  title,
  message,
  typedPhrase = null,
  confirmLabel = "Confirm",
  danger = true,
}) {
  return new Promise((resolve) => {
    const dialog = $("#confirm-dialog");
    const ok = $("#confirm-ok");
    const typedField = $("#confirm-typed-field");
    const typed = $("#confirm-typed");
    const phrase = $("#confirm-phrase");

    $("#confirm-title").textContent = title;
    $("#confirm-message").textContent = message;
    ok.textContent = confirmLabel;
    ok.classList.toggle("danger", danger);
    ok.classList.toggle("primary", !danger);
    const needsTyping = Boolean(typedPhrase);
    typedField.hidden = !needsTyping;
    typed.value = "";
    if (needsTyping) phrase.textContent = typedPhrase;
    ok.disabled = needsTyping;

    const onInput = () => {
      ok.disabled = needsTyping && typed.value.trim() !== typedPhrase;
    };
    typed.oninput = onInput;

    const onClose = () => {
      dialog.removeEventListener("close", onClose);
      resolve(dialog.returnValue === "ok" && (!needsTyping || typed.value.trim() === typedPhrase));
    };
    dialog.addEventListener("close", onClose);
    dialog.returnValue = "";
    dialog.showModal();
  });
}
