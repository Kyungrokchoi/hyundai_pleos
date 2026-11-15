import type { Issue } from "@/api/types";


export function uniqueDisclaimers(disclaimers: readonly string[], issues: readonly Issue[]): string[] {
  const shown = new Set(issues.map((i) => i.message_ko));
  return [...new Set(disclaimers)].filter((d) => !shown.has(d));
}

export function mergeNotices(...lists: ReadonlyArray<readonly string[]>): string[] {
  return [...new Set(lists.flat())];
}

export const NOTICES_KEY = "moata.notices.v1";
export const NOTICES_EVENT = "moata:notices";


export function rememberNotices(texts: readonly string[]): void {
  if (typeof window === "undefined" || texts.length === 0) return;
  try {
    const next = JSON.stringify(texts);
    if (window.localStorage.getItem(NOTICES_KEY) === next) return;
    window.localStorage.setItem(NOTICES_KEY, next);
    window.dispatchEvent(new Event(NOTICES_EVENT));
  } catch {  }
}

export function parseNotices(raw: string): string[] {
  if (!raw) return [];
  try {
    const v: unknown = JSON.parse(raw);
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
  } catch { return []; }
}
