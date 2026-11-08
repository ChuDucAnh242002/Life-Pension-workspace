import { useEffect, useRef, useState } from "react";
import { api } from "./api";
import { label, money } from "./models";

type Fixture = "SOLE_TRADER" | "EMPLOYEE" | "CAREER_CHANGE";
type RequestStatus =
  "QUEUED" | "WAITING" | "COMPLETED" | "TIMED_OUT" | "FAILED" | "STALE";
type Snapshot = {
  source: string;
  policyReference: string;
  monthlyPension: number | null;
  lifeCover: number | null;
  explanation: string;
};
type PolicyRequest = {
  id: string;
  caseId: number;
  caseRevision: number;
  fixture: Fixture;
  status: RequestStatus;
  requestedBy: string;
  createdAt: string;
  completedAt: string | null;
  expiresAt: number;
  attempts: number;
  lastError: string | null;
  snapshot: Snapshot | null;
};
type Workspace = {
  transport: string;
  simulator: boolean;
  requests: PolicyRequest[];
};
const fixtures: Fixture[] = ["SOLE_TRADER", "EMPLOYEE", "CAREER_CHANGE"];

export default function PolicyPanel({
  caseId,
  revision,
  disabled,
}: {
  caseId: number;
  revision: number;
  disabled: boolean;
}) {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [fixture, setFixture] = useState<Fixture>("SOLE_TRADER");
  const [loadError, setLoadError] = useState("");
  const [commandError, setCommandError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const errorRef = useRef<HTMLParagraphElement>(null);
  const commandInProgress = useRef(false);
  const active = useRef(false);
  const generation = useRef(0);
  const error = commandError || loadError;

  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);

  useEffect(() => {
    let current = true;
    let timer: ReturnType<typeof setTimeout>;
    async function load() {
      if (commandInProgress.current) {
        timer = setTimeout(() => void load(), 2000);
        return;
      }
      const requestGeneration = generation.current;
      try {
        const data = await api<Workspace>(`/cases/${caseId}/policy`);
        if (current && requestGeneration === generation.current) {
          setWorkspace(data);
          setLoadError("");
        }
      } catch (failure) {
        if (current && requestGeneration === generation.current)
          setLoadError(
            failure instanceof Error
              ? failure.message
              : "Policy status could not be loaded.",
          );
      } finally {
        if (current) timer = setTimeout(() => void load(), 2000);
      }
    }
    void load();
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [caseId, revision, refresh]);

  async function request() {
    generation.current++;
    commandInProgress.current = true;
    setSubmitting(true);
    setCommandError("");
    try {
      const result = await api<PolicyRequest>(
        `/cases/${caseId}/policy/requests`,
        "POST",
        {
          revision,
          fixture,
        },
      );
      if (active.current) {
        setWorkspace((value) =>
          value
            ? {
                ...value,
                requests: [
                  result,
                  ...value.requests.filter((item) => item.id !== result.id),
                ].slice(0, 10),
              }
            : value,
        );
        setRefresh((value) => value + 1);
      }
    } catch (failure) {
      if (active.current) {
        setCommandError(
          failure instanceof Error
            ? failure.message
            : "Policy request could not be submitted.",
        );
        requestAnimationFrame(() => errorRef.current?.focus());
      }
    } finally {
      commandInProgress.current = false;
      if (active.current) setSubmitting(false);
    }
  }
  const latest = workspace?.requests[0];
  const pending = latest?.status === "QUEUED" || latest?.status === "WAITING";
  const fresh =
    latest?.caseRevision === revision && latest?.status === "COMPLETED";

  return (
    <section className="policy-panel" aria-label="Simulated policy information">
      <h3>Simulated policy information</h3>
      <p className="muted">
        Fictional legacy-policy records, separate from customer-declared facts.
        Selecting a fixture does not link or verify a real customer's policy.
      </p>
      {workspace && (
        <p className="transport">
          Transport:{" "}
          <strong>
            {workspace.transport === "IBM_MQ"
              ? "IBM MQ"
              : "In-process demo (not IBM MQ)"}
          </strong>{" "}
          · Simulator {workspace.simulator ? "enabled" : "disabled"}
        </p>
      )}
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}{" "}
          <button
            type="button"
            className="secondary"
            onClick={() => {
              setCommandError("");
              setRefresh((value) => value + 1);
            }}
          >
            Retry policy status
          </button>
        </p>
      )}
      {!workspace && !error && <p role="status">Loading policy status…</p>}
      <div className="policy-controls">
        <label>
          Fictional policy fixture
          <select
            value={fixture}
            disabled={disabled || submitting || pending}
            onChange={(event) => {
              const value = fixtures.find(
                (item) => item === event.target.value,
              );
              if (value) setFixture(value);
            }}
          >
            {fixtures.map((value) => (
              <option key={value} value={value}>
                {label(value)}
              </option>
            ))}
          </select>
        </label>
        <button
          disabled={
            disabled || submitting || pending || !workspace || Boolean(error)
          }
          onClick={() => void request()}
        >
          {submitting
            ? "Submitting policy request…"
            : pending
              ? "Policy request pending…"
              : "Request simulated policy"}
        </button>
      </div>
      {latest && (
        <div className="policy-result">
          <p role="status">
            Policy request: <strong>{label(latest.status)}</strong> · Case
            revision {latest.caseRevision}
          </p>
          {pending && (
            <p className="muted">
              Deadline: {new Date(latest.expiresAt).toLocaleString("en-FI")}.
              Requests are retained for retry until this deadline.
            </p>
          )}
          {latest.lastError && <p className="error">{latest.lastError}</p>}
          {latest.snapshot && (
            <>
              {!fresh && (
                <p className="error">
                  Stale policy snapshot: the case revision changed. Request
                  again before using this information.
                </p>
              )}
              <p>
                <strong>{latest.snapshot.policyReference}</strong> · Fictional
                policy simulator
              </p>
              <dl className="facts">
                <div>
                  <dt>Simulated pension / month</dt>
                  <dd>{money(latest.snapshot.monthlyPension)}</dd>
                </div>
                <div>
                  <dt>Simulated life cover</dt>
                  <dd>{money(latest.snapshot.lifeCover)}</dd>
                </div>
              </dl>
              <p>{latest.snapshot.explanation}</p>
              <p className="muted">
                Received{" "}
                {latest.completedAt
                  ? new Date(latest.completedAt).toLocaleString("en-FI")
                  : "unknown"}{" "}
                · Not merged into the profile.
              </p>
            </>
          )}
          <details>
            <summary>Policy request history (latest 10)</summary>
            <ul>
              {workspace?.requests.map((item) => (
                <li key={item.id}>
                  {label(item.fixture)} · {label(item.status)} · Revision{" "}
                  {item.caseRevision} · {item.requestedBy}
                  <br />
                  <span className="muted">
                    Correlation ID: {item.id} · Publication attempts:{" "}
                    {item.attempts}
                  </span>
                </li>
              ))}
            </ul>
          </details>
        </div>
      )}
    </section>
  );
}
