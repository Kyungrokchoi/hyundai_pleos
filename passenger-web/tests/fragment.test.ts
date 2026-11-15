import { describe, expect, it } from "vitest";
import { parseTokenFragment } from "@/lib/fragment";

const TOKEN = "AbCdEfGh1234567_-xyzXYZ0123456789abcdefg";

describe("invite token fragment", () => {
  it("reads #<token>", () => expect(parseTokenFragment(`#${TOKEN}`)).toBe(TOKEN));
  it("tolerates #t=<token>", () => expect(parseTokenFragment(`#t=${TOKEN}`)).toBe(TOKEN));
  it("rejects empty, short, and odd characters", () => {
    expect(parseTokenFragment("")).toBeNull();
    expect(parseTokenFragment("#")).toBeNull();
    expect(parseTokenFragment("#short")).toBeNull();
    expect(parseTokenFragment(`#${TOKEN}<script>`)).toBeNull();
    expect(parseTokenFragment("#%E0%A4%A")).toBeNull();
  });
  it("never accepts query-string style input", () => {
    expect(parseTokenFragment(`?t=${TOKEN}`)).toBeNull();
  });
});
