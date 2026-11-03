import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { API, Driver, outsider } from "./driver";
import { joinApproved, myUserId, openAccordion, plantSession } from "./helpers";

const PHONE = { locale: "ko-KR", viewport: { width: 412, height: 915 } };

async function expectNoA11yViolations(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]).analyze();
  expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(" | ")}`)).toEqual([]);
}

test("full flow: QR join -> approval -> board -> distance -> midway alight -> split -> approve -> Moa grows", async ({ browser }) => {
  const driver = await new Driver("MANUAL").setup();
  const urls: string[] = [];

  const ctxB = await browser.newContext({ ...PHONE, permissions: ["clipboard-read", "clipboard-write"] });
  const b = await ctxB.newPage();
  b.on("request", (r) => urls.push(r.url()));
  const ctxC = await browser.newContext(PHONE);
  const c = await ctxC.newPage();

  // the token arrives in the fragment and is wiped from the address bar
  const tokenB = await driver.invite();
  await b.goto(`/join#${tokenB}`);
  await expect(b.getByRole("heading", { name: "금요일 퇴근 카풀" })).toBeVisible();
  // rules and notices start collapsed; the server's wording is unchanged
  await expect(b.getByTestId("rules")).toBeHidden();
  await openAccordion(b, "정산 규칙 보기");
  await expect(b.getByTestId("rules")).toBeVisible();
  await openAccordion(b, "자세히");
  await expect(b.getByTestId("disclaimers")).toContainText("이 금액은 입력한 연비·단가로 계산한 추정치이며");
  await expect(b.getByTestId("disclaimers")).toContainText("MOATA는 결제·송금을 하지 않습니다.");
  expect(new URL(b.url()).hash).toBe("");
  expect(b.url()).not.toContain(tokenB);
  await expectNoA11yViolations(b);

  // the submit stays disabled until both consents are ticked
  await b.getByRole("textbox", { name: /닉네임/ }).fill("별이");
  await expect(b.getByRole("button", { name: "참여 요청 보내기" })).toBeDisabled();
  await b.getByRole("checkbox", { name: /주행거리를 저장하는 데 동의/ }).check();
  await expect(b.getByRole("button", { name: "참여 요청 보내기" })).toBeDisabled();
  await b.getByRole("checkbox", { name: /정산 규칙.*동의/ }).check();
  await expect(b.getByRole("button", { name: "참여 요청 보내기" })).toBeEnabled();
  await b.getByRole("button", { name: "참여 요청 보내기" }).click();
  await expect(b.getByTestId("waiting")).toBeVisible();

  // B's page moves on by itself once the driver approves
  await driver.approveMember(await myUserId(b));
  await b.waitForURL(new RegExp(`/trip/${driver.tripId}$`));
  await expect(b.getByTestId("state-label")).toHaveText("모이는 중");
  await joinApproved(c, driver, "달이");

  // board while still parked, so there is no race with 출발하기
  await expect(b.getByTestId("board")).toHaveText("탔어요");
  await b.getByTestId("board").click();
  await expect(b.getByText("탔어요로 기록했어요")).toBeVisible();
  // the passenger can undo a wrong tap while parked
  await b.getByTestId("unboard").click();
  await expect(b.getByTestId("board")).toBeVisible();
  await b.getByTestId("board").click();
  await expect(b.getByTestId("boarded-parked")).toBeVisible();
  await expectNoA11yViolations(b);
  // the other passenger boards after the driver marked the trip ready
  await driver.ready();
  await c.getByTestId("board").click();
  await expect(c.getByText("탔어요로 기록했어요")).toBeVisible();

  // both phones show the same distance
  await driver.start(1000);
  await expect(b.getByTestId("state-label")).toHaveText("이동 중");
  await driver.drive(1010);
  await expect(b.getByTestId("distance")).toContainText("10");
  await expect(c.getByTestId("distance")).toContainText("10");
  // one lane per person, driver first
  await expect(b.getByTestId("strip-lane")).toHaveCount(3);
  await expect(c.getByTestId("strip-lane")).toHaveCount(3);
  await expect(b.getByTestId("strip-lane").first()).toContainText("운전자");
  await expect(b.getByTestId("strip-axis")).toContainText("지금 10km");
  await expectNoA11yViolations(b);

  // B gets off halfway (confirm dialog)
  await b.getByTestId("alight").click();
  await expect(b.getByTestId("alight-dialog")).toBeVisible();
  await b.getByTestId("alight-confirm").click();
  await expect(b.locator('[data-testid="strip-lane"]', { hasText: "별이" })).toContainText("0–10km 탑승 · 10km에서 내림");
  // after getting off, the big number is my distance, not the car's
  await expect(b.getByTestId("my-distance")).toContainText("10");
  await expect(b.getByTestId("distance")).toHaveCount(0);
  await expect(b.getByTestId("state-label")).toHaveText("내렸어요");
  await expect(c.getByTestId("state-label")).toHaveText("이동 중");
  // no primary board button right after getting off; re-boarding is a text link
  await expect(b.getByTestId("alighted")).toContainText("10km 지점에서 내렸어요");
  await expect(b.getByTestId("board")).toHaveCount(0);
  await expect(b.getByTestId("alight")).toHaveCount(0);
  await expect(b.getByTestId("reboard")).toBeVisible();
  // a raw odometer reading (1,010km) is never shown to a passenger
  await expect(b.locator("main")).not.toContainText("1,010");

  await driver.drive(1024);
  await driver.end(1024);
  await driver.toll(2400);
  const settlement = await driver.preview();
  expect(settlement.id).toBeTruthy();

  await b.getByTestId("to-bill").click();

  // amount, sentence, notice line and actions are all on the first screen
  await expect(b.getByTestId("my-total")).toHaveText(/1,300/);
  await expect(b.getByTestId("riding-sentence")).toHaveText("24km 중 10km를 함께 탔어요.");
  // the test driver types every value, so a single MANUAL row covers them all
  await expect(b.getByTestId("source-manual")).toHaveText("MANUAL · 직접 입력 거리 · 연비 · 유가 · 통행료·주차비");
  await expect(b.getByTestId("source-line").getByTestId("source-badge")).toHaveCount(1);
  // the amount column holds only addends (500 + 800 = 1,300); a single segment does not repeat its result
  await expect(b.getByTestId("my-segment")).toHaveCount(1);
  await expect(b.getByTestId("my-segment")).toContainText("1,500원 ÷ 3명");
  await expect(b.getByTestId("my-segment")).not.toContainText("= 500원");
  await expect(b.getByTestId("my-energy").locator("dd")).toHaveText("500원");
  // the toll is split equally regardless of distance
  await expect(b.getByTestId("my-item")).toContainText("통행료 · 고속도로");
  await expect(b.getByTestId("item-reason")).toHaveText("2,400원 ÷ 3명 · 탄 거리와 관계없이 똑같이");
  await expect(b.getByTestId("my-item").locator("dd")).toHaveText("800원");
  await expect(b.getByText("이 금액에 동의해요 · 돈은 나가지 않아요")).toBeVisible();
  await expect(b.getByText("추정 금액이에요 · 결제·송금은 하지 않아요")).toBeVisible();
  await expect(b.getByTestId("approve")).toBeInViewport();
  await expect(b.getByTestId("dispute-open")).toBeInViewport();
  await expect(b.getByTestId("equation")).toBeHidden(); // collapsed by default
  await expect(b.getByRole("button", { name: /송금|이체|결제하기|결제/ })).toHaveCount(0);
  await expectNoA11yViolations(b);

  await openAccordion(b, "어떻게 계산했어요?");
  await expect(b.getByTestId("equation")).toContainText("24km ÷ 12km/L × 1,800원/L = 3,600원");
  await expect(b.getByTestId("total")).toContainText("6,000원");
  // 별이 rode 10 of 24 km: 500 energy + 800 toll = 1,300. 달이 and the driver: 1,550 + 800 = 2,350.
  await expect(b.getByTestId("my-basis")).toContainText("500원");
  await expect(b.getByTestId("my-basis")).toContainText("800원");
  await expect(b.getByTestId("segments").locator("li")).toHaveCount(2);
  await expect(b.getByTestId("segments")).toContainText("출발 후 0–10km"); // trip-relative, never raw odometer values
  await expect(b.getByTestId("segments")).not.toContainText("1,000");
  await expect(b.getByText("운전자가 직접 적은 값이에요.")).toBeVisible();
  await openAccordion(b, "모두의 몫");
  await expect(b.getByTestId("shares")).toContainText("2,350원");
  await expectNoA11yViolations(b);
  // the action bar stays pinned after scrolling to the bottom
  await b.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await expect(b.getByTestId("approve")).toBeInViewport();

  // everyone approves (B through the UI)
  await b.getByTestId("approve").click();
  await expect(b.getByTestId("my-approval")).toContainText("승인");
  await expect(b.getByTestId("approve")).toHaveCount(0); // no second primary action after deciding
  await c.goto(`/trip/${driver.tripId}/bill`);
  await c.getByTestId("approve").click();
  await driver.approveSettlement(settlement.id);
  await expect(b.getByTestId("celebrate")).toBeVisible();
  // agreed, not paid: paying the driver happens outside the app
  await expect(b.getByTestId("settlement-state")).toHaveText("금액 합의 완료");
  await expect(b.getByTestId("pay-note")).toContainText("하늘님께 직접 보내 주세요");
  await expect(b.getByTestId("copy-amount")).toHaveText(/1,300원 복사/);
  await b.getByTestId("copy-amount").click();
  await expect(b.getByTestId("amount-copied")).toContainText("1,300원을 복사했어요");
  expect(await b.evaluate(() => navigator.clipboard.readText())).toBe("1300");
  await expectNoA11yViolations(b);

  // Moa grew exactly once for this trip
  await b.getByRole("link", { name: "모아 만나러 가기" }).click();
  await expect(b.getByTestId("moa-level")).toContainText("Lv.1");
  await expect(b.getByTestId("xp-line")).toContainText("10");
  await expect(b.getByTestId("history").locator("li")).toHaveCount(1);
  await expect(b.getByText("시연용 · 실제 교환 불가").first()).toBeVisible();
  await expectNoA11yViolations(b);

  // the invite token never appeared in a request URL
  expect(urls.filter((u) => u.includes(tokenB))).toEqual([]);
  await ctxB.close();
  await ctxC.close();
});

