import { describe, expect, it } from "vitest";
import { formatAge, formatInt, formatKm, formatKrw, kmBetween } from "@/lib/format";

describe("formatters", () => {
  it("formats KRW with separators and the 원 suffix", () => {
    expect(formatKrw(0)).toBe("0원");
    expect(formatKrw(6000)).toBe("6,000원");
    expect(formatKrw(1234567)).toBe("1,234,567원");
    expect(formatInt(2350)).toBe("2,350");
  });
  it("formats km strings without inventing precision", () => {
    expect(formatKm("24")).toBe("24");
    expect(formatKm("24.50")).toBe("24.5");
    expect(formatKm("1234.567")).toBe("1,234.6");
    expect(formatKm("10", 2)).toBe("10");
    expect(formatKm(null)).toBe("-");
    expect(formatKm("abc")).toBe("-");
    expect(formatKm("NaN")).toBe("-");
  });
  it("computes display-only distance between odometer readings", () => {
    expect(kmBetween("100", "124")).toBe("24");
    expect(kmBetween("100.2", "110.7")).toBe("10.5");
    expect(kmBetween("124", "100")).toBeNull(); // odometer never goes backwards: show nothing, not a negative
    expect(kmBetween(null, "100")).toBeNull();
  });
  it("formats ages in Korean", () => {
    expect(formatAge(2)).toBe("방금");
    expect(formatAge(30)).toBe("30초 전");
    expect(formatAge(180)).toBe("3분 전");
  });
});
