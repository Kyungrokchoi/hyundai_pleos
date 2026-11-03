// Plays the vehicle app against the real API.
import { randomUUID } from "node:crypto";

export const API = process.env.E2E_API_BASE ?? "http://127.0.0.1:8010/api/v1";
const id = () => randomUUID().replace(/-/g, "");

export type DataMode = "MANUAL" | "DEMO" | "PLEOS";

async function call<T>(method: string, path: string, token: string | null, body?: unknown): Promise<T> {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return (text ? JSON.parse(text) : null) as T;
}

export class Driver {
  groupId = id();
  tripId = id();
  userId = "";
  private token = "";
  constructor(readonly mode: DataMode = "MANUAL") {}

  /** guest -> group -> trip (INVITING) */
  async setup(name = "하늘") {
    const a = await call<{ user_id: string; token: string }>("POST", "/auth/guest", null, { nickname: name, consent_version: "2026-11" });
    this.userId = a.user_id;
    this.token = a.token;
    await call("POST", "/groups", this.token, { id: this.groupId, name: "금요일 퇴근 카풀" });
    await call("POST", "/trips", this.token, {
      id: this.tripId, group_id: this.groupId, vehicle_alias: "테스트카", data_mode: this.mode,
      cost_rule: {
        vehicle_type: "ICE", efficiency_value: 12, efficiency_unit: "km/L",
        efficiency_source: "MANUAL", unit_price_krw: 1800, // typed-in efficiency is MANUAL even on a DEMO trip
        driver_included: true, energy_policy: "SEGMENT_SHARED",
      },
    });
    return this;
  }

  async invite(): Promise<string> {
    const r = await call<{ token: string }>("POST", `/groups/${this.groupId}/invites`, this.token, { trip_id: this.tripId });
    return r.token;
  }
  approveMember = (userId: string) => call("POST", `/groups/${this.groupId}/members/${userId}/decision`, this.token, { decision: "APPROVE" });
  ready = () => call("POST", `/trips/${this.tripId}/ready`, this.token);
  // departure check: the driver marks an approved passenger as on board
  boardFor = (userId: string) => call("POST", `/trips/${this.tripId}/participants/${userId}/board`, this.token);

  private odo(km: number, eventId: string) {
    return {
      event_id: eventId, type: "ODOMETER", value: km, unit: "km", captured_at: new Date().toISOString(),
      source: this.mode, quality: "VERIFIED", sdk_version: this.mode === "PLEOS" ? "2.3.2" : null,
    };
  }
  start = (km: number) => call("POST", `/trips/${this.tripId}/start`, this.token, { odometer: this.odo(km, "s" + id()) });
  drive = (km: number) => call("POST", `/trips/${this.tripId}/readings/batch`, this.token, { readings: [this.odo(km, "r" + id())] });
  end = (km: number) => call("POST", `/trips/${this.tripId}/end`, this.token, { odometer: this.odo(km, "e" + id()) });
  toll = (amount: number) =>
    call("POST", `/trips/${this.tripId}/cost-items`, this.token, { id: id(), kind: "TOLL", amount_krw: amount, allocation: "EQUAL", note: "고속도로" });
  preview = () => call<{ id: string; revision: number }>("POST", `/trips/${this.tripId}/settlements/preview`, this.token);
  summary = () => call<{ settlement: { id: string; state: string } | null; state: string }>("GET", `/trips/${this.tripId}/summary`, this.token);
  approveSettlement = (sid: string) => call("POST", `/settlements/${sid}/approvals`, this.token, { decision: "APPROVE" });
}

/** A signed-in user with no membership anywhere (for 403 checks). */
export async function outsider() {
  return call<{ user_id: string; token: string; nickname: string }>("POST", "/auth/guest", null, { nickname: "외부인", consent_version: "2026-11" });
}
