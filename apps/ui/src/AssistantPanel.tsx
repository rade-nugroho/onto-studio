import { useState } from "react";
import { api } from "./api";

type Turn = { role: "user" | "assistant"; text: string };

export default function AssistantPanel({
  entityIri,
  entityLabel,
}: {
  entityIri: string | null;
  entityLabel: string | null;
}) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);

  const send = async () => {
    const question = value.trim();
    if (!question || busy) {
      return;
    }
    setTurns((current) => [...current, { role: "user", text: question }]);
    setValue("");
    setBusy(true);
    try {
      const response = await api.assistant(question, entityIri);
      setTurns((current) => [...current, { role: "assistant", text: response.answer }]);
    } catch (error) {
      setTurns((current) => [...current, { role: "assistant", text: (error as Error).message }]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="assistant-panel">
      <div className="assistant-head">
        <span>Assistant</span>
        <span className="muted">{entityLabel ? `about ${entityLabel}` : "whole ontology"}</span>
      </div>
      <div className="assistant-log">
        {turns.length === 0 && (
          <p className="muted">
            Ask about the open ontology. Answers come from Claude, grounded in the
            ontology summary and the selected entity.
          </p>
        )}
        {turns.map((turn, index) => (
          <p key={index} className={turn.role === "user" ? "turn user" : "turn"}>
            {turn.text}
          </p>
        ))}
        {busy && <p className="muted">Thinking…</p>}
      </div>
      <div className="assistant-composer">
        <input
          placeholder="Ask anything…"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              send();
            }
          }}
        />
        <button onClick={send} disabled={busy || value.trim().length === 0}>
          Send
        </button>
      </div>
    </div>
  );
}
