import { createFileRoute } from "@tanstack/react-router";
import type { Extension, Resource } from "fhir/r4";
import {
  AlertCircle,
  ChevronDown,
  Code,
  Loader2,
  Play,
  Server,
  Settings,
} from "lucide-react";
import { useCallback, useId, useState } from "react";
import { CdsResponsePanel } from "@/components/cds/cds-response-panel";
import { CdsServiceList } from "@/components/cds/cds-service-list";
import { CrdRequestEditor } from "@/components/cds/crd-request-editor";
import { CrdScenarioList } from "@/components/cds/crd-scenario-list";
import { HookContextBuilder } from "@/components/cds/hook-context-builder";
import { PrefetchPanel } from "@/components/cds/prefetch-panel";
import {
  JsonViewerDialog,
  useJsonViewer,
} from "@/components/json-viewer-dialog";
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
import {
  buildCdsRequest,
  type CdsError,
  useCdsDiscovery,
  useCdsServiceCall,
  usePrefetchQueries,
} from "@/hooks/use-cds-api";
import { useCdsServer, useCdsServerSelection } from "@/hooks/use-cds-server";
import { useCrdScenarios } from "@/hooks/use-crd-scenarios";
import { useServerStatus } from "@/hooks/use-fhir-api";
import { useFhirServer, useServerSelection } from "@/hooks/use-fhir-server";
import type {
  CdsCard,
  CdsRequest,
  CdsService,
  CdsSystemAction,
} from "@/lib/cds-types";
import { extractFhirReferenceId } from "@/lib/cds-types";
import type { CrdHookVariant, CrdScenario } from "@/lib/crd-types";

export const Route = createFileRoute("/hooks/")({
  component: CdsHooksPage,
});

type CrdMode = "scenarios" | "manual";

