import { describe, expect, it } from "vitest";
import type { IntervalOut, ParticipantOut, SegmentOut, TripSummary } from "@/api/types";
import { boardedByDriver, mySegments, pct, rideStrip, segmentTicks } from "@/lib/ride";
import { laneText } from "@/components/RideStrip";

const iv = (on: string | null, off: string | null, closedBy: IntervalOut["closed_by"] = off ? "USER" : null): IntervalOut => ({
  id: Math.random().toString(36).slice(2), user_id: "u", onboard_at: "2026-11-02T10:00:00Z", offboard_at: off ? "2026-11-02T10:30:00Z" : null,
  odometer_on_km: on, odometer_off_km: off, boundary_quality: "VERIFIED", closed_by: closedBy,
});
const person = (id: string, nickname: string, intervals: IntervalOut[], extra: Partial<ParticipantOut> = {}): ParticipantOut => ({
  user_id: id, nickname, is_driver: false, member_status: "ACTIVE", on_board: intervals.some((i) => i.offboard_at === null), intervals, ...extra,
});
const trip = (over: Partial<TripSummary>): TripSummary => ({
  id: "t", group_id: "g", group_name: "퇴근", state: "IN_PROGRESS", data_mode: "PLEOS", vehicle_alias: "", driver_user_id: "d", revision: 1,
  started_at: null, ended_at: null, odometer_start_km: "1000", odometer_end_km: null, distance_km: null,
  latest_odometer: { km: "1016", captured_at: "2026-11-02T10:30:00Z", source: "PLEOS", quality: "VERIFIED", age_seconds: 1 },
  cost_rule: null, cost_items: [], participants: [], settlement: null, issues: [], disclaimers: [], ...over,
});

describe("rideStrip (display-only geometry of who rode where)", () => {
  it("is empty until the trip has a first odometer snapshot", () => {
    expect(rideStrip(trip({ odometer_start_km: null, state: "INVITING" }), "b")).toBeNull();
  });

  it("puts the driver first, then me; spans are km since the start; an open span grows to now", () => {
    const t = trip({
      participants: [
        person("c", "달이", [iv("1000", null)]),
        person("d", "하늘", [], { is_driver: true }),
        person("b", "별이", [iv("1000", "1013")]),
        person("p", "대기", [], { member_status: "PENDING" }),
      ],
    });
    const s = rideStrip(t, "b")!;
    expect(s.totalKm).toBe(16);
    expect(s.live).toBe(true);
    expect(s.lanes.map((l) => l.nickname)).toEqual(["하늘", "별이", "달이"]);
    const [driver, me, other] = s.lanes;
    expect(driver!.spans).toEqual([{ from: 0, to: 16, open: true, exited: false }]);
    expect(me!.mine && me!.riddenKm).toBe(13);
    expect(me!.spans[0]!.exited).toBe(true);
    expect(other!.spans[0]).toMatchObject({ from: 0, to: 16, open: true });
    expect(laneText(me!, true)).toBe("13km 탑승");
    expect(laneText(other!, true)).toBe("16km 탑승 · 타는 중");
    expect(laneText(driver!, true)).toBe("16km 운전");
  });

  it("alight then reboard gives two spans and sums only the ridden part", () => {
    const t = trip({ state: "ENDED", odometer_end_km: "1024", participants: [person("b", "별이", [iv("1000", "1010"), iv("1015", "1024", "TRIP_END")])] });
    const me = rideStrip(t, "b")!.lanes[0]!;
    expect(me.spans.map((x) => [x.from, x.to, x.exited])).toEqual([[0, 10, true], [15, 24, false]]);
    expect(me.riddenKm).toBe(19);
    expect(laneText(me, false)).toBe("19km 탑승");
  });

  it("a pre-departure boarding with no on-odometer starts at 0; values are clamped to the trip", () => {
    const t = trip({ state: "ENDED", odometer_end_km: "1010", participants: [person("b", "별이", [iv(null, "1012", "TRIP_END")])] });
    expect(rideStrip(t, "b")!.lanes[0]!.spans[0]).toMatchObject({ from: 0, to: 10 });
  });

  it("someone approved who never boarded has an empty lane that says so", () => {
    const lane = rideStrip(trip({ participants: [person("b", "별이", [])] }), "x")!.lanes[0]!;
    expect(lane.spans).toEqual([]);
    expect(laneText(lane, true)).toBe("탄 구간이 없어요");
  });

  it("a boarding the driver recorded is flagged on the lane and said in words; my own tap is not", () => {
    const byDriver = { ...iv("1000", null), opened_by: "DRIVER" as const };
    const mine = { ...iv("1000", null), opened_by: "USER" as const };
    const t = trip({ participants: [person("d", "하늘", [], { is_driver: true }), person("b", "별이", [byDriver]), person("c", "달이", [mine])] });
    const s = rideStrip(t, "b")!;
    const b = s.lanes.find((l) => l.userId === "b")!, c = s.lanes.find((l) => l.userId === "c")!;
    expect([b.byDriver, c.byDriver, s.lanes[0]!.byDriver]).toEqual([true, false, false]);
    expect(laneText(b, true)).toBe("16km 탑승 · 타는 중 · 운전자가 기록");
    expect(laneText(c, true)).toBe("16km 탑승 · 타는 중");
    expect(boardedByDriver(t.participants[1])).toBe(true);
    expect(boardedByDriver(t.participants[2])).toBe(false);
    expect(boardedByDriver(undefined)).toBe(false);
  });

  it("pct is bounded and safe for a zero-length trip", () => {
    expect(pct(5, 10)).toBe(50);
    expect(pct(15, 10)).toBe(100);
    expect(pct(3, 0)).toBe(0);
  });
});

