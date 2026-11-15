import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Result } from "@/api/client";
import { NetworkError } from "@/lib/errors";
import { usePolling } from "@/lib/usePolling";

type D = { revision: number };
const ok = (revision: number, etag: string | null = `"${revision}"`): Result<D> => ({ status: "ok", data: { revision }, etag });

async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}

describe("usePolling", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("sends the previous ETag, keeps data on 304, and replaces it on change", async () => {
    const seen: Array<string | null> = [];
    const replies: Array<Result<D>> = [ok(1), { status: "not-modified" }, ok(2)];
    const fetcher = vi.fn(async (etag: string | null) => { seen.push(etag); return replies.shift()!; });

    const { result } = renderHook(() => usePolling<D>(fetcher, { intervalMs: 1000 }));
    await advance(0);
    expect(result.current.data).toEqual({ revision: 1 });
    expect(result.current.loading).toBe(false);

    await advance(1000);
    expect(result.current.data).toEqual({ revision: 1 });
    await advance(1000);
    expect(result.current.data).toEqual({ revision: 2 });
    expect(seen).toEqual([null, '"1"', '"1"']);
  });

  it("derives the ETag from the body when the browser hides the header (CORS)", async () => {
    const seen: Array<string | null> = [];
    const fetcher = vi.fn(async (etag: string | null) => { seen.push(etag); return ok(7, null); });
    renderHook(() => usePolling<D>(fetcher, { intervalMs: 1000 }, (d) => `"${d.revision}"`));
    await advance(0);
    await advance(1000);
    expect(seen).toEqual([null, '"7"']);
  });

  it("keeps showing the last good data when a refresh fails, flags stale/offline, then recovers", async () => {
    const replies: Array<Result<D> | Error> = [ok(1), new NetworkError(), ok(2)];
    const fetcher = vi.fn(async () => { const r = replies.shift()!; if (r instanceof Error) throw r; return r; });
    const { result } = renderHook(() => usePolling<D>(fetcher, { intervalMs: 1000 }));
    await advance(0);
    await advance(1000);
    expect(result.current.data).toEqual({ revision: 1 });
    expect(result.current.stale).toBe(true);
    expect(result.current.offline).toBe(true);
    expect(result.current.error).toBeInstanceOf(NetworkError);

    await advance(2000);
    expect(result.current.data).toEqual({ revision: 2 });
    expect(result.current.stale).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it("backs off exponentially after failures", async () => {
    const fetcher = vi.fn(async () => { throw new NetworkError(); });
    renderHook(() => usePolling<D>(fetcher, { intervalMs: 1000, maxBackoffMs: 8000 }));
    await advance(0);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await advance(1999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(fetcher).toHaveBeenCalledTimes(2);
    await advance(4000);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it("does not poll while disabled and stops on unmount", async () => {
    const fetcher = vi.fn(async () => ok(1));
    const { rerender, unmount } = renderHook(({ enabled }) => usePolling<D>(fetcher, { intervalMs: 1000, enabled }), { initialProps: { enabled: false } });
    await advance(3000);
    expect(fetcher).not.toHaveBeenCalled();
    rerender({ enabled: true });
    await advance(0);
    expect(fetcher).toHaveBeenCalledTimes(1);
    unmount();
    await advance(5000);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("refresh() fetches immediately", async () => {
    const fetcher = vi.fn(async () => ok(1));
    const { result } = renderHook(() => usePolling<D>(fetcher, { intervalMs: 60_000 }));
    await advance(0);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await act(async () => { result.current.refresh(); await vi.advanceTimersByTimeAsync(0); });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
