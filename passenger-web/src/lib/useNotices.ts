"use client";
import { useMemo, useSyncExternalStore } from "react";
import { NOTICES_EVENT, NOTICES_KEY, parseNotices } from "./notices";

function subscribe(cb: () => void) {
  window.addEventListener("storage", cb);
  window.addEventListener(NOTICES_EVENT, cb);
  return () => {
    window.removeEventListener("storage", cb);
    window.removeEventListener(NOTICES_EVENT, cb);
  };
}
const read = () => {
  try { return window.localStorage.getItem(NOTICES_KEY) ?? ""; } catch { return ""; }
};

// Empty until a trip or invite has been opened on this device.
export function useRecalledNotices(): string[] {
  const raw = useSyncExternalStore(subscribe, read, () => "");
  return useMemo(() => parseNotices(raw), [raw]);
}
