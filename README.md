# Life & Pension Adviser Workspace

A small workspace for preparing pension and protection conversations: record
customer-declared facts, identify missing information, and track what changed.
**Fictional profiles only. Independent of Nordea; not financial advice.**

## Implemented: cases, policy requests, and grounded meeting drafts

- One adviser login with server-side sessions and CSRF-protected writes.
- Three synthetic cases: sole trader, employee, and career change.
- Create/edit profiles: age, employment, dependants, income, voluntary pension
  contribution, life cover, and meeting notes.
- Blank facts remain **unknown**, distinct from an explicitly declared zero.
- Draft → in review → closed, with return/reopen actions. Editing reviewed facts
  returns the case to draft; closed cases must be reopened before editing.
- Revision checks prevent overwriting changes made in another session.
- Each saved change records the adviser and a complete historical fact snapshot.

Step 2 adds correlated policy request/reply behind a transport port. The default
demo uses an **in-process queue, not IBM MQ**. An optional IBM MQ adapter and container
setup are included; live request/reply was verified with IBM MQ Developer 10.0.1.0
and the H2 demo database after user deployment.
Step 3 adds optional cloud AI meeting drafts, disabled by default. Case status
represents meeting preparation, not product suitability or briefing approval.

Select a case's **Fictional policy fixture**, then **Request simulated policy**.
The response appears separately from the customer-declared profile:

- Requests persist with a correlation ID, case revision, adviser, and 60-second deadline.
- Publication retries until the deadline; duplicate/late replies do not overwrite results.
- Replies for changed cases are stale. Profile edits also invalidate previous snapshots.
- Timed-out or invalid replies display an explicit error; a new request uses a new ID.
- Fixture selection is an explicit demonstration choice, not a verified customer-policy link.

## Run the demo

Requires Docker Engine and Docker Compose. The demo uses persistent **H2 in Oracle
compatibility mode**, not an Oracle server.

```sh
docker compose --env-file /dev/null -f compose.demo.yaml up --build -d --wait
```

Open **http://localhost:8089**. Sign in with **`adviser` / `local-demo-password`**.

Try it: open **Synthetic: Mika, sole trader** → **Start review** → **Edit profile** →
enter a pension contribution → save. The case returns to draft. Expand **Case
history**, then review, close, and reopen the case.

Stop while preserving data:

```sh
docker compose --env-file /dev/null -f compose.demo.yaml down
```

Use `--volumes` only to deliberately erase that stack's cases and history.

## Run with Oracle

