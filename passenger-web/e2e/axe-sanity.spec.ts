import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

// Makes sure the axe setup used by the other specs really reports violations.
test("axe reports a deliberately inaccessible page", async ({ page }) => {
  await page.setContent('<html lang="ko"><body><img src="x.png"><button></button></body></html>');
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
  expect(r.violations.map((v) => v.id)).toEqual(expect.arrayContaining(["image-alt", "button-name"]));
});
