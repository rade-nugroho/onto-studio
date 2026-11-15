// Copyright (c) Meta Platforms, Inc. and affiliates.

"use client";

import { useEffect, useState, type ReactNode } from "react";
import { Divider } from "@astryxdesign/core/Divider";
import { Icon } from "@astryxdesign/core/Icon";
import { Layout, LayoutContent, LayoutPanel, Stack, StackItem } from "@astryxdesign/core/Layout";
import { useMediaQuery } from "@astryxdesign/core/hooks";
import { ResizeHandle, useResizable } from "@astryxdesign/core/Resizable";
import { Tab, TabList } from "@astryxdesign/core/TabList";
import { Text } from "@astryxdesign/core/Text";
import { TextInput } from "@astryxdesign/core/TextInput";
import type { EditState } from "../../api";
import { ClassExpressionEditor } from "./ClassExpressionEditor";
import { ConnectorsPanel } from "./ConnectorsPanel";
import { IndividualAssertionEditor } from "./IndividualAssertionEditor";
import { PropertyCharacteristicEditor } from "./PropertyCharacteristicEditor";

export type OntologyIdePageProps = {
  searchValue?: string;
  onSearchChange?: (value: string) => void;
  selectedIri?: string | null;
  onEdited?: (state: EditState) => void;
  hierarchy?: ReactNode;
  editor?: ReactNode;
  inspector?: ReactNode;
  assistant?: ReactNode;
  connectorsOpen?: boolean;
  onConnectorsOpenChange?: (open: boolean) => void;
};

type EditorKind = "class" | "property" | "individual";

function isEditorKind(value: string): value is EditorKind {
  return value === "class" || value === "property" || value === "individual";
}

function EditorSurface({
  kind,
  iri,
  onEdited,
}: {
  kind: EditorKind;
  iri: string | null;
  onEdited?: (state: EditState) => void;
}) {
  switch (kind) {
    case "class":
      return <ClassExpressionEditor iri={iri} onEdited={onEdited} />;
    case "property":
      return <PropertyCharacteristicEditor iri={iri} onEdited={onEdited} />;
    case "individual":
      return <IndividualAssertionEditor iri={iri} onEdited={onEdited} />;
    default: {
      const exhaustive: never = kind;
      return exhaustive;
    }
  }
}

function AxiomDetails({ inspector }: { inspector?: ReactNode }) {
  return (
    inspector ?? (
      <Text type="supporting" display="block">
        Select a class to see its axioms.
      </Text>
    )
  );
}

export default function OntologyIdePage({
  searchValue,
  onSearchChange,
  selectedIri = null,
  onEdited,
  hierarchy,
  editor,
  inspector,
  assistant,
  connectorsOpen = false,
  onConnectorsOpenChange,
}: OntologyIdePageProps) {
  const [uncontrolledQuery, setUncontrolledQuery] = useState("");
  const [editorKind, setEditorKind] = useState<EditorKind>("class");
  const query = searchValue ?? uncontrolledQuery;
  const setQuery = onSearchChange ?? setUncontrolledQuery;
  const assistantShown = assistant != null;
  const shareInspector = assistantShown && inspector != null;

  const startPanel = useResizable({
    defaultSize: 256,
    minSize: 160,
    maxSize: 400,
    collapsible: true,
    collapsedSize: 50,
  });

  const endPanel = useResizable({
    defaultSize: 320,
    minSize: 180,
    maxSize: 500,
    collapsible: true,
    collapsedSize: 50,
  });

  useEffect(() => {
    if (assistantShown && endPanel.isCollapsed) {
      endPanel.expand();
    }
  }, [assistantShown, endPanel.isCollapsed, endPanel.expand]);

  const isMobile = useMediaQuery("(max-width: 768px)");

  const hierarchyColumn = isMobile ? undefined : (
    <>
      {!startPanel.isCollapsed && (
        <LayoutPanel
          width={startPanel.size}
          hasDivider={false}
          padding={0}
          label="Class hierarchy"
        >
          <Stack direction="vertical" gap={2} padding={4} height="100%">
            <TextInput
              id="hierarchy-filter"
              htmlName="hierarchy-filter"
              type="search"
              autoComplete="off"
              hasSpellCheck={false}
              label="Filter hierarchy"
              isLabelHidden
              value={query}
              onChange={setQuery}
              placeholder="Search…"
              size="md"
              startIcon={<Icon icon="search" />}
            />
            <StackItem size="fill">
              {hierarchy ?? (
                <Text type="supporting" display="block">
                  Open an ontology to browse classes.
                </Text>
              )}
            </StackItem>
          </Stack>
        </LayoutPanel>
      )}
      <ResizeHandle
        direction="horizontal"
        hasDivider
        isAlwaysVisible={false}
        resizable={startPanel.props}
        label="Resize class hierarchy"
      />
    </>
  );

  const editorColumn = (
    <LayoutContent
      padding={0}
      label={connectorsOpen ? "Connectors" : "Expression editor"}
    >
      {connectorsOpen ? (
        <ConnectorsPanel onClose={() => onConnectorsOpenChange?.(false)} />
      ) : (
        editor ?? (
          <Stack direction="vertical" gap={3} padding={4} height="100%">
            <TabList
              aria-label="Ontology editors"
              role="tablist"
              value={editorKind}
              onChange={(value) => {
                if (isEditorKind(value)) {
                  setEditorKind(value);
                }
              }}
              size="md"
              layout="hug"
              hasDivider
              overflow="visible"
            >
              <Tab label="Class expression" value="class" panelId="ontology-editor-panel" />
              <Tab label="Characteristics" value="property" panelId="ontology-editor-panel" />
              <Tab label="Assertions" value="individual" panelId="ontology-editor-panel" />
            </TabList>
            <StackItem id="ontology-editor-panel" size="fill">
              <EditorSurface kind={editorKind} iri={selectedIri} onEdited={onEdited} />
            </StackItem>
          </Stack>
        )
      )}
    </LayoutContent>
  );

  const showRightColumn = !isMobile || assistantShown;

  const rightColumn = showRightColumn ? (
    <>
      <ResizeHandle
        direction="horizontal"
        hasDivider
        isReversed
        isAlwaysVisible={false}
        resizable={endPanel.props}
        label={assistantShown ? "Resize assistant" : "Resize inspector"}
      />
      {!endPanel.isCollapsed && (
        <LayoutPanel
          width={endPanel.size}
          hasDivider={false}
          padding={4}
          isScrollable={!assistantShown}
          role="complementary"
          label={assistantShown ? "Assistant" : "Inspector"}
        >
          {assistantShown ? (
            <Stack direction="vertical" gap={3} height="100%">
              <StackItem
                size={shareInspector ? "fill" : undefined}
                isScrollable={shareInspector}
                role="region"
                aria-label="Inspector"
              >
                <AxiomDetails inspector={inspector} />
              </StackItem>
              <Divider />
              <StackItem size="fill">{assistant}</StackItem>
            </Stack>
          ) : (
            <Stack direction="vertical" gap={3} height="100%">
              <AxiomDetails inspector={inspector} />
            </Stack>
          )}
        </LayoutPanel>
      )}
    </>
  ) : undefined;

  return (
    <Layout
      height="fill"
      start={hierarchyColumn}
      content={editorColumn}
      end={rightColumn}
    />
  );
}
