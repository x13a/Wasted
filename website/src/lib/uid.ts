let counter = 0;
/** Deterministic per-build unique id for SVG gradient/filter references. */
export function uid(prefix = 'u'): string {
  counter += 1;
  return `${prefix}${counter.toString(36)}`;
}