function CdsHooksPage() {
  // Mode toggle with sessionStorage persistence
  const [mode, setMode] = useState<CrdMode>(() => {
    const stored = sessionStorage.getItem("crd-hooks-mode");
    return stored === "manual" ? "manual" : "scenarios";
  });

  // Server configuration - CDS (both modes)
  const {
    serverUrl: cdsServerUrl,
    presetServers: cdsPresetServers,
    setServerUrl: setCdsServerUrl,
    isCustomServer: isCdsCustomServer,
  } = useCdsServer();

  const cdsServerSelection = useCdsServerSelection(
    setCdsServerUrl,
    isCdsCustomServer,
    cdsServerUrl,
  );

  // Server configuration - EHR FHIR (manual mode)
  const {
    serverUrl: fhirServerUrl,
    presetServers: fhirPresetServers,
    setServerUrl: setFhirServerUrl,
    isCustomServer: isFhirCustomServer,
  } = useFhirServer();

  const fhirServerSelection = useServerSelection(
    setFhirServerUrl,
    isFhirCustomServer,
    fhirServerUrl,
  );

  const ehrStatus = useServerStatus(fhirServerUrl);

  // Scenarios from the server
  const {
    data: scenarios = [],
    isLoading: scenariosLoading,
    error: scenariosError,
  } = useCrdScenarios(cdsServerUrl);

  // Discovery for both modes (service list in manual, service ID resolution in scenarios)
  const {
    data: discovery,
    isLoading: discoveryLoading,
    error: discoveryError,
  } = useCdsDiscovery(cdsServerUrl);

  // Scenario mode state
  const [selectedScenario, setSelectedScenario] = useState<CrdScenario | null>(
    null,
  );
  const [selectedVariant, setSelectedVariant] = useState<CrdHookVariant | null>(
    null,
  );
  const [requestJson, setRequestJson] = useState("");

  // Manual mode state
  const [selectedService, setSelectedService] = useState<CdsService | null>(
    null,
  );
  const [hookContext, setHookContext] = useState<Record<string, unknown>>({});

  // Prefetch queries (only active in manual mode with a selected service)
  const { prefetchResults, prefetchData } = usePrefetchQueries(
    fhirServerUrl,
    mode === "manual" ? selectedService?.prefetch : undefined,
    hookContext,
  );

  // Hook execution (shared between both modes)
  const {
    mutate: executeHook,
    data: response,
    error: hookError,
    isPending: isExecuting,
    reset: resetResponse,
  } = useCdsServiceCall();

  // JSON viewer
  const { viewerData, openViewer, closeViewer } = useJsonViewer();

  const cdsServerId = useId();
  const fhirServerId = useId();

  // Mode switching - clears response but preserves mode-specific state
  const handleModeChange = useCallback(
    (newMode: string) => {
      setMode(newMode as CrdMode);
      sessionStorage.setItem("crd-hooks-mode", newMode);
      resetResponse();
    },
    [resetResponse],
  );

  // Scenario mode: resolve service ID from hook name
  const resolveServiceId = useCallback(
    (hookName: string): string => {
      const discovered = discovery?.services?.find((s) => s.hook === hookName);
      return discovered?.id ?? `${hookName}-crd`;
    },
    [discovery],
  );

  const handleSelectScenario = useCallback(
    (scenario: CrdScenario) => {
      setSelectedScenario(scenario);
      const defaultVariant = scenario.hooks[0];
      setSelectedVariant(defaultVariant);
      setRequestJson(JSON.stringify(defaultVariant.requestJson, null, 2));
      resetResponse();
    },
    [resetResponse],
  );

  const handleSelectVariant = useCallback((variant: CrdHookVariant) => {
    setSelectedVariant(variant);
    setRequestJson(JSON.stringify(variant.requestJson, null, 2));
  }, []);

  const handleExecute = useCallback(
    (request: CdsRequest) => {
      if (!selectedVariant) return;
      const serviceId = resolveServiceId(selectedVariant.hookName);
      executeHook({ cdsServerUrl, serviceId, request });
    },
    [selectedVariant, resolveServiceId, cdsServerUrl, executeHook],
  );

  const handlePreview = useCallback(
    (request: CdsRequest) => {
      if (!selectedVariant) return;
      const serviceId = resolveServiceId(selectedVariant.hookName);
      openViewer(
        request,
        "CDS Hook Request",
        `POST /cds-services/${serviceId}`,
      );
    },
    [selectedVariant, resolveServiceId, openViewer],
  );

  // Manual mode handlers
  const handleSelectService = useCallback(
    (service: CdsService) => {
      setSelectedService(service);
      setHookContext({});
      resetResponse();
    },
    [resetResponse],
  );

  const buildContext = useCallback((): Record<string, unknown> => {
    const ctx: Record<string, unknown> = {};
    const idOnlyFields = new Set(["patientId", "encounterId"]);
    for (const [key, value] of Object.entries(hookContext)) {
      if (
        typeof value === "string" &&
        value.includes("/") &&
        idOnlyFields.has(key)
      ) {
        ctx[key] = extractFhirReferenceId(value) ?? value;
      } else {
        ctx[key] = value;
      }
    }
    return ctx;
  }, [hookContext]);

  const handleManualExecute = useCallback(() => {
    if (!selectedService) return;
    const request = buildCdsRequest(
      selectedService,
      buildContext(),
      prefetchData,
      fhirServerUrl,
    );
    executeHook({ cdsServerUrl, serviceId: selectedService.id, request });
  }, [
    selectedService,
    buildContext,
    prefetchData,
    fhirServerUrl,
    cdsServerUrl,
    executeHook,
  ]);

  const handleManualPreview = useCallback(() => {
    if (!selectedService) return;
    const request = buildCdsRequest(
      selectedService,
      buildContext(),
      prefetchData,
      fhirServerUrl,
    );
    openViewer(
      request,
      "CDS Hook Request",
      `POST /cds-services/${selectedService.id}`,
    );
  }, [selectedService, buildContext, prefetchData, fhirServerUrl, openViewer]);

  return (
    <div className="flex flex-col h-full">
      {/* Header with Mode Toggle and Server Configuration */}
      <div className="border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <Collapsible defaultOpen={false}>
          <div className="flex items-center justify-between px-6 py-3">
            <div className="flex items-center gap-6">
              <div className="flex items-center gap-2">
                <Server className="h-4 w-4 text-muted-foreground" />
                <div className="flex items-center gap-2 text-sm">
                  <span className="text-muted-foreground">CDS:</span>
                  <div
                    className={`h-2 w-2 rounded-full ${discovery ? "bg-green-500" : "bg-red-500"}`}
                  />
                </div>
              </div>
              {mode === "manual" && (
                <div className="flex items-center gap-2 text-sm">
                  <span className="text-muted-foreground">EHR:</span>
                  <div
                    className={`h-2 w-2 rounded-full ${ehrStatus.isConnected ? "bg-green-500" : "bg-red-500"}`}
                  />
                  {ehrStatus.latency != null && (
                    <span className="text-[10px] text-muted-foreground">
                      {ehrStatus.latency}ms
                    </span>
                  )}
                </div>
              )}
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
              <div
                className={`pt-4 ${mode === "manual" ? "grid grid-cols-2 gap-6" : "max-w-md"}`}
              >
                {/* CDS Server (always visible) */}
                <div className="space-y-2">
                  <label
                    htmlFor={cdsServerId}
                    className="text-xs font-medium text-muted-foreground"
                  >
                    CDS Server
                  </label>
                  <Select
                    value={isCdsCustomServer ? "custom" : cdsServerUrl}
                    onValueChange={cdsServerSelection.handleServerChange}
                  >
                    <SelectTrigger id={cdsServerId} className="h-8">
                      <SelectValue placeholder="Select CDS server" />
                    </SelectTrigger>
                    <SelectContent>
                      {cdsPresetServers.map((s) => (
                        <SelectItem key={s.url} value={s.url}>
                          {s.name}
                        </SelectItem>
                      ))}
                      <SelectItem value="custom">Custom URL...</SelectItem>
                    </SelectContent>
                  </Select>
                  {cdsServerSelection.showCustomInput && (
                    <div className="flex gap-2">
                      <Input
                        placeholder="https://cds-server.example.com"
                        value={cdsServerSelection.customUrl}
                        onChange={(e) =>
                          cdsServerSelection.setCustomUrl(e.target.value)
                        }
                        onKeyDown={(e) => {
                          if (e.key === "Enter") {
                            cdsServerSelection.handleCustomUrlSubmit();
                          }
                        }}
                        className="h-8 text-xs"
                      />
                      {cdsServerSelection.isEditing && (
                        <Button
                          size="sm"
                          className="h-8"
                          onClick={cdsServerSelection.handleCustomUrlSubmit}
                        >
                          Connect
                        </Button>
                      )}
                    </div>
                  )}
                </div>

                {/* EHR FHIR Server (manual mode only) */}
                {mode === "manual" && (
                  <div className="space-y-2">
                    <label
                      htmlFor={fhirServerId}
                      className="text-xs font-medium text-muted-foreground"
                    >
                      EHR FHIR Server
                    </label>
                    <Select
                      value={isFhirCustomServer ? "custom" : fhirServerUrl}
                      onValueChange={fhirServerSelection.handleServerChange}
                    >
                      <SelectTrigger id={fhirServerId} className="h-8">
                        <SelectValue placeholder="Select FHIR server" />
                      </SelectTrigger>
                      <SelectContent>
                        {fhirPresetServers.map((s) => (
                          <SelectItem key={s.url} value={s.url}>
                            {s.name}
                          </SelectItem>
                        ))}
                        <SelectItem value="custom">Custom URL...</SelectItem>
                      </SelectContent>
                    </Select>
                    {fhirServerSelection.showCustomInput && (
                      <div className="flex gap-2">
                        <Input
                          placeholder="https://fhir-server.example.com/fhir"
                          value={fhirServerSelection.customUrl}
                          onChange={(e) =>
                            fhirServerSelection.setCustomUrl(e.target.value)
                          }
                          onKeyDown={(e) => {
                            if (e.key === "Enter") {
                              fhirServerSelection.handleCustomUrlSubmit();
                            }
                          }}
                          className="h-8 text-xs"
                        />
                        {fhirServerSelection.isEditing && (
                          <Button
                            size="sm"
                            className="h-8"
                            onClick={fhirServerSelection.handleCustomUrlSubmit}
                          >
                            Connect
                          </Button>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          </CollapsibleContent>
        </Collapsible>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 overflow-hidden">
        <div className="grid grid-cols-12 gap-0 h-full">
          {mode === "scenarios" ? (
            <>
              {/* Left Sidebar - Scenario Selection */}
              <div className="col-span-3 border-r overflow-y-auto p-4">
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
                  <CrdScenarioList
                    scenarios={scenarios}
                    selectedScenario={selectedScenario}
                    selectedVariant={selectedVariant}
                    onSelectScenario={handleSelectScenario}
                    onSelectVariant={handleSelectVariant}
                  />
                )}
              </div>

              {/* Middle Column - Request Editor */}
              <div className="col-span-3 border-r overflow-y-auto p-4">
                <CrdRequestEditor
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
          ) : (
            <>
              {/* Left Sidebar - Service List */}
              <div className="col-span-3 border-r overflow-y-auto p-4">
                <CdsServiceList
                  services={discovery?.services}
                  selectedService={selectedService}
                  onSelectService={handleSelectService}
                  onViewDiscovery={() =>
                    openViewer(
                      discovery,
                      "CDS Discovery",
                      `GET ${cdsServerUrl}/cds-services`,
                    )
                  }
                  onViewService={(s) => openViewer(s, s.title || s.id)}
                  isLoading={discoveryLoading}
                  error={(discoveryError as Error) ?? null}
                />
              </div>

              {/* Middle Column - Context Builder, Prefetch, Execute */}
              <div className="col-span-3 border-r overflow-y-auto p-4 space-y-4">
                <HookContextBuilder
                  service={selectedService}
                  fhirServerUrl={fhirServerUrl}
                  context={hookContext}
                  onContextChange={setHookContext}
                  onViewContextResource={(key, resource) =>
                    openViewer(resource, `Context: ${key}`)
                  }
                />
                <PrefetchPanel
                  prefetchResults={prefetchResults}
                  onViewPrefetchItem={(key, data) =>
                    openViewer(data, `Prefetch: ${key}`)
                  }
                  onViewAllPrefetch={(data) =>
                    openViewer(data, "All Prefetch Data")
                  }
                  hasContext={!!hookContext.patientId}
                />
                <div className="grid grid-cols-2 gap-2">
                  <Button
                    onClick={handleManualExecute}
                    disabled={!selectedService || isExecuting}
                    className="h-9"
                  >
                    {isExecuting ? (
                      <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                    ) : (
                      <Play className="h-4 w-4 mr-2" />
                    )}
                    Execute
                  </Button>
                  <Button
                    variant="outline"
                    onClick={handleManualPreview}
                    disabled={!selectedService}
                    className="h-9"
                  >
                    <Code className="h-4 w-4 mr-2" />
                    Preview
                  </Button>
                </div>
              </div>
            </>
          )}

          {/* Right Column - Response (shared between both modes) */}
          <div className="col-span-6 overflow-y-auto p-4">
            <CdsResponsePanel
              response={response ?? null}
              error={(hookError as CdsError) ?? null}
              onViewRawResponse={() => {
                if (hookError) {
                  const cdsError = hookError as CdsError;
                  openViewer(
                    cdsError.operationOutcome ??
                      cdsError.body ?? { message: cdsError.message },
                    "CDS Hook Error",
                    `HTTP ${cdsError.status ?? "Error"}`,
                  );
                } else {
                  openViewer(
                    response,
                    "CDS Hook Response",
                    "Full response from CDS server",
                  );
                }
              }}
              onViewCard={(card: CdsCard, index: number) =>
                openViewer(
                  card,
                  `Card ${index + 1}: ${card.summary.substring(0, 30)}...`,
                )
              }
              onViewSystemAction={(action: CdsSystemAction, index: number) =>
                openViewer(action, `System Action ${index + 1}`)
              }
              onViewResource={(resource: Resource) =>
                openViewer(
                  resource,
                  `${resource.resourceType}/${resource.id}`,
                  "Resource from system action",
                )
              }
              onViewCoverageInfo={(
                rawExtension: Extension,
                resourceType: string,
                resourceId: string,
              ) =>
                openViewer(
                  rawExtension,
                  `Coverage Info: ${resourceType}/${resourceId}`,
                  "FHIR coverage-information extension",
                )
              }
            />
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
