import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const cwd = fileURLToPath(new URL("..", import.meta.url));

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
  const cloudArgs = [
    "compose", "--env-file", "/dev/null", "-p", "life-pension-ai-config-test",
    "-f", "compose.demo.yaml", "-f", overlay, "config", "--format", "json",
  ];
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
  test(`${backend} selects one backend with file credentials`, () => {
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
