import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "@/api/client";
import { NetworkError } from "@/lib/errors";
import { loadSession, saveSession } from "@/lib/session";
import { signOut } from "@/lib/signout";

vi.mock("@/api/client", () => ({ api: { logout: vi.fn() } }));
const logout = vi.mocked(api.logout);

describe("signOut", () => {
  beforeEach(() => {
    logout.mockReset();
    window.localStorage.clear();
    saveSession({ userId: "u", token: "tok", nickname: "별이" });
  });
  it("revokes the token on the server, then clears the local session", async () => {
    logout.mockResolvedValue(null);
    await signOut("tok");
    expect(logout).toHaveBeenCalledWith("tok");
    expect(loadSession()).toBeNull();
  });
  it("still signs out locally when the server cannot be reached", async () => {
    logout.mockRejectedValue(new NetworkError());
    await signOut("tok");
    expect(loadSession()).toBeNull();
  });
});
