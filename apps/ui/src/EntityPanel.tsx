import { Fragment, useCallback, useEffect, useState } from "react";
import {
  api,
  type ActionPreview,
  type AuditRow,
  type EditState,
  type EntityInfo,
  type Ref,
  type SearchResult,
  type WhyResult,
} from "./api";

type EntityPanelProps = {
  entity: EntityInfo;
  onEdited: (state: EditState) => void;
  onSelect: (iri: string) => void;
  onError: (message: string) => void;
};

export default function EntityPanel({ entity, onEdited, onSelect, onError }: EntityPanelProps) {
  const [newSuper, setNewSuper] = useState("");
  const [suggestions, setSuggestions] = useState<SearchResult[]>([]);
  const [axiomText, setAxiomText] = useState("");
  const [busy, setBusy] = useState(false);
  const [why, setWhy] = useState<WhyResult | null>(null);
  const [labelDraft, setLabelDraft] = useState("");
  const [preview, setPreview] = useState<ActionPreview | null>(null);
  const [audit, setAudit] = useState<AuditRow[]>([]);

  const run = useCallback(
    async (action: () => Promise<EditState>) => {
      setBusy(true);
      try {
        onEdited(await action());
      } catch (error) {
        onError((error as Error).message);
      } finally {
        setBusy(false);
      }
    },
    [onEdited, onError],
  );

  const addSuper = useCallback(
    async (sup: string) => {
      await run(() => api.addSubclass(entity.iri, sup));
      setNewSuper("");
      setSuggestions([]);
    },
    [entity.iri, run],
  );

  const suggestSupers = useCallback(async (value: string) => {
    setNewSuper(value);
    if (value.trim().length < 2) {
      setSuggestions([]);
      return;
    }
    try {
      const found = (await api.search(value)).results.filter((result) => result.kind === "class");
      setSuggestions(found.slice(0, 8));
    } catch {
      setSuggestions([]);
    }
  }, []);

  const explain = useCallback(
    async (axiom: string) => {
      if (why?.axiom === axiom) {
        setWhy(null);
        return;
      }
      try {
        setWhy(await api.why(entity.iri, axiom));
      } catch (error) {
        onError((error as Error).message);
      }
    },
    [entity.iri, onError, why?.axiom],
  );

  useEffect(() => {
    api
      .audit()
      .then((result) =>
        setAudit(result.rows.filter((row) => row.entities.includes(entity.iri)).slice(0, 5)),
      )
      .catch(() => setAudit([]));
  }, [entity.iri]);

  const previewLabel = useCallback(async () => {
    if (!labelDraft.trim()) {
      return;
    }
    try {
      setPreview(
        await api.previewAction("set-label", { iri: entity.iri, label: labelDraft.trim() }),
      );
    } catch (error) {
      onError((error as Error).message);
    }
  }, [entity.iri, labelDraft, onError]);

  const commitLabel = useCallback(async () => {
    if (!preview) {
      return;
    }
    try {
      const result = await api.commitAction("set-label", {
        iri: entity.iri,
        label: labelDraft.trim(),
      });
      onEdited(result);
      setPreview(null);
      setLabelDraft("");
      setAudit(
        (await api.audit()).rows
          .filter((row) => row.entities.includes(entity.iri))
          .slice(0, 5),
      );
    } catch (error) {
      onError((error as Error).message);
    }
  }, [entity.iri, labelDraft, onEdited, onError, preview]);

  const addAxiom = useCallback(async () => {
    const text = axiomText.trim();
    if (!text) {
      return;
    }
    await run(() => api.addAxiomText(text));
    setAxiomText("");
  }, [axiomText, run]);

  return (
    <>
      <h2>
        {entity.label} <span className="kind">{entity.kind}</span>
        <select
          className={`marking ${entity.marking}`}
          value={entity.marking}
          onChange={async (event) => {
            try {
              await api.setMarking(entity.iri, event.target.value);
              onEdited({ dirty: true, canUndo: true, canRedo: false });
            } catch (error) {
              onError((error as Error).message);
            }
          }}
        >
          <option value="public">public</option>
          <option value="internal">internal</option>
          <option value="restricted">restricted</option>
        </select>
      </h2>
      <div className="iri">{entity.iri}</div>
      <div className="usage">{entity.usage} referencing axioms</div>

      {entity.kind === "class" && (
        <Section title="Subclasses">
          {entity.subClasses.length === 0 && <div className="muted">None</div>}
          {entity.subClasses.map((sub) => (
            <button key={sub.iri} className="link" onClick={() => onSelect(sub.iri)}>
              {sub.label}
            </button>
          ))}
        </Section>
      )}

      {entity.kind === "class" && (
        <Section title="Superclasses">
          {entity.superClasses.map((sup) => (
            <RefRow
              key={sup.iri}
              item={sup}
              onOpen={() => onSelect(sup.iri)}
              onRemove={() => run(() => api.removeSubclass(entity.iri, sup.iri))}
              disabled={busy}
            />
          ))}
          <div className="add-super">
            <input
              placeholder="Add superclass…"
              value={newSuper}
              onChange={(event) => suggestSupers(event.target.value)}
            />
            {suggestions.length > 0 && (
              <div className="suggestions">
                {suggestions.map((candidate) => (
                  <button key={candidate.iri} onClick={() => addSuper(candidate.iri)}>
                    {candidate.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </Section>
      )}

      {entity.kind === "object property" && (
        <RefsSection title="Domain" items={entity.domains} onSelect={onSelect} />
      )}
      {entity.kind === "object property" && (
        <RefsSection title="Range" items={entity.ranges} onSelect={onSelect} />
      )}
      {entity.kind === "data property" && (
        <RefsSection title="Domain" items={entity.domains} onSelect={onSelect} />
      )}
      {entity.kind === "data property" && (
        <RefsSection title="Range" items={entity.ranges} onSelect={onSelect} />
      )}
      {entity.kind === "individual" && (
        <RefsSection title="Types" items={entity.types} onSelect={onSelect} />
      )}

      <Section title="Annotations">
        {entity.annotations.length === 0 && <div className="muted">None</div>}
        {entity.annotations.map((line) => (
          <div key={line} className="line">
            {line}
          </div>
        ))}
      </Section>

      <Section title="Axioms">
        {entity.axioms.map((line) => (
          <Fragment key={line}>
          <div className="line axiom-row">
            <button className="axiom axiom-link" onClick={() => explain(line)}>
              {line}
            </button>
            <button
              className="remove"
              disabled={busy}
              onClick={() => run(() => api.removeRenderedAxiom(entity.iri, line))}
            >
              ×
            </button>
          </div>
            {why?.axiom === line && (
              <div className="why">
                <div className="why-head">
                  <span className={`badge ${why.status}`}>{why.status}</span>
                  <span className="muted">
                    {why.status === "asserted"
                      ? "recorded evidence"
                      : why.note ?? "entailed by the reasoner"}
                  </span>
                </div>
                {why.status === "asserted" && why.evidence.length > 0 && (
                  <ul className="why-list">
                    {why.evidence.map((row) => (
                      <li key={row.ts}>
                        <strong>{row.action}</strong> by {row.author} · {row.source} ·{" "}
                        {new Date(row.ts).toLocaleString()} · confidence {row.confidence}
                      </li>
                    ))}
                  </ul>
                )}
                {why.status === "asserted" && why.evidence.length === 0 && (
                  <p className="muted">Imported from the ontology file; no edit recorded.</p>
                )}
                {why.status === "inferred" && (
                  <ul className="why-list">
                    {(why.supporting ?? []).map((support) => (
                      <li key={support} className="axiom">
                        {support}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </Fragment>
        ))}
      </Section>

      <Section title="Actions">
        <div className="action-row">
          <input
            placeholder="New label…"
            value={labelDraft}
            onChange={(event) => setLabelDraft(event.target.value)}
          />
          <button onClick={previewLabel} disabled={busy || !labelDraft.trim()}>
            Preview
          </button>
        </div>
        {preview && (
          <div className="preview">
            <div className="preview-head">set-label · {preview.summary.length} changes</div>
            <ul className="why-list">
              {preview.changes.map((change) => (
                <li key={change} className="axiom">
                  {change}
                </li>
              ))}
            </ul>
            <div className="row-actions">
              <button onClick={commitLabel}>Commit</button>
              <button onClick={() => setPreview(null)}>Cancel</button>
            </div>
          </div>
        )}
        {audit.length > 0 && (
          <div className="audit">
            <h4>Recent actions</h4>
            <ul className="why-list">
              {audit.map((row) => (
                <li key={row.ts}>
                  <strong>{row.actionName ?? "action"}</strong> ·{" "}
                  {new Date(row.ts).toLocaleString()} · {row.author}
                  {row.reason ? ` · “${row.reason}”` : ""}
                </li>
              ))}
            </ul>
          </div>
        )}
      </Section>

      <Section title="Add axiom">
        <textarea
          className="axiom-input"
          rows={3}
          placeholder={"Manchester syntax, e.g.\nClass: Pizza\n  SubClassOf: Food"}
          value={axiomText}
          onChange={(event) => setAxiomText(event.target.value)}
        />
        <div className="row-actions">
          <button onClick={addAxiom} disabled={busy || axiomText.trim().length === 0}>
            Add axiom
          </button>
        </div>
      </Section>
    </>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="block">
      <h3>{title}</h3>
      {children}
    </div>
  );
}

function RefsSection({
  title,
  items,
  onSelect,
}: {
  title: string;
  items: Ref[];
  onSelect: (iri: string) => void;
}) {
  return (
    <Section title={title}>
      {items.length === 0 && <div className="muted">None</div>}
      {items.map((item) =>
        item.iri ? (
          <button key={item.iri} className="link" onClick={() => onSelect(item.iri)}>
            {item.label}
          </button>
        ) : (
          <div key={item.label} className="line">
            {item.label}
          </div>
        ),
      )}
    </Section>
  );
}

function RefRow({
  item,
  onOpen,
  onRemove,
  disabled,
}: {
  item: Ref;
  onOpen: () => void;
  onRemove: () => void;
  disabled: boolean;
}) {
  return (
    <div className="line super">
      <button className="link" onClick={onOpen}>
        {item.label}
      </button>
      <button className="remove" onClick={onRemove} disabled={disabled}>
        ×
      </button>
    </div>
  );
}
