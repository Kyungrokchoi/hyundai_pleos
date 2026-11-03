"use client";
import { useEffect } from "react";
import { ApiError } from "./errors";
import { clearSession } from "./session";

// A 401 means the stored token is dead and retrying cannot help; clear it so screens fall back to "join again".
export function useSessionGuard(...errors: unknown[]): void {
  const dead = errors.some((e) => e instanceof ApiError && e.status === 401);
  useEffect(() => {
    if (dead) clearSession();
  }, [dead]);
}
