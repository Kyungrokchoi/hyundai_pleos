import { describe, expect, it } from "vitest";
import { ApiError, NetworkError, describeError, parseErrorBody } from "@/lib/errors";

describe("error mapping", () => {
  it("maps a network failure to offline + retry", () => {
    const v = describeError(new NetworkError());
    expect(v.kind).toBe("offline");
    expect(v.retryable).toBe(true);
  });
  it.each([
    [401, "auth", false], [403, "forbidden", false], [404, "notfound", false], [410, "gone", false],
    [409, "conflict", true], [413, "toolarge", false], [422, "validation", false], [500, "server", true], [503, "server", true],
  ])("HTTP %i -> %s (retryable=%s)", (status, kind, retry) => {
    const v = describeError(new ApiError(status as number, null, null));
    expect(v.kind).toBe(kind);
    expect(v.retryable).toBe(retry);
  });
  it("treats an unknown invite (404) as expired, not as a missing page", () => {
    expect(describeError(new ApiError(404, null, null), { invite: true }).kind).toBe("gone");
  });
  it("prefers the server's Korean sentence for validation and conflicts only", () => {
    expect(describeError(new ApiError(422, "X", "닉네임이 너무 길어요")).body).toBe("닉네임이 너무 길어요");
    expect(describeError(new ApiError(409, "X", "이미 종료된 이동이에요")).body).toBe("이미 종료된 이동이에요");
    expect(describeError(new ApiError(500, "X", "Traceback at line 3")).body).not.toContain("Traceback");
  });
  it("never leaks raw exceptions for unknown errors", () => {
    const v = describeError(new Error("boom: secret/path"));
    expect(v.kind).toBe("server");
    expect(v.body).not.toContain("secret");
  });
  it("parses {code,message_ko}, nested detail, and FastAPI string detail", () => {
    expect(parseErrorBody(409, { code: "STATE", message_ko: "상태 오류" })).toMatchObject({ status: 409, code: "STATE", messageKo: "상태 오류" });
    expect(parseErrorBody(403, { detail: { code: "FORBIDDEN", message_ko: "권한 없음" } })).toMatchObject({ code: "FORBIDDEN", messageKo: "권한 없음" });
    expect(parseErrorBody(404, { detail: "Not Found" })).toMatchObject({ status: 404, code: null, messageKo: null });
    expect(parseErrorBody(422, { detail: [{ loc: ["body"], msg: "x" }] })).toMatchObject({ status: 422 });
    expect(parseErrorBody(500, null)).toMatchObject({ status: 500 });
  });
});
