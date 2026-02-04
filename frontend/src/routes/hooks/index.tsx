import { createFileRoute } from "@tanstack/react-router";
import type { Extension, Resource } from "fhir/r4";
import {
  ChevronDown,
  Code,
  Loader2,
  Play,
  RefreshCw,
  Server,
  Settings,
} from "lucide-react";
import { useCallback, useId, useMemo, useState } from "react";
import { CdsResponsePanel } from "@/components/cds/cds-response-panel";
import { CdsServiceList } from "@/components/cds/cds-service-list";
import { HookContextBuilder } from "@/components/cds/hook-context-builder";
import { PrefetchPanel } from "@/components/cds/prefetch-panel";
import {
  JsonViewerDialog,
  useJsonViewer,
} from "@/components/json-viewer-dialog";
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
import {
  buildCdsRequest,
  type CdsError,
  useCdsDiscovery,
  useCdsServerStatus,
  useCdsServiceCall,
  usePrefetchQueries,
} from "@/hooks/use-cds-api";
import { useCdsServer, useCdsServerSelection } from "@/hooks/use-cds-server";
import { useFhirServer, useServerSelection } from "@/hooks/use-fhir-server";
import type { CdsCard, CdsService, CdsSystemAction } from "@/lib/cds-types";
import { extractFhirReferenceId } from "@/lib/cds-types";

export const Route = createFileRoute("/hooks/")({
  component: CdsHooksPage,
});

