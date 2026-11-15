import type { SettlementOut, TripSummary } from "@/api/types";
import { formatKm, formatKrw } from "./format";


export function buildShareText(trip: TripSummary, s: SettlementOut, opts: { anonymize: boolean }): string {
  const names = new Map<string, string>();
  let n = 0;
  for (const sh of s.shares) {
    if (opts.anonymize) names.set(sh.user_id, sh.is_driver ? "운전자" : `참가자 ${String.fromCharCode(65 + n++)}`);
    else names.set(sh.user_id, sh.nickname);
  }
  const lines: string[] = [];
  lines.push(`모아타 정산 안내 · ${opts.anonymize ? "" : trip.group_name}`.trim());
  lines.push(`이동 거리 ${formatKm(s.distance_km)}km · 총 ${formatKrw(s.total_krw)} (추정)`);
  lines.push(`계산식: ${s.equation}`);
  lines.push("");
  for (const sh of s.shares) {
    const who = names.get(sh.user_id) ?? "참가자";
    const extra = sh.items.map((i) => `${i.label || "추가 비용"} ${formatKrw(i.amount_krw)}`).join(", ");
    lines.push(`- ${who}: ${formatKrw(sh.total_krw)} (연료·전기 ${formatKrw(sh.energy_krw)}${extra ? `, ${extra}` : ""}, ${formatKm(sh.ridden_km)}km)`);
  }
  lines.push("");
  lines.push("추정치이며 실제 연료비·전기요금과 다를 수 있어요. 이 앱은 결제나 송금을 하지 않아요.");
  if (trip.data_mode === "DEMO") lines.push("※ DEMO · 가상 데이터로 만든 시연용 계산이에요.");
  return lines.join("\n");
}