test("dispute: a passenger can object with a reason and the bill says so", async ({ browser }) => {
  const driver = await new Driver("MANUAL").setup();
  const ctx = await browser.newContext(PHONE);
  const p = await ctx.newPage();
  await joinApproved(p, driver, "별이");
  await driver.ready();
  await p.getByTestId("board").click();
  await driver.start(500);
  await driver.drive(520);
  await driver.end(520);
  await driver.preview();

  await p.goto(`/trip/${driver.tripId}/bill`);
  await p.getByTestId("dispute-open").click();
  await expect(p.getByTestId("dispute-send")).toBeDisabled(); // a reason is required
  await p.getByTestId("dispute-comment").fill("톨비가 두 번 들어간 것 같아요");
  await p.getByTestId("dispute-send").click();
  await expect(p.getByTestId("my-approval")).toContainText("이의 제기");
  await openAccordion(p, "모두의 몫");
  await expect(p.getByTestId("shares")).toContainText("톨비가 두 번");
  await ctx.close();
});

test("a boarding the driver recorded is shown to the rider, on the trip and on the bill", async ({ browser }) => {
  const driver = await new Driver("MANUAL").setup();
  const ctx = await browser.newContext(PHONE);
  const p = await ctx.newPage();
  await joinApproved(p, driver, "별이");
  // the rider never taps 탔어요; the driver marks them at departure
  await driver.boardFor(await myUserId(p));
  await driver.ready();
  await driver.start(500);
  await expect(p.getByTestId("boarded-by-driver")).toContainText("운전자가 대신 탑승으로 기록했어요");
  await expect(p.locator('[data-testid="strip-lane"]', { hasText: "별이" })).toContainText("운전자가 기록");
  await expectNoA11yViolations(p);
  await driver.drive(512);
  await driver.end(512);
  await driver.toll(2400);
  await driver.preview();
  await p.goto(`/trip/${driver.tripId}/bill`);
  await expect(p.locator('[data-testid="strip-lane"]', { hasText: "별이" })).toContainText("0–12km 탑승 · 운전자가 기록");
  await expect(p.getByTestId("item-reason")).toHaveText("2,400원 ÷ 2명 · 탄 거리와 관계없이 똑같이");
  await expectNoA11yViolations(p);
  await ctx.close();
});

