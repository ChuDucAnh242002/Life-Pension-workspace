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
  "life-pension-mq-test",
  "-f",
  "compose.demo.yaml",
  "-f",
  "compose.mq.yaml",
  "config",
  "--format",
  "json",
];
const env = {
  ...process.env,
  MQ_IMAGE: "icr.io/ibm-messaging/mq:review-required",
  MQ_LICENSE: "not-accepted",
};

test("MQ requires explicit licence configuration and never starts during rendering", () => {
  const result = spawnSync("docker", args, {
    cwd,
    env: { ...env, MQ_LICENSE: "" },
    encoding: "utf8",
  });
  assert.notEqual(result.status, 0);
  assert.ok(result.stderr.includes("MQ_LICENSE"));
});

test("MQ setup isolates data and keeps its broker and credentials off host ports", () => {
  const config = JSON.parse(
    execFileSync("docker", args, { cwd, env, encoding: "utf8" }),
  );
  assert.equal(config.name, "life-pension-mq-test");
  assert.equal(config.services.app.environment.POLICY_TRANSPORT, "mq");
  assert.equal(
    config.services.app.environment.MQ_PASSWORD_FILE,
    "/run/secrets/mqAppPassword",
  );
  assert.ok(!config.services.mq.ports?.length);
  assert.equal(
    config.services.mq.environment.MQ_ENABLE_EMBEDDED_WEB_SERVER,
    "false",
  );
  assert.equal(config.services.mq.environment.LICENSE, "not-accepted");
  assert.ok(
    Object.values(config.volumes).every((volume) =>
      volume.name.startsWith("life-pension-mq-test_"),
    ),
  );
});
