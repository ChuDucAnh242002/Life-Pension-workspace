import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const cwd = fileURLToPath(new URL("..", import.meta.url));
function render(overlay) {
  return execFileSync(
    "kubectl",
    ["kustomize", `deploy/kubernetes/${overlay}`],
    {
      cwd,
      encoding: "utf8",
    },
  );
}

test("demo stays single-replica, private, persistent and cloud-disabled without plaintext secrets", () => {
  const yaml = render("demo");
  assert.match(yaml, /namespace: life-pension-demo/);
  assert.match(yaml, /replicas: 1/);
  assert.match(yaml, /type: Recreate/);
  assert.match(yaml, /claimName: workspace-data/);
  assert.match(yaml, /name: AI_ENABLED\n\s+value: "false"/);
  assert.match(yaml, /readOnlyRootFilesystem: true/);
  assert.match(yaml, /automountServiceAccountToken: false/);
  assert.match(yaml, /name: workspace-auth/);
  assert.doesNotMatch(
    yaml,
    /kind: Secret|NodePort|LoadBalancer|hostPath:|hostPort:|kind: Ingress/,
  );
  assert.doesNotMatch(yaml, /local-demo-password|local-demo-database/);
});

test("Oracle replaces H2 storage/profile and requires separate private bootstrap secret", () => {
  const yaml = render("oracle");
  assert.match(yaml, /namespace: life-pension-oracle/);
  assert.match(yaml, /jdbc:oracle:thin:@oracle:1521\/FREEPDB1/);
  assert.match(yaml, /name: SPRING_PROFILES_ACTIVE\n\s+value: ""/);
  assert.match(yaml, /kind: StatefulSet/);
  assert.match(yaml, /name: oracle-bootstrap/);
  assert.match(yaml, /storage: 10Gi/);
  assert.doesNotMatch(
    yaml,
    /claimName: workspace-data|name: workspace-data|mountPath: \/data/,
  );
});

test("MQ reuses queue definitions, requires licence secret and an explicitly reviewed image", () => {
  const yaml = render("mq");
  assert.match(yaml, /namespace: life-pension-mq/);
  assert.match(yaml, /ibm-mq-image-review-required:do-not-run/);
  assert.match(yaml, /name: mq-license/);
  assert.match(yaml, /secretName: mq-app/);
  assert.match(yaml, /name: POLICY_TRANSPORT\n\s+value: mq/);
  assert.match(yaml, /DEV.POLICY.REQUESTS/);
  assert.match(yaml, /DEV.POLICY.REPLIES/);
  assert.match(yaml, /mountPath: \/run\/secrets/);
  assert.doesNotMatch(
    yaml,
    /value: accept|NodePort|LoadBalancer|containerPort: 9443/,
  );
});