test("DEMO trips keep a persistent 'DEMO / 가상 데이터' banner and badge on every trip screen", async ({ browser }) => {
  const driver = await new Driver("DEMO").setup();
  const ctx = await browser.newContext(PHONE);
  const p = await ctx.newPage();
  await joinApproved(p, driver, "별이");
  await expect(p.getByTestId("demo-bar")).toContainText("DEMO / 가상 데이터");
  await expect(p.getByTestId("source-badge")).toHaveAttribute("data-source", "DEMO");
  await driver.ready();
  await p.getByTestId("board").click();
  await driver.start(0);
  await driver.drive(24);
  await driver.end(24);
  await driver.preview();
  await p.goto(`/trip/${driver.tripId}/bill`);
  await expect(p.getByTestId("demo-bar")).toBeVisible();
  await expect(p.getByTestId("bill-live")).toBeAttached();
  await openAccordion(p, "단체 대화방에 공유");
  await expect(p.getByTestId("share")).toBeVisible();
  await ctx.close();
});

test("invalid or expired invite explains itself and offers no form", async ({ page }) => {
  await page.goto("/join#" + "x".repeat(43));
  await expect(page.getByTestId("error-state")).toHaveAttribute("data-kind", "gone");
  await expect(page.getByText("초대 링크가 만료되었어요")).toBeVisible();
  await expect(page.getByRole("button", { name: "참여 요청 보내기" })).toHaveCount(0);

  // clearing a dead invite falls back to the "invite needed" screen
  await page.getByRole("button", { name: "이 초대 정보 지우기" }).click();
  await expect(page.getByRole("heading", { name: "초대 링크가 필요해요" })).toBeVisible();
});

