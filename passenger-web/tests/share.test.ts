import { describe, expect, it } from "vitest";
import type { SettlementOut, TripSummary } from "@/api/types";
import { buildShareText } from "@/lib/share";

const settlement = {
  total_krw: 6000, distance_km: "24", equation: "24km ÷ 12km/L × 1,800원/L = 3,600원 + 통행료·주차비 등 2,400원 = 6,000원",
  shares: [
    { user_id: "a", nickname: "하늘", is_driver: true, total_krw: 2350, energy_krw: 1550, items: [{ item_id: "t", label: "통행료", amount_krw: 800 }], ridden_km: "24" },
    { user_id: "b", nickname: "별이", is_driver: false, total_krw: 2350, energy_krw: 1550, items: [{ item_id: "t", label: "통행료", amount_krw: 800 }], ridden_km: "24" },
    { user_id: "c", nickname: "달이", is_driver: false, total_krw: 1300, energy_krw: 500, items: [{ item_id: "t", label: "통행료", amount_krw: 800 }], ridden_km: "10" },
  ],
} as unknown as SettlementOut;
const trip = { group_name: "금요일 카풀", data_mode: "PLEOS" } as unknown as TripSummary;

describe("share text", () => {
  it("copies the server's numbers verbatim", () => {
    const t = buildShareText(trip, settlement, { anonymize: false });
    expect(t).toContain("총 6,000원");
    expect(t).toContain(settlement.equation);
    expect(t).toContain("별이: 2,350원");
    expect(t).toContain("달이: 1,300원");
    expect(t).toContain("결제나 송금을 하지 않아요");
  });
  it("anonymizes names and the group name", () => {
    const t = buildShareText(trip, settlement, { anonymize: true });
    expect(t).not.toMatch(/하늘|별이|달이|금요일/);
    expect(t).toContain("운전자: 2,350원");
    expect(t).toContain("참가자 A: 2,350원");
    expect(t).toContain("참가자 B: 1,300원");
  });
  it("labels demo data in the shared text", () => {
    const t = buildShareText({ ...trip, data_mode: "DEMO" } as TripSummary, settlement, { anonymize: true });
    expect(t).toContain("DEMO · 가상 데이터");
  });
});
