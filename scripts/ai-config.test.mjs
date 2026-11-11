import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const cwd = fileURLToPath(new URL("..", import.meta.url));
const args = [
  "compose",
  "--env-file",
  "/dev/null",
  "-p",
  "life-pension-ai-config-test",
  "-f",
  "compose.demo.yaml",
  "-f",
  "compose.ai.yaml",
  "config",
  "--format",
  "json",
];
const env = {
  ...process.env,
  HF_PROVIDER: "test-provider",
  HF_MODEL: "test-model",
};

test("AI overlay requires explicit model and provider without running inference", () => {
  for (const field of ["HF_MODEL", "HF_PROVIDER"]) {
    const result = spawnSync("docker", args, {
      cwd,
      env: { ...env, [field]: "" },
      encoding: "utf8",
    });
    assert.notEqual(result.status, 0);
    assert.ok(result.stderr.includes(field));
  }
});

test("AI configuration mounts a private file token, never an environment token", () => {
  const config = JSON.parse(
    execFileSync("docker", args, { cwd, env, encoding: "utf8" }),
  );
  const app = config.services.app;
  assert.equal(app.environment.AI_ENABLED, "true");
  assert.equal(app.environment.AI_BACKEND, "huggingface");
  assert.equal(app.environment.HF_PROVIDER, "test-provider");
  assert.equal(app.environment.HF_MODEL, "test-model");
  assert.equal(app.environment.HF_TOKEN_FILE, "/run/secrets/hfToken");
  assert.ok(!Object.hasOwn(app.environment, "HF_TOKEN"));
  assert.equal(app.secrets[0].source, "hfToken");
  assert.ok(config.secrets.hfToken.file.endsWith("/secrets/hf-token"));
});

for (const [backend, overlay, required, secret, secretFile] of [
  [
    "azure",
    "compose.ai-azure.yaml",
    {
      AZURE_OPENAI_ENDPOINT: "https://test.openai.azure.com",
      AZURE_OPENAI_DEPLOYMENT: "test-deployment",
    },
    "azureOpenAiKey",
    "azure-openai-key",
  ],
  [
    "bedrock",
    "compose.ai-bedrock.yaml",
    { AWS_REGION: "eu-north-1", BEDROCK_MODEL_ID: "test-model" },
    "awsCredentials",
    "aws-credentials",
  ],
]) {
  const cloudArgs = args.map((arg) =>
    arg === "compose.ai.yaml" ? overlay : arg,
  );
  test(`${backend} requires explicit deployment configuration without starting cloud calls`, () => {
    for (const field of Object.keys(required)) {
      const result = spawnSync("docker", cloudArgs, {
        cwd,
        env: { ...process.env, ...required, [field]: "" },
        encoding: "utf8",
      });
      assert.notEqual(result.status, 0);
      assert.ok(result.stderr.includes(field));
    }
  });
  test(`${backend} selects one backend with file credentials and no HF credential mount`, () => {
    const config = JSON.parse(
      execFileSync("docker", cloudArgs, {
        cwd,
        env: { ...process.env, ...required },
        encoding: "utf8",
      }),
    );
    const app = config.services.app;
    assert.equal(app.environment.AI_BACKEND, backend);
    assert.equal(app.environment.AI_ENABLED, "true");
    assert.deepEqual(
      app.secrets.map((entry) => entry.source),
      [secret],
    );
    assert.ok(config.secrets[secret].file.endsWith(`/secrets/${secretFile}`));
    assert.ok(!Object.hasOwn(app.environment, "HF_TOKEN_FILE"));
    assert.ok(!Object.hasOwn(app.environment, "AWS_SECRET_ACCESS_KEY"));
    assert.ok(!Object.hasOwn(app.environment, "AZURE_OPENAI_API_KEY"));
    if (backend === "bedrock") {
      assert.equal(app.environment.AWS_EC2_METADATA_DISABLED, "true");
      assert.equal(
        app.environment.AWS_SHARED_CREDENTIALS_FILE,
        "/run/secrets/awsCredentials",
      );
    }
  });
}
