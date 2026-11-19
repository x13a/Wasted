import assert from "node:assert/strict";
import test from "node:test";
import {
  androidReleaseLabel,
  appVersionLabel,
  devicePlatformLabel,
} from "../src/android.js";

test("Android release is displayed exactly as reported by the device", () => {
  assert.equal(androidReleaseLabel("11"), "Android 11");
  assert.equal(androidReleaseLabel("13"), "Android 13");
  assert.equal(androidReleaseLabel("16"), "Android 16");
  assert.equal(androidReleaseLabel(""), "Android unknown");
});

test("app versions are prefixed once", () => {
  assert.equal(appVersionLabel("3.0.0"), "v3.0.0");
  assert.equal(appVersionLabel("v3.0.0"), "v3.0.0");
  assert.equal(appVersionLabel(""), "unknown");
});

test("device platform combines both labels", () => {
  assert.equal(
    devicePlatformLabel({ app_version: "3.0.0", android_release: "14" }),
    "v3.0.0 · Android 14",
  );
  assert.equal(devicePlatformLabel(null), "unknown");
});