test("signed-out visitors and strangers are told why they cannot see a trip", async ({ page, browser }) => {
  const driver = await new Driver("MANUAL").setup();

  await page.goto(`/trip/${driver.tripId}`);
  await expect(page.getByTestId("need-join")).toBeVisible();

  // non-member: 404 or 403, never data
  const who = await outsider();
  const ctx = await browser.newContext(PHONE);
  const p = await ctx.newPage();
  await plantSession(p, { userId: who.user_id, token: who.token, nickname: who.nickname });
  await p.goto(`/trip/${driver.tripId}`);
  await expect(p.getByTestId("error-state")).toHaveAttribute("data-kind", /^(forbidden|notfound)$/);
  await expect(p.getByTestId("participants")).toHaveCount(0);

  // unknown token (401): the session is dropped and the visitor is asked to rejoin
  const q = await (await browser.newContext({ locale: "ko-KR" })).newPage();
  await plantSession(q, { userId: "ghost", token: "not-a-real-token-0000000000", nickname: "유령" });
  await q.goto(`/trip/${driver.tripId}`);
  await expect(q.getByTestId("need-join")).toBeVisible();
  await expect.poll(() => q.evaluate(() => window.localStorage.getItem("moata.session.v1"))).toBeNull();
  await ctx.close();
});

test("connection loss keeps the last data on screen, says so, and recovers", async ({ browser }) => {
  const driver = await new Driver("MANUAL").setup();
  const ctx = await browser.newContext(PHONE);
  const p = await ctx.newPage();
  await joinApproved(p, driver, "별이");
  await driver.ready();
  await driver.start(100);
  await driver.drive(112);
  await expect(p.getByTestId("distance")).toContainText("12");

  await ctx.setOffline(true);
  await expect(p.getByTestId("stale-notice")).toBeVisible();
  await expect(p.getByTestId("distance")).toContainText("12"); // old data is still there
  await ctx.setOffline(false);
  await expect(p.getByTestId("stale-notice")).toBeHidden({ timeout: 40_000 });
  await ctx.close();
});

test("home and 404 pages have no accessibility violations", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("공평하게");
  await expectNoA11yViolations(page);
  await page.goto("/does-not-exist");
  await expect(page.getByText("찾는 화면이 없어요")).toBeVisible();
  await expectNoA11yViolations(page);
});

