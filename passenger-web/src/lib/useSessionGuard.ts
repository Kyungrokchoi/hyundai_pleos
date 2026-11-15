"use client";
import { useEffect } from "react";
import { ApiError } from "./errors";
import { clearSession } from "./session";


export function useSessionGuard(...errors: unknown[]): void {
  const dead = errors.some((e) => e instanceof ApiError && e.status === 401);
  useEffect(() => {
    if (dead) clearSession();
  }, [dead]);
}
