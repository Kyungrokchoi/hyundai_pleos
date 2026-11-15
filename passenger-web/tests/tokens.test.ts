import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(resolve(process.cwd(), "src/app/globals.css"), "utf8");
const tok = (name: string): string => {
  const m = css.match(new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`));
  if (!m) throw new Error(`token --${name} missing`);
  return m[1]!;
};
function lum(hex: string): number {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0]! + 0.7152 * c[1]! + 0.0722 * c[2]!;
}
const ratio = (a: string, b: string) => {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
};

describe("design tokens meet WCAG AA", () => {
  const text: Array<[string, string, string]> = [
    ["ink", "paper", "body text"], ["ink", "paper-2", "default button / chip"], ["ink", "rule", "button hover"],
    ["ink-2", "paper", "secondary text"], ["ink-2", "paper-2", "secondary text on soft surface"],
    ["ink-3", "paper", "caption"], ["ink-3", "paper-2", "disabled button"],
    ["route", "paper", "link / ok mark"], ["route", "paper-2", "link on soft surface"], ["route", "route-bg", "ok badge / avatar"],
    ["alert", "paper", "alert mark"], ["alert", "alert-bg", "danger button / badge"], ["ink", "moa", "the one primary action"],
    ["paper", "ink", "demo bar"], ["ink", "alert-bg", "alert banner"], ["ink", "route-bg", "ok banner"],
  ];
  it.each(text)("%s on %s (%s) >= 4.5:1", (fg, bg) => {
    expect(ratio(tok(fg), tok(bg))).toBeGreaterThanOrEqual(4.5);
  });
  it("white on route and on alert fills >= 4.5:1", () => {
    expect(ratio("#ffffff", tok("route"))).toBeGreaterThanOrEqual(4.5);
    expect(ratio("#ffffff", tok("alert"))).toBeGreaterThanOrEqual(4.5);
  });
  it.each([["edge", "paper"], ["edge", "paper-2"], ["route-line", "paper"], ["route-line", "paper-2"]])(
    "%s (borders / progress fill) >= 3:1 on %s", (n, bg) => {
      expect(ratio(tok(n), tok(bg))).toBeGreaterThanOrEqual(3);
    });
  it("input borders are visible on the white field", () => {
    expect(ratio(tok("edge"), "#ffffff")).toBeGreaterThanOrEqual(3);
  });
});

describe("visual rules", () => {
  it("respects reduced motion, 48px targets, 56px primary action and 16px secondary text", () => {
    expect(css).toContain("prefers-reduced-motion: reduce");
    expect(css).toMatch(/--target:\s*3rem/);
    expect(css).toMatch(/--target-lg:\s*3\.5rem/);
    expect(css).toMatch(/--t-sm:\s*1rem/);
    expect(css).toContain("word-break: keep-all");
  });
  it("has no heavy chrome: no 2px outlines, no offset shadows, no gradients", () => {
    expect(css).not.toMatch(/border[^:]*:\s*2px/);
    expect(css).not.toMatch(/box-shadow:\s*0 [1-9]px 0/);
    expect(css).not.toMatch(/gradient\(/);
    expect(css).not.toMatch(/backdrop-filter/);
  });
  it("uses the yellow token only for the primary action", () => {
    const uses = [...css.matchAll(/var\(--moa\)/g)].length;
    expect(uses).toBe(1);
  });
  it("action bar is sticky, safe-area aware, and the only elevation", () => {
    expect(css).toMatch(/\.action-bar\s*\{[^}]*position:\s*sticky[^}]*safe-area-inset-bottom/s);
    expect([...css.matchAll(/var\(--elev\)/g)].length).toBe(1);
  });
});
