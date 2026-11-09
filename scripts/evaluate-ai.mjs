import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

const root = fileURLToPath(new URL("..", import.meta.url));
const backends = {
  huggingface: {
    env: ".env.ai",
    secret: "hf-token",
    target: "hfToken",
    variable: "HF_TOKEN_FILE",
  },
  azure: {
    env: ".env.azure",
    secret: "azure-openai-key",
    target: "azureOpenAiKey",
    variable: "AZURE_OPENAI_KEY_FILE",
  },
  bedrock: {
    env: ".env.bedrock",
    secret: "aws-credentials",
    target: "awsCredentials",
    variable: "AWS_SHARED_CREDENTIALS_FILE",
  },
};

export function evaluationPlan(args, directory = root) {
  if (args.length !== 2 || !args.includes("--allow-paid-synthetic"))
    throw new Error(
      "Usage: node scripts/evaluate-ai.mjs --backend=azure|bedrock|huggingface --allow-paid-synthetic",
    );
  const selector = args.find((arg) => arg.startsWith("--backend="));
  const backend = selector?.slice("--backend=".length);
  if (!Object.hasOwn(backends, backend ?? ""))
    throw new Error("Select a supported explicit evaluation backend.");
  const config = backends[backend];
  const env = resolve(directory, config.env);
  const secret = resolve(directory, "secrets", config.secret);
  return {
    backend,
    files: [env, secret],
    command: [
      "run",
      "--rm",
      "--env-file",
      env,
      "-e",
      "AI_ENABLED=true",
      "-e",
      `AI_BACKEND=${backend}`,
      "-e",
      "LIVE_AI_EVALUATION=accept-paid-synthetic-only",
      "-e",
      `${config.variable}=/run/secrets/${config.target}`,
      ...(backend === "bedrock"
        ? ["-e", "AWS_EC2_METADATA_DISABLED=true"]
        : []),
      "-v",
      `${secret}:/run/secrets/${config.target}:ro`,
      "-v",
      `${resolve(directory, "backend")}:/workspace`,
      "-v",
      "life-pension-evaluation-maven:/root/.m2",
      "-w",
      "/workspace",
      "maven:3.9.11-eclipse-temurin-21",
      "mvn",
      "-B",
      "-Plive-ai-evaluation",
      "-Dtest=BriefingEvaluationTest",
      "verify",
    ],
  };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    const plan = evaluationPlan(process.argv.slice(2));
    for (const file of plan.files)
      if (!existsSync(file))
        throw new Error(
          `Required private configuration file is missing: ${file}`,
        );
    console.log(
      `Paid synthetic ${plan.backend} evaluation: up to 6 inference calls, no retries. No existing workspace data is used.`,
    );
    const result = spawnSync("docker", plan.command, { stdio: "inherit" });
    if (result.error) throw result.error;
    process.exitCode = result.status ?? 1;
  } catch (failure) {
    console.error(
      failure instanceof Error
        ? failure.message
        : "Evaluation could not start.",
    );
    process.exitCode = 1;
  }
}