The separate Oracle stack uses `gvenzl/oracle-free:23.26.1-slim`, a community image
with application-user provisioning and a built-in health check. Review the
[Oracle terms](https://www.oracle.com/database/free/) and
[image documentation](https://hub.docker.com/r/gvenzl/oracle-free) before starting it.

```sh
cp .env.example .env
chmod 600 .env
# Replace placeholder passwords in .env with unique local passwords.
docker compose --env-file .env -f compose.oracle.yaml up --build -d --wait
```

Open **http://localhost:8090** with your configured adviser credentials.
Stop with the same command prefix followed by `down` instead of `up --build -d --wait`.

The Oracle and H2 stacks have **separate volumes**; they do not share or migrate
cases. Database ports are not exposed. Never commit `.env`; Docker administrators
can see environment credentials. Changing bootstrap passwords does not rotate
passwords in an existing database volume.

**Step 1 locally verified on Oracle:** migrations V1/V2, the original API smoke check, all five case browser
tests, and exact preservation of six cases and their history across Oracle/app restart.
**Caveat:** Flyway warns that Oracle 23.26 is newer than its tested support range.
Passing local checks is not vendor-certified compatibility. Automated CI uses H2 only.
The Step 2 policy migration V3 has been verified on H2 with live IBM MQ, but not on Oracle.

## Design

```mermaid
flowchart LR
    Adviser --> UI["React / TypeScript"]
    UI --> API["Spring Boot: session + CSRF"]
    API --> Cases["Case workflow / revision checks"]
    Cases --> DB[("Oracle / H2 demo: cases + history")]
    Cases --> Policy["Persisted policy requests"]
    Policy --> Port["PolicyPort: demo / IBM MQ"]
    Port --> Simulator["Fictional policy simulator"]
    Policy --> DB
    API --> Briefing["Grounded draft / citation validation"]
    Briefing --> Model["BriefingModel: Hugging Face / Azure / Bedrock"]
    Briefing --> DB
```

A modular monolith using Java 21, JDBC, and Flyway. Profile/status writes compare the
submitted revision in SQL and commit the new facts and history **together**, or roll
back both. EUR amounts persist as decimals with at most two decimal places.

The [case service](backend/src/main/java/fi/adviser/workspace/CaseService.java) owns
workflow and persistence; the [controller](backend/src/main/java/fi/adviser/workspace/CaseController.java)
defines the API. The [React UI](frontend/src/App.tsx) handles forms, loading, session
errors, and stale-request ordering without making workflow decisions.

History is append-only through the API, not tamper-proof against database
administrators. Initial synthetic fixtures predate history; subsequent changes are recorded.

### Optional IBM MQ lab

The simulator is a separate logical consumer in the same application, not a real
policy system or independently deployed service. MQ uses persistent request/reply
queues and transacted JMS receipt: processing commits before message acknowledgement.
There is no distributed DB/MQ transaction; duplicate delivery is expected and handled.
Pending requests are resent after restart, including when the demo's memory queues
were lost. Completed snapshots survive in the database and never overwrite declared facts.
Run **one app replica**; multi-replica publisher coordination is not implemented.

After reviewing the [IBM container licence instructions](https://github.com/ibm-messaging/mq-container/blob/master/docs/usage.md)
and [developer configuration](https://github.com/ibm-messaging/mq-container/blob/master/docs/developer-config.md),
create a private `.env.mq` containing `MQ_IMAGE` (a reviewed versioned Developer
image) and `MQ_LICENSE` (set to `accept` only after your own review).
The repository supplies no automatic acceptance value.

MQ's `app` password is supplied via a local file secret, not an admin credential:

```sh
mkdir -p secrets
chmod 700 secrets
read -r -s -p "Local MQ app password (at least 8 characters): " mq_password
printf '\n'
printf '%s' "$mq_password" > secrets/mq-app-password
unset mq_password
chmod 644 secrets/mq-app-password
chmod 600 .env.mq
APP_PORT=8092 docker compose --env-file .env.mq -p life-pension-mq \
  -f compose.demo.yaml -f compose.mq.yaml up --build -d --wait
```

The secret file must be readable by the different container UIDs; its parent directory
is private on the host. Never commit it. Open **http://localhost:8092** with the demo
adviser login. The policy panel must say **IBM MQ**. MQ ports/web console are not exposed.
Use the identical command prefix with `down` to stop while retaining both DB/MQ volumes.

To use Oracle instead of H2, combine `compose.oracle.yaml` and `compose.mq.yaml`
with an env file containing both sets of variables; keep a separate project/volume
from your existing Oracle workspace. That combination has not been live-verified.
Do not swap images or reuse queue-manager data across incompatible MQ versions.

Malformed JSON or mismatched JMS correlation is rolled back and retained; the worker
logs a sanitized warning. There is no automatic dead-letter/skip policy. An operator
must investigate retained poison messages. The requesting case eventually times out.
Readiness checks the app/database, not MQ; the policy request state exposes MQ lag/failure.

### Optional AI drafts

AI selects and orders supplied fact IDs and meeting-question IDs. The server renders
**exact saved source values**, not free-form model prose. Unknown stays distinct
from zero; fictional policy facts remain separate from declarations. This constrained
first version prioritizes preparation rather than generating advice.

Only allowlisted numeric/employment facts and the latest current completed fictional
policy snapshot cross the model boundary. Alias, meeting notes, history, adviser
identity, and credentials are excluded from the prompt. Unknown, duplicate, empty,
or excessive citations are rejected; provider errors never become a successful
demo fallback. Drafts persist with sources, actor, model/provider, and prompt version.
Changed case revisions or latest policy request states make them stale. Sources that
change during inference cause a conflict and no draft is saved.

Select **one backend per deployment**: Hugging Face, Azure OpenAI in Foundry, or AWS
Bedrock. All use the same `BriefingModel` port, prompt, citation validation, and draft
storage; there is no cross-provider fallback. `AI_BACKEND` defaults to `huggingface`
for existing configurations; unsupported values fail application startup.
No vector database, agent framework, or document ingestion is needed for the current
small structured context. Human approval is Step 4.

#### Hugging Face

Review [HF pricing](https://huggingface.co/docs/inference-providers/pricing),
[provider privacy terms](https://huggingface.co/docs/inference-providers/security),
and [structured-output support](https://huggingface.co/docs/inference-providers/guides/structured-output).
Choose an explicit model/provider pair supporting JSON schema in the
[HF Playground](https://huggingface.co/playground). Availability varies; automatic
model/provider routing is not used. Free accounts currently require purchased credits.
Never send real customer information, and never paste an API token into chat.

Prepare `.env.ai` with `HF_PROVIDER` and `HF_MODEL`, and create an inference-authorized
token privately in Hugging Face. Save it locally:

```sh
mkdir -p secrets
chmod 700 secrets
read -r -s -p "Hugging Face inference token: " hf_token
printf '\n'
printf '%s' "$hf_token" > secrets/hf-token
unset hf_token
chmod 644 secrets/hf-token
chmod 600 .env.ai
APP_PORT=8093 docker compose --env-file .env.ai -p life-pension-ai \
  -f compose.demo.yaml -f compose.ai.yaml up --build -d --wait
```

The private parent directory protects the host file; the non-root app must be able
to read the mounted secret. Open **http://localhost:8093** with the demo login.
**Generate AI draft** makes one external, potentially paid call; no automatic retries.
Timeout is 30 seconds; truncated, oversized, or invalid JSON responses are rejected.
Requests use the unified HF chat router with `model:provider` to select the explicit
backend. HTTP 402 means inference credits are required; 401/403 means authorization
failed; 400/404/422 indicates request/model compatibility; 429 indicates a rate limit.
Provider bodies are not displayed or logged.
The server validates IDs but cannot guarantee the model's prioritization is useful.
Use the same command prefix with `down` to stop and preserve data.

#### Azure OpenAI in Foundry

Use an **Azure OpenAI deployment in Foundry**, not a partner/community model endpoint.
Deploy a model supporting [structured outputs](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/structured-outputs).
Create a private `.env.azure` containing:

```dotenv
AZURE_OPENAI_ENDPOINT=https://YOUR-RESOURCE.openai.azure.com
AZURE_OPENAI_DEPLOYMENT=YOUR-DEPLOYMENT-NAME
```

The endpoint is the HTTPS resource root, not a full inference URL. Public-cloud
`*.openai.azure.com` and `*.services.ai.azure.com` roots are supported. The adapter
calls `/openai/v1/chat/completions` with the **deployment name**, not the model
catalog ID, and authenticates using `api-key`.
Save your resource API key privately in `secrets/azure-openai-key`, using the same
hidden `read`/file procedure above. Keep `secrets` mode 700, the key file 644 for
the non-root container, and `.env.azure` mode 600. Never paste or commit the key.

```sh
APP_PORT=8095 docker compose --env-file .env.azure -p life-pension-azure \
  -f compose.demo.yaml -f compose.ai-azure.yaml up --build -d --wait
```

Open **http://localhost:8095**. This version uses API-key authentication, not Entra ID,
and does not provision Azure resources. Regional model availability, quotas, charges,
and data-processing terms must be checked in your Azure subscription.

#### AWS Bedrock

Choose a model/inference profile that supports **Converse JSON-schema structured
outputs** in your region; Converse support alone is insufficient. Review
[structured-output support](https://docs.aws.amazon.com/bedrock/latest/userguide/structured-output.html)
and model access requirements. Create a private `.env.bedrock`:

```dotenv
AWS_REGION=YOUR-REGION
BEDROCK_MODEL_ID=YOUR-MODEL-OR-INFERENCE-PROFILE-ID
AWS_PROFILE=default
```

For the local Compose lab, privately save a dedicated least-privilege AWS shared
credentials profile in `secrets/aws-credentials` (INI format). Prefer temporary
credentials with a session token rather than long-lived keys:

```ini
[default]
aws_access_key_id=YOUR-ACCESS-KEY
aws_secret_access_key=YOUR-SECRET-KEY
aws_session_token=YOUR-SESSION-TOKEN
```

Never use root credentials or paste credentials into chat. Omit the session-token
line only for credentials that genuinely have no session token. Protect the parent
directory and file as above; set `.env.bedrock` mode 600. Temporary credentials must
be refreshed privately when they expire. Allow `bedrock:InvokeModel` on the intended
model/inference-profile resources; cross-region profiles may require permissions
for their destination model resources.

```sh
APP_PORT=8096 docker compose --env-file .env.bedrock -p life-pension-bedrock \
  -f compose.demo.yaml -f compose.ai-bedrock.yaml up --build -d --wait
```

Open **http://localhost:8096**. The adapter uses the AWS SDK default credential chain,
SigV4 signing, and native Converse `outputConfig.textFormat`. The local overlay mounts
only the selected shared-credentials file and disables EC2 metadata discovery; it
does not mount your whole AWS directory or expose keys in environment variables.
For managed workloads, use an appropriate IAM role through the SDK chain rather
than this file-based lab overlay. SSO profiles need their config/cache available;
the local overlay instead expects exported temporary credentials in INI format.
No cloud resources are provisioned.

Bedrock can spend about 90 seconds compiling a new schema; its per-attempt timeout
is 125 seconds and overall timeout is 130 seconds. Azure/HF use 30 seconds. Output
budgets are 500 tokens; truncated responses are rejected. All backends make no
automatic inference retries. No backend can guarantee useful prioritization.

Use only one AI overlay; each can be combined with the MQ overlay and an env file
containing both configurations. Keep a separate project so you do not accidentally
recreate an existing workspace. Stop with the same prefix followed by `down`, without
`--volumes`, to preserve data. Successful cloud generation and Oracle V4 remain
unverified; local mock tests are not evidence of cloud deployment compatibility.

## Test

Step 1 verified: **11 backend tests, 3 frontend unit tests, and 5 browser tests**, plus live
API and restart checks. Coverage includes login/CSRF, validation, unknown vs zero,
workflow, concurrent/stale writes, rollback, historical snapshots, and mobile layout.

Step 2 brings the verified totals to **29 backend tests, 3 frontend unit tests,
9 browser tests, and 2 MQ configuration checks**. The live demo smoke passed.
Runtime checks also verified completed-snapshot persistence, recovery of the same
pending request after app recreation, and a real missing-simulator timeout.
JMS unit tests use mocks. Separately, the user-deployed IBM MQ Developer 10.0.1.0
lab passed the API smoke check and all three fictional policy fixtures. The API
reported `IBM_MQ`, broker channel message counts increased, and both persistent
queues drained. Replies preserved declared facts/history; a later profile edit
made the snapshot stale. This lab uses H2, not Oracle. MQ restart/outage recovery
was not tested; no containers were restarted or existing volumes removed.

The tested deployment used an unversioned image reference; pin a reviewed version
or image digest before recreating it. MQ reported plaintext communication, acceptable
only for this isolated local lab; production requires TLS and hardened configuration.

Step 3 verified totals: **44 backend tests, 3 frontend unit tests, 12 browser tests,
and 4 Compose configuration checks**. Provider HTTP tests use a localhost mock, and
enabled-draft browser tests mock the API. They verify strict JSON parsing, citations,
source privacy, saved snapshots, revision/policy race rejection, and visible errors.
An isolated H2 deployment passed migrations V1–V4 and the smoke check with AI disabled.
No external inference request was made; live model quality and provider compatibility
remain unverified.

Subsequent authorized synthetic diagnostics found and corrected the DeepInfra
provider-specific route rejection: the unified router accepted routing but returned
HTTP 402 for missing prepaid credits. No successful live generation or model-quality
verification has occurred.

With Azure and Bedrock adapters, current totals are **59 backend tests, 3 frontend
unit tests, 14 browser tests, and 8 Compose checks**. Local HTTP tests verify Azure
deployment/key handling and real SDK Bedrock request serialization/SigV4 signing,
error handling, no automatic retries, and shared schema behavior. Backend selection
tests verify inactive providers need no secrets; browser tests mock Azure/Bedrock
draft responses. The isolated H2 smoke passed. No Azure/AWS resources were created
or paid requests made; live credentials, model access, quotas, and compatibility
still require explicit cloud validation.

```sh
cd backend
mvn -B verify                 # Requires Java 21 and Maven.
cd ../frontend
npm ci
npm run lint
npm test
npm run build
cd ..

# Browser/smoke tests add synthetic cases: use a separate stack.
APP_PORT=8091 docker compose --env-file /dev/null -p life-pension-step1-test \
  -f compose.demo.yaml up --build -d --wait
APP_URL=http://127.0.0.1:8091 node scripts/smoke.mjs
node --test scripts/*.test.mjs
cd frontend
npx playwright install chromium
APP_URL=http://127.0.0.1:8091 npm run test:e2e
cd ..
APP_PORT=8091 docker compose --env-file /dev/null -p life-pension-step1-test \
  -f compose.demo.yaml down --volumes
```

[GitHub Actions](.github/workflows/ci.yaml) runs the same verification on a disposable demo.

## Scope

One adviser, fictional Finnish EUR profiles, local HTTP. No policy verification,
recommendations, suitability assessment, or live customer-data ingestion.
Do not enter real customer, health, policy, or financial information.
Production would require TLS, managed identity, retention/access controls, backups,
and legal/regulatory review.

Next: human approval and evaluation → Kubernetes deployment.
