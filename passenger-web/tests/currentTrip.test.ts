import { describe, expect, it, vi } from "vitest";
import { api } from "@/api/client";
import { fetchMyMembership } from "@/lib/membership";

describe("fetchMyMembership", () => {
  it("passes through today's trip so a returning rider is not sent to last week's", async () => {
    vi.spyOn(api, "myMembership").mockResolvedValue({ group_id: "g1", user_id: "u", role: "MEMBER", status: "ACTIVE", current_trip_id: "t-today" });
    expect((await fetchMyMembership("tok", "g1")).current_trip_id).toBe("t-today");
  });
});
