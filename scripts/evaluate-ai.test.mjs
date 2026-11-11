import assert from "node:assert/strict";
import { test } from "node:test";
import { evaluationPlan } from "./evaluate-ai.mjs";

test("evaluation requires explicit paid synthetic authorization and known backend", () => {
  for (const args of [
    [],
    ["--backend=azure"],
    ["--allow-paid-synthetic"],
    ["--backend=auto", "--allow-paid-synthetic"],
    ["--backend=azure", "--allow-paid-synthetic", "--extra"],
  ])
    assert.throws(() => evaluationPlan(args));
});

test("each evaluation backend isolates DB, mounts only its secret and runs opt-in live profile", () => {
  for (const backend of ["azure", "bedrock", "huggingface"]) {
    const plan = evaluationPlan(
      [`--backend=${backend}`, "--allow-paid-synthetic"],
      "/synthetic/repository",
    );
    assert.equal(plan.backend, backend);
    assert.equal(plan.files.length, 2);
    assert.ok(plan.command.includes("AI_ENABLED=true"));
    assert.ok(plan.command.includes(`AI_BACKEND=${backend}`));
    assert.ok(
      plan.command.includes("LIVE_AI_EVALUATION=accept-paid-synthetic-only"),
    );
    assert.ok(plan.command.includes("-Plive-ai-evaluation"));
    assert.ok(plan.command.includes("-Dtest=BriefingEvaluationTest"));
    assert.equal(
      plan.command.filter((arg) => arg.includes(":/run/secrets/")).length,
      1,
    );
    assert.ok(!plan.command.includes("--network"));
    assert.ok(!plan.command.some((arg) => arg.startsWith("DATABASE_URL=")));
    assert.ok(
      !plan.command.some((arg) => arg.startsWith("AWS_SECRET_ACCESS_KEY=")),
    );
    if (backend === "bedrock")
      assert.ok(plan.command.includes("AWS_EC2_METADATA_DISABLED=true"));
  }
});
