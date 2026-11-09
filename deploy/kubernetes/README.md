# Kubernetes lab and presentation

These are **single-replica local labs**, not production banking infrastructure.
The demo has H2 and in-process policy transport; Oracle and IBM MQ are separate
prepared alternatives, not prerequisites. Cloud AI stays disabled. No licences,
cloud resources, secrets, Ingress, or public ports are provisioned automatically.

## Persistent demo

Requires kubectl/Kustomize, a reachable cluster with a default dynamic storage class,
and sufficient node capacity. Check the target before applying anything:

```sh
kubectl config current-context
kubectl get nodes
kubectl get storageclass
kubectl get namespace life-pension-demo
```

If that namespace already exists, confirm ownership or choose a separate namespace
in a local Kustomize overlay. Do not reuse someone else's workspace.

From the repository root:

```sh
docker build -t life-pension-workspace:step6 .
kubectl apply -f deploy/kubernetes/demo/namespace.yaml
```

Make the image available to the nodes. A host Docker image is **not automatically
available to Kubernetes**. Use a private registry and a local Kustomize image override
(plus an image-pull secret when needed), or import this exact image into a disposable
lab's containerd nodes. Do not use a mutable tag for upgrades.

Create `secrets/adviser-password` (12-72 characters) and `secrets/database-password`
privately using hidden terminal input as described in the main README. Keep their
parent directory mode 700. They are ignored by Git. Then:

```sh
kubectl -n life-pension-demo create secret generic workspace-auth \
  --from-file=adviser-password=secrets/adviser-password \
  --from-file=database-password=secrets/database-password
kubectl apply -k deploy/kubernetes/demo
kubectl -n life-pension-demo rollout status deployment/workspace --timeout=300s
kubectl -n life-pension-demo get pods,pvc,svc
kubectl -n life-pension-demo port-forward --address 127.0.0.1 service/workspace 8099:8080
```

Keep the last command running; open http://localhost:8099. Sign in as `adviser`
with your private password. For VS Code Remote SSH, forward **8099** in the Ports
panel, or use an SSH local tunnel. `localhost` in your laptop's browser is not the
remote Linux host. The service itself is ClusterIP only.

The app runs non-root with a read-only root filesystem, a writable temporary volume,
and a persistent `/data` claim. Recreate updates avoid simultaneous H2 writers.
One replica is required for H2 and the current policy publisher design.
Readiness checks the app/database, **not MQ or AI**; liveness only checks the TCP
listener to avoid repeatedly restarting the app during database outages.
Local-path storage is node-local: pod recreation is not protection against node loss.
Kubernetes Secrets need RBAC and at-rest encryption for anything beyond a private lab.

### Changing the adviser login password

Update only the `adviser-password` key in `workspace-auth`, keeping the existing
`database-password` unchanged. The running pod reads these values at startup, so
restart it after updating the Secret:

```sh
kubectl -n life-pension-demo rollout restart deployment/workspace
kubectl -n life-pension-demo rollout status deployment/workspace --timeout=300s
```

Reconnect the port-forward after the pod is replaced. Sign in as `adviser` with
the new password.

The database password must match the password used to initialize the persistent
H2 database. Replacing the Secret does **not** rotate that stored password.
Changing it independently prevents startup with an H2 authentication error,
before adviser login is available. Restore the original database credential;
do not delete the PVC to troubleshoot login. Resetting the database discards
its active cases, history, policy snapshots, drafts, and approvals, and requires
explicit acceptance of that data loss.

Stop without deleting data:

```sh
kubectl -n life-pension-demo scale deployment/workspace --replicas=0
```

Resume with `--replicas=1`. Do **not** delete the namespace, PVC, or apply
`kubectl delete -k` unless you deliberately want to remove data. Default storage
reclaim policies can delete backing volumes. No automatic cleanup command is supplied.

## Oracle alternative: prepare only

Render with `kubectl kustomize deploy/kubernetes/oracle`. It uses the community
`gvenzl/oracle-free:23.26.1-slim` image, 10Gi storage, and a separate namespace
`life-pension-oracle`. Review Oracle/image terms before deployment. No Oracle
container has been started by this Kubernetes setup.

