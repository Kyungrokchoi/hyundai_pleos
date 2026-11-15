"use client";
import { useEffect, useSyncExternalStore } from "react";
import { parseTokenFragment } from "./fragment";
import { INVITE_EVENT, readInviteToken, stashInviteToken } from "./session";

const READING = "\u0000";

function subscribe(cb: () => void) {
  window.addEventListener(INVITE_EVENT, cb);
  return () => window.removeEventListener(INVITE_EVENT, cb);
}

const read = () => parseTokenFragment(window.location.hash) ?? readInviteToken() ?? "";



export function useInviteToken(): string | null | undefined {
  const raw = useSyncExternalStore(subscribe, read, () => READING);
  useEffect(() => {
    const frag = parseTokenFragment(window.location.hash);
    if (frag) {
      stashInviteToken(frag);
      window.history.replaceState(null, "", window.location.pathname);
    }
  }, []);
  if (raw === READING) return undefined;
  return raw || null;
}
