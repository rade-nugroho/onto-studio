import { useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { EmptyState } from "@astryxdesign/core/EmptyState";
import { Heading, Text } from "@astryxdesign/core/Text";
import { RadioList, RadioListItem } from "@astryxdesign/core/RadioList";
import { Stack } from "@astryxdesign/core/Layout";
import { TextInput } from "@astryxdesign/core/TextInput";
import { api, type EditState, type PropertyCharacteristic } from "../../api";
import { EditorCommitBar, SelectedEntity, optionalReason, usePreviewCommit } from "./EditorCommitBar";

const CHARACTERISTICS = [
  ["functional", "Functional"],
  ["inverse-functional", "Inverse functional"],
  ["transitive", "Transitive"],
  ["symmetric", "Symmetric"],
  ["asymmetric", "Asymmetric"],
  ["reflexive", "Reflexive"],
  ["irreflexive", "Irreflexive"],
  ["inverse", "Inverse"],
  ["chain", "Chain"],
] as const satisfies ReadonlyArray<readonly [PropertyCharacteristic, string]>;

function isPropertyCharacteristic(value: string): value is PropertyCharacteristic {
  return CHARACTERISTICS.some(([kind]) => kind === value);
}

function characteristicExtra(
  characteristic: PropertyCharacteristic,
  inverse: string,
  chain: string[],
): { inverse?: string; chain?: string[] } | null | undefined {
  switch (characteristic) {
    case "functional":
    case "inverse-functional":
    case "transitive":
    case "symmetric":
    case "asymmetric":
    case "reflexive":
    case "irreflexive":
      return undefined;
    case "inverse": {
      const value = inverse.trim();
      if (value.length === 0) {
        return null;
      }
      return { inverse: value };
    }
    case "chain": {
      const links = chain.map((item) => item.trim());
      if (links.length < 2 || links.some((item) => item.length === 0)) {
        return null;
      }
      return { chain: links };
    }
    default: {
      const exhaustive: never = characteristic;
      return exhaustive;
    }
  }
}

export function PropertyCharacteristicEditor({
  iri,
  onEdited,
}: {
  iri: string | null;
  onEdited?: (state: EditState) => void;
}) {
  const [characteristic, setCharacteristic] = useState<PropertyCharacteristic>("functional");
  const [inverse, setInverse] = useState("");
  const [chain, setChain] = useState<string[]>(["", ""]);
  const subject = iri == null || iri.trim().length === 0 ? null : iri.trim();
  const extra = characteristicExtra(characteristic, inverse, chain);
  const fingerprint =
    subject == null || extra === null ? "" : JSON.stringify({ iri: subject, characteristic, extra: extra ?? {} });
  const form = usePreviewCommit(fingerprint);

  if (subject == null) {
    return (
      <EmptyState
        headingLevel={2}
        isCompact
        title="Property characteristic editor"
        description="Select a property in the hierarchy, then choose a characteristic."
      />
    );
  }

  return (
    <Stack direction="vertical" gap={3}>
      <Heading level={2}>Property characteristic</Heading>
      <Text type="supporting" display="block">
        Choose a characteristic, preview the axiom, then commit it.
      </Text>
      <SelectedEntity label="Selected property" iri={subject} />
      <RadioList
        label="Characteristic"
        value={characteristic}
        onChange={(value) => {
          if (isPropertyCharacteristic(value)) {
            setCharacteristic(value);
          }
        }}
        size="sm"
      >
        {CHARACTERISTICS.map(([value, label]) => (
          <RadioListItem key={value} label={label} value={value} />
        ))}
      </RadioList>
      {characteristic === "inverse" ? (
        <TextInput
          label="Inverse property IRI"
          value={inverse}
          onChange={setInverse}
          isRequired
        />
      ) : null}
      {characteristic === "chain" ? (
        <Stack direction="vertical" gap={2}>
          {chain.map((value, index) => (
            <Stack key={`chain-${index}`} direction="vertical" gap={2}>
              <TextInput
                label={`Chain property ${index + 1}`}
                value={value}
                onChange={(next) =>
                  setChain((current) => current.map((item, itemIndex) => (itemIndex === index ? next : item)))
                }
                isRequired
              />
              {chain.length > 2 ? (
                <Button
                  label={`Remove chain property ${index + 1}`}
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setChain((current) => current.filter((_, itemIndex) => itemIndex !== index))}
                />
              ) : null}
            </Stack>
          ))}
          <Button
            label="Add chain property"
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => setChain((current) => [...current, ""])}
          />
        </Stack>
      ) : null}
      <EditorCommitBar
        reason={form.reason}
        onReasonChange={form.setReason}
        lines={form.lines}
        error={form.error}
        committed={form.committed}
        canPreview={form.canPreview}
        isPreviewing={form.isPreviewing}
        isCommitting={form.isCommitting}
        onPreview={() => {
          if (extra === null) {
            return;
          }
          void form.preview(() => api.previewPropertyCharacteristic(subject, characteristic, extra));
        }}
        onCommit={() => {
          if (extra === null) {
            return;
          }
          void form.commit(
            () =>
              api.commitPropertyCharacteristic(
                subject,
                characteristic,
                extra,
                optionalReason(form.reason),
              ),
            onEdited,
          );
        }}
      />
    </Stack>
  );
}
