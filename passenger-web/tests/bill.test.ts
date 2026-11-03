import { describe, expect, it } from "vitest";
import { itemLabel, itemReason, payersOf, ridingSentence, sinceStart, splitIssues } from "@/lib/bill";
import { mergeNotices, parseNotices } from "@/lib/notices";

describe("ridingSentence (one plain sentence from the server's distances)", () => {
  it("partial ride", () => expect(ridingSentence("24", "16", false)).toBe("24km 중 16km를 함께 탔어요."));
  it("whole ride", () => expect(ridingSentence("24", "24", false)).toBe("24km를 끝까지 함께 탔어요."));
  it("compares numerically, not as strings", () => expect(ridingSentence("24", "24.0", false)).toBe("24km를 끝까지 함께 탔어요."));
  it("driver", () => expect(ridingSentence("24", "24", true)).toBe("24km를 운전했고, 함께 탄 사람과 비용을 나눠요."));
  it("no distance on record", () => {
    expect(ridingSentence("0", "0", false)).toBe("이동 거리가 아직 기록되지 않았어요.");
    expect(ridingSentence("x", "1", false)).toBe("이동 거리가 아직 기록되지 않았어요.");
  });
  it("rode nothing", () => expect(ridingSentence("24", "0", false)).toBe("이번 이동에서 함께 탄 거리가 없어요."));
});

describe("sinceStart (odometer readings never reach the passenger)", () => {
  it("shows distance since the trip started", () => expect(sinceStart("34200", "34216")).toBe("16"));
  it("keeps decimals short", () => expect(sinceStart("100.2", "110.7")).toBe("10.5"));
  it("returns null instead of a raw odometer when it cannot subtract", () => {
    expect(sinceStart(null, "34216")).toBeNull();
    expect(sinceStart("34200", null)).toBeNull();
    expect(sinceStart("34216", "34200")).toBeNull();
  });
});

describe("splitIssues", () => {
  const i = (code: string) => ({ code, message_ko: `m-${code}` });
  it("keeps real problems visible and moves data-source restatements out of the banners", () => {
    const r = splitIssues([i("DEMO_DATA"), i("ODOMETER_STALE"), i("MANUAL_DATA"), i("SETTLEMENT_OUTDATED")]);
    expect(r.attention.map((x) => x.code)).toEqual(["ODOMETER_STALE", "SETTLEMENT_OUTDATED"]);
    expect(r.info.map((x) => x.code)).toEqual(["DEMO_DATA", "MANUAL_DATA"]);
  });
});

describe("notice helpers", () => {
  it("mergeNotices drops exact repeats and keeps the server's order and wording", () => {
    expect(mergeNotices(["a", "b"], ["b", "c"], [])).toEqual(["a", "b", "c"]);
  });
  it("parseNotices tolerates empty and corrupt storage", () => {
    expect(parseNotices("")).toEqual([]);
    expect(parseNotices("not json")).toEqual([]);
    expect(parseNotices('{"a":1}')).toEqual([]);
    expect(parseNotices('["x",1,"y"]')).toEqual(["x", "y"]);
  });
});

describe("cost items say how they were split (the toll is the most predictable dispute)", () => {
  const shares = [{ items: [{ item_id: "toll" }] }, { items: [{ item_id: "toll" }, { item_id: "park" }] }, { items: [{ item_id: "toll" }] }];
  it("payersOf counts the shares that carry the item, read from the server", () => {
    expect(payersOf(shares, "toll")).toBe(3);
    expect(payersOf(shares, "park")).toBe(1);
    expect(payersOf(shares, "none")).toBe(0);
  });
  it("EQUAL says it ignores distance; the other policies say theirs", () => {
    expect(itemReason({ allocation: "EQUAL", amount_krw: 2400 }, 3)).toBe("2,400원 ÷ 3명 · 탄 거리와 관계없이 똑같이");
    expect(itemReason({ allocation: "DISTANCE_WEIGHTED", amount_krw: 2400 }, 3)).toBe("2,400원 · 탄 거리만큼 나눔");
    expect(itemReason({ allocation: "DRIVER_ONLY", amount_krw: 2400 }, 1)).toBe("운전자가 부담");
    expect(itemReason(undefined, 3)).toBeNull();
  });
  it("the label leads with the kind, then the driver's note", () => {
    expect(itemLabel({ kind: "TOLL", note: "고속도로" }, "고속도로")).toBe("통행료 · 고속도로");
    expect(itemLabel({ kind: "PARKING", note: "" }, "")).toBe("주차비");
    expect(itemLabel(undefined, "")).toBe("추가 비용");
  });
});
