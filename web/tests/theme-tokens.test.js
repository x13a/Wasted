import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const css = readFileSync(new URL("../styles/main.css", import.meta.url), "utf8");

const requiredTokens = [
  "--background",
  "--foreground",
  "--card",
  "--card-foreground",
  "--popover",
  "--popover-foreground",
  "--primary",
  "--primary-foreground",
  "--secondary",
  "--secondary-foreground",
  "--muted",
  "--muted-foreground",
  "--accent",
  "--accent-foreground",
  "--destructive",
  "--destructive-foreground",
  "--border",
  "--input",
  "--ring",
  "--radius",
  "--radius-sm",
  "--radius-lg",
  "--shadow-sm",
  "--shadow-md",
  "--shadow-lg",
  "--font-sans",
  "--font-mono",
];

test("shadcn-compatible tokens and theme scopes are present", () => {
  for (const token of requiredTokens) assert.match(css, new RegExp(`${token}:`));
  assert.match(css, /@media \(prefers-color-scheme: dark\)/);
  assert.match(css, /:root\[data-theme="light"\]/);
  assert.match(css, /:root\[data-theme="dark"\]/);
});

test("canonical muted surface and muted text tokens are separate", () => {
  assert.match(css, /--muted:\s*var\(--(light|dark)-muted\)/);
  assert.match(css, /--muted-foreground:\s*var\(--(light|dark)-muted-foreground\)/);
});

test("palette values live once and compatibility aliases are gone", () => {
  assert.match(css, /--light-background:/);
  assert.match(css, /--dark-background:/);
  for (const alias of ["--bg:", "--surface:", "--text:", "--ink:", "--danger:", "--mono:"]) {
    assert.equal(css.includes(alias), false, `legacy alias ${alias} must not exist`);
  }
  assert.doesNotMatch(css, /var\(--ink\)/);
  assert.doesNotMatch(css, /var\(--surface\)/);
});
