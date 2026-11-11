import { useEffect, useRef, useState } from "react";
import { api } from "./api";

type Source = { id: string; label: string; value: string; origin: string };
type Question = { id: string; text: string; sourceId: string };
type Draft = {
  id: string;
  caseRevision: number;
  createdAt: string;
  createdBy: string;
  provider: string;
  model: string;
  promptVersion: string;
  summary: Source[];
  questions: Question[];
  sources: Source[];
};
type View = {
  enabled: boolean;
  provider: string;
  model: string;
  stale: boolean;
  draft: Draft | null;
  approvals: Approval[];
  usable: boolean;
};
type Approval = {
  draftId: string;
  approvedAt: string;
  approvedBy: string;
  note: string | null;
  status: "CURRENT" | "STALE" | "SUPERSEDED";
};

function ApprovalForm({
  disabled,
  submitting,
  approve,
}: {
  disabled: boolean;
  submitting: boolean;
  approve: (reviewed: boolean, note: string) => Promise<void>;
}) {
  const [reviewed, setReviewed] = useState(false);
  const [note, setNote] = useState("");
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void approve(reviewed, note);
      }}
    >
      <h4>Adviser source review</h4>
      <label>
        <input
          type="checkbox"
          checked={reviewed}
          disabled={disabled || submitting}
          onChange={(event) => setReviewed(event.target.checked)}
        />
        I reviewed every source
      </label>
      <label>
        Review note (optional)
        <textarea
          value={note}
          maxLength={1000}
          disabled={disabled || submitting}
          onChange={(event) => setNote(event.target.value)}
        />
      </label>
      <button type="submit" disabled={disabled || submitting || !reviewed}>
        {submitting ? "Saving…" : "Approve meeting briefing"}
      </button>
    </form>
  );
}

