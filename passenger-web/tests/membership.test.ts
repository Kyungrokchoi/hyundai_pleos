import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "@/api/client";
import { ApiError, NetworkError } from "@/lib/errors";
import { fetchMyStatus } from "@/lib/membership";

vi.mock("@/api/client", () => ({ api: { myMembership: vi.fn() } }));
const myMembership = vi.mocked(api.myMembership);

describe("fetchMyStatus", () => {
  beforeEach(() => { myMembership.mockReset(); });

  it.each(["PENDING", "ACTIVE", "REJECTED"])("returns %s from the server", async (status) => {
    myMembership.mockResolvedValue({ group_id: "g1", user_id: "me", role: "MEMBER", status } as never);
    expect(await fetchMyStatus("t", "g1")).toBe(status);
  });

  it.each([401, 404])("propagates HTTP %i instead of pretending to wait", async (status) => {
    myMembership.mockRejectedValue(new ApiError(status, null, null));
    const err = await fetchMyStatus("t", "g1").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(status);
  });

  it("propagates network errors", async () => {
    myMembership.mockRejectedValue(new NetworkError());
    const err = await fetchMyStatus("t", "g1").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(NetworkError);
  });
});
