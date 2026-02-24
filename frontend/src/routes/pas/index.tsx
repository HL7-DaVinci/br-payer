import { createFileRoute } from "@tanstack/react-router";
import {
  AlertCircle,
  ChevronDown,
  Loader2,
  Server,
  Settings,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import { toast } from "sonner";
import {
  JsonViewerDialog,
  useJsonViewer,
} from "@/components/json-viewer-dialog";
import { PasAutoPollControl } from "@/components/pas/pas-autopoll-control";
import { PasManualConfig } from "@/components/pas/pas-manual-config";
import { PasRequestEditor } from "@/components/pas/pas-request-editor";
import { PasScenarioList } from "@/components/pas/pas-scenario-list";
import { PasSuggestionBar } from "@/components/pas/pas-suggestion-bar";
import { PasTimeline } from "@/components/pas/pas-timeline";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useServerStatus } from "@/hooks/use-fhir-api";
import { useFhirServer, useServerSelection } from "@/hooks/use-fhir-server";
import { usePasInquire, usePasSubmit } from "@/hooks/use-pas-api";
import { usePasAutoPoll } from "@/hooks/use-pas-autopoll";
import { usePasScenarios } from "@/hooks/use-pas-scenarios";
import { usePasSuggestions } from "@/hooks/use-pas-suggestions";
import { usePasTimeline } from "@/hooks/use-pas-timeline";
import type {
  AutoPollConfig,
  PasError,
  PasMode,
  PasScenario,
  PasVariant,
  ReviewActionCode,
  SuggestedOperation,
  TimelineEntry,
} from "@/lib/pas-types";
import {
  extractAuthorizationNumber,
  extractClaimResponseFromBundle,
  extractResponseBundlesFromParameters,
  extractReviewAction,
  findResponseBundleByClaimResponseId,
} from "@/lib/pas-types";

export const Route = createFileRoute("/pas/")({
  component: PasPage,
});

