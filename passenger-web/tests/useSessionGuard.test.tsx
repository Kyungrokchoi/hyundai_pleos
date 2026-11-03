import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "@/lib/errors";
import { loadSession, saveSession } from "@/lib/session";
import { useSessionGuard } from "@/lib/useSessionGuard";

describe("useSessionGuard", () => {
  beforeEach(() => {
    window.localStorage.clear();
    saveSession({ userId: "u", token: "t", nickname: "별이" });
  });
  it("drops a dead session on 401", () => {
    renderHook(() => useSessionGuard(null, new ApiError(401, null, null)));
    expect(loadSession()).toBeNull();
  });
  it("keeps the session for 403, 404, and network errors", () => {
    renderHook(() => useSessionGuard(new ApiError(403, null, null), new ApiError(404, null, null), new NetworkError()));
    expect(loadSession()).not.toBeNull();
  });
});
