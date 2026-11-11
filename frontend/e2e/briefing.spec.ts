import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

async function createCase(page: Page) {
  await page.goto("/");
  await page.getByLabel("Username").fill("adviser");
  await page
    .getByLabel("Password", { exact: true })
    .fill("local-demo-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("button", { name: "New case" }).click();
  const alias = `Synthetic briefing browser ${Date.now()}`;
  await page.getByLabel("Customer alias").fill(alias);
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(page.getByRole("heading", { name: alias })).toBeVisible();
}

const source = {
  id: "declared.pension",
  label: "Declared monthly pension contribution (EUR)",
  value: "Unknown",
  origin: "Customer-declared case revision 1",
};
const draft = {
  id: "local-browser-test",
  caseRevision: 1,
  createdAt: "2026-11-08T12:00:00Z",
  createdBy: "adviser",
  provider: "Local browser mock (not Hugging Face)",
  model: "test-model",
  promptVersion: "source-selection-v1",
  summary: [source],
  questions: [
    {
      id: "clarify_pension",
      text: "Clarify the declared voluntary pension contribution.",
      sourceId: source.id,
    },
  ],
  sources: [source],
};

for (const provider of [
  "Azure OpenAI in Foundry",
  "AWS Bedrock / eu-north-1",
]) {
  test(`${provider} uses the same grounded draft UI without Hugging Face configuration`, async ({
    page,
  }) => {
    let saved = false;
    const cloudDraft = { ...draft, provider, model: "cloud-test-deployment" };
    await page.route("**/api/cases/*/briefing", async (route) => {
      if (route.request().method() === "POST") {
        saved = true;
        await route.fulfill({ status: 201, json: cloudDraft });
      } else {
        await route.fulfill({
          json: {
            enabled: true,
            provider,
            model: cloudDraft.model,
            stale: false,
            draft: saved ? cloudDraft : null,
          },
        });
      }
    });
    await createCase(page);
    const panel = page.getByRole("region", { name: "AI meeting draft" });
    await expect(
      panel.getByText(`${provider} · cloud-test-deployment`, { exact: true }),
    ).toBeVisible();
    await panel.getByRole("button", { name: "Generate AI draft" }).click();
    await expect(panel.getByRole("status")).toContainText("unapproved");
    await expect(
      panel.getByRole("link", { name: "[declared.pension]" }),
    ).toHaveCount(2);
    await expect(
      panel.getByRole("heading", { name: "Prioritized facts" }),
    ).toBeVisible();
  });
}

test("unconfigured AI is explicitly disabled and never silently generates a demo draft", async ({
  page,
}) => {
  await createCase(page);
  const panel = page.getByRole("region", { name: "AI meeting draft" });
  await expect(
    panel.getByText(
      "AI disabled. Configure an AI backend to enable generation.",
    ),
  ).toBeVisible();
  await expect(
    panel.getByRole("button", { name: "Generate AI draft" }),
  ).toBeDisabled();
  await expect(
    panel.getByRole("heading", { name: "Prioritized facts" }),
  ).toHaveCount(0);
});

test("saved draft displays exact citations, opens source details, and reports changed sources", async ({
  page,
}) => {
  await createCase(page);
  let saved = false;
  let stale = false;
  await page.route("**/api/cases/*/briefing", async (route) => {
    if (route.request().method() === "POST") {
      expect(route.request().postDataJSON().revision).toBe(1);
      saved = true;
      await route.fulfill({ status: 201, json: draft });
    } else {
      await route.fulfill({
        json: {
          enabled: true,
          provider: draft.provider,
          model: draft.model,
          stale,
          draft: saved ? draft : null,
        },
      });
    }
  });
  const panel = page.getByRole("region", { name: "AI meeting draft" });
  await expect(
    panel.getByRole("button", { name: "Generate AI draft" }),
  ).toBeEnabled({ timeout: 6000 });
  await panel.getByRole("button", { name: "Generate AI draft" }).click();
  await expect(panel.getByRole("status")).toContainText("unapproved");
  await panel.getByRole("link", { name: "[declared.pension]" }).first().click();
  await expect(panel.getByText(source.origin, { exact: false })).toBeVisible();
  await expect(
    panel.getByText(draft.questions[0].text, { exact: false }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Reload case", exact: true }).click();
  await expect(panel.getByRole("status")).toContainText("unapproved");
  stale = true;
  await expect(panel.getByRole("status")).toContainText("Stale draft", {
    timeout: 6000,
  });
});

test("generation errors survive successful polling and failed loads remain visible", async ({
  page,
}) => {
  await createCase(page);
  let failLoad = false;
  await page.route("**/api/cases/*/briefing", async (route) => {
    if (route.request().method() === "POST")
      await route.fulfill({
        status: 502,
        json: {
          message: "AI returned invalid source citations. No draft was saved.",
        },
      });
    else if (failLoad)
      await route.fulfill({
        status: 503,
        json: { message: "Draft status unavailable." },
      });
    else
      await route.fulfill({
        json: {
          enabled: true,
          provider: draft.provider,
          model: draft.model,
          stale: false,
          draft: null,
        },
      });
  });
  const panel = page.getByRole("region", { name: "AI meeting draft" });
  await expect(
    panel.getByRole("button", { name: "Generate AI draft" }),
  ).toBeEnabled({ timeout: 6000 });
  await panel.getByRole("button", { name: "Generate AI draft" }).click();
  await expect(panel.getByRole("alert")).toContainText(
    "invalid source citations",
  );
  await page.waitForTimeout(2500);
  await expect(panel.getByRole("alert")).toContainText(
    "invalid source citations",
  );
  await page.getByRole("button", { name: "New case" }).click();
  await page
    .getByLabel("Customer alias")
    .fill(`Synthetic load error ${Date.now()}`);
  failLoad = true;
  await page.getByRole("button", { name: "Save profile" }).click();
  await expect(
    page.getByRole("region", { name: "AI meeting draft" }).getByRole("alert"),
  ).toContainText("Draft status unavailable");
});