function PasPage() {
  // Mode toggle with sessionStorage persistence
  const [mode, setMode] = useState<PasMode>(() => {
    const stored = sessionStorage.getItem("pas-mode");
    return stored === "manual" ? "manual" : "scenarios";
  });

  // Server configuration
  const { serverUrl, presetServers, setServerUrl, isCustomServer } =
    useFhirServer();
  const serverSelection = useServerSelection(
    setServerUrl,
    isCustomServer,
    serverUrl,
  );
  const { isConnected, latency } = useServerStatus(serverUrl);

  // Scenarios
  const {
    data: scenarios = [],
    isLoading: scenariosLoading,
    error: scenariosError,
    refetch: refetchScenarios,
    isRefetching: scenariosRefetching,
  } = usePasScenarios(serverUrl);

  // Scenario mode state
  const [selectedScenario, setSelectedScenario] = useState<PasScenario | null>(
    null,
  );
  const [selectedVariant, setSelectedVariant] = useState<PasVariant | null>(
    null,
  );
  const [requestJson, setRequestJson] = useState("");

  // Manual mode state
  const [manualOperation, setManualOperation] = useState<
    "$submit" | "$inquire"
  >("$submit");
  const [manualJson, setManualJson] = useState("");

  // Auto-poll config (sessionStorage-persisted)
  const [autoPollConfig, setAutoPollConfig] = useState<AutoPollConfig>(() => {
    try {
      const stored = sessionStorage.getItem("pas-autopoll");
      if (stored) return JSON.parse(stored) as AutoPollConfig;
    } catch {
      // Ignore parse errors
    }
    return { enabled: false, intervalSeconds: 10 };
  });

  // API mutations
  const { mutate: executeSubmit, isPending: isSubmitting } = usePasSubmit();
  const { mutate: executeInquire, isPending: isInquiring } = usePasInquire();

  const isExecuting = isSubmitting || isInquiring;

  // Timeline state
  const {
    entries,
    addEntry,
    clearAll,
    authorizationGroups,
    pendedAuthorizationIds,
  } = usePasTimeline();

  // Suggestions
  const suggestions = usePasSuggestions(authorizationGroups, selectedScenario);
  const mostRecentPendedId = useMemo(() => {
    let latestId: string | null = null;
    let latestTimestamp = Number.NEGATIVE_INFINITY;

    for (const group of authorizationGroups) {
      if (!group.isPended) continue;

      const groupLatestTimestamp = group.entries.reduce(
        (max, entry) => Math.max(max, entry.timestamp.getTime()),
        Number.NEGATIVE_INFINITY,
      );

      if (groupLatestTimestamp > latestTimestamp) {
        latestTimestamp = groupLatestTimestamp;
        latestId = group.authorizationId;
      }
    }

    return latestId;
  }, [authorizationGroups]);

  // JSON viewer
  const { viewerData, openViewer, closeViewer } = useJsonViewer();

  const fhirServerId = useId();
  const previousServerUrlRef = useRef(serverUrl);

  // Mapping from authorizationId -> inquiry bundle for auto-poll
  const inquiryBundleMapRef = useRef<Map<string, object>>(new Map());

  // Reset state when server changes
  useEffect(() => {
    if (previousServerUrlRef.current === serverUrl) return;
    previousServerUrlRef.current = serverUrl;
    setSelectedScenario(null);
    setSelectedVariant(null);
    setRequestJson("");
    clearAll();
    inquiryBundleMapRef.current.clear();
  }, [serverUrl, clearAll]);

  // Persist auto-poll config to sessionStorage
  useEffect(() => {
    sessionStorage.setItem("pas-autopoll", JSON.stringify(autoPollConfig));
  }, [autoPollConfig]);

  // Auto-disable polling when no pended authorizations remain
  useEffect(() => {
    if (autoPollConfig.enabled && pendedAuthorizationIds.length === 0) {
      setAutoPollConfig((prev) => ({ ...prev, enabled: false }));
    }
  }, [autoPollConfig.enabled, pendedAuthorizationIds.length]);

  // Auto-poll hook
  usePasAutoPoll({
    serverUrl,
    config: autoPollConfig,
    pendedAuthorizationIds,
    getInquiryBundle: useCallback(
      (authId: string) => inquiryBundleMapRef.current.get(authId) ?? null,
      [],
    ),
    onResult: addEntry,
  });

  // Mode switching
  const handleModeChange = useCallback((newMode: string) => {
    setMode(newMode as PasMode);
    sessionStorage.setItem("pas-mode", newMode);
  }, []);

  // Scenario selection -- clear timeline since prior entries belong to a different claim context
  const handleSelectScenario = useCallback(
    (scenario: PasScenario) => {
      setSelectedScenario(scenario);
      const defaultVariant = scenario.variants[0];
      setSelectedVariant(defaultVariant);
      setRequestJson(JSON.stringify(defaultVariant.bundle, null, 2));
      clearAll();
      inquiryBundleMapRef.current.clear();
    },
    [clearAll],
  );

  const handleSelectVariant = useCallback((variant: PasVariant) => {
    setSelectedVariant(variant);
    setRequestJson(JSON.stringify(variant.bundle, null, 2));
  }, []);

  // Build a timeline entry from a $submit response
  const buildSubmitEntry = useCallback(
    (
      bundle: object,
      responseBundle: object,
      durationMs: number,
      payloadType: string,
    ): TimelineEntry => {
      const cr = extractClaimResponseFromBundle(responseBundle);
      const reviewAction: ReviewActionCode | null = cr
        ? extractReviewAction(cr)
        : null;
      const authorizationId = (cr as { id?: string } | null)?.id ?? null;
      const authorizationNumber = cr ? extractAuthorizationNumber(cr) : null;

      return {
        id: crypto.randomUUID(),
        timestamp: new Date(),
        source: "user",
        operation: "$submit",
        payloadType,
        requestBundle: bundle,
        responseData: responseBundle,
        error: null,
        authorizationId,
        reviewAction,
        authorizationNumber,
        durationMs,
      };
    },
    [],
  );

  // Build timeline entries from a $inquire response.
  // When targetAuthId is provided, returns a single entry for the matching CR.
  // When null, returns one entry per responseBundle so all results are visible.
  const buildInquireEntries = useCallback(
    (
      bundle: object,
      params: object,
      durationMs: number,
      targetAuthId: string | null,
    ): TimelineEntry[] => {
      const responseBundles = extractResponseBundlesFromParameters(params);
      if (responseBundles.length === 0) {
        return [
          {
            id: crypto.randomUUID(),
            timestamp: new Date(),
            source: "user",
            operation: "$inquire",
            payloadType: "inquiry",
            requestBundle: bundle,
            responseData: params,
            error: null,
            authorizationId: null,
            reviewAction: null,
            authorizationNumber: null,
            durationMs,
          },
        ];
      }

      const bundlesToShow = targetAuthId
        ? [findResponseBundleByClaimResponseId(responseBundles, targetAuthId)]
        : responseBundles;

      return bundlesToShow
        .filter((b): b is NonNullable<typeof b> => b != null)
        .map((responseBundle) => {
          const cr = extractClaimResponseFromBundle(responseBundle);
          const reviewAction: ReviewActionCode | null = cr
            ? extractReviewAction(cr)
            : null;
          const authorizationId = (cr as { id?: string } | null)?.id ?? null;
          const authorizationNumber = cr
            ? extractAuthorizationNumber(cr)
            : null;

          return {
            id: crypto.randomUUID(),
            timestamp: new Date(),
            source: "user" as const,
            operation: "$inquire" as const,
            payloadType: "inquiry",
            requestBundle: bundle,
            responseData: params,
            error: null,
            authorizationId,
            reviewAction,
            authorizationNumber,
            durationMs,
          };
        });
    },
    [],
  );

  // Build a timeline entry for a failed operation
  const buildErrorEntry = useCallback(
    (
      bundle: object,
      err: Error,
      operation: "$submit" | "$inquire",
      payloadType: string,
      durationMs: number,
    ): TimelineEntry => {
      const pasError = err as PasError;
      return {
        id: crypto.randomUUID(),
        timestamp: new Date(),
        source: "user",
        operation,
        payloadType,
        requestBundle: bundle,
        responseData: (pasError.body as object) ?? null,
        error: pasError,
        authorizationId: null,
        reviewAction: null,
        authorizationNumber: null,
        durationMs,
      };
    },
    [],
  );

  // Execute operation (scenarios mode)
  const handleExecute = useCallback(() => {
    let parsed: object;
    try {
      parsed = JSON.parse(requestJson) as object;
    } catch {
      toast.error("Invalid JSON in request editor");
      return;
    }

    if (!selectedVariant) return;

    const startTime = performance.now();

    if (selectedVariant.operation === "$submit") {
      executeSubmit(
        { serverUrl, bundle: parsed },
        {
          onSuccess: (responseBundle) => {
            const durationMs = Math.round(performance.now() - startTime);
            const entry = buildSubmitEntry(
              parsed,
              responseBundle,
              durationMs,
              selectedVariant.payloadType,
            );
            addEntry(entry);

            // If pended, store the inquiry bundle for auto-poll
            if (
              entry.reviewAction === "A4" &&
              entry.authorizationId &&
              selectedScenario
            ) {
              const inquiryVariant = selectedScenario.variants.find(
                (v) => v.operation === "$inquire",
              );
              if (inquiryVariant) {
                inquiryBundleMapRef.current.set(
                  entry.authorizationId,
                  inquiryVariant.bundle,
                );
              }
            }
          },
          onError: (err) => {
            const durationMs = Math.round(performance.now() - startTime);
            addEntry(
              buildErrorEntry(
                parsed,
                err,
                "$submit",
                selectedVariant.payloadType,
                durationMs,
              ),
            );
            toast.error(err.message ?? "Submit failed");
          },
        },
      );
    } else {
      executeInquire(
        { serverUrl, bundle: parsed },
        {
          onSuccess: (params) => {
            const durationMs = Math.round(performance.now() - startTime);
            for (const entry of buildInquireEntries(
              parsed,
              params,
              durationMs,
              mostRecentPendedId,
            )) {
              addEntry(entry);
            }
          },
          onError: (err) => {
            const durationMs = Math.round(performance.now() - startTime);
            addEntry(
              buildErrorEntry(parsed, err, "$inquire", "inquiry", durationMs),
            );
            toast.error(err.message ?? "Inquire failed");
          },
        },
      );
    }
  }, [
    requestJson,
    selectedVariant,
    selectedScenario,
    mostRecentPendedId,
    serverUrl,
    executeSubmit,
    executeInquire,
    addEntry,
    buildSubmitEntry,
    buildInquireEntries,
    buildErrorEntry,
  ]);

  // Execute operation (manual mode)
  const handleManualExecute = useCallback(() => {
    let parsed: object;
    try {
      parsed = JSON.parse(manualJson) as object;
    } catch {
      toast.error("Invalid JSON in editor");
      return;
    }

    const startTime = performance.now();

    if (manualOperation === "$submit") {
      executeSubmit(
        { serverUrl, bundle: parsed },
        {
          onSuccess: (responseBundle) => {
            const durationMs = Math.round(performance.now() - startTime);
            const entry = buildSubmitEntry(
              parsed,
              responseBundle,
              durationMs,
              "manual",
            );
            addEntry(entry);
          },
          onError: (err) => {
            const durationMs = Math.round(performance.now() - startTime);
            addEntry(
              buildErrorEntry(parsed, err, "$submit", "manual", durationMs),
            );
            toast.error(err.message ?? "Submit failed");
          },
        },
      );
    } else {
      executeInquire(
        { serverUrl, bundle: parsed },
        {
          onSuccess: (params) => {
            const durationMs = Math.round(performance.now() - startTime);
            for (const entry of buildInquireEntries(
              parsed,
              params,
              durationMs,
              mostRecentPendedId,
            )) {
              addEntry(entry);
            }
          },
          onError: (err) => {
            const durationMs = Math.round(performance.now() - startTime);
            addEntry(
              buildErrorEntry(parsed, err, "$inquire", "manual", durationMs),
            );
            toast.error(err.message ?? "Inquire failed");
          },
        },
      );
    }
  }, [
    manualJson,
    manualOperation,
    mostRecentPendedId,
    serverUrl,
    executeSubmit,
    executeInquire,
    addEntry,
    buildSubmitEntry,
    buildInquireEntries,
    buildErrorEntry,
  ]);

  // Preview request
  const handlePreview = useCallback(() => {
    try {
      const parsed = JSON.parse(requestJson);
      const operation = selectedVariant?.operation ?? "$submit";
      openViewer(
        parsed,
        `${operation} Request Preview`,
        `POST ${serverUrl}/Claim/${operation}`,
      );
    } catch {
      toast.error("Invalid JSON in request editor");
    }
  }, [requestJson, selectedVariant, serverUrl, openViewer]);

  // Suggestion click
  const handleSuggestionClick = useCallback(
    (suggestion: SuggestedOperation) => {
      if (!selectedScenario) return;
      const matchingVariant = selectedScenario.variants.find(
        (v) =>
          v.operation === suggestion.operation &&
          (!suggestion.payloadType || v.payloadType === suggestion.payloadType),
      );
      if (matchingVariant) {
        handleSelectVariant(matchingVariant);
      }
    },
    [selectedScenario, handleSelectVariant],
  );

  // Clear timeline
  const handleClearTimeline = useCallback(() => {
    clearAll();
    inquiryBundleMapRef.current.clear();
  }, [clearAll]);

  // Auto-poll controls
  const handleToggleAutoPoll = useCallback(() => {
    setAutoPollConfig((prev) => ({ ...prev, enabled: !prev.enabled }));
  }, []);

  const handlePollIntervalChange = useCallback((seconds: number) => {
    setAutoPollConfig((prev) => ({ ...prev, intervalSeconds: seconds }));
  }, []);

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <Collapsible defaultOpen={false}>
          <div className="flex items-center justify-between px-6 py-3">
            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2">
                <Server className="h-4 w-4 text-muted-foreground" />
                <div className="flex items-center gap-2 text-sm">
                  <span className="text-muted-foreground">FHIR:</span>
                  <div
                    className={`h-2 w-2 rounded-full ${isConnected ? "bg-green-500" : "bg-red-500"}`}
                  />
                  {isConnected && latency !== undefined && (
                    <Badge variant="outline" className="text-[10px] h-5">
                      {latency}ms
                    </Badge>
                  )}
                </div>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <Tabs value={mode} onValueChange={handleModeChange}>
                <TabsList className="h-7">
                  <TabsTrigger
                    value="scenarios"
                    className="text-xs px-3 h-6 cursor-pointer"
                  >
                    Scenarios
                  </TabsTrigger>
                  <TabsTrigger
                    value="manual"
                    className="text-xs px-3 h-6 cursor-pointer"
                  >
                    Manual
                  </TabsTrigger>
                </TabsList>
              </Tabs>
              <CollapsibleTrigger asChild>
                <Button variant="ghost" size="sm" className="h-7">
                  <Settings className="h-3 w-3 mr-2" />
                  Configure Server
                  <ChevronDown className="h-3 w-3 ml-2" />
                </Button>
              </CollapsibleTrigger>
            </div>
          </div>
          <CollapsibleContent>
            <div className="px-6 pb-4 border-t bg-muted/40">
              <div className="pt-4 max-w-md">
                <div className="space-y-2">
                  <label
                    htmlFor={fhirServerId}
                    className="text-xs font-medium text-muted-foreground"
                  >
                    Payer FHIR Server
                  </label>
                  <Select
                    value={isCustomServer ? "custom" : serverUrl}
                    onValueChange={serverSelection.handleServerChange}
                  >
                    <SelectTrigger id={fhirServerId} className="h-8">
                      <SelectValue placeholder="Select FHIR server" />
                    </SelectTrigger>
                    <SelectContent>
                      {presetServers.map((s) => (
                        <SelectItem key={s.url} value={s.url}>
                          {s.name}
                        </SelectItem>
                      ))}
                      <SelectItem value="custom">Custom URL...</SelectItem>
                    </SelectContent>
                  </Select>
                  {serverSelection.showCustomInput && (
                    <div className="flex gap-2">
                      <Input
                        placeholder="https://payer-server.example.com/fhir"
                        value={serverSelection.customUrl}
                        onChange={(e) =>
                          serverSelection.setCustomUrl(e.target.value)
                        }
                        onKeyDown={(e) => {
                          if (e.key === "Enter") {
                            serverSelection.handleCustomUrlSubmit();
                          }
                        }}
                        className="h-8 text-xs"
                      />
                      {serverSelection.isEditing && (
                        <Button
                          size="sm"
                          className="h-8"
                          onClick={serverSelection.handleCustomUrlSubmit}
                        >
                          Connect
                        </Button>
                      )}
                    </div>
                  )}
                </div>
              </div>
            </div>
          </CollapsibleContent>
        </Collapsible>
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-hidden">
        <div className="grid grid-cols-12 gap-0 h-full">
          {/* Left Panel */}
          <div className="col-span-4 border-r overflow-y-auto p-4">
            {mode === "scenarios" ? (
              <>
                {scenariosLoading && (
                  <div className="flex items-center justify-center py-8">
                    <Loader2 className="h-5 w-5 animate-spin text-muted-foreground mr-2" />
                    <span className="text-sm text-muted-foreground">
                      Loading scenarios...
                    </span>
                  </div>
                )}
                {scenariosError && (
                  <div className="flex items-center gap-2 p-4 text-sm text-destructive">
                    <AlertCircle className="h-4 w-4 shrink-0" />
                    <span>
                      Failed to load scenarios. Is the server running?
                    </span>
                  </div>
                )}
                {!scenariosLoading && !scenariosError && (
                  <PasScenarioList
                    scenarios={scenarios}
                    selectedScenario={selectedScenario}
                    selectedVariant={selectedVariant}
                    onSelectScenario={handleSelectScenario}
                    onSelectVariant={handleSelectVariant}
                    suggestions={suggestions}
                    onRefresh={() => {
                      refetchScenarios();
                      setSelectedScenario(null);
                      setSelectedVariant(null);
                      setRequestJson("");
                      clearAll();
                      inquiryBundleMapRef.current.clear();
                    }}
                    isRefreshing={scenariosRefetching}
                  />
                )}
              </>
            ) : (
              <PasManualConfig
                requestJson={manualJson}
                onRequestJsonChange={setManualJson}
                operation={manualOperation}
                onOperationChange={setManualOperation}
                onExecute={handleManualExecute}
                isExecuting={isExecuting}
              />
            )}
          </div>

          {/* Right Panel */}
          <div className="col-span-8 flex flex-col overflow-hidden">
            {mode === "scenarios" && (
              <>
                <PasSuggestionBar
                  suggestions={suggestions}
                  onSuggestionClick={handleSuggestionClick}
                />
                <div className="shrink-0 p-4 pb-2">
                  <PasRequestEditor
                    scenario={selectedScenario}
                    variant={selectedVariant}
                    requestJson={requestJson}
                    onRequestJsonChange={setRequestJson}
                    onExecute={handleExecute}
                    onPreview={handlePreview}
                    isExecuting={isExecuting}
                  />
                </div>
              </>
            )}
            <div className="flex-1 overflow-y-auto p-4 pt-2">
              <PasTimeline
                entries={entries}
                authorizationGroups={authorizationGroups}
                onViewJson={openViewer}
                onClear={handleClearTimeline}
              />
              <PasAutoPollControl
                config={autoPollConfig}
                onToggle={handleToggleAutoPoll}
                onIntervalChange={handlePollIntervalChange}
                pendedCount={pendedAuthorizationIds.length}
              />
            </div>
          </div>
        </div>
      </div>

      {/* JSON Viewer Dialog */}
      {viewerData && (
        <JsonViewerDialog
          data={viewerData.data}
          title={viewerData.title}
          description={viewerData.description}
          onClose={closeViewer}
        />
      )}
    </div>
  );
}
