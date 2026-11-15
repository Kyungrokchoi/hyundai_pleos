import type { CostItemOut, Issue } from "@/api/types";
import { formatKm, formatKrw, kmBetween } from "./format";
import { ITEM_KIND } from "./labels";


const INFO_CODES = new Set(["DEMO_DATA", "MANUAL_DATA"]);

export function splitIssues(issues: readonly Issue[]): { attention: Issue[]; info: Issue[] } {
  const attention: Issue[] = [];
  const info: Issue[] = [];
  for (const i of issues) (INFO_CODES.has(i.code) ? info : attention).push(i);
  return { attention, info };
}

export function ridingSentence(totalKm: string, riddenKm: string, isDriver: boolean): string {
  const total = Number(totalKm);
  const mine = Number(riddenKm);
  if (!Number.isFinite(total) || !Number.isFinite(mine) || total <= 0) return "이동 거리가 아직 기록되지 않았어요.";
  if (isDriver) return `${formatKm(totalKm)}km를 운전했고, 함께 탄 사람과 비용을 나눠요.`;
  if (mine <= 0) return "이번 이동에서 함께 탄 거리가 없어요.";
  if (mine >= total) return `${formatKm(totalKm)}km를 끝까지 함께 탔어요.`;
  return `${formatKm(totalKm)}km 중 ${formatKm(riddenKm)}km를 함께 탔어요.`;
}


export function sinceStart(startKm: string | null, km: string | null): string | null {
  if (km === null) return null;
  const d = kmBetween(startKm, km);
  return d === null ? null : formatKm(d);
}


export const stripEstimate = (label: string): string => label.replace(/\s*·\s*추정\s*$/, "");


export function itemLabel(item: Pick<CostItemOut, "kind" | "note" | "road_name"> | undefined, fallback: string): string {
  if (!item) return stripEstimate(fallback) || "추가 비용";
  return [ITEM_KIND[item.kind], item.road_name, item.note].filter(Boolean).join(" · ");
}

export interface BillFact { id: "distance" | "economy" | "price" | "toll"; label: string; value: string }


export function billFacts(i: {
  distanceKm: string | null | undefined; ev: boolean; efficiencyKmPerUnit: string | null | undefined;
  unitPriceKrw: string | null | undefined; items: ReadonlyArray<Pick<CostItemOut, "kind" | "amount_krw">>;
}): BillFact[] {
  const tolls = i.items.filter((c) => c.kind === "TOLL");
  return [
    { id: "distance", label: "거리", value: i.distanceKm ? `${formatKm(i.distanceKm)}km` : "-" },
    { id: "economy", label: "연비", value: i.efficiencyKmPerUnit ? `${formatKm(i.efficiencyKmPerUnit)} ${i.ev ? "km/kWh" : "km/L"}` : "-" },
    { id: "price", label: "유가", value: i.unitPriceKrw ? `${formatKm(i.unitPriceKrw)}원/${i.ev ? "kWh" : "L"}` : "-" },
    { id: "toll", label: "통행료", value: tolls.length > 0 ? formatKrw(tolls.reduce((sum, c) => sum + c.amount_krw, 0)) : "없음" },
  ];
}


export function itemReason(item: Pick<CostItemOut, "allocation" | "amount_krw"> | undefined, payers: number): string | null {
  if (!item) return null;
  switch (item.allocation) {
    case "EQUAL": return payers > 0 ? `${formatKrw(item.amount_krw)} ÷ ${payers}명 · 탄 거리와 관계없이 똑같이` : `${formatKrw(item.amount_krw)} · 똑같이 나눔`;
    case "DISTANCE_WEIGHTED": return `${formatKrw(item.amount_krw)} · 탄 거리만큼 나눔`;
    case "DRIVER_ONLY": return "운전자가 부담";
  }
}

export function payersOf(shares: readonly { items: readonly { item_id: string }[] }[], itemId: string): number {
  return shares.filter((sh) => sh.items.some((i) => i.item_id === itemId)).length;
}