function CdsHooksPage() {
  // Server configuration
  const {
    serverUrl: cdsServerUrl,
    presetServers: cdsPresetServers,
    setServerUrl: setCdsServerUrl,
    isCustomServer: isCdsCustomServer,
  } = useCdsServer();

  const {
    serverUrl: fhirServerUrl,
    presetServers: fhirPresetServers,
    setServerUrl: setFhirServerUrl,
    isCustomServer: isFhirCustomServer,
  } = useFhirServer();

  const cdsServerSelection = useCdsServerSelection(
    setCdsServerUrl,
    isCdsCustomServer,
    cdsServerUrl,
  );

  const fhirServerSelection = useServerSelection(
    setFhirServerUrl,
    isFhirCustomServer,
    fhirServerUrl,
  );

  // CDS server status
  const { isConnected: cdsConnected, latency: cdsLatency } =
    useCdsServerStatus(cdsServerUrl);

  // Service discovery
  const {
    data: discovery,
    isLoading: isDiscoveryLoading,
    error: discoveryError,
    refetch: refetchDiscovery,
  } = useCdsDiscovery(cdsServerUrl);

  // Selected service and hook context
  const [selectedService, setSelectedService] = useState<CdsService | null>(
    null,
  );
  const [hookContext, setHookContext] = useState<Record<string, unknown>>({});

  // Prefetch data
  const {
    prefetchResults,
    prefetchData,
    isLoading: isPrefetchLoading,
  } = usePrefetchQueries(fhirServerUrl, selectedService?.prefetch, hookContext);

  // Hook execution
  const {
    mutate: executeHook,
    data: response,
    error: hookError,
    isPending: isExecuting,
    reset: resetResponse,
  } = useCdsServiceCall();

  // JSON viewer
  const { viewerData, openViewer, closeViewer } = useJsonViewer();

  // IDs for form elements
  const cdsServerId = useId();
  const fhirServerId = useId();

  // Build context for the CDS request
  // Per CDS Hooks spec:
  // - patientId/encounterId: just the FHIR resource ID (e.g., "123")
  // - userId/performer: full FHIR reference (e.g., "Practitioner/123")
  const buildContext = useCallback((): Record<string, unknown> => {
    const ctx: Record<string, unknown> = {};

    // Fields that should have just the ID (not the full reference)
    const idOnlyFields = new Set(["patientId", "encounterId"]);

    for (const [key, value] of Object.entries(hookContext)) {
      if (typeof value === "string" && value.includes("/")) {
        if (idOnlyFields.has(key)) {
          // Extract just the ID for patient/encounter
          ctx[key] = extractFhirReferenceId(value) ?? value;
        } else {
          // Keep full reference for userId, performer, order, etc.
          ctx[key] = value;
        }
      } else {
        ctx[key] = value;
      }
    }

    return ctx;
  }, [hookContext]);

  // Check if we have minimum required context
  const hasMinimumContext = useMemo(() => {
    return !!hookContext.patientId || !!hookContext.userId;
  }, [hookContext]);

  // Handle service selection
  const handleSelectService = useCallback(
    (service: CdsService) => {
      setSelectedService(service);
      setHookContext({});
      resetResponse();
    },
    [resetResponse],
  );

  // Handle hook execution
  const handleExecuteHook = useCallback(() => {
    if (!selectedService) return;

    const context = buildContext();
    const request = buildCdsRequest(
      selectedService,
      context,
      prefetchData,
      fhirServerUrl,
    );

    executeHook({
      cdsServerUrl,
      serviceId: selectedService.id,
      request,
    });
  }, [
    selectedService,
    buildContext,
    prefetchData,
    fhirServerUrl,
    cdsServerUrl,
    executeHook,
  ]);

  // Preview request handler
  const handlePreviewRequest = useCallback(() => {
    if (!selectedService) return;

    const context = buildContext();
    const request = buildCdsRequest(
      selectedService,
      context,
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
    <div className="flex flex-col h-screen">
      {/* Compact Header with Server Configuration */}
      <div className="border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <Collapsible defaultOpen={false}>
          <div className="flex items-center justify-between px-6 py-3">
            <div className="flex items-center gap-6">
              <div className="flex items-center gap-2">
                <Server className="h-4 w-4 text-muted-foreground" />
                <div className="flex items-center gap-2 text-sm">
                  <span className="text-muted-foreground">CDS:</span>
                  <div
                    className={`h-2 w-2 rounded-full ${cdsConnected ? "bg-green-500" : "bg-red-500"}`}
                  />
                  {cdsConnected && (
                    <Badge variant="outline" className="text-[10px] h-5">
                      {cdsLatency}ms
                    </Badge>
                  )}
                </div>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => refetchDiscovery()}
                disabled={isDiscoveryLoading}
                className="h-7"
              >
                {isDiscoveryLoading ? (
                  <Loader2 className="h-3 w-3 animate-spin" />
                ) : (
                  <RefreshCw className="h-3 w-3" />
                )}
              </Button>
            </div>
            <CollapsibleTrigger asChild>
              <Button variant="ghost" size="sm" className="h-7">
                <Settings className="h-3 w-3 mr-2" />
                Configure Servers
                <ChevronDown className="h-3 w-3 ml-2" />
              </Button>
            </CollapsibleTrigger>
          </div>
          <CollapsibleContent>
            <div className="px-6 pb-4 border-t bg-muted/40">
              <div className="grid grid-cols-2 gap-4 pt-4">
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <label
                      htmlFor={cdsServerId}
                      className="text-xs font-medium text-muted-foreground"
                    >
                      CDS Server
                    </label>
                  </div>
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
              </div>
            </div>
          </CollapsibleContent>
        </Collapsible>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 overflow-hidden">
        <div className="grid grid-cols-12 gap-0 h-full">
          {/* Left Sidebar - Service Selection */}
          <div className="col-span-3 border-r overflow-y-auto p-4">
            <CdsServiceList
              services={discovery?.services}
              selectedService={selectedService}
              onSelectService={handleSelectService}
              onViewDiscovery={() =>
                openViewer(
                  discovery,
                  "CDS Discovery Response",
                  `GET ${cdsServerUrl}/cds-services`,
                )
              }
              onViewService={(service) =>
                openViewer(service, `Service: ${service.title || service.id}`)
              }
              isLoading={isDiscoveryLoading}
              error={discoveryError}
            />
          </div>

          {/* Middle Column - Request Builder */}
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

            <div className="space-y-2 pt-2 grid grid-cols-2 gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={handlePreviewRequest}
                disabled={!selectedService}
                className="w-full"
              >
                <Code className="h-4 w-4 mr-1" />
                Preview Request
              </Button>
              <Button
                size="sm"
                onClick={handleExecuteHook}
                disabled={!selectedService || isExecuting || isPrefetchLoading}
                className="w-full"
              >
                {isExecuting ? (
                  <Loader2 className="h-4 w-4 animate-spin mr-1" />
                ) : (
                  <Play className="h-4 w-4 mr-1" />
                )}
                Execute Hook
              </Button>
            </div>

            <PrefetchPanel
              prefetchResults={prefetchResults}
              onViewPrefetchItem={(key, data) =>
                openViewer(data, `Prefetch: ${key}`)
              }
              onViewAllPrefetch={(data) =>
                openViewer(data, "All Prefetch Data")
              }
              hasContext={hasMinimumContext}
            />
          </div>

          {/* Right Column - Response */}
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
