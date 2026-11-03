import type { CostItemOut, Issue } from "@/api/types";
import { formatKm, formatKrw, kmBetween } from "./format";
import { ITEM_KIND } from "./labels";

// Source-only warnings: the DEMO bar and source line already cover them, so no banner (text stays in the notices).
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

// Passengers see distance since the start, never raw odometer readings.
export function sinceStart(startKm: string | null, km: string | null): string | null {
  if (km === null) return null;
  const d = kmBetween(startKm, km);
  return d === null ? null : formatKm(d);
}

// "통행료 · 고속도로": kind, then the driver's note.
export function itemLabel(item: Pick<CostItemOut, "kind" | "note"> | undefined, fallback: string): string {
  if (!item) return fallback || "추가 비용";
  return item.note ? `${ITEM_KIND[item.kind]} · ${item.note}` : ITEM_KIND[item.kind];
}

// Explains the split only; amounts and `payers` come from the server's shares.
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