describe("mySegments (the plain division behind my fuel share)", () => {
  const seg = (start: string, end: string, energy: number, payers: string[], index = 0): SegmentOut => ({
    index, start_km: start, end_km: end, length_km: String(Number(end) - Number(start)), payers, energy_krw: energy,
  });
  it("keeps only segments I paid for, relative to the start, exact or marked approximate", () => {
    const r = mySegments([seg("1000", "1013", 1950, ["b", "d"]), seg("1013", "1024", 1651, ["c", "d"]), seg("1024", "1030", 1000, ["b", "c", "d"])], "1000", "b");
    expect(r).toEqual([
      { from: "0", to: "13", energyKrw: 1950, payers: 2, each: 975, exact: true },
      { from: "24", to: "30", energyKrw: 1000, payers: 3, each: 333, exact: false },
    ]);
  });
  it("segment ticks are the inner boundaries in km since the start", () => {
    expect(segmentTicks([seg("1000", "1013", 1, ["b"]), seg("1013", "1024", 1, ["b"])], "1000")).toEqual([13]);
    expect(segmentTicks([], null)).toEqual([]);
  });
});

describe("laneText shows how long, never a km range", () => {
  const lane = (spans: Array<[number, number, boolean?, boolean?]>, extra: Partial<{ isDriver: boolean; byDriver: boolean }> = {}) => ({
    userId: "u", nickname: "별이", isDriver: false, mine: false, riddenKm: 0, byDriver: false, ...extra,
    spans: spans.map(([from, to, exited = false, open = false]) => ({ from, to, exited, open })),
  });
  it("driver and rider over the whole trip: 24km 운전 / 24km 탑승", () => {
    expect(laneText(lane([[0, 24]], { isDriver: true }), false)).toBe("24km 운전");
    expect(laneText(lane([[0, 24]]), false)).toBe("24km 탑승");
  });
  it("a rider who got off midway shows their length; a late boarding also says where they got off", () => {
    expect(laneText(lane([[0, 16, true]]), false)).toBe("16km 탑승");
    expect(laneText(lane([[5, 13, true]]), false)).toBe("8km 탑승 · 13km에서 내림");
  });
  it("the driver-recorded suffix stays", () => {
    expect(laneText(lane([[0, 24]], { byDriver: true }), false)).toBe("24km 탑승 · 운전자가 기록");
  });
  it("no km range (a dash between numbers) ever appears", () => {
    for (const l of [lane([[0, 24]], { isDriver: true }), lane([[0, 10, true], [15, 24]]), lane([[3, 9, true]])]) expect(laneText(l, true)).not.toMatch(/\d–\d/);
  });
});
