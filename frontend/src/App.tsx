import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { api, ApiError, login, refreshCsrf } from "./api";
import {
  amountInput,
  emptyProfile,
  employmentValues,
  integerInput,
  label,
  money,
} from "./models";
import type { CaseDetail, CaseView, Profile, Status } from "./models";
import "./App.css";

function ProfileForm({
  profile,
  busy,
  onSave,
  onCancel,
}: {
  profile: Profile;
  busy: boolean;
  onSave: (profile: Profile) => Promise<void>;
  onCancel: () => void;
}) {
  const [alias, setAlias] = useState(profile.customerAlias);
  const [age, setAge] = useState(profile.age?.toString() ?? "");
  const [employment, setEmployment] = useState(profile.employment);
  const [dependants, setDependants] = useState(
    profile.dependants?.toString() ?? "",
  );
  const [income, setIncome] = useState(profile.monthlyIncome?.toString() ?? "");
  const [pension, setPension] = useState(
    profile.pensionContribution?.toString() ?? "",
  );
  const [cover, setCover] = useState(profile.lifeCover?.toString() ?? "");
  const [notes, setNotes] = useState(profile.notes ?? "");
  const [error, setError] = useState("");
  const errorRef = useRef<HTMLParagraphElement>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      await onSave({
        customerAlias: alias.trim(),
        age: integerInput(age, 18, 100),
        employment,
        dependants: integerInput(dependants, 0, 20),
        monthlyIncome: amountInput(income),
        pensionContribution: amountInput(pension),
        lifeCover: amountInput(cover),
        notes: notes.trim() || null,
      });
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "Could not save the profile.",
      );
      requestAnimationFrame(() => errorRef.current?.focus());
    }
  }
  return (
    <form onSubmit={submit} className="profile-form">
      <h2>
        {profile.customerAlias ? "Edit declared facts" : "New synthetic case"}
      </h2>
      <p>
        Blank values mean <strong>unknown</strong>. Enter zero only when
        explicitly declared. All amounts are EUR.
      </p>
      {error && (
        <p className="error" role="alert" tabIndex={-1} ref={errorRef}>
          {error} Your entered values have been retained.
        </p>
      )}
      <fieldset disabled={busy}>
        <div className="fields">
          <label>
            Customer alias
            <input
              required
              maxLength={100}
              value={alias}
              onChange={(e) => setAlias(e.target.value)}
            />
          </label>
          <label>
            Age
            <input
              inputMode="numeric"
              value={age}
              onChange={(e) => setAge(e.target.value)}
            />
          </label>
          <label>
            Employment
            <select
              value={employment}
              onChange={(e) => {
                const value = employmentValues.find(
                  (item) => item === e.target.value,
                );
                if (value) setEmployment(value);
              }}
            >
              {employmentValues.map((value) => (
                <option key={value} value={value}>
                  {label(value)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Dependants
            <input
              inputMode="numeric"
              value={dependants}
              onChange={(e) => setDependants(e.target.value)}
            />
          </label>
          <label>
            Monthly income (EUR)
            <input
              inputMode="decimal"
              value={income}
              onChange={(e) => setIncome(e.target.value)}
            />
          </label>
          <label>
            Voluntary pension contribution / month (EUR)
            <input
              inputMode="decimal"
              value={pension}
              onChange={(e) => setPension(e.target.value)}
            />
          </label>
          <label>
            Declared life cover (EUR)
            <input
              inputMode="decimal"
              value={cover}
              onChange={(e) => setCover(e.target.value)}
            />
          </label>
        </div>
        <label>
          Meeting notes
          <textarea
            rows={4}
            maxLength={2000}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </label>
        <p className="muted">
          Changing facts returns an in-review case to draft. These facts are not
          verified policy information.
        </p>
        <div className="actions">
          <button type="submit">{busy ? "Saving…" : "Save profile"}</button>
          <button type="button" className="secondary" onClick={onCancel}>
            Cancel
          </button>
        </div>
      </fieldset>
    </form>
  );
}

function App() {
  const [user, setUser] = useState<string | null>(null);
  const [checking, setChecking] = useState(true);
  const [cases, setCases] = useState<CaseView[]>([]);
  const [detail, setDetail] = useState<CaseDetail | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [historyReady, setHistoryReady] = useState(false);
  const sequence = useRef(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);
  function report(failure: unknown) {
    setError(
      failure instanceof Error ? failure.message : "The request failed.",
    );
    if (failure instanceof ApiError && failure.status === 401) {
      sequence.current++;
      setUser(null);
      setCases([]);
      setDetail(null);
      setEditing(false);
      setCreating(false);
    }
    requestAnimationFrame(() => errorRef.current?.focus());
  }
  useEffect(() => {
    let active = true;
    async function start() {
      try {
        await refreshCsrf();
        const session = await api<{ username: string }>("/session");
        const items = await api<CaseView[]>("/cases");
        if (active) {
          setUser(session.username);
          setCases(items);
        }
      } catch (failure) {
        if (active && !(failure instanceof ApiError && failure.status === 401))
          report(failure);
      } finally {
        if (active) setChecking(false);
      }
    }
    void start();
    return () => {
      active = false;
    };
  }, []);

  async function open(id: number) {
    const request = ++sequence.current;
    setLoading(true);
    setError("");
    setNotice("");
    setEditing(false);
    setCreating(false);
    setDetail(null);
    try {
      const result = await api<CaseDetail>(`/cases/${id}`);
      if (request === sequence.current) {
        setDetail(result);
        setHistoryReady(true);
        setCases((items) =>
          items.map((item) =>
            item.id === result.caseData.id ? result.caseData : item,
          ),
        );
        requestAnimationFrame(() => heading.current?.focus());
      }
    } catch (failure) {
      if (request === sequence.current) report(failure);
    } finally {
      if (request === sequence.current) setLoading(false);
    }
  }

  async function save(profile: Profile) {
    setBusy(true);
    setError("");
    let persisted = false;
    try {
      const result = creating
        ? await api<CaseView>("/cases", "POST", profile)
        : await api<CaseView>(`/cases/${detail?.caseData.id}/profile`, "PUT", {
            revision: detail?.caseData.revision,
            profile,
          });
      persisted = true;
      setHistoryReady(false);
      setCases((items) =>
        [...items.filter((item) => item.id !== result.id), result].sort(
          (a, b) => a.id - b.id,
        ),
      );
      setDetail({ caseData: result, history: [] });
      setCreating(false);
      setEditing(false);
      setNotice("Profile saved. Changes are recorded in case history.");
      setDetail(await api<CaseDetail>(`/cases/${result.id}`));
      setHistoryReady(true);
      requestAnimationFrame(() => heading.current?.focus());
    } catch (failure) {
      if (persisted)
        report(
          new Error(
            "Profile was saved, but history could not be refreshed. Reload the case.",
          ),
        );
      else {
        if (failure instanceof ApiError && failure.status === 401)
          report(failure);
        throw failure;
      }
    } finally {
      setBusy(false);
    }
  }

  async function transition(status: Status) {
    if (!detail) return;
    setBusy(true);
    setError("");
    setNotice("");
    let persisted = false;
    try {
      const result = await api<CaseView>(
        `/cases/${detail.caseData.id}/status`,
        "POST",
        { revision: detail.caseData.revision, status },
      );
      persisted = true;
      setHistoryReady(false);
      setCases((items) =>
        items.map((item) => (item.id === result.id ? result : item)),
      );
      setDetail({ caseData: result, history: [] });
      setDetail(await api<CaseDetail>(`/cases/${result.id}`));
      setHistoryReady(true);
      setNotice(`Case moved to ${label(status).toLowerCase()}.`);
    } catch (failure) {
      report(
        persisted
          ? new Error(
              "Status was saved, but history could not be refreshed. Reload the case.",
            )
          : failure,
      );
    } finally {
      setBusy(false);
    }
  }

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError("");
    try {
      await login(String(form.get("username")), String(form.get("password")));
      const session = await api<{ username: string }>("/session");
      const items = await api<CaseView[]>("/cases");
      setUser(session.username);
      setCases(items);
    } catch (failure) {
      report(failure);
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    setBusy(true);
    try {
      await api<void>("/logout", "POST");
      sequence.current++;
      setLoading(false);
      setUser(null);
      setCases([]);
      setDetail(null);
      setCreating(false);
      setEditing(false);
      setNotice("");
      setError("");
      await refreshCsrf();
    } catch (failure) {
      report(failure);
    } finally {
      setBusy(false);
    }
  }

  const selected = detail?.caseData;
  return (
    <>
      <a className="skip" href="#main">
        Skip to workspace
      </a>
      <header>
        <div>
          <p className="eyebrow">Adviser preparation • Step 1</p>
          <h1>Life &amp; Pension Workspace</h1>
        </div>
        {user && (
          <div className="user">
            <span>{user}</span>
            <button
              className="secondary"
              disabled={busy}
              onClick={() => void signOut()}
            >
              Sign out
            </button>
          </div>
        )}
      </header>
      <div className="banner">
        Synthetic training data only. No financial advice, suitability
        assessment, or live AI generation.
      </div>
      <main id="main">
        {error && (
          <p className="error" role="alert" tabIndex={-1} ref={errorRef}>
            {error}
            {selected && (
              <button
                className="secondary"
                disabled={busy}
                onClick={() => void open(selected.id)}
              >
                Reload case
              </button>
            )}
          </p>
        )}
        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        {checking ? (
          <p role="status">Checking session…</p>
        ) : !user ? (
          <section className="login">
            <h2>Sign in as adviser</h2>
            <p>Prepare cases using fictional, customer-declared facts.</p>
            <form onSubmit={(e) => void signIn(e)}>
              <fieldset disabled={busy}>
                <label>
                  Username
                  <input name="username" autoComplete="username" required />
                </label>
                <label>
                  Password
                  <input
                    name="password"
                    type="password"
                    autoComplete="current-password"
                    required
                  />
                </label>
                <button>{busy ? "Signing in…" : "Sign in"}</button>
              </fieldset>
            </form>
          </section>
        ) : (
          <div className="workspace">
            <aside aria-label="Cases">
              <div className="list-heading">
                <h2>
                  Cases <span>{cases.length}</span>
                </h2>
                <button
                  disabled={busy || loading}
                  onClick={() => {
                    sequence.current++;
                    setCreating(true);
                    setEditing(false);
                    setDetail(null);
                    setError("");
                    setNotice("");
                  }}
                >
                  New case
                </button>
              </div>
              <p className="muted">One adviser • EUR • fictional profiles</p>
              <ul className="case-list">
                {cases.map((item) => (
                  <li key={item.id}>
                    <button
                      disabled={busy}
                      className={selected?.id === item.id ? "selected" : ""}
                      aria-current={
                        selected?.id === item.id ? "true" : undefined
                      }
                      onClick={() => void open(item.id)}
                    >
                      <strong>{item.profile.customerAlias}</strong>
                      <span>
                        {label(item.status)} · Revision {item.revision}
                      </span>
                      <span>{label(item.profile.employment)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </aside>
            <section className="case-panel" aria-busy={busy || loading}>
              {loading ? (
                <p role="status">Loading case…</p>
              ) : creating ? (
                <ProfileForm
                  profile={emptyProfile}
                  busy={busy}
                  onSave={save}
                  onCancel={() => setCreating(false)}
                />
              ) : !selected ? (
                <div className="empty">
                  <h2>Prepare a clearer customer conversation</h2>
                  <p>
                    Choose a case to review known facts, record missing
                    information, and track the meeting-preparation workflow.
                  </p>
                  <p>
                    AI briefings and policy integration are planned for later
                    steps—not implemented yet.
                  </p>
                </div>
              ) : (
                <>
                  <div className="case-heading">
                    <div>
                      <p className="eyebrow">
                        Case {selected.id} · Revision {selected.revision}
                      </p>
                      <h2 ref={heading} tabIndex={-1}>
                        {selected.profile.customerAlias}
                      </h2>
                      <span className="badge">{label(selected.status)}</span>
                    </div>
                    <button
                      className="secondary"
                      disabled={busy}
                      onClick={() => void open(selected.id)}
                    >
                      Reload case
                    </button>
                  </div>
                  {editing ? (
                    <ProfileForm
                      key={`${selected.id}:${selected.revision}`}
                      profile={selected.profile}
                      busy={busy}
                      onSave={save}
                      onCancel={() => setEditing(false)}
                    />
                  ) : (
                    <>
                      <h3>Customer-declared facts</h3>
                      <p className="muted">
                        Not policy-verified. Unknown is different from zero.
                      </p>
                      <dl className="facts">
                        <div>
                          <dt>Age</dt>
                          <dd>{selected.profile.age ?? "Unknown"}</dd>
                        </div>
                        <div>
                          <dt>Employment</dt>
                          <dd>{label(selected.profile.employment)}</dd>
                        </div>
                        <div>
                          <dt>Dependants</dt>
                          <dd>{selected.profile.dependants ?? "Unknown"}</dd>
                        </div>
                        <div>
                          <dt>Monthly income</dt>
                          <dd>{money(selected.profile.monthlyIncome)}</dd>
                        </div>
                        <div>
                          <dt>Voluntary pension / month</dt>
                          <dd>{money(selected.profile.pensionContribution)}</dd>
                        </div>
                        <div>
                          <dt>Declared life cover</dt>
                          <dd>{money(selected.profile.lifeCover)}</dd>
                        </div>
                      </dl>
                      <h3>Meeting notes</h3>
                      <p className="notes">
                        {selected.profile.notes ?? "No notes recorded."}
                      </p>
                      <div className="actions">
                        {selected.status !== "CLOSED" && (
                          <button
                            disabled={busy}
                            onClick={() => {
                              setEditing(true);
                              setNotice("");
                            }}
                          >
                            Edit profile
                          </button>
                        )}
                        {selected.status === "DRAFT" && (
                          <button
                            className="secondary"
                            disabled={busy}
                            onClick={() => void transition("IN_REVIEW")}
                          >
                            Start review
                          </button>
                        )}
                        {selected.status === "IN_REVIEW" && (
                          <>
                            <button
                              className="secondary"
                              disabled={busy}
                              onClick={() => void transition("DRAFT")}
                            >
                              Return to draft
                            </button>
                            <button
                              className="secondary"
                              disabled={busy}
                              onClick={() => void transition("CLOSED")}
                            >
                              Close case
                            </button>
                          </>
                        )}
                        {selected.status === "CLOSED" && (
                          <button
                            disabled={busy}
                            onClick={() => void transition("DRAFT")}
                          >
                            Reopen case
                          </button>
                        )}
                      </div>
                      <p className="muted">
                        Closing records meeting-preparation status, not product
                        suitability or approval.
                      </p>
                    </>
                  )}
                  <section className="history">
                    <h3>Case history</h3>
                    <p className="muted">
                      Each saved change records actor, revision, and a fact
                      snapshot. Initial synthetic fixtures predate this history.
                    </p>
                    {!historyReady ? (
                      <p>
                        {busy
                          ? "Refreshing history…"
                          : "History is unavailable. Reload the case to retrieve it."}
                      </p>
                    ) : detail.history.length === 0 ? (
                      <p>No recorded changes.</p>
                    ) : (
                      <ol>
                        {detail.history.map((change) => (
                          <li key={change.revision}>
                            <details>
                              <summary>
                                Revision {change.revision} ·{" "}
                                {label(change.action)} · {change.actor}
                              </summary>
                              <p>
                                {new Date(change.changedAt).toLocaleString(
                                  "en-FI",
                                )}{" "}
                                · {label(change.snapshot.status)}
                              </p>
                              <p>
                                {change.snapshot.profile.customerAlias} · Age{" "}
                                {change.snapshot.profile.age ?? "Unknown"} ·{" "}
                                {label(change.snapshot.profile.employment)} ·
                                Dependants{" "}
                                {change.snapshot.profile.dependants ??
                                  "Unknown"}
                              </p>
                              <p>
                                Income{" "}
                                {money(change.snapshot.profile.monthlyIncome)} ·
                                Pension{" "}
                                {money(
                                  change.snapshot.profile.pensionContribution,
                                )}{" "}
                                · Life cover{" "}
                                {money(change.snapshot.profile.lifeCover)}
                              </p>
                              <p className="notes">
                                {change.snapshot.profile.notes ?? "No notes."}
                              </p>
                            </details>
                          </li>
                        ))}
                      </ol>
                    )}
                  </section>
                </>
              )}
            </section>
          </div>
        )}
      </main>
      <footer>
        Independent prototype. Not a Nordea product. Use fictional profiles
        only.
      </footer>
    </>
  );
}

export default App;