Create that namespace and a `workspace-auth` secret as above, plus
`oracle-bootstrap` with key `admin-password`, privately from a local file.
Only after explicit licence review, apply the overlay. It replaces the H2 profile
and PVC with Oracle JDBC and a single Oracle StatefulSet. The app can restart
while the database initializes; wait for both workloads. Bootstrap secrets do not
rotate passwords on existing volumes. Oracle uses image-specific permissions and
requires more memory than the demo. V3-V5 on Oracle remain unverified.

## IBM MQ alternative: prepare only

Render with `kubectl kustomize deploy/kubernetes/mq`. This is H2 + MQ in namespace
`life-pension-mq`; it reuses the Compose lab's MQSC queue definitions.
The MQ image is deliberately **unrunnable** until replaced with a reviewed pinned
Developer image. No automatic `LICENSE=accept` is included.

Before any deployment, review IBM terms, set the image via a local Kustomize image
override, and privately create:

- `workspace-auth`: adviser/database passwords, as above.
- `mq-app`: key `password`, from the private MQ app-password file.
- `mq-license`: key `acceptance`, containing `accept` **only after your own review**.

MQ gets a 2Gi persistent claim and private broker service, with no console or host
port. The simulator still runs in the app, not an external insurer system.
MQ delivery is at-least-once; database requests and correlations handle duplicates.
Plaintext broker traffic and Developer defaults are isolated-lab only.
This overlay has not been live deployed. Do not combine Oracle/MQ alternatives
by applying both; a combined deployment needs an explicit common namespace/overlay.

## Optional cloud AI

The default demo deliberately cannot generate new AI drafts. Existing drafts and
approvals survive and can be reviewed without inference. For a live presentation,
Compose cloud overlays remain the verified setup described in the main README.
Kubernetes cloud enablement requires an explicit local deployment patch and Secret:
`AI_ENABLED=true`, `AI_BACKEND`, and the matching Azure/HF/Bedrock settings.
Use mounted credential files for Azure/HF, and workload IAM roles for Bedrock on
managed infrastructure. Never bake credentials into images or commit Secret YAML.
No cloud-enabled Kubernetes variant has been live-verified.

## Five-minute presentation

1. State the boundary: fictional Finnish EUR preparation, no advice or suitability.
2. Show unknown versus zero and revision/history tracking.
3. Request fictional policy information; explain demo transport versus real MQ.
4. On an explicitly cloud-enabled stack, generate a cited draft, open its sources,
   review every source, and approve. This step may incur a charge.
5. Edit the facts: show stale approval and blocked current use. Explain preserved history.
6. Show offline evaluation evidence and the restart/concurrency tests.
7. Explain architecture: modular monolith, ports/adapters for MQ/models, durable
   request workflow, at-least-once delivery, source-grounded output, human review.

Do not present mocked/offline evaluation as model-quality evidence, or H2 validation
as Oracle certification. Production additionally needs managed identity, TLS,
backups/restore tests, retention controls, network restrictions and regulatory review.

## Local verification evidence

The H2 demo was deployed to a new `life-pension-demo` namespace on the selected
four-node Kubernetes 1.30.14 lab with default `local-path` storage. Its pinned app
image was imported into the three worker nodes; the existing namespaces/workloads
were not modified. Private random adviser/database credentials were generated
directly into `workspace-auth`, not saved in Git or printed.

The app and PVC became ready. Authenticated API smoke passed (including correlated
demo policy retrieval, source separation, disabled AI and blocked unapproved use).
After recreating only the demo pod, all four case/history/policy/briefing-state
snapshots matched exactly. No AI drafts were generated in Kubernetes.
The image build passed 74 backend tests; 13 CLI/configuration checks passed.
All three manifest variants rendered and passed client-side schema validation.
Oracle and MQ were **not** deployed; their Kubernetes runtime remains unverified.

After a later database credential mismatch, an explicitly authorized demo reset
initialized a fresh database with three starter cases. The old H2 files were
retained on the same PVC; they are not an independent backup. Health, login with
the updated adviser password, and logout passed after the reset.

The demo is left running, but temporary validation tunnels were closed.
Start the port-forward command above to access it. Retrieve the current
adviser password privately in your own terminal if needed:

```sh
kubectl -n life-pension-demo get secret workspace-auth \
  -o jsonpath='{.data.adviser-password}' | base64 --decode
printf '\n'
```

Do not paste this output into chat, screenshots, source code, or shared logs.
