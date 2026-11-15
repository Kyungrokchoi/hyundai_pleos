

export interface Session {
  userId: string;
  token: string;
  nickname: string;

  groupId?: string;
  tripId?: string | null;

  pending?: boolean;
}

const KEY = "moata.session.v1";
const INVITE_KEY = "moata.invite.v1";

function ls(): Storage | null {
  try { return typeof window === "undefined" ? null : window.localStorage; } catch { return null; }
}
function ss(): Storage | null {
  try { return typeof window === "undefined" ? null : window.sessionStorage; } catch { return null; }
}

export function loadSession(): Session | null {
  const raw = ls()?.getItem(KEY);
  if (!raw) return null;
  try {
    const s = JSON.parse(raw) as Partial<Session>;
    if (typeof s.userId === "string" && typeof s.token === "string" && typeof s.nickname === "string") return s as Session;
  } catch {  }
  return null;
}
export const SESSION_EVENT = "moata:session";
export const SESSION_KEY = KEY;

function notify(): void {
  if (typeof window !== "undefined") window.dispatchEvent(new Event(SESSION_EVENT));
}
export function saveSession(s: Session): void {
  try { ls()?.setItem(KEY, JSON.stringify(s)); } catch {  }
  notify();
}
export function updateSession(patch: Partial<Session>): Session | null {
  const cur = loadSession();
  if (!cur) return null;
  const next = { ...cur, ...patch };
  saveSession(next);
  return next;
}
export function clearSession(): void {
  try { ls()?.removeItem(KEY); } catch {  }
  notify();
}

export const INVITE_EVENT = "moata:invite";
function notifyInvite(): void {
  if (typeof window !== "undefined") window.dispatchEvent(new Event(INVITE_EVENT));
}
export function stashInviteToken(token: string): void {
  try { ss()?.setItem(INVITE_KEY, token); } catch {  }
  notifyInvite();
}
export function readInviteToken(): string | null {
  return ss()?.getItem(INVITE_KEY) ?? null;
}
export function dropInviteToken(): void {
  try { ss()?.removeItem(INVITE_KEY); } catch {  }
  notifyInvite();
}
