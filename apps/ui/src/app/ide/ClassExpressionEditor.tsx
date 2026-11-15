import { useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { EmptyState } from "@astryxdesign/core/EmptyState";
import { Heading, Text } from "@astryxdesign/core/Text";
import { NumberInput } from "@astryxdesign/core/NumberInput";
import { RadioList, RadioListItem } from "@astryxdesign/core/RadioList";
import {
  SegmentedControl,
  SegmentedControlItem,
} from "@astryxdesign/core/SegmentedControl";
import { Stack } from "@astryxdesign/core/Layout";
import { Switch } from "@astryxdesign/core/Switch";
import { TextInput } from "@astryxdesign/core/TextInput";
import {
  api,
  type ClassExpression,
  type DatatypeFiller,
  type EditState,
} from "../../api";
import { EditorCommitBar, SelectedEntity, optionalReason, usePreviewCommit } from "./EditorCommitBar";

const EXPRESSION_KINDS = [
  ["class", "Named class"],
  ["some", "Some"],
  ["only", "Only"],
  ["min", "Min"],
  ["max", "Max"],
  ["exactly", "Exactly"],
  ["intersection", "Intersection"],
  ["union", "Union"],
  ["complement", "Complement"],
] as const;

const FILLER_MODES = [
  ["class", "Class"],
  ["datatype", "Datatype"],
  ["expression", "Expression"],
] as const;

const RELATIONS = [
  ["subclass", "Subclass of"],
  ["equivalent", "Equivalent to"],
] as const;

type ExpressionKind = (typeof EXPRESSION_KINDS)[number][0];
type FillerMode = (typeof FILLER_MODES)[number][0];
type ClassAxiomRelation = (typeof RELATIONS)[number][0];

type ExpressionDraft = {
  kind: ExpressionKind;
  classIri: string;
  property: string;
  cardinality: number | null;
  fillerMode: FillerMode;
  fillerIri: string;
  qualify: boolean;
  filler: ExpressionDraft | null;
  operands: ExpressionDraft[];
  operand: ExpressionDraft | null;
};

function isExpressionKind(value: string): value is ExpressionKind {
  return EXPRESSION_KINDS.some(([kind]) => kind === value);
}

function isFillerMode(value: string): value is FillerMode {
  return FILLER_MODES.some(([mode]) => mode === value);
}

function isClassAxiomRelation(value: string): value is ClassAxiomRelation {
  return RELATIONS.some(([relation]) => relation === value);
}

function blankExpression(): ExpressionDraft {
  return {
    kind: "class",
    classIri: "",
    property: "",
    cardinality: 1,
    fillerMode: "class",
    fillerIri: "",
    qualify: true,
    filler: null,
    operands: [],
    operand: null,
  };
}

function scoped(scope: string, label: string): string {
  if (scope.length === 0) {
    return label;
  }
  return `${scope} ${label.charAt(0).toLowerCase()}${label.slice(1)}`;
}

function withKind(draft: ExpressionDraft, kind: ExpressionKind): ExpressionDraft {
  switch (kind) {
    case "intersection":
    case "union":
      return {
        ...draft,
        kind,
        operands: draft.operands.length >= 2 ? draft.operands : [blankExpression(), blankExpression()],
      };
    case "complement":
      return { ...draft, kind, operand: draft.operand ?? blankExpression() };
    case "class":
    case "some":
    case "only":
    case "min":
    case "max":
    case "exactly":
      return { ...draft, kind };
    default: {
      const exhaustive: never = kind;
      return exhaustive;
    }
  }
}

function withFillerMode(draft: ExpressionDraft, fillerMode: FillerMode): ExpressionDraft {
  switch (fillerMode) {
    case "expression":
      return { ...draft, fillerMode, filler: draft.filler ?? blankExpression() };
    case "class":
    case "datatype":
      return { ...draft, fillerMode };
    default: {
      const exhaustive: never = fillerMode;
      return exhaustive;
    }
  }
}

function replaceOperand(draft: ExpressionDraft, index: number, operand: ExpressionDraft): ExpressionDraft {
  const operands = draft.operands.slice();
  operands[index] = operand;
  return { ...draft, operands };
}

function removeOperand(draft: ExpressionDraft, index: number): ExpressionDraft {
  return { ...draft, operands: draft.operands.filter((_, item) => item !== index) };
}

function addOperand(draft: ExpressionDraft): ExpressionDraft {
  return { ...draft, operands: [...draft.operands, blankExpression()] };
}

function fillerOf(draft: ExpressionDraft): ClassExpression | DatatypeFiller | null {
  switch (draft.fillerMode) {
    case "class":
    case "datatype": {
      const iri = draft.fillerIri.trim();
      if (iri.length === 0) {
        return null;
      }
      return draft.fillerMode === "datatype" ? { kind: "datatype", iri } : { kind: "class", iri };
    }
    case "expression":
      return draft.filler ? toExpression(draft.filler) : null;
    default: {
      const exhaustive: never = draft.fillerMode;
      return exhaustive;
    }
  }
}

function toExpression(draft: ExpressionDraft): ClassExpression | null {
  switch (draft.kind) {
    case "class": {
      const iri = draft.classIri.trim();
      if (iri.length === 0) {
        return null;
      }
      return { kind: "class", iri };
    }
    case "some":
    case "only": {
      const property = draft.property.trim();
      const filler = fillerOf(draft);
      if (property.length === 0 || filler == null) {
        return null;
      }
      return { kind: draft.kind, property, filler };
    }
    case "min":
    case "max":
    case "exactly": {
      const property = draft.property.trim();
      const cardinality = draft.cardinality;
      if (
        property.length === 0 ||
        cardinality == null ||
        !Number.isInteger(cardinality) ||
        cardinality < 0
      ) {
        return null;
      }
      if (!draft.qualify) {
        return { kind: draft.kind, property, cardinality };
      }
      const filler = fillerOf(draft);
      if (filler == null) {
        return null;
      }
      return { kind: draft.kind, property, cardinality, filler };
    }
    case "intersection":
    case "union": {
      if (draft.operands.length < 2) {
        return null;
      }
      const operands: ClassExpression[] = [];
      for (const operand of draft.operands) {
        const built = toExpression(operand);
        if (built == null) {
          return null;
        }
        operands.push(built);
      }
      return { kind: draft.kind, operands };
    }
    case "complement": {
      if (draft.operand == null) {
        return null;
      }
      const operand = toExpression(draft.operand);
      if (operand == null) {
        return null;
      }
      return { kind: "complement", operand };
    }
    default: {
      const exhaustive: never = draft.kind;
      return exhaustive;
    }
  }
}

function previewClass(iri: string, relation: ClassAxiomRelation, expression: ClassExpression) {
  switch (relation) {
    case "subclass":
      return api.previewSubclassExpression(iri, expression);
    case "equivalent":
      return api.previewEquivalentClass(iri, expression);
    default: {
      const exhaustive: never = relation;
      return exhaustive;
    }
  }
}

function commitClass(
  iri: string,
  relation: ClassAxiomRelation,
  expression: ClassExpression,
  reason: string | undefined,
) {
  switch (relation) {
    case "subclass":
      return api.commitSubclassExpression(iri, expression, reason);
    case "equivalent":
      return api.commitEquivalentClass(iri, expression, reason);
    default: {
      const exhaustive: never = relation;
      return exhaustive;
    }
  }
}

function FillerControls({
  draft,
  onChange,
  scope,
}: {
  draft: ExpressionDraft;
  onChange: (next: ExpressionDraft) => void;
  scope: string;
}) {
  return (
    <Stack direction="vertical" gap={3}>
      <SegmentedControl
        label={scoped(scope, "Filler")}
        value={draft.fillerMode}
        onChange={(value) => {
          if (isFillerMode(value)) {
            onChange(withFillerMode(draft, value));
          }
        }}
        size="sm"
        layout="fill"
      >
        {FILLER_MODES.map(([value, label]) => (
          <SegmentedControlItem key={value} label={label} value={value} />
        ))}
      </SegmentedControl>
      <FillerBody draft={draft} onChange={onChange} scope={scope} />
    </Stack>
  );
}

function FillerBody({
  draft,
  onChange,
  scope,
}: {
  draft: ExpressionDraft;
  onChange: (next: ExpressionDraft) => void;
  scope: string;
}) {
  switch (draft.fillerMode) {
    case "class":
      return (
        <TextInput
          label={scoped(scope, "Filler class IRI")}
          value={draft.fillerIri}
          onChange={(value) => onChange({ ...draft, fillerIri: value })}
          isRequired
        />
      );
    case "datatype":
      return (
        <TextInput
          label={scoped(scope, "Datatype IRI")}
          description="Datatype IRI or XML Schema name, such as string."
          value={draft.fillerIri}
          onChange={(value) => onChange({ ...draft, fillerIri: value })}
          isRequired
        />
      );
    case "expression":
      return draft.filler ? (
        <ExpressionFields
          draft={draft.filler}
          scope={scoped(scope, "Filler")}
          onChange={(filler) => onChange({ ...draft, filler })}
        />
      ) : null;
    default: {
      const exhaustive: never = draft.fillerMode;
      return exhaustive;
    }
  }
}

function KindFields({
  draft,
  onChange,
  scope,
}: {
  draft: ExpressionDraft;
  onChange: (next: ExpressionDraft) => void;
  scope: string;
}) {
  switch (draft.kind) {
    case "class":
      return (
        <TextInput
          label={scoped(scope, "Class IRI")}
          value={draft.classIri}
          onChange={(value) => onChange({ ...draft, classIri: value })}
          isRequired
        />
      );
    case "some":
    case "only":
      return (
        <Stack direction="vertical" gap={3}>
          <TextInput
            label={scoped(scope, "Property IRI")}
            value={draft.property}
            onChange={(value) => onChange({ ...draft, property: value })}
            isRequired
          />
          <FillerControls draft={draft} onChange={onChange} scope={scope} />
        </Stack>
      );
    case "min":
    case "max":
    case "exactly":
      return (
        <Stack direction="vertical" gap={3}>
          <TextInput
            label={scoped(scope, "Property IRI")}
            value={draft.property}
            onChange={(value) => onChange({ ...draft, property: value })}
            isRequired
          />
          <NumberInput
            label={scoped(scope, "Cardinality")}
            value={draft.cardinality}
            onChange={(value) => onChange({ ...draft, cardinality: value })}
            min={0}
            isIntegerOnly
            isRequired
            hasClear
          />
          <Switch
            label={scoped(scope, "Qualify with a filler")}
            value={draft.qualify}
            onChange={(checked) => {
              if (!checked) {
                onChange({ ...draft, qualify: false });
                return;
              }
              onChange({ ...withFillerMode(draft, draft.fillerMode), qualify: true });
            }}
          />
          {draft.qualify ? <FillerControls draft={draft} onChange={onChange} scope={scope} /> : null}
        </Stack>
      );
    case "intersection":
    case "union":
      return (
        <Stack direction="vertical" gap={3}>
          {draft.operands.map((operand, index) => (
            <Stack key={`${draft.kind}-${index}`} direction="vertical" gap={2}>
              <ExpressionFields
                draft={operand}
                scope={scoped(scope, `Operand ${index + 1}`)}
                onChange={(next) => onChange(replaceOperand(draft, index, next))}
              />
              {draft.operands.length > 2 ? (
                <Button
                  label={scoped(scope, `Remove operand ${index + 1}`)}
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => onChange(removeOperand(draft, index))}
                />
              ) : null}
            </Stack>
          ))}
          <Button
            label={scoped(scope, "Add operand")}
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => onChange(addOperand(draft))}
          />
        </Stack>
      );
    case "complement":
      return draft.operand ? (
        <ExpressionFields
          draft={draft.operand}
          scope={scoped(scope, "Operand")}
          onChange={(operand) => onChange({ ...draft, operand })}
        />
      ) : null;
    default: {
      const exhaustive: never = draft.kind;
      return exhaustive;
    }
  }
}

