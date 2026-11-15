import { useState } from "react";
import { EmptyState } from "@astryxdesign/core/EmptyState";
import { Heading, Text } from "@astryxdesign/core/Text";
import { RadioList, RadioListItem } from "@astryxdesign/core/RadioList";
import { Stack } from "@astryxdesign/core/Layout";
import { TextInput } from "@astryxdesign/core/TextInput";
import { api, type EditState } from "../../api";
import { EditorCommitBar, SelectedEntity, optionalReason, usePreviewCommit } from "./EditorCommitBar";

const ASSERTIONS = [
  ["type", "Class type"],
  ["object", "Object property"],
  ["data", "Data property"],
  ["same", "Same as"],
  ["different", "Different from"],
] as const;

type AssertionKind = (typeof ASSERTIONS)[number][0];

type AssertionFields = {
  classIri: string;
  property: string;
  target: string;
  value: string;
  datatype: string;
  other: string;
};

type BuiltAssertion =
  | { kind: "type"; classIri: string }
  | { kind: "object"; property: string; target: string }
  | { kind: "data"; property: string; value: string; datatype?: string }
  | { kind: "same"; other: string }
  | { kind: "different"; other: string };

function isAssertionKind(value: string): value is AssertionKind {
  return ASSERTIONS.some(([kind]) => kind === value);
}

function blankFields(): AssertionFields {
  return {
    classIri: "",
    property: "",
    target: "",
    value: "",
    datatype: "",
    other: "",
  };
}

function buildAssertion(kind: AssertionKind, fields: AssertionFields): BuiltAssertion | null {
  switch (kind) {
    case "type": {
      const classIri = fields.classIri.trim();
      if (classIri.length === 0) {
        return null;
      }
      return { kind: "type", classIri };
    }
    case "object": {
      const property = fields.property.trim();
      const target = fields.target.trim();
      if (property.length === 0 || target.length === 0) {
        return null;
      }
      return { kind: "object", property, target };
    }
    case "data": {
      const property = fields.property.trim();
      const value = fields.value.trim();
      if (property.length === 0 || value.length === 0) {
        return null;
      }
      const datatype = fields.datatype.trim();
      return datatype.length > 0
        ? { kind: "data", property, value, datatype }
        : { kind: "data", property, value };
    }
    case "same": {
      const other = fields.other.trim();
      if (other.length === 0) {
        return null;
      }
      return { kind: "same", other };
    }
    case "different": {
      const other = fields.other.trim();
      if (other.length === 0) {
        return null;
      }
      return { kind: "different", other };
    }
    default: {
      const exhaustive: never = kind;
      return exhaustive;
    }
  }
}

function previewAssertion(iri: string, built: BuiltAssertion) {
  switch (built.kind) {
    case "type":
      return api.previewClassAssertion(iri, built.classIri);
    case "object":
      return api.previewObjectAssertion(iri, built.property, built.target);
    case "data":
      return api.previewDataAssertion(iri, built.property, built.value, built.datatype);
    case "same":
      return api.previewSameAs(iri, built.other);
    case "different":
      return api.previewDifferentFrom(iri, built.other);
    default: {
      const exhaustive: never = built;
      return exhaustive;
    }
  }
}

function commitAssertion(iri: string, built: BuiltAssertion, reason: string | undefined) {
  switch (built.kind) {
    case "type":
      return api.commitClassAssertion(iri, built.classIri, reason);
    case "object":
      return api.commitObjectAssertion(iri, built.property, built.target, reason);
    case "data":
      return api.commitDataAssertion(iri, built.property, built.value, built.datatype, reason);
    case "same":
      return api.commitSameAs(iri, built.other, reason);
    case "different":
      return api.commitDifferentFrom(iri, built.other, reason);
    default: {
      const exhaustive: never = built;
      return exhaustive;
    }
  }
}

function AssertionFieldsForm({
  kind,
  fields,
  onChange,
}: {
  kind: AssertionKind;
  fields: AssertionFields;
  onChange: (next: AssertionFields) => void;
}) {
  switch (kind) {
    case "type":
      return (
        <TextInput
          label="Class IRI"
          value={fields.classIri}
          onChange={(value) => onChange({ ...fields, classIri: value })}
          isRequired
        />
      );
    case "object":
      return (
        <Stack direction="vertical" gap={3}>
          <TextInput
            label="Property IRI"
            value={fields.property}
            onChange={(value) => onChange({ ...fields, property: value })}
            isRequired
          />
          <TextInput
            label="Target individual IRI"
            value={fields.target}
            onChange={(value) => onChange({ ...fields, target: value })}
            isRequired
          />
        </Stack>
      );
    case "data":
      return (
        <Stack direction="vertical" gap={3}>
          <TextInput
            label="Property IRI"
            value={fields.property}
            onChange={(value) => onChange({ ...fields, property: value })}
            isRequired
          />
          <TextInput
            label="Value"
            value={fields.value}
            onChange={(value) => onChange({ ...fields, value: value })}
            isRequired
          />
          <TextInput
            label="Datatype"
            description="Optional XML Schema name or IRI. Leave blank for a plain literal."
            value={fields.datatype}
            onChange={(value) => onChange({ ...fields, datatype: value })}
            isOptional
          />
        </Stack>
      );
    case "same":
    case "different":
      return (
        <TextInput
          label="Other individual IRI"
          value={fields.other}
          onChange={(value) => onChange({ ...fields, other: value })}
          isRequired
        />
      );
    default: {
      const exhaustive: never = kind;
      return exhaustive;
    }
  }
}

export function IndividualAssertionEditor({
  iri,
  onEdited,
}: {
  iri: string | null;
  onEdited?: (state: EditState) => void;
}) {
  const [kind, setKind] = useState<AssertionKind>("type");
  const [fields, setFields] = useState(blankFields);
  const built = buildAssertion(kind, fields);
  const subject = iri == null || iri.trim().length === 0 ? null : iri.trim();
  const fingerprint = subject && built ? JSON.stringify({ iri: subject, built }) : "";
  const form = usePreviewCommit(fingerprint);

  if (subject == null) {
    return (
      <EmptyState
        headingLevel={2}
        isCompact
        title="Individual assertion editor"
        description="Select an individual in the hierarchy, then add a type, property assertion, or identity axiom."
      />
    );
  }

  return (
    <Stack direction="vertical" gap={3}>
      <Heading level={2}>Individual assertion</Heading>
      <Text type="supporting" display="block">
        Choose an assertion, preview the axiom, then commit it.
      </Text>
      <SelectedEntity label="Selected individual" iri={subject} />
      <RadioList
        label="Assertion"
        value={kind}
        onChange={(value) => {
          if (isAssertionKind(value)) {
            setKind(value);
          }
        }}
        size="sm"
      >
        {ASSERTIONS.map(([value, label]) => (
          <RadioListItem key={value} label={label} value={value} />
        ))}
      </RadioList>
      <AssertionFieldsForm kind={kind} fields={fields} onChange={setFields} />
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
          if (built == null) {
            return;
          }
          void form.preview(() => previewAssertion(subject, built));
        }}
        onCommit={() => {
          if (built == null) {
            return;
          }
          void form.commit(
            () => commitAssertion(subject, built, optionalReason(form.reason)),
            onEdited,
          );
        }}
      />
    </Stack>
  );
}
