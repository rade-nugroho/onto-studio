import { useCallback, useState } from "react";
import { api, type EditState, type EntityInfo, type Ref, type SearchResult } from "./api";

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
      </h2>
      <div className="iri">{entity.iri}</div>

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
          <div key={line} className="line axiom-row">
            <span className="axiom">{line}</span>
            <button
              className="remove"
              disabled={busy}
              onClick={() => run(() => api.removeRenderedAxiom(entity.iri, line))}
            >
              ×
            </button>
          </div>
        ))}
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
