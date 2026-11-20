import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

async function mockWorkspace(page: Page, publicDemo: boolean) {
  const caseData = {
    id: 1,
    revision: 1,
    status: "DRAFT",
    profile: {
      customerAlias: "Alex",
      age: 35,
      employment: "EMPLOYED",
      dependants: null,
      monthlyIncome: null,
      pensionContribution: null,
      lifeCover: null,
      notes: null,
    },
    updatedAt: "2026-01-01T12:00:00Z",
  };
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    switch (path) {
      case "/api/csrf":
        await route.fulfill({
          json: { headerName: "X-CSRF-TOKEN", token: "test" },
        });
        break;
      case "/api/session":
        await route.fulfill({
          json: {
            username: publicDemo ? "public-demo" : "adviser",
            publicDemo,
          },
        });
        break;
      case "/api/cases":
        await route.fulfill({ json: [caseData] });
        break;
      case "/api/cases/1":
        await route.fulfill({
          json: {
            caseData,
            history: [
              {
                revision: 1,
                actor: "public-demo",
                action: "CREATED",
                changedAt: "2026-01-01T12:00:00Z",
                snapshot: caseData,
              },
            ],
          },
        });
        break;
      case "/api/cases/1/policy":
        await route.fulfill({
          json: {
            transport: "DEMO",
            simulator: true,
            requests: [
              {
                id: "request-1",
                caseRevision: 1,
                fixture: "EMPLOYEE",
                status: "COMPLETED",
                requestedBy: "public-demo",
                snapshot: null,
              },
            ],
          },
        });
        break;
      case "/api/cases/1/briefing":
        await route.fulfill({
          json: {
            enabled: false,
            provider: "",
            model: "",
            stale: false,
            usable: false,
            draft: null,
            approvals: [
              {
                draftId: "draft-1",
                approvedAt: "2026-01-01T12:00:00Z",
                approvedBy: "public-demo",
                note: null,
                status: "SUPERSEDED",
              },
            ],
          },
        });
        break;
      default:
        await route.fulfill({ status: 404 });
    }
  });
}

test("shared activity uses clear product language and retains safety notices", async ({
  page,
}) => {
  await mockWorkspace(page, true);
  await page.goto("/");
  await expect(
    page.getByText("Shared workspace", { exact: true }),
  ).toBeVisible();
  await expect(page.locator(".banner")).toContainText(
    "Fictional profiles only.",
  );
  await expect(page.locator(".banner")).toContainText(
    "Anyone with access can view and change shared data.",
  );
  await expect(
    page.getByText("Not a Nordea product.", { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign out" })).toHaveCount(0);
  await page.getByRole("button", { name: /Alex/ }).click();
  await page.getByText("Policy request history (latest 10)").click();
  await page.getByText("Approval history (latest 10)").click();
  await expect(
    page.getByText("In-process (not IBM MQ)", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(/public-demo|Adviser preparation.*Step 4/i),
  ).toHaveCount(0);
  await expect(
    page.getByText("Shared workspace", { exact: false }),
  ).toHaveCount(4);
});

test("signed-in identity and sign-out remain available on a narrow screen", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await mockWorkspace(page, false);
  await page.goto("/");
  await expect(page.getByText("adviser", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  await expect(
    page.getByText("Anyone with access can view and change shared data.", {
      exact: false,
    }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: /Alex/ }).click();
  await expect(
    page.getByRole("button", { name: "Edit profile" }),
  ).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(320);
});
