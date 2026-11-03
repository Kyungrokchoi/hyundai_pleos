import { describe, expect, it } from "vitest";
import { uniqueDisclaimers } from "@/lib/notices";

describe("uniqueDisclaimers", () => {
  it("drops exact duplicates of a warning shown elsewhere, keeps everything else verbatim and in order", () => {
    const issues = [{ code: "DEMO", message_ko: "DEMO / 가상 데이터입니다." }];
    expect(uniqueDisclaimers(["이 금액은 추정치입니다.", "DEMO / 가상 데이터입니다.", "이 금액은 추정치입니다."], issues))
      .toEqual(["이 금액은 추정치입니다."]);
  });
  it("keeps all when nothing overlaps", () => {
    expect(uniqueDisclaimers(["a", "b"], [])).toEqual(["a", "b"]);
  });
});
