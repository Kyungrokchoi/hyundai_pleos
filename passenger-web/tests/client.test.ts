import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, getApiBase, request } from "@/api/client";
import { ApiError, NetworkError } from "@/lib/errors";

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers });

describe("api client", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

  it("derives the API base from the page host so a phone on the LAN reaches the PC", () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE", "");
    expect(getApiBase()).toBe(`${window.location.protocol}//${window.location.hostname}:8000/api/v1`);
  });
  it("honours NEXT_PUBLIC_API_BASE and trims a trailing slash", () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE", "https://moata.example/api/v1/");
    expect(getApiBase()).toBe("https://moata.example/api/v1");
  });

  it("sends bearer + If-None-Match, never puts tokens in the URL, and bypasses caches", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE", "http://api.test/api/v1");
    fetchMock.mockResolvedValue(json(200, { revision: 3 }, { ETag: '"3"' }));
    const r = await api.tripSummary("secret-token", "trip 1/x", '"2"');
    expect(r).toMatchObject({ status: "ok", etag: '"3"' });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.test/api/v1/trips/trip%201%2Fx/summary");
    expect(url).not.toContain("secret-token");
    expect(init.headers).toMatchObject({ Authorization: "Bearer secret-token", "If-None-Match": '"2"' });
    expect(init.cache).toBe("no-store");
    expect(init.credentials).toBe("omit");
  });

  it("posts the invite token in the body, not in the URL", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE", "http://api.test/api/v1");
    fetchMock.mockResolvedValue(json(200, { group_name: "g" }));
    await api.previewInvite("INVITE-TOKEN-1234567890");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.test/api/v1/invites/preview");
    expect(JSON.parse(init.body as string)).toEqual({ token: "INVITE-TOKEN-1234567890" });
  });

  it("resolves 304 as not-modified", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 304 }));
    expect(await request("/x")).toEqual({ status: "not-modified" });
  });

  it("throws ApiError carrying the server's Korean message", async () => {
    fetchMock.mockResolvedValue(json(409, { code: "TRIP_STATE", message_ko: "아직 출발 전이에요" }));
    await expect(request("/x")).rejects.toMatchObject({ name: "ApiError", status: 409, code: "TRIP_STATE", messageKo: "아직 출발 전이에요" });
  });

  it("wraps connection failures in NetworkError", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    await expect(request("/x")).rejects.toBeInstanceOf(NetworkError);
  });

  it("tolerates non-JSON error bodies", async () => {
    fetchMock.mockResolvedValue(new Response("<html>bad gateway</html>", { status: 502 }));
    await expect(request("/x")).rejects.toBeInstanceOf(ApiError);
  });
});
