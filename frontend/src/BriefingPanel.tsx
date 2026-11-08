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
};

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
          previous ? { ...previous, draft, stale: false } : previous,
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

  const draft = view?.draft;
  const stale = view?.stale || (draft && draft.caseRevision !== revision);
  return (
    <section className="policy-panel" aria-label="AI meeting draft">
      <h3>AI meeting draft</h3>
      <p className="muted">
        AI prioritizes supplied facts and questions. Values and citations are
        rendered from saved sources, not invented prose. Unapproved preparation
        only—not recommendations, suitability decisions, or financial advice.
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
                  : "Draft saved—unapproved. Review every source before use."}
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
            </>
          )}
        </>
      )}
    </section>
  );
}
