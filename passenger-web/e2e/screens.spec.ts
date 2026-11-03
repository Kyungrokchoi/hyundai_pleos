import { mkdirSync, writeFileSync } from "node:fs";
import { test, type Page } from "@playwright/test";
import { Driver } from "./driver";
import { joinApproved, myUserId } from "./helpers";

// Optional screenshot capture, not a test gate:
//   E2E_SHOTS=1 E2E_SHOTS_DIR=/some/dir npx playwright test e2e/screens.spec.ts
// Also writes heights.json (document height in CSS px at 412px width).
const OUT = process.env.E2E_SHOTS_DIR ?? "test-results/screens";
test.skip(!process.env.E2E_SHOTS, "set E2E_SHOTS=1 to capture screenshots");

test("capture the main screens at phone size", async ({ browser, page: home }) => {
  mkdirSync(OUT, { recursive: true });
  const heights: Record<string, number> = {};
  const shot = async (p: Page, name: string, opts: { full?: boolean } = {}) => {
    await p.waitForTimeout(350); // let entrance transitions settle
    heights[name] = await p.evaluate(() => document.documentElement.scrollHeight);
    await p.screenshot({ path: `${OUT}/${name}.png`, fullPage: opts.full ?? true });
  };

  await home.goto("/");
  await shot(home, "01-home");

  // values are typed in by the test driver, so MANUAL rather than PLEOS
  const driver = await new Driver("MANUAL").setup();
  const phone = { locale: "ko-KR", viewport: { width: 412, height: 915 }, deviceScaleFactor: 2 };
  const ctx = await browser.newContext({ ...phone, permissions: ["clipboard-read", "clipboard-write"] });
  const p = await ctx.newPage();
  await p.goto(`/join#${await driver.invite()}`);
  await p.getByRole("textbox", { name: /닉네임/ }).fill("별이");
  await p.getByRole("checkbox", { name: /주행거리를 저장하는 데 동의/ }).check();
  await p.getByRole("checkbox", { name: /정산 규칙.*동의/ }).check();
  await shot(p, "02-join-ready", { full: false });
  await p.getByRole("button", { name: "참여 요청 보내기" }).click();
  await p.getByTestId("waiting").waitFor();
  await shot(p, "03-waiting", { full: false });
  await driver.approveMember(await myUserId(p));
  await p.waitForURL(new RegExp(`/trip/${driver.tripId}$`));

  // a second passenger so the strip has three lanes
  const ctx2 = await browser.newContext(phone);
  const q = await ctx2.newPage();
  await joinApproved(q, driver, "달이");

  await p.getByTestId("board").waitFor();
  await shot(p, "04-trip-inviting-board", { full: false });
  await p.getByTestId("board").click();
  await p.getByTestId("boarded-parked").waitFor();
  await shot(p, "04b-trip-boarded-parked", { full: false });
  await q.getByTestId("board").click();
  await driver.ready();
  await driver.start(1000);
  await driver.drive(1013);
  await p.getByTestId("distance").filter({ hasText: "13" }).waitFor();
  await shot(p, "05-trip-driving-strip", { full: false });
  await p.screenshot({ path: `${OUT}/05-trip-driving-fullpage.png`, fullPage: true });
  await p.getByTestId("alight").click();
  await p.getByTestId("alight-confirm").click();
  await p.getByTestId("alighted").waitFor();
  await driver.drive(1024);
  await p.getByTestId("strip-axis").filter({ hasText: "24" }).waitFor();
  await shot(p, "06-trip-alighted", { full: false });

  await driver.end(1024);
  await driver.toll(2400);
  const s = await driver.preview();
  await p.goto(`/trip/${driver.tripId}/bill`);
  await p.getByTestId("my-total").waitFor();
  await shot(p, "07-bill-top", { full: false });
  await p.screenshot({ path: `${OUT}/07-bill-fullpage.png`, fullPage: true });
  heights["07-bill-fullpage"] = await p.evaluate(() => document.documentElement.scrollHeight);
  await p.getByTestId("approve").click();
  await q.goto(`/trip/${driver.tripId}/bill`);
  await q.getByTestId("approve").click();
  await driver.approveSettlement(s.id);
  await p.getByTestId("celebrate").waitFor();
  await p.getByTestId("copy-amount").click();
  await p.getByTestId("amount-copied").filter({ hasText: "복사했어요" }).waitFor();
  await shot(p, "08-bill-confirmed-copy", { full: false });
  await p.screenshot({ path: `${OUT}/08-bill-confirmed-fullpage.png`, fullPage: true });
  await p.goto(`/group/${driver.groupId}`);
  await p.getByTestId("moa-level").waitFor();
  await shot(p, "09-group");
  await ctx.close();
  await ctx2.close();
  writeFileSync(`${OUT}/heights.json`, JSON.stringify(heights, null, 2));
});
