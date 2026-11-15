import { useRef, useState } from "react";
import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Heading, Text } from "@astryxdesign/core/Text";
import { List, ListItem } from "@astryxdesign/core/List";
import { Stack } from "@astryxdesign/core/Layout";
import { TextInput } from "@astryxdesign/core/TextInput";
import type { ActionCommit, ActionPreview, EditState } from "../../api";

export type EditorFormError = {
  title: string;
  message: string;
};

type Phase = "idle" | "preview" | "commit";

export function optionalReason(reason: string): string | undefined {
  const trimmed = reason.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function messageOf(caught: unknown, fallback: string): string {
  return caught instanceof Error && caught.message.length > 0 ? caught.message : fallback;
}

function previewLines(result: ActionPreview): string[] {
  if (result.summary.length > 0) {
    return result.summary;
  }
  return result.changes;
}

export function usePreviewCommit(fingerprint: string) {
  const [reason, setReason] = useState("");
  const [lines, setLines] = useState<string[] | null>(null);
  const [previewKey, setPreviewKey] = useState<string | null>(null);
  const [error, setError] = useState<EditorFormError | null>(null);
  const [errorKey, setErrorKey] = useState<string | null>(null);
  const [committed, setCommitted] = useState<string | null>(null);
  const [committedKey, setCommittedKey] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>("idle");
  const phaseRef = useRef<Phase>("idle");

  function begin(next: Phase): boolean {
    if (phaseRef.current !== "idle") {
      return false;
    }
    phaseRef.current = next;
    setPhase(next);
    return true;
  }

  function finish() {
    phaseRef.current = "idle";
    setPhase("idle");
  }

  async function preview(action: () => Promise<ActionPreview>) {
    if (fingerprint.length === 0 || !begin("preview")) {
      return;
    }
    const key = fingerprint;
    setError(null);
    setErrorKey(null);
    setCommitted(null);
    setCommittedKey(null);
    setLines(null);
    setPreviewKey(null);
    try {
      const result = await action();
      setLines(previewLines(result));
      setPreviewKey(key);
    } catch (caught) {
      setError({ title: "Preview failed", message: messageOf(caught, "Preview failed") });
      setErrorKey(key);
    } finally {
      finish();
    }
  }

  async function commit(action: () => Promise<ActionCommit>, onEdited?: (state: EditState) => void) {
    if (lines == null || previewKey !== fingerprint || !begin("commit")) {
      return;
    }
    setError(null);
    setErrorKey(null);
    try {
      const result = await action();
      onEdited?.(result);
      const text = result.summary.filter((line) => line.length > 0).join(" ");
      setCommitted(text.length > 0 ? text : "The axiom was added.");
      setCommittedKey(fingerprint);
      setLines(null);
      setPreviewKey(null);
    } catch (caught) {
      setError({ title: "Commit failed", message: messageOf(caught, "Commit failed") });
      setErrorKey(fingerprint);
    } finally {
      finish();
    }
  }

  return {
    reason,
    setReason,
    lines: lines != null && previewKey === fingerprint ? lines : null,
    error: error != null && errorKey === fingerprint ? error : null,
    committed: committed != null && committedKey === fingerprint ? committed : null,
    canPreview: fingerprint.length > 0 && phase === "idle",
    isPreviewing: phase === "preview",
    isCommitting: phase === "commit",
    preview,
    commit,
  };
}

export function SelectedEntity({ label, iri }: { label: string; iri: string }) {
  return (
    <Stack direction="vertical" gap={1}>
      <Text type="supporting" display="block">
        {label}
      </Text>
      <Text display="block" maxLines={2} wordBreak="break-all">
        {iri}
      </Text>
    </Stack>
  );
}

export function EditorCommitBar({
  reason,
  onReasonChange,
  lines,
  error,
  committed,
  canPreview,
  isPreviewing,
  isCommitting,
  onPreview,
  onCommit,
}: {
  reason: string;
  onReasonChange: (value: string) => void;
  lines: string[] | null;
  error: EditorFormError | null;
  committed: string | null;
  canPreview: boolean;
  isPreviewing: boolean;
  isCommitting: boolean;
  onPreview: () => void;
  onCommit: () => void;
}) {
  const isBusy = isPreviewing || isCommitting;
  return (
    <Stack direction="vertical" gap={3}>
      <TextInput
        label="Reason"
        description="Optional note stored with the edit."
        value={reason}
        onChange={onReasonChange}
        isOptional
      />
      <Button
        label="Preview"
        type="button"
        variant="secondary"
        onClick={onPreview}
        isDisabled={!canPreview || isBusy}
        isLoading={isPreviewing}
      />
      {error ? <Banner status="error" title={error.title} description={error.message} /> : null}
      {lines ? (
        <Stack direction="vertical" gap={2}>
          {lines.length === 0 ? (
            <Banner status="info" title="Preview" description="The engine returned no summary lines." />
          ) : (
            <List header={<Heading level={3}>Preview</Heading>} density="compact">
              {lines.map((line, index) => (
                <ListItem key={`${index}:${line}`} label={line} />
              ))}
            </List>
          )}
          <Button
            label="Commit"
            type="button"
            variant="primary"
            onClick={onCommit}
            isDisabled={isBusy}
            isLoading={isCommitting}
          />
        </Stack>
      ) : null}
      {committed ? <Banner status="success" title="Committed" description={committed} /> : null}
    </Stack>
  );
}
