"use client";
import { useCallback, useEffect, useState } from "react";

interface State<T> { key: string | null; data: T | null; error: unknown }



export function useLoad<T>(fn: ((signal: AbortSignal) => Promise<T>) | null, deps: Array<string | number | null>) {
  const [nonce, setNonce] = useState(0);
  const [state, setState] = useState<State<T>>({ key: null, data: null, error: null });
  const key = `${nonce}|${deps.join("|")}`;

  useEffect(() => {
    if (!fn) return;
    const ctrl = new AbortController();
    fn(ctrl.signal)
      .then((data) => setState({ key, data, error: null }))
      .catch((error) => {
        if (error instanceof DOMException && error.name === "AbortError") return;
        setState((prev) => ({ key, data: prev.data, error }));
      });
    return () => ctrl.abort();

    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  return { data: state.data, error: state.error, loading: fn !== null && state.key !== key, reload };
}
