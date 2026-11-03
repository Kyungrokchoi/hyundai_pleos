"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import type { Result } from "@/api/client";
import { NetworkError } from "./errors";

export interface PollState<T> {
  data: T | null;
  error: unknown;
  /** true until the first response (or error) arrives */
  loading: boolean;
  /** last attempt failed; data is from an earlier poll */
  stale: boolean;
  offline: boolean;
  lastUpdated: number | null;
  refresh: () => void;
}

interface Opts {
  intervalMs: number;
  enabled?: boolean;
  maxBackoffMs?: number;
}

// Conditional polling: pauses while the tab is hidden, backs off after errors, keeps the last good data.
// Uses the ETag header when the browser exposes it, otherwise `etagOf(data)`.
export function usePolling<T>(
  fetcher: (etag: string | null, signal: AbortSignal) => Promise<Result<T>>,
  { intervalMs, enabled = true, maxBackoffMs = 30_000 }: Opts,
  etagOf?: (data: T) => string | null,
): PollState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [stale, setStale] = useState(false);
  const [offline, setOffline] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);

  const fetcherRef = useRef(fetcher);
  const etagOfRef = useRef(etagOf);
  const etagRef = useRef<string | null>(null);
  const wake = useRef<(() => void) | null>(null);
  useEffect(() => { fetcherRef.current = fetcher; etagOfRef.current = etagOf; });

  const refresh = useCallback(() => { wake.current?.(); }, []);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let failures = 0;
    let inflight = false;
    const ctrl = new AbortController();

    const schedule = () => {
      if (cancelled) return;
      const delay = failures === 0 ? intervalMs : Math.min(maxBackoffMs, intervalMs * 2 ** Math.min(failures, 5));
      timer = setTimeout(tick, delay);
    };

    const tick = async () => {
      timer = null;
      if (cancelled || inflight) return;
      if (typeof document !== "undefined" && document.visibilityState === "hidden") { schedule(); return; }
      inflight = true;
      try {
        const r = await fetcherRef.current(etagRef.current, ctrl.signal);
        if (cancelled) return;
        if (r.status === "ok") {
          setData(r.data);
          etagRef.current = r.etag ?? etagOfRef.current?.(r.data) ?? null;
        }
        failures = 0;
        setError(null); setStale(false); setOffline(false); setLastUpdated(Date.now());
      } catch (e) {
        if (cancelled || (e instanceof DOMException && e.name === "AbortError")) return;
        failures += 1;
        setError(e); setStale(true);
        setOffline(e instanceof NetworkError || (typeof navigator !== "undefined" && navigator.onLine === false));
      } finally {
        inflight = false;
        if (!cancelled) { setLoading(false); schedule(); }
      }
    };

    wake.current = () => { if (timer) clearTimeout(timer); void tick(); };
    const onVisible = () => { if (document.visibilityState === "visible") wake.current?.(); };
    const onOnline = () => wake.current?.();
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("online", onOnline);
    void tick();

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
      ctrl.abort();
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("online", onOnline);
      wake.current = null;
    };
  }, [enabled, intervalMs, maxBackoffMs]);

  return { data, error, loading, stale, offline, lastUpdated, refresh };
}
