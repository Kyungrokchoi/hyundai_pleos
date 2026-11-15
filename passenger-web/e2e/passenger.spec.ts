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


  const tokenB = await driver.invite();
  await b.goto(`/join#${tokenB}`);
  await expect(b.getByRole("heading", { name: "금요일 퇴근 카풀" })).toBeVisible();

  await expect(b.getByTestId("rules")).toBeHidden();
  await openAccordion(b, "정산 규칙 보기");
  await expect(b.getByTestId("rules")).toBeVisible();
  await openAccordion(b, "자세히");
  await expect(b.getByTestId("disclaimers")).toContainText("이 금액은 입력한 연비·단가로 계산한 추정치이며");
  await expect(b.getByTestId("disclaimers")).toContainText("MOATA는 결제·송금을 하지 않습니다.");
  expect(new URL(b.url()).hash).toBe("");
  expect(b.url()).not.toContain(tokenB);
  await expectNoA11yViolations(b);


  await b.getByRole("textbox", { name: /닉네임/ }).fill("별이");
  await expect(b.getByRole("button", { name: "참여 요청 보내기" })).toBeDisabled();
  await b.getByRole("checkbox", { name: /주행거리를 저장하는 데 동의/ }).check();
  await expect(b.getByRole("button", { name: "참여 요청 보내기" })).toBeDisabled();
  await b.getByRole("checkbox", { name: /정산 규칙.*동의/ }).check();
  await expect(b.getByRole("button", { name: "참여 요청 보내기" })).toBeEnabled();
  await b.getByRole("button", { name: "참여 요청 보내기" }).click();
  await expect(b.getByTestId("waiting")).toBeVisible();


  await driver.approveMember(await myUserId(b));
  await b.waitForURL(new RegExp(`/trip/${driver.tripId}$`));
  await expect(b.getByTestId("state-label")).toHaveText("모이는 중");
  await joinApproved(c, driver, "달이");


  await expect(b.getByTestId("board")).toHaveText("탔어요");
  await b.getByTestId("board").click();
  await expect(b.getByText("탔어요로 기록했어요")).toBeVisible();

  await b.getByTestId("unboard").click();
  await expect(b.getByTestId("board")).toBeVisible();
  await b.getByTestId("board").click();
  await expect(b.getByTestId("boarded-parked")).toBeVisible();
  await expectNoA11yViolations(b);

  await driver.ready();
  await c.getByTestId("board").click();
  await expect(c.getByText("탔어요로 기록했어요")).toBeVisible();


  await driver.start(1000);
  await expect(b.getByTestId("state-label")).toHaveText("이동 중");
  await driver.drive(1010);
  await expect(b.getByTestId("distance")).toContainText("10");
  await expect(c.getByTestId("distance")).toContainText("10");

  await expect(b.getByTestId("strip-lane")).toHaveCount(3);
  await expect(c.getByTestId("strip-lane")).toHaveCount(3);
  await expect(b.getByTestId("strip-lane").first()).toContainText("운전자");
  await expect(b.getByTestId("strip-axis")).toContainText("지금 10km");
  await expectNoA11yViolations(b);


  await b.getByTestId("alight").click();
  await expect(b.getByTestId("alight-dialog")).toBeVisible();
  await b.getByTestId("alight-confirm").click();
  await expect(b.locator('[data-testid="strip-lane"]', { hasText: "별이" })).toContainText("10km 탑승");

  await expect(b.getByTestId("my-distance")).toContainText("10");
  await expect(b.getByTestId("distance")).toHaveCount(0);
  await expect(b.getByTestId("state-label")).toHaveText("내렸어요");
  await expect(c.getByTestId("state-label")).toHaveText("이동 중");

  await expect(b.getByTestId("alighted")).toContainText("10km 지점에서 내렸어요");
  await expect(b.getByTestId("board")).toHaveCount(0);
  await expect(b.getByTestId("alight")).toHaveCount(0);
  await expect(b.getByTestId("reboard")).toBeVisible();

  await expect(b.locator("main")).not.toContainText("1,010");

  await driver.drive(1024);
  await driver.end(1024);
  await driver.toll(2400);
  const settlement = await driver.preview();
  expect(settlement.id).toBeTruthy();

  await b.getByTestId("to-bill").click();


  await expect(b.getByTestId("my-total")).toHaveText(/1,300/);
  await expect(b.getByTestId("riding-sentence")).toHaveText("24km 중 10km를 함께 탔어요.");

  await expect(b.getByTestId("fact-distance")).toContainText("거리");
  await expect(b.getByTestId("fact-economy")).toContainText("연비");
  await expect(b.getByTestId("fact-price")).toContainText("유가");
  await expect(b.getByTestId("fact-toll")).toContainText("통행료");
  await expect(b.getByTestId("source-line").getByTestId("source-badge")).toHaveCount(0);

  await expect(b.getByTestId("my-segment")).toHaveCount(1);
  await expect(b.getByTestId("my-segment")).toContainText("1,500원 ÷ 3명");
  await expect(b.getByTestId("my-segment")).not.toContainText("= 500원");
  await expect(b.getByTestId("my-energy").locator("dd")).toHaveText("500원");

  await expect(b.getByTestId("my-item")).toContainText("통행료 · 고속도로");
  await expect(b.getByTestId("item-reason")).toHaveText("2,400원 ÷ 3명 · 탄 거리와 관계없이 똑같이");
  await expect(b.getByTestId("my-item").locator("dd")).toHaveText("800원");
  await expect(b.getByText("이 금액에 동의해요 · 돈은 나가지 않아요")).toBeVisible();
  await expect(b.getByText("결제·송금은 하지 않아요")).toBeVisible();
  await expect(b.getByTestId("approve")).toBeInViewport();
  await expect(b.getByTestId("dispute-open")).toBeInViewport();
  await expect(b.getByTestId("equation")).toBeHidden();
  await expect(b.getByRole("button", { name: /송금|이체|결제하기|결제/ })).toHaveCount(0);
  await expectNoA11yViolations(b);

  await openAccordion(b, "어떻게 계산했어요?");
  await expect(b.getByTestId("equation")).toContainText("24km ÷ 12km/L × 1,800원/L = 3,600원");
  await expect(b.getByTestId("total")).toContainText("6,000원");

  await expect(b.getByTestId("my-basis")).toContainText("500원");
  await expect(b.getByTestId("my-basis")).toContainText("800원");
  await expect(b.getByTestId("segments").locator("li")).toHaveCount(2);
  await expect(b.getByTestId("segments")).toContainText("출발 후 0–10km");
  await expect(b.getByTestId("segments")).not.toContainText("1,000");
  await expect(b.getByText("운전자가 직접 적은 값이에요.")).toBeVisible();
  await openAccordion(b, "모두의 몫");
  await expect(b.getByTestId("shares")).toContainText("2,350원");
  await expectNoA11yViolations(b);

  await b.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await expect(b.getByTestId("approve")).toBeInViewport();


  await b.getByTestId("approve").click();
  await expect(b.getByTestId("my-approval")).toContainText("승인");
  await expect(b.getByTestId("approve")).toHaveCount(0);
  await c.goto(`/trip/${driver.tripId}/bill`);
  await c.getByTestId("approve").click();
  await driver.approveSettlement(settlement.id);
  await expect(b.getByTestId("celebrate")).toBeVisible();

  await expect(b.getByTestId("settlement-state")).toHaveText("금액 합의 완료");
  await expect(b.getByTestId("pay-note")).toContainText("하늘님께 직접 보내 주세요");
  await expect(b.getByTestId("copy-amount")).toHaveText(/1,300원 복사/);
  await b.getByTestId("copy-amount").click();
  await expect(b.getByTestId("amount-copied")).toContainText("1,300원을 복사했어요");
  expect(await b.evaluate(() => navigator.clipboard.readText())).toBe("1300");
  await expectNoA11yViolations(b);


  await b.getByRole("link", { name: "모아 만나러 가기" }).click();
  await expect(b.getByTestId("moa-level")).toContainText("Lv.1");
  await expect(b.getByTestId("xp-line")).toContainText("10");
  await expect(b.getByTestId("history").locator("li")).toHaveCount(1);
  await expect(b.getByText("시연용 · 실제 교환 불가").first()).toBeVisible();
  await expectNoA11yViolations(b);


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
  await expect(p.getByTestId("dispute-send")).toBeDisabled();
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
  await expect(p.locator('[data-testid="strip-lane"]', { hasText: "별이" })).toContainText("12km 탑승 · 운전자가 기록");
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


  await page.getByRole("button", { name: "이 초대 정보 지우기" }).click();
  await expect(page.getByRole("heading", { name: "초대 링크가 필요해요" })).toBeVisible();
});