test("a blank nickname is rejected inline before anything is sent", async ({ page }) => {
  const driver = await new Driver("MANUAL").setup();
  await page.goto(`/join#${await driver.invite()}`);
  await page.getByRole("textbox", { name: /닉네임/ }).fill("   ");
  await page.getByRole("textbox", { name: /닉네임/ }).blur();
  await expect(page.getByText("닉네임을 1~20자로 입력해 주세요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "참여 요청 보내기" })).toBeDisabled();
});

test("leaving this device revokes the token on the server, not just in the browser", async ({ browser }) => {
  const driver = await new Driver("MANUAL").setup();
  const ctx = await browser.newContext(PHONE);
  const p = await ctx.newPage();
  await joinApproved(p, driver, "별이");
  const token = await p.evaluate(() => JSON.parse(window.localStorage.getItem("moata.session.v1") ?? "{}").token as string);

  await p.goto("/");
  await expect(p.getByTestId("resume")).toBeVisible();
  await p.getByRole("button", { name: "이 기기에서 나가기" }).click();
  await expect(p.getByTestId("resume")).toHaveCount(0);

  const res = await fetch(`${API}/groups`, { headers: { Authorization: `Bearer ${token}` } });
  expect(res.status).toBe(401);
  await ctx.close();
});

test("the bill is a one-screen answer, and no screen has more than one primary action", async ({ browser }) => {
  const driver = await new Driver("MANUAL").setup();
  const ctx = await browser.newContext(PHONE);
  const p = await ctx.newPage();
  const primaries = (page: Page) => page.locator(".btn--primary:visible");

  await joinApproved(p, driver, "별이");
  await expect(primaries(p)).toHaveCount(1); // 탔어요 while parked
  await p.getByTestId("board").click();
  await expect(p.getByTestId("boarded-parked")).toBeVisible();
  await expect(primaries(p)).toHaveCount(0); // boarded: nothing to do until getting off
  await driver.ready();
  await driver.start(1000);
  await driver.drive(1013);
  await expect(p.getByTestId("alight")).toBeVisible();
  await expect(primaries(p)).toHaveCount(1); // 내렸어요
  await driver.end(1024);
  await driver.preview();
  await expect(p.getByTestId("to-bill")).toBeVisible();
  await expect(primaries(p)).toHaveCount(1); // 정산 보기

  await p.goto(`/trip/${driver.tripId}/bill`);
  await expect(p.getByTestId("my-total")).toBeVisible();
  await expect(primaries(p)).toHaveCount(1); // 승인할게요
  for (const id of ["my-total", "riding-sentence", "source-line"]) await expect(p.getByTestId(id)).toBeInViewport();
  const height = await p.evaluate(() => document.documentElement.scrollHeight);
  expect(height, "bill page height with accordions closed").toBeLessThanOrEqual(1300);
  await expect(p.getByTestId("approve")).toBeInViewport();
  const bar = await p.getByTestId("action-bar").boundingBox();
  expect(bar!.height).toBeGreaterThanOrEqual(56);

  // notices stay collapsed on the trip screen
  await p.goto(`/trip/${driver.tripId}`);
  await expect(p.getByTestId("state-label")).toBeVisible();
  await expect(p.getByTestId("disclaimers")).toBeHidden();
  await p.locator("summary", { hasText: "안내 보기" }).click();
  await expect(p.getByTestId("disclaimers")).toContainText("결제·송금을 하지 않습니다");
  await ctx.close();
});

test("a failed approval shows plain copy, and '다시 시도' really sends it again", async ({ browser }) => {
  const driver = await new Driver("MANUAL").setup();
  const ctx = await browser.newContext(PHONE);
  const p = await ctx.newPage();
  await joinApproved(p, driver, "별이");
  await p.getByTestId("board").click();
  await driver.ready();
  await driver.start(500);
  await driver.drive(512);
  await driver.end(512);
  await driver.preview();
  await p.goto(`/trip/${driver.tripId}/bill`);

  let calls = 0;
  await p.route("**/settlements/*/approvals", async (route) => {
    calls += 1;
    if (calls === 1) await route.abort("internetdisconnected"); // the first send never reaches the server
    else await route.continue();
  });
  await p.getByTestId("approve").click();
  await expect(p.getByTestId("error-state")).toHaveAttribute("data-kind", "offline");
  await p.getByRole("button", { name: "다시 시도" }).click();
  await expect(p.getByTestId("my-approval")).toContainText("승인했어요");
  expect(calls).toBe(2);
  await ctx.close();
});