export default function BriefingPanel({
  caseId,
  revision,
  disabled,
}: {
  caseId: number;
  revision: number;
  disabled: boolean;
}) {
  const [view, setView] = useState<View | null>(null);
  const [loadError, setLoadError] = useState("");
  const [commandError, setCommandError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const active = useRef(false);
  const generating = useRef(false);
  const generation = useRef(0);
  const sourceDetails = useRef<HTMLDetailsElement>(null);

  function revealSources() {
    if (sourceDetails.current) sourceDetails.current.open = true;
  }

  useEffect(() => {
    active.current = true;
    let current = true;
    let timer: ReturnType<typeof setTimeout>;
    async function load() {
      const version = generation.current;
      try {
        if (!generating.current) {
          const result = await api<View>(`/cases/${caseId}/briefing`);
          if (current && version === generation.current) {
            setView(result);
            setLoadError("");
          }
        }
      } catch (failure) {
        if (current && version === generation.current)
          setLoadError(
            failure instanceof Error ? failure.message : "Draft load failed.",
          );
      } finally {
        if (current) timer = setTimeout(() => void load(), 2000);
      }
    }
    void load();
    return () => {
      active.current = false;
      current = false;
      clearTimeout(timer);
    };
  }, [caseId]);

  async function generate() {
    if (generating.current) return;
    generating.current = true;
    generation.current++;
    setSubmitting(true);
    setCommandError("");
    try {
      const draft = await api<Draft>(`/cases/${caseId}/briefing`, "POST", {
        revision,
      });
      if (active.current)
        setView((previous) =>
          previous
            ? {
                ...previous,
                draft,
                stale: false,
                usable: false,
                approvals: (previous.approvals ?? []).map((approval) => ({
                  ...approval,
                  status: "SUPERSEDED",
                })),
              }
            : previous,
        );
    } catch (failure) {
      if (active.current)
        setCommandError(
          failure instanceof Error
            ? failure.message
            : "Draft generation failed.",
        );
    } finally {
      generating.current = false;
      if (active.current) setSubmitting(false);
    }
  }

  async function approve(reviewedSources: boolean, note: string) {
    const draft = view?.draft;
    if (!draft || generating.current) return;
    generating.current = true;
    generation.current++;
    setSubmitting(true);
    setCommandError("");
    try {
      const approval = await api<Approval>(
        `/cases/${caseId}/briefing/approval`,
        "POST",
        {
          draftId: draft.id,
          revision,
          reviewedSources,
          note,
        },
      );
      if (active.current)
        setView((previous) =>
          previous
            ? {
                ...previous,
                usable: true,
                approvals: [
                  approval,
                  ...(previous.approvals ?? []).filter(
                    (item) => item.draftId !== approval.draftId,
                  ),
                ].slice(0, 10),
              }
            : previous,
        );
    } catch (failure) {
      if (active.current) {
        setCommandError(
          failure instanceof Error ? failure.message : "Approval failed.",
        );
        setView((previous) =>
          previous ? { ...previous, usable: false } : previous,
        );
      }
    } finally {
      generating.current = false;
      if (active.current) setSubmitting(false);
    }
  }

  const draft = view?.draft;
  const stale = view?.stale || (draft && draft.caseRevision !== revision);
  const usable = view?.usable && !stale && !loadError;
  return (
    <section className="policy-panel" aria-label="AI meeting draft">
      <h3>AI meeting draft</h3>
      <p className="muted">
        AI prioritizes supplied facts and questions. Values and citations are
        rendered from saved sources, not invented prose. Approval confirms
        adviser source review only—not recommendations, suitability decisions,
        or financial advice.
      </p>
      {(commandError || loadError) && (
        <p className="error" role="alert">
          {commandError || loadError}
        </p>
      )}
      {!view ? (
        <p>Loading draft status…</p>
      ) : (
        <>
          <p>
            {view.enabled
              ? `${view.provider} · ${view.model}`
              : "AI disabled. Configure an AI backend to enable generation."}
          </p>
          <p className="muted">
            Generation sends selected fictional facts to the configured external
            provider and may incur charges. Alias and meeting notes are
            excluded. Bedrock's first schema compilation can take up to two
            minutes.
          </p>
          <button
            disabled={disabled || !view.enabled || submitting}
            onClick={() => void generate()}
          >
            {submitting ? "Generating draft…" : "Generate AI draft"}
          </button>
          {draft && (
            <>
              <p role="status">
                {stale
                  ? "Stale draft: case or policy sources changed. Generate a new draft."
                  : usable
                    ? "Approved meeting briefing—source review recorded."
                    : "Draft saved—unapproved or not verified current. Review every source before use."}
              </p>
              <h4>Prioritized facts</h4>
              <ol>
                {draft.summary.map((source) => (
                  <li key={source.id}>
                    {source.label}: <strong>{source.value}</strong>{" "}
                    <a
                      href={`#briefing-${caseId}-${source.id}`}
                      onClick={revealSources}
                    >
                      [{source.id}]
                    </a>
                  </li>
                ))}
              </ol>
              <h4>Meeting questions</h4>
              <ol>
                {draft.questions.map((question) => (
                  <li key={question.id}>
                    {question.text}{" "}
                    <a
                      href={`#briefing-${caseId}-${question.sourceId}`}
                      onClick={revealSources}
                    >
                      [{question.sourceId}]
                    </a>
                  </li>
                ))}
              </ol>
              <details ref={sourceDetails}>
                <summary>Saved source citations and generation details</summary>
                <p>
                  {draft.provider} · {draft.model} · {draft.promptVersion}
                  <br />
                  Case revision {draft.caseRevision} · {draft.createdBy} ·{" "}
                  {new Date(draft.createdAt).toLocaleString("en-FI")}
                </p>
                <ul>
                  {draft.sources.map((source) => (
                    <li key={source.id} id={`briefing-${caseId}-${source.id}`}>
                      [{source.id}] {source.label}: {source.value}
                      <br />
                      {source.origin}
                    </li>
                  ))}
                </ul>
              </details>
              {!usable && (
                <ApprovalForm
                  key={`${draft.id}:${Boolean(stale)}`}
                  disabled={disabled || Boolean(stale) || Boolean(loadError)}
                  submitting={submitting}
                  approve={approve}
                />
              )}
            </>
          )}
          {(view.approvals ?? []).length > 0 && (
            <details>
              <summary>Approval history (latest 10)</summary>
              <ol>
                {view.approvals.map((approval) => (
                  <li key={approval.draftId}>
                    {approval.status === "CURRENT" && stale
                      ? "STALE"
                      : approval.status}
                    {" · "}
                    {approval.approvedBy}
                    {" · "}
                    {new Date(approval.approvedAt).toLocaleString("en-FI")}
                    <br />
                    Draft: {approval.draftId}
                    {approval.note && <p>{approval.note}</p>}
                  </li>
                ))}
              </ol>
              <p className="muted">
                Stale or superseded approvals are historical evidence, not
                authorization to use the current briefing. Approval does not
                approve a financial product.
              </p>
            </details>
          )}
        </>
      )}
    </section>
  );
}
