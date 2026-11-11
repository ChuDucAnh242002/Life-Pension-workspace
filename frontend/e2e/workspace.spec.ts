import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("Username").fill(process.env.ADVISER_USER ?? "adviser");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.ADVISER_PASSWORD ?? "local-demo-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}

test("login, synthetic facts, unknown values, and logout are explicit", async ({
  page,
}) => {
  await signIn(page);
  await page.getByRole("button", { name: /Synthetic: Mika/ }).click();
  await expect(
    page.getByRole("heading", { name: /Synthetic: Mika/ }),
  ).toBeVisible();
  await expect(
    page.locator(".facts").getByText("Unknown", { exact: true }),
  ).toHaveCount(2);
  await expect(
    page.getByText(
      "No financial advice, suitability assessment, or live AI generation.",
      { exact: false },
    ),
  ).toBeVisible();
  await page.reload();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(
    page.getByRole("heading", { name: "Sign in as adviser" }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Sign in as adviser" }),
  ).toBeVisible();
});

test("create, edit, review, close, reopen and history survive page reload", async ({
  page,
}) => {
  await signIn(page);
  const alias = `Synthetic browser case ${Date.now()}`;
  await page.getByRole("button", { name: "New case" }).click();
  await page.getByLabel("Customer alias").fill(alias);
  await page
    .getByLabel("Monthly income (EUR)", { exact: true })
    .fill("2500.50");
  await page
    .getByLabel("Voluntary pension contribution / month (EUR)")
    .fill("0");
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(
    page.getByRole("heading", { name: alias, exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Start review" }).click();
  await expect(page.locator(".badge")).toHaveText("In review");
  await page.getByRole("button", { name: "Edit profile" }).click();
  await page
    .getByLabel("Meeting notes")
    .fill("Synthetic meeting preparation, not advice.");
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(page.locator(".badge")).toHaveText("Draft");
  await page.getByRole("button", { name: "Start review" }).click();
  await page.getByRole("button", { name: "Close case" }).click();
  await expect(page.locator(".badge")).toHaveText("Closed");
  await expect(page.getByRole("button", { name: "Edit profile" })).toHaveCount(
    0,
  );
  await page.reload();
  await page.getByRole("button", { name: new RegExp(alias) }).click();
  await expect(page.locator(".badge")).toHaveText("Closed");
  await expect(page.locator(".history li")).toHaveCount(5);
  await page.getByRole("button", { name: "Reopen case" }).click();
  await expect(page.locator(".badge")).toHaveText("Draft");
  await expect(page.locator(".history li")).toHaveCount(6);
});

test("invalid amounts retain the draft and move focus to an error", async ({
  page,
}) => {
  await signIn(page);
  await page.getByRole("button", { name: "New case" }).click();
  await page.getByLabel("Customer alias").fill("Synthetic invalid draft");
  await page.getByLabel("Monthly income (EUR)", { exact: true }).fill("-5");
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(page.getByRole("alert")).toContainText("non-negative");
  await expect(page.getByRole("alert")).toBeFocused();
  await expect(
    page.getByLabel("Monthly income (EUR)", { exact: true }),
  ).toHaveValue("-5");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(
    page.getByRole("button", { name: /Synthetic invalid draft/ }),
  ).toHaveCount(0);
});

test("stale session cannot overwrite a newer profile", async ({
  page,
  browser,
}) => {
  await signIn(page);
  const alias = `Synthetic concurrent ${Date.now()}`;
  await page.getByRole("button", { name: "New case" }).click();
  await page.getByLabel("Customer alias").fill(alias);
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(page.getByRole("heading", { name: alias })).toBeVisible();
  const context = await browser.newContext({
    baseURL: process.env.APP_URL ?? "http://127.0.0.1:8089",
  });
  try {
    const second = await context.newPage();
    await signIn(second);
    await second.getByRole("button", { name: new RegExp(alias) }).click();
    await second.getByRole("button", { name: "Edit profile" }).click();
    await second.getByLabel("Meeting notes").fill("Stale session draft");
    await page.getByRole("button", { name: "Edit profile" }).click();
    await page.getByLabel("Meeting notes").fill("Newest saved facts");
    await page.getByRole("button", { name: "Save profile" }).click();
    await expect(page.getByRole("status")).toContainText("Profile saved");
    await second.getByRole("button", { name: "Save profile" }).click();
    await expect(second.getByRole("alert")).toContainText("another session");
    await expect(second.getByLabel("Meeting notes")).toHaveValue(
      "Stale session draft",
    );
    await second.getByRole("button", { name: "Cancel", exact: true }).click();
    await second
      .getByRole("button", { name: "Reload case", exact: true })
      .click();
    await expect(second.locator(".case-panel > .notes")).toHaveText(
      "Newest saved facts",
    );
  } finally {
    await context.close();
  }
});

test("mobile workspace fits without horizontal scrolling", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await signIn(page);
  await page.getByRole("button", { name: /Synthetic: Mika/ }).click();
  await expect(
    page.getByRole("button", { name: "Edit profile" }),
  ).toBeVisible();
  const width = await page.evaluate(() => ({
    content: document.documentElement.scrollWidth,
    viewport: window.innerWidth,
  }));
  expect(width.content).toBeLessThanOrEqual(width.viewport);
});
