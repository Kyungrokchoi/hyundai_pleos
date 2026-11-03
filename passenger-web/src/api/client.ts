// Typed fetch wrapper for the MOATA API; the only module that calls fetch().
import { ApiError, NetworkError, parseErrorBody } from "@/lib/errors";
import type {
  ApprovalIn, AuthOut, BoardingIn, CharacterOut, CouponOut, GroupOut, HistoryItem, InviteAcceptIn,
  InviteAcceptOut, InvitePreviewOut, MembershipOut, SettlementOut, TripSummary,
} from "./types";

const DEFAULT_PORT = 8000;

// Falls back to <page host>:8000 so a phone on the LAN reaches the PC's API.
export function getApiBase(): string {
  const env = process.env.NEXT_PUBLIC_API_BASE;
  if (env && env.trim()) return env.trim().replace(/\/+$/, "");
  if (typeof window !== "undefined") {
    const { protocol, hostname } = window.location;
    const host = hostname.includes(":") ? `[${hostname}]` : hostname;
    return `${protocol}//${host}:${DEFAULT_PORT}/api/v1`;
  }
  return `http://localhost:${DEFAULT_PORT}/api/v1`;
}

export type Result<T> = { status: "ok"; data: T; etag: string | null } | { status: "not-modified" };

interface Opts {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  token?: string | null;
  etag?: string | null;
  signal?: AbortSignal;
}

export async function request<T>(path: string, opts: Opts = {}): Promise<Result<T>> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  if (opts.etag) headers["If-None-Match"] = opts.etag;

  let res: Response;
  try {
    res = await fetch(`${getApiBase()}${path}`, {
      method: opts.method ?? "GET",
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      cache: "no-store",
      credentials: "omit",
      signal: opts.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") throw e;
    throw new NetworkError(e);
  }

  if (res.status === 304) return { status: "not-modified" };
  let json: unknown = null;
  const text = await res.text();
  if (text) {
    try { json = JSON.parse(text); } catch { json = null; }
  }
  if (!res.ok) throw parseErrorBody(res.status, json);
  return { status: "ok", data: json as T, etag: res.headers.get("ETag") };
}

async function data<T>(path: string, opts: Opts = {}): Promise<T> {
  const r = await request<T>(path, opts);
  if (r.status !== "ok") throw new ApiError(500, null, null, "unexpected 304");
  return r.data;
}

export const api = {
  previewInvite: (token: string, signal?: AbortSignal) =>
    data<InvitePreviewOut>("/invites/preview", { method: "POST", body: { token }, signal }),

  acceptInvite: (body: InviteAcceptIn, bearer: string | null) =>
    data<InviteAcceptOut>("/invites/accept", { method: "POST", body, token: bearer }),

  logout: (token: string) => data<unknown>("/auth/logout", { method: "POST", token }),

  listGroups: (token: string, signal?: AbortSignal) => data<GroupOut[]>("/groups", { token, signal }),
  // Own membership in any status, including PENDING and REJECTED.
  myMembership: (token: string, groupId: string, signal?: AbortSignal) => data<MembershipOut>(`/groups/${enc(groupId)}/me`, { token, signal }),
  getGroup: (token: string, id: string, signal?: AbortSignal) => data<GroupOut>(`/groups/${enc(id)}`, { token, signal }),

  // Conditional GET; 304 resolves to { status: "not-modified" }.
  tripSummary: (token: string, id: string, etag: string | null, signal?: AbortSignal) =>
    request<TripSummary>(`/trips/${enc(id)}/summary`, { token, etag, signal }),

  board: (token: string, tripId: string, userId: string, body: BoardingIn = {}) =>
    data<unknown>(`/trips/${enc(tripId)}/participants/${enc(userId)}/board`, { method: "POST", body, token }),
  alight: (token: string, tripId: string, userId: string, body: BoardingIn = {}) =>
    data<unknown>(`/trips/${enc(tripId)}/participants/${enc(userId)}/alight`, { method: "POST", body, token }),

  decide: (token: string, settlementId: string, body: ApprovalIn) =>
    data<SettlementOut>(`/settlements/${enc(settlementId)}/approvals`, { method: "POST", body, token }),

  character: (token: string, groupId: string, signal?: AbortSignal) =>
    data<CharacterOut>(`/groups/${enc(groupId)}/character`, { token, signal }),
  history: (token: string, groupId: string, signal?: AbortSignal) =>
    data<HistoryItem[]>(`/groups/${enc(groupId)}/history`, { token, signal }),
  rewards: (token: string, groupId: string, signal?: AbortSignal) =>
    data<CouponOut[]>(`/groups/${enc(groupId)}/rewards`, { token, signal }),
};

export type { AuthOut };

function enc(s: string): string {
  return encodeURIComponent(s);
}