test("signed-out visitors and strangers are told why they cannot see a trip", async ({ page, browser }) => {
  const driver = await new Driver("MANUAL").setup();

  await page.goto(`/trip/${driver.tripId}`);
  await expect(page.getByTestId("need-join")).toBeVisible();


  const who = await outsider();
  const ctx = await browser.newContext(PHONE);
  const p = await ctx.newPage();
  await plantSession(p, { userId: who.user_id, token: who.token, nickname: who.nickname });
  await p.goto(`/trip/${driver.tripId}`);
  await expect(p.getByTestId("error-state")).toHaveAttribute("data-kind", /^(forbidden|notfound)$/);
  await expect(p.getByTestId("participants")).toHaveCount(0);


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
  await expect(p.getByTestId("distance")).toContainText("12");
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
  await expect(primaries(p)).toHaveCount(1);
  await p.getByTestId("board").click();
  await expect(p.getByTestId("boarded-parked")).toBeVisible();
  await expect(primaries(p)).toHaveCount(0);
  await driver.ready();
  await driver.start(1000);
  await driver.drive(1013);
  await expect(p.getByTestId("alight")).toBeVisible();
  await expect(primaries(p)).toHaveCount(1);
  await driver.end(1024);
  await driver.preview();
  await expect(p.getByTestId("to-bill")).toBeVisible();
  await expect(primaries(p)).toHaveCount(1);

  await p.goto(`/trip/${driver.tripId}/bill`);
  await expect(p.getByTestId("my-total")).toBeVisible();
  await expect(primaries(p)).toHaveCount(1);
  for (const id of ["my-total", "riding-sentence", "source-line"]) await expect(p.getByTestId(id)).toBeInViewport();
  const height = await p.evaluate(() => document.documentElement.scrollHeight);
  expect(height, "bill page height with accordions closed").toBeLessThanOrEqual(1300);
  await expect(p.getByTestId("approve")).toBeInViewport();
  const bar = await p.getByTestId("action-bar").boundingBox();
  expect(bar!.height).toBeGreaterThanOrEqual(56);


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
    if (calls === 1) await route.abort("internetdisconnected");
    else await route.continue();
  });
  await p.getByTestId("approve").click();
  await expect(p.getByTestId("error-state")).toHaveAttribute("data-kind", "offline");
  await p.getByRole("button", { name: "다시 시도" }).click();
  await expect(p.getByTestId("my-approval")).toContainText("승인했어요");
  expect(calls).toBe(2);
  await ctx.close();
});
