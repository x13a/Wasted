/** Formats a whole-euro amount the way SPEX lists prices: 1049 → "1,049€". */
export function eur(amount: number): string {
  return `${amount.toLocaleString('en-US')}€`;
}

/** "from 499€" helper used by overview cards. */
export function fromPrice(amounts: number[]): string {
  return `from ${eur(Math.min(...amounts))}`;
}

/** Per-piece price for quantity tiers: 950 / 100 → "9.50€". */
export function eurEach(total: number, qty: number): string {
  const each = total / qty;
  return Number.isInteger(each) ? eur(each) : `${each.toFixed(2)}€`;
}
