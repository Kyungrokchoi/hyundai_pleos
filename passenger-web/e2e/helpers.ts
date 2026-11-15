import type { Page } from "@playwright/test";
import type { Driver } from "./driver";

export const SESSION_KEY = "moata.session.v1";

export async function joinAs(page: Page, token: string, nickname: string) {
  await page.goto(`/join#${token}`);
  await page.getByRole("textbox", { name: /닉네임/ }).fill(nickname);
  await page.getByRole("checkbox", { name: /주행거리를 저장하는 데 동의/ }).check();
  await page.getByRole("checkbox", { name: /정산 규칙.*동의/ }).check();
  await page.getByRole("button", { name: "참여 요청 보내기" }).click();
}

export async function myUserId(page: Page): Promise<string> {
  return page.evaluate((k) => JSON.parse(window.localStorage.getItem(k) ?? "{}").userId as string, SESSION_KEY);
}


export async function joinApproved(page: Page, driver: Driver, nickname: string) {
  await joinAs(page, await driver.invite(), nickname);
  await page.getByTestId("waiting").waitFor();
  await driver.approveMember(await myUserId(page));
  await page.waitForURL(new RegExp(`/trip/${driver.tripId}$`));
}

export function plantSession(page: Page, s: { userId: string; token: string; nickname: string; groupId?: string; tripId?: string }) {
  return page.addInitScript(([k, v]) => window.localStorage.setItem(k as string, v as string), [SESSION_KEY, JSON.stringify(s)]);
}


export async function openAccordion(page: Page, title: string | RegExp) {
  const summary = page.locator("summary", { hasText: title }).first();
  await summary.click();
}
