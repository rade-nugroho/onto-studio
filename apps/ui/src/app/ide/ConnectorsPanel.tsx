import { useCallback, useEffect, useMemo, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { Banner } from "@astryxdesign/core/Banner";
import { EmptyState } from "@astryxdesign/core/EmptyState";
import { Heading, Text } from "@astryxdesign/core/Text";
import { List, ListItem } from "@astryxdesign/core/List";
import { Stack, StackItem } from "@astryxdesign/core/Layout";
import {
  SegmentedControl,
  SegmentedControlItem,
} from "@astryxdesign/core/SegmentedControl";
import { TextInput } from "@astryxdesign/core/TextInput";
import { api, type ConnectorAction, type ConnectorApp, type ConnectorConnection } from "../../api";

type BrowseMode = "yours" | "discover";

function messageOf(caught: unknown, fallback: string): string {
  return caught instanceof Error && caught.message.length > 0 ? caught.message : fallback;
}

export function ConnectorsPanel({ onClose }: { onClose?: () => void }) {
  const [mode, setMode] = useState<BrowseMode>("yours");
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(false);
  const [gatewayError, setGatewayError] = useState<string | null>(null);
  const [connections, setConnections] = useState<ConnectorConnection[]>([]);
  const [apps, setApps] = useState<ConnectorApp[]>([]);
  const [selectedConnection, setSelectedConnection] = useState<ConnectorConnection | null>(null);
  const [actions, setActions] = useState<ConnectorAction[]>([]);
  const [selectedAction, setSelectedAction] = useState<ConnectorAction | null>(null);
  const [classIri, setClassIri] = useState("");
  const [idField, setIdField] = useState("");
  const [fieldMapText, setFieldMapText] = useState("");
  const [previewLines, setPreviewLines] = useState<string[] | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [commitLoading, setCommitLoading] = useState(false);
  const [committed, setCommitted] = useState(false);

  const loadBrowseData = useCallback(async () => {
    setLoading(true);
    setGatewayError(null);
    try {
      if (mode === "yours") {
        const result = await api.connectorConnections();
        setConnections(result.connections);
        if (!result.gatewayAvailable) {
          setGatewayError("Open Connector gateway is unavailable.");
        }
      } else {
        const result = await api.connectorApps(search.trim() || undefined);
        setApps(result.apps);
        if (!result.gatewayAvailable) {
          setGatewayError("Open Connector gateway is unavailable.");
        }
      }
    } catch (caught) {
      setGatewayError(messageOf(caught, "Could not reach the connector gateway."));
    } finally {
      setLoading(false);
    }
  }, [mode, search]);

  useEffect(() => {
    void loadBrowseData();
  }, [loadBrowseData]);

  useEffect(() => {
    if (!selectedConnection) {
      setActions([]);
      setSelectedAction(null);
      return;
    }
    const controller = new AbortController();
    void (async () => {
      try {
        const result = await api.connectorActions(selectedConnection.service, controller.signal);
        setActions(result.actions);
        if (!result.gatewayAvailable) {
          setGatewayError("Open Connector gateway is unavailable.");
        }
      } catch (caught) {
        if (!controller.signal.aborted) {
          setGatewayError(messageOf(caught, "Could not load read actions."));
        }
      }
    })();
    return () => controller.abort();
  }, [selectedConnection]);

  const fieldMap = useMemo(() => {
    const map: Record<string, string> = {};
    for (const line of fieldMapText.split("\n")) {
      const trimmed = line.trim();
      if (!trimmed || !trimmed.includes("=")) {
        continue;
      }
      const [left, right] = trimmed.split("=", 2);
      const field = left.trim();
      const property = right.trim();
      if (field.length > 0 && property.length > 0) {
        map[field] = property;
      }
    }
    return map;
  }, [fieldMapText]);

  async function previewMapping() {
    if (!selectedConnection || !selectedAction) {
      return;
    }
    setPreviewLoading(true);
    setCommitted(false);
    setPreviewLines(null);
    try {
      const result = await api.previewConnectorMapping({
        connectionName: selectedConnection.name,
        service: selectedConnection.service,
        actionId: selectedAction.id,
        classIri: classIri.trim() || undefined,
        idField: idField.trim(),
        fieldMap,
        sampleLimit: 5,
      });
      setPreviewLines(
        result.individuals.flatMap((individual) =>
          individual.axioms.length > 0 ? individual.axioms : [`${individual.label} (${individual.iri})`],
        ),
      );
    } catch (caught) {
      setGatewayError(messageOf(caught, "Preview failed."));
    } finally {
      setPreviewLoading(false);
    }
  }

  async function commitMapping() {
    if (!selectedConnection || !selectedAction || previewLines === null) {
      return;
    }
    setCommitLoading(true);
    try {
      await api.commitConnectorMapping({
        connectionName: selectedConnection.name,
        service: selectedConnection.service,
        actionId: selectedAction.id,
        classIri: classIri.trim() || undefined,
        idField: idField.trim(),
        fieldMap,
        sampleLimit: 5,
      });
      setCommitted(true);
    } catch (caught) {
      setGatewayError(messageOf(caught, "Commit failed."));
    } finally {
      setCommitLoading(false);
    }
  }

  const browseRows =
    mode === "yours"
      ? connections.filter((row) => {
          const needle = search.trim().toLowerCase();
          if (!needle) {
            return true;
          }
          return (
            row.service.toLowerCase().includes(needle) ||
            row.name.toLowerCase().includes(needle) ||
            row.authType.toLowerCase().includes(needle)
          );
        })
      : apps;

  return (
    <Stack direction="vertical" gap={3} height="100%" padding={4}>
      <Stack direction="vertical" gap={2}>
        <Stack direction="horizontal" align="center" justify="between">
          <Heading level={2}>Connectors</Heading>
          {onClose ? (
            <Button type="button" variant="ghost" label="Back to editor" onClick={onClose} />
          ) : null}
        </Stack>
        <Text type="supporting" display="block">
          Browse OpenConnector providers and map read actions to OWL classes.
        </Text>
        <SegmentedControl
          label="Connector list"
          value={mode}
          onChange={(value) => setMode(value as BrowseMode)}
          size="sm"
        >
          <SegmentedControlItem label="Yours" value="yours" />
          <SegmentedControlItem label="Discover" value="discover" />
        </SegmentedControl>
        <TextInput
          label={mode === "yours" ? "Search connections" : "Search providers"}
          value={search}
          onChange={(value) => setSearch(value)}
          placeholder={mode === "yours" ? "Filter configured connections" : "Filter provider catalog"}
        />
        {gatewayError ? <Banner status="warning" title="Gateway" description={gatewayError} /> : null}
      </Stack>

      <StackItem size="fill" isScrollable>
        {loading ? (
          <Text type="supporting" display="block">Loading…</Text>
        ) : browseRows.length === 0 ? (
          <EmptyState
            isCompact
            title={mode === "yours" ? "No connections" : "No providers"}
            description={
              gatewayError
                ? "Reconnect the OpenConnector gateway or try Discover when it is available."
                : "Try another search or configure a connection in the gateway console."
            }
          />
        ) : mode === "yours" ? (
          <Stack direction="vertical" gap={1}>
            {browseRows.map((row) => {
              const connection = row as ConnectorConnection;
              const key = `${connection.service}:${connection.name}`;
              return (
                <Button
                  key={key}
                  type="button"
                  variant="ghost"
                  label={connection.service}
                  tooltip={`${connection.name} · ${connection.authType} · ${
                    connection.status === "connected" ? "Connected" : "Reconnect"
                  }`}
                  onClick={() => {
                    setSelectedConnection(connection);
                    setPreviewLines(null);
                    setCommitted(false);
                  }}
                />
              );
            })}
          </Stack>
        ) : (
          <List density="compact">
            {(browseRows as ConnectorApp[]).map((app) => (
              <ListItem
                key={app.id}
                label={app.name}
                description={`${app.id} · ${app.authType} · ${app.locallyExecutable ? "Local" : "Catalog"}`}
              />
            ))}
          </List>
        )}
      </StackItem>

      {selectedConnection ? (
        <Stack direction="vertical" gap={2} paddingBlockStart={2}>
          <Heading level={3}>Mapper</Heading>
          <Text type="supporting" display="block">
            {selectedConnection.service} · {selectedConnection.name}
          </Text>
          <Stack direction="vertical" gap={1}>
            {actions.map((action) => (
              <Button
                key={action.id}
                type="button"
                variant="ghost"
                label={action.id}
                tooltip={action.description}
                onClick={() => {
                  setSelectedAction(action);
                  setPreviewLines(null);
                  setCommitted(false);
                  if (!idField && action.outputFields.length > 0) {
                    setIdField(action.outputFields[0]);
                  }
                }}
              />
            ))}
          </Stack>
          <TextInput label="Class IRI" value={classIri} onChange={(value) => setClassIri(value)} />
          <TextInput
            label="ID output field"
            value={idField}
            onChange={(value) => setIdField(value)}
          />
          <TextInput
            label="Field map"
            value={fieldMapText}
            onChange={(value) => setFieldMapText(value)}
            description="One mapping per line: outputField=propertyIri"
          />
          <Stack direction="horizontal" gap={2}>
            <Button
              label={previewLoading ? "Previewing…" : "Preview"}
              type="button"
              variant="secondary"
              isDisabled={!selectedAction || previewLoading}
              isLoading={previewLoading}
              onClick={() => void previewMapping()}
            />
            <Button
              label={commitLoading ? "Committing…" : "Commit mapping"}
              type="button"
              variant="primary"
              isDisabled={previewLines === null || commitLoading || committed}
              isLoading={commitLoading}
              onClick={() => void commitMapping()}
            />
          </Stack>
          {previewLines ? (
            <List density="compact">
              {previewLines.map((line) => (
                <ListItem key={line} label={line} />
              ))}
            </List>
          ) : null}
          {committed ? (
            <Banner
              status="success"
              title="Saved"
              description="Connector mapping stored in .onto-studio/mapping.json."
            />
          ) : null}
        </Stack>
      ) : null}
    </Stack>
  );
}