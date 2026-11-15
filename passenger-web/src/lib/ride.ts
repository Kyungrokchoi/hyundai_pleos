import type { ParticipantOut, SegmentOut, TripSummary } from "@/api/types";


export interface Span { from: number; to: number; open: boolean; exited: boolean }

export interface Lane { userId: string; nickname: string; isDriver: boolean; mine: boolean; spans: Span[]; riddenKm: number; byDriver: boolean }
export interface Strip { totalKm: number; live: boolean; lanes: Lane[] }

const rel = (startKm: number, km: string | null | undefined): number | null => {
  if (km === null || km === undefined || km === "") return null;
  const n = Number(km);
  return Number.isFinite(n) ? n - startKm : null;
};
const round1 = (n: number) => Math.round(n * 10) / 10;


export function rideStrip(trip: TripSummary, meId: string | null): Strip | null {
  const start = trip.odometer_start_km === null ? NaN : Number(trip.odometer_start_km);
  if (!Number.isFinite(start)) return null;
  const live = trip.state === "IN_PROGRESS";
  const endAbs = trip.odometer_end_km ?? trip.latest_odometer?.km ?? null;
  const total = Math.max(0, rel(start, endAbs) ?? 0);

  const clamp = (n: number) => Math.min(total, Math.max(0, n));
  const lanes: Lane[] = [];
  for (const p of trip.participants) {
    if (!p.is_driver && p.member_status !== "ACTIVE") continue;
    let spans: Span[];
    if (p.is_driver) {
      spans = [{ from: 0, to: total, open: live, exited: false }];
    } else {
      spans = p.intervals
        .filter((i) => i.onboard_at)
        .map((i) => {
          const from = clamp(rel(start, i.odometer_on_km) ?? 0);
          const off = rel(start, i.odometer_off_km);
          const open = i.offboard_at === null;
          return { from, to: open || off === null ? total : clamp(off), open, exited: !open && i.closed_by !== "TRIP_END" };
        })
        .filter((s) => s.to >= s.from)
        .sort((a, b) => a.from - b.from);
    }
    const ridden = round1(spans.reduce((a, s) => a + (s.to - s.from), 0));
    const byDriver = !p.is_driver && p.intervals.some((i) => i.onboard_at && i.opened_by === "DRIVER");
    lanes.push({ userId: p.user_id, nickname: p.nickname, isDriver: p.is_driver, mine: p.user_id === meId, spans, riddenKm: ridden, byDriver });
  }

  lanes.sort((a, b) => Number(b.isDriver) - Number(a.isDriver) || Number(b.mine) - Number(a.mine));
  return { totalKm: round1(total), live, lanes };
}


export function boardedByDriver(p: ParticipantOut | undefined): boolean {
  return Boolean(p && !p.is_driver && p.intervals.some((i) => i.onboard_at && i.opened_by === "DRIVER"));
}

export function pct(km: number, total: number): number {
  if (!(total > 0)) return 0;
  return Math.min(100, Math.max(0, (km / total) * 100));
}

export interface MySegment { from: string; to: string; energyKrw: number; payers: number; each: number; exact: boolean }



export function mySegments(segments: readonly SegmentOut[], startKm: string | null, meId: string): MySegment[] {
  const start = startKm === null ? NaN : Number(startKm);
  const km = (v: string) => {
    const n = Number(v) - (Number.isFinite(start) ? start : 0);
    return String(round1(n));
  };
  return segments
    .filter((g) => g.payers.includes(meId) && g.payers.length > 0)
    .map((g) => {
      const n = g.payers.length;
      const exact = g.energy_krw % n === 0;
      return { from: km(g.start_km), to: km(g.end_km), energyKrw: g.energy_krw, payers: n, each: Math.floor(g.energy_krw / n), exact };
    });
}


export function segmentTicks(segments: readonly SegmentOut[], startKm: string | null): number[] {
  const start = startKm === null ? NaN : Number(startKm);
  if (!Number.isFinite(start)) return [];
  return segments.slice(1).map((g) => round1(Number(g.start_km) - start)).filter((n) => Number.isFinite(n) && n > 0);
}
