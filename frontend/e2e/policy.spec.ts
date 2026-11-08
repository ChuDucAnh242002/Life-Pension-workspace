import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

async function createCase(page: Page) {
  await page.goto("/");
  await page.getByLabel("Username").fill(process.env.ADVISER_USER ?? "adviser");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.ADVISER_PASSWORD ?? "local-demo-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("button", { name: "New case" }).click();
  const alias = `Synthetic policy browser ${Date.now()}`;
  await page.getByLabel("Customer alias").fill(alias);
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(page.getByRole("heading", { name: alias })).toBeVisible();
}

test("policy request is correlated, persisted, and never merged into declared facts", async ({
  page,
}) => {
  await createCase(page);
  const panel = page.getByRole("region", {
    name: "Simulated policy information",
  });
  await expect(
    panel.getByText("In-process demo (not IBM MQ)", { exact: true }),
  ).toBeVisible();
  await panel
    .getByLabel("Fictional policy fixture")
    .selectOption("SOLE_TRADER");
  await panel
    .getByRole("button", { name: "Request simulated policy", exact: true })
    .click();
  await expect(panel.getByRole("status")).toContainText("Completed", {
    timeout: 15000,
  });
  await expect(
    panel.getByText("DEMO-TRADER-001", { exact: true }),
  ).toBeVisible();
  await expect(
    page.locator(".case-panel > .facts").getByText("Unknown", { exact: true }),
  ).toHaveCount(6);
  await expect(page.locator(".history li")).toHaveCount(1);
  await page.getByRole("button", { name: "Reload case", exact: true }).click();
  await expect(
    panel.getByText("DEMO-TRADER-001", { exact: true }),
  ).toBeVisible();
  await panel
    .getByText("Policy request history (latest 10)", { exact: true })
    .click();
  await expect(panel.getByText(/Correlation ID:/)).toBeVisible();
});

test("profile edits invalidate policy snapshots and a new request refreshes them", async ({
  page,
}) => {
  await createCase(page);
  const panel = page.getByRole("region", {
    name: "Simulated policy information",
  });
  await panel
    .getByLabel("Fictional policy fixture")
    .selectOption("CAREER_CHANGE");
  await panel
    .getByRole("button", { name: "Request simulated policy", exact: true })
    .click();
  await expect(panel.getByRole("status")).toContainText("Completed", {
    timeout: 15000,
  });
  await expect(panel.getByText("Unknown", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Edit profile" }).click();
  await page.getByLabel("Meeting notes").fill("Synthetic facts changed.");
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(panel.getByText(/Stale policy snapshot:/)).toBeVisible();
  await panel
    .getByRole("button", { name: "Request simulated policy", exact: true })
    .click();
  await expect(panel.getByRole("status")).toContainText("Completed", {
    timeout: 15000,
  });
  await expect(panel.getByText(/Stale policy snapshot:/)).toHaveCount(0);
});

test("policy timeout and failed status loads are visible, not a healthy empty result", async ({
  page,
}) => {
  await createCase(page);
  const panel = page.getByRole("region", {
    name: "Simulated policy information",
  });
  await page.route("**/api/cases/*/policy", async (route) => {
    const response = await route.fetch();
    const json = await response.json();
    json.requests = [
      {
        id: "00000000-0000-0000-0000-000000000001",
        caseId: 1,
        caseRevision: 1,
        fixture: "EMPLOYEE",
        status: "TIMED_OUT",
        requestedBy: "adviser",
        createdAt: "2026-01-01T00:00:00Z",
        expiresAt: 1,
        completedAt: null,
        attempts: 3,
        lastError:
          "Policy request timed out. Check the transport and request again.",
        snapshot: null,
      },
    ];
    await route.fulfill({ response, json });
  });
  await expect(panel.getByRole("status")).toContainText("Timed out", {
    timeout: 10000,
  });
  await expect(
    panel.getByText(
      "Policy request timed out. Check the transport and request again.",
      { exact: true },
    ),
  ).toBeVisible();
  await page.unroute("**/api/cases/*/policy");
  await page.route("**/api/cases/*/policy", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ message: "Policy status unavailable." }),
    }),
  );
  await expect(panel.getByRole("alert")).toContainText(
    "Policy status unavailable.",
    { timeout: 10000 },
  );
  await expect(
    panel.getByRole("button", {
      name: "Request simulated policy",
      exact: true,
    }),
  ).toBeDisabled();
});

test("submission errors stay visible when background status polling succeeds", async ({
  page,
}) => {
  await createCase(page);
  const panel = page.getByRole("region", {
    name: "Simulated policy information",
  });
  let statusReads = 0;
  await page.route("**/api/cases/*/policy", async (route) => {
    statusReads++;
    await route.continue();
  });
  await page.route("**/api/cases/*/policy/requests", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ message: "Policy command unavailable." }),
    }),
  );
  await panel
    .getByRole("button", { name: "Request simulated policy", exact: true })
    .click();
  await expect(panel.getByRole("alert")).toContainText(
    "Policy command unavailable.",
  );
  await expect(panel.getByRole("alert")).toBeFocused();
  const before = statusReads;
  await expect
    .poll(() => statusReads, { timeout: 10000 })
    .toBeGreaterThan(before);
  await expect(panel.getByRole("alert")).toContainText(
    "Policy command unavailable.",
  );
});
