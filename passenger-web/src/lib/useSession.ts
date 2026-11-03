"use client";
import { useMemo, useSyncExternalStore } from "react";
import { SESSION_EVENT, SESSION_KEY, type Session } from "./session";

function subscribe(cb: () => void) {
  window.addEventListener("storage", cb);
  window.addEventListener(SESSION_EVENT, cb);
  return () => {
    window.removeEventListener("storage", cb);
    window.removeEventListener(SESSION_EVENT, cb);
  };
}
const read = () => {
  try { return window.localStorage.getItem(SESSION_KEY) ?? ""; } catch { return ""; }
};
const READING = "\u0000"; // server render / first hydration pass

// undefined = not read yet, null = signed out. The object identity only changes when storage does.
export function useSession(): Session | null | undefined {
  const raw = useSyncExternalStore(subscribe, read, () => READING);
  return useMemo(() => {
    if (raw === READING) return undefined;
    if (!raw) return null;
    try {
      const s = JSON.parse(raw) as Partial<Session>;
      return typeof s.userId === "string" && typeof s.token === "string" && typeof s.nickname === "string" ? (s as Session) : null;
    } catch { return null; }
  }, [raw]);
}