function ExpressionFields({
  draft,
  onChange,
  scope,
}: {
  draft: ExpressionDraft;
  onChange: (next: ExpressionDraft) => void;
  scope: string;
}) {
  return (
    <Stack direction="vertical" gap={3}>
      <RadioList
        label={scoped(scope, "Expression kind")}
        value={draft.kind}
        onChange={(value) => {
          if (isExpressionKind(value)) {
            onChange(withKind(draft, value));
          }
        }}
        size="sm"
      >
        {EXPRESSION_KINDS.map(([value, label]) => (
          <RadioListItem key={value} label={label} value={value} />
        ))}
      </RadioList>
      <KindFields draft={draft} onChange={onChange} scope={scope} />
    </Stack>
  );
}

export function ClassExpressionEditor({
  iri,
  onEdited,
}: {
  iri: string | null;
  onEdited?: (state: EditState) => void;
}) {
  const [relation, setRelation] = useState<ClassAxiomRelation>("subclass");
  const [draft, setDraft] = useState(blankExpression);
  const expression = toExpression(draft);
  const subject = iri == null || iri.trim().length === 0 ? null : iri.trim();
  const fingerprint =
    subject && expression ? JSON.stringify({ iri: subject, relation, expression }) : "";
  const form = usePreviewCommit(fingerprint);

  if (subject == null) {
    return (
      <EmptyState
        headingLevel={2}
        isCompact
        title="Class expression editor"
        description="Select a class in the hierarchy, then build a subclass or equivalent expression."
      />
    );
  }

  return (
    <Stack direction="vertical" gap={3}>
      <Heading level={2}>Class expression</Heading>
      <Text type="supporting" display="block">
        Build a subclass or equivalent expression, preview the axiom, then commit it.
      </Text>
      <SelectedEntity label="Selected class" iri={subject} />
      <SegmentedControl
        label="Axiom"
        value={relation}
        onChange={(value) => {
          if (isClassAxiomRelation(value)) {
            setRelation(value);
          }
        }}
        size="sm"
        layout="fill"
      >
        {RELATIONS.map(([value, label]) => (
          <SegmentedControlItem key={value} label={label} value={value} />
        ))}
      </SegmentedControl>
      <ExpressionFields draft={draft} onChange={setDraft} scope="" />
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
          if (expression == null) {
            return;
          }
          void form.preview(() => previewClass(subject, relation, expression));
        }}
        onCommit={() => {
          if (expression == null) {
            return;
          }
          void form.commit(
            () => commitClass(subject, relation, expression, optionalReason(form.reason)),
            onEdited,
          );
        }}
      />
    </Stack>
  );
}
