import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

const source = {
  id: "declared.age",
  label: "Age",
  value: "38",
  origin: "Customer-declared case revision 1",
};
const original = {
  id: "11111111-1111-1111-1111-111111111111",
  caseRevision: 1,
  createdAt: "2026-11-08T12:00:00Z",
  createdBy: "adviser",
  provider: "Local browser mock",
  model: "test",
  promptVersion: "source-selection-v1",
  summary: [source],
  questions: [
    { id: "confirm_declared", text: "Confirm facts.", sourceId: source.id },
  ],
  sources: [source],
};

async function createCase(page: Page) {
  await page.goto("/");
  await page.getByLabel("Username").fill("adviser");
  await page
    .getByLabel("Password", { exact: true })
    .fill("local-demo-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("button", { name: "New case" }).click();
  const alias = `Synthetic approval browser ${Date.now()}`;
  await page.getByLabel("Customer alias").fill(alias);
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(page.getByRole("heading", { name: alias })).toBeVisible();
}

test("explicit review approves a saved draft, survives reload and becomes historical after source changes", async ({
  page,
}) => {
  let stale = false;
  let approved = false;
  await page.route("**/api/cases/*/briefing", (route) =>
    route.fulfill({
      json: {
        enabled: false,
        provider: original.provider,
        model: original.model,
        draft: original,
        stale,
        usable: approved && !stale,
        approvals: approved
          ? [
              {
                draftId: original.id,
                approvedBy: "adviser",
                approvedAt: "2026-11-08T12:10:00Z",
                note: "Checked exact source values.",
                status: stale ? "STALE" : "CURRENT",
              },
            ]
          : [],
      },
    }),
  );
  await page.route("**/api/cases/*/briefing/approval", async (route) => {
    const body = route.request().postDataJSON();
    expect(body.draftId).toBe(original.id);
    expect(body.revision).toBe(1);
    expect(body.reviewedSources).toBe(true);
    expect(body.note).toBe("Checked exact source values.");
    approved = true;
    await route.fulfill({
      json: {
        draftId: original.id,
        approvedBy: "adviser",
        approvedAt: "2026-11-08T12:10:00Z",
        note: body.note,
        status: "CURRENT",
      },
    });
  });
  await createCase(page);
  const panel = page.getByRole("region", { name: "AI meeting draft" });
  const button = panel.getByRole("button", {
    name: "Approve meeting briefing",
  });
  await expect(button).toBeDisabled();
  await panel.getByRole("link", { name: "[declared.age]" }).first().click();
  await panel.getByLabel("I reviewed every source").check();
  await panel
    .getByLabel("Review note (optional)")
    .fill("Checked exact source values.");
  await button.click();
  await expect(panel.getByRole("status")).toContainText(
    "Approved meeting briefing",
  );
  await page.getByRole("button", { name: "Reload case", exact: true }).click();
  await expect(panel.getByRole("status")).toContainText(
    "Approved meeting briefing",
  );
  await panel
    .getByText("Approval history (latest 10)", { exact: true })
    .click();
  await expect(
    panel.getByText("Checked exact source values.", { exact: true }),
  ).toBeVisible();
  stale = true;
  await expect(panel.getByRole("status")).toContainText("Stale draft", {
    timeout: 6000,
  });
  await expect(
    panel.getByRole("button", { name: "Approve meeting briefing" }),
  ).toBeDisabled();
  await expect(panel.getByText(/STALE · adviser/)).toBeVisible();
});

test("newer draft resets source confirmation and supersedes prior approval", async ({
  page,
}) => {
  let draft = original;
  let usable = false;
  let approval: {
    draftId: string;
    approvedBy: string;
    approvedAt: string;
    note: null;
    status: string;
  } | null = null;
  await page.route("**/api/cases/*/briefing", async (route) => {
    if (route.request().method() === "POST") {
      draft = { ...original, id: "22222222-2222-2222-2222-222222222222" };
      usable = false;
      if (approval) approval.status = "SUPERSEDED";
      await route.fulfill({ status: 201, json: draft });
    } else
      await route.fulfill({
        json: {
          enabled: true,
          provider: draft.provider,
          model: draft.model,
          draft,
          stale: false,
          usable,
          approvals: approval ? [approval] : [],
        },
      });
  });
  await page.route("**/api/cases/*/briefing/approval", async (route) => {
    usable = true;
    approval = {
      draftId: draft.id,
      approvedBy: "adviser",
      approvedAt: "2026-11-08T12:00:00Z",
      note: null,
      status: "CURRENT",
    };
    await route.fulfill({ json: approval });
  });
  await createCase(page);
  const panel = page.getByRole("region", { name: "AI meeting draft" });
  await panel.getByLabel("I reviewed every source").check();
  await panel.getByRole("button", { name: "Approve meeting briefing" }).click();
  await expect(panel.getByRole("status")).toContainText(
    "Approved meeting briefing",
  );
  await panel.getByRole("button", { name: "Generate AI draft" }).click();
  await expect(panel.getByLabel("I reviewed every source")).not.toBeChecked();
  await expect(
    panel.getByRole("button", { name: "Approve meeting briefing" }),
  ).toBeDisabled();
  await panel
    .getByText("Approval history (latest 10)", { exact: true })
    .click();
  await expect(panel.getByText(/SUPERSEDED · adviser/)).toBeVisible();
});

test("approval conflicts remain visible after polling and unavailable status cannot appear approved", async ({
  page,
}) => {
  let unavailable = false;
  await page.route("**/api/cases/*/briefing", (route) =>
    unavailable
      ? route.fulfill({
          status: 503,
          json: { message: "Approval status unavailable." },
        })
      : route.fulfill({
          json: {
            enabled: false,
            provider: original.provider,
            model: original.model,
            draft: original,
            stale: false,
            usable: false,
            approvals: [],
          },
        }),
  );
  await page.route("**/api/cases/*/briefing/approval", (route) =>
    route.fulfill({
      status: 409,
      json: {
        message:
          "Case or policy sources changed. Reload and generate a new draft.",
      },
    }),
  );
  await createCase(page);
  const panel = page.getByRole("region", { name: "AI meeting draft" });
  await panel.getByLabel("I reviewed every source").check();
  await panel.getByRole("button", { name: "Approve meeting briefing" }).click();
  await expect(panel.getByRole("alert")).toContainText("sources changed");
  await page.waitForTimeout(2500);
  await expect(panel.getByRole("alert")).toContainText("sources changed");
  unavailable = true;
  await expect(
    panel.getByRole("button", { name: "Approve meeting briefing" }),
  ).toBeDisabled({ timeout: 6000 });
  await expect(panel.getByRole("status")).not.toContainText(
    "Approved meeting briefing",
  );
});
