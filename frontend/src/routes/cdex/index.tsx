import { useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import type { Parameters } from "fhir/r4";
import { Send } from "lucide-react";
import { useCallback, useMemo, useState } from "react";
import { toast } from "sonner";
import {
  CdexTimeline,
  type CdexTimelineEvent,
} from "@/components/cdex/cdex-timeline";
import { DocRequestCard } from "@/components/cdex/doc-request-card";
import { PendedClaimList } from "@/components/cdex/pended-claim-list";
import {
  JsonViewerDialog,
  useJsonViewer,
} from "@/components/json-viewer-dialog";
import {
  RequestEditor,
  type SummaryItem,
} from "@/components/shared/request-editor";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import {
  fetchClaimResponse,
  fetchGeneratedSubmitAttachment,
  usePendedClaims,
  useSubmitAttachment,
} from "@/hooks/use-cdex-api";
import { useFhirServer } from "@/hooks/use-fhir-server";
import { usePasSubmit } from "@/hooks/use-pas-api";
import { usePasScenarios } from "@/hooks/use-pas-scenarios";
import {
  buildCdexTask,
  type DocumentationRequest,
  type PendedClaim,
  pendedClaimLabel,
} from "@/lib/cdex-types";
import {
  extractClaimResponseFromBundle,
  extractReviewAction,
} from "@/lib/pas-types";

export const Route = createFileRoute("/cdex/")({
  component: CdexPage,
});

let eventCounter = 0;
function nextEventId(): string {
  eventCounter += 1;
  return `evt-${eventCounter}`;
}

function CdexPage() {
  const { serverUrl } = useFhirServer();
  const queryClient = useQueryClient();
  const pendedQuery = usePendedClaims(serverUrl);
  const submitAttachment = useSubmitAttachment();
  const { viewerData, openViewer, closeViewer } = useJsonViewer();

  const [selectedClaimId, setSelectedClaimId] = useState<string | null>(null);
  const [requestJson, setRequestJson] = useState("");
  const [finalSubmission, setFinalSubmission] = useState(true);
  const [events, setEvents] = useState<CdexTimelineEvent[]>([]);
  const [isGenerating, setIsGenerating] = useState(false);

  const selectedClaim = useMemo(
    () =>
      pendedQuery.data?.find((c) => c.claimResponseId === selectedClaimId) ??
      null,
    [pendedQuery.data, selectedClaimId],
  );

  const addEvent = useCallback(
    (event: Omit<CdexTimelineEvent, "id" | "timestamp">) => {
      setEvents((prev) => [
        ...prev,
        { ...event, id: nextEventId(), timestamp: new Date() },
      ]);
    },
    [],
  );

  const refreshPended = useCallback(() => {
    queryClient.invalidateQueries({
      queryKey: ["cdex-pended", serverUrl.replace(/\/fhir$/, "")],
    });
  }, [queryClient, serverUrl]);

  const handleSelectClaim = useCallback(
    (claim: PendedClaim) => {
      setSelectedClaimId(claim.claimResponseId);
      setRequestJson("");
      addEvent({
        kind: "info",
        label: `Selected ${pendedClaimLabel(claim)}`,
        detail: `${claim.documentationRequests.length} documentation request(s)`,
        data: claim,
      });
    },
    [addEvent],
  );

  const handleGenerate = useCallback(async () => {
    if (!selectedClaim) return;
    setIsGenerating(true);
    try {
      const parameters = await fetchGeneratedSubmitAttachment(
        serverUrl,
        selectedClaim.claimResponseId,
        { final: finalSubmission },
      );
      setRequestJson(JSON.stringify(parameters, null, 2));
      addEvent({
        kind: "info",
        label: "Generated $submit-attachment payload",
        data: parameters,
      });
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "Failed to generate payload",
      );
    } finally {
      setIsGenerating(false);
    }
  }, [selectedClaim, serverUrl, finalSubmission, addEvent]);

  const checkReadjudication = useCallback(
    async (claimResponseId: string, attempt: number) => {
      try {
        const cr = await fetchClaimResponse(serverUrl, claimResponseId);
        const reviewAction = extractReviewAction(cr);
        if (reviewAction && reviewAction !== "A4") {
          addEvent({
            kind: "success",
            label: `Re-adjudicated: ${reviewAction}`,
            detail: "The pended prior authorization has been decided.",
            data: cr,
          });
          refreshPended();
          return;
        }
        if (attempt < 1) {
          setTimeout(
            () => checkReadjudication(claimResponseId, attempt + 1),
            1500,
          );
        } else {
          addEvent({
            kind: "pending",
            label: "Still pended",
            detail: "Awaiting remaining documentation or resolution.",
            data: cr,
          });
          refreshPended();
        }
      } catch (error) {
        addEvent({
          kind: "error",
          label: "Failed to read ClaimResponse",
          detail: error instanceof Error ? error.message : String(error),
        });
      }
    },
    [serverUrl, addEvent, refreshPended],
  );

  const handleExecute = useCallback(() => {
    if (!selectedClaim || !requestJson) return;
    const parameters = JSON.parse(requestJson) as Parameters;
    addEvent({
      kind: "info",
      label: "POST $submit-attachment",
      data: parameters,
    });
    submitAttachment.mutate(
      { serverUrl, parameters },
      {
        onSuccess: (outcome) => {
          addEvent({
            kind: "success",
            label: "$submit-attachment accepted",
            detail: outcome.issue?.[0]?.diagnostics,
            data: outcome,
          });
          checkReadjudication(selectedClaim.claimResponseId, 0);
        },
        onError: (error) => {
          addEvent({
            kind: "error",
            label: "$submit-attachment failed",
            detail: error instanceof Error ? error.message : String(error),
            data: (error as { operationOutcome?: unknown }).operationOutcome,
          });
        },
      },
    );
  }, [
    selectedClaim,
    requestJson,
    serverUrl,
    submitAttachment,
    addEvent,
    checkReadjudication,
  ]);

  const extractSummary = useCallback(
    (parsed: Record<string, unknown>): SummaryItem[] => {
      const parameters = (parsed.parameter ?? []) as Array<{
        name?: string;
        valueIdentifier?: { value?: string };
        valueCode?: string;
        valueBoolean?: boolean;
      }>;
      const byName = (name: string) => parameters.find((p) => p.name === name);
      return [
        {
          label: "TrackingId",
          value: byName("TrackingId")?.valueIdentifier?.value ?? "-",
        },
        { label: "AttachTo", value: byName("AttachTo")?.valueCode ?? "-" },
        {
          label: "MemberId",
          value: byName("MemberId")?.valueIdentifier?.value ?? "-",
        },
        {
          label: "Attachments",
          value: String(
            parameters.filter((p) => p.name === "Attachment").length,
          ),
        },
        {
          label: "Final",
          value: String(byName("Final")?.valueBoolean ?? true),
        },
      ];
    },
    [],
  );

  return (
    <div className="flex flex-col h-full">
      <div className="border-b bg-background/95 backdrop-blur px-4 py-3">
        <div className="flex items-center justify-between gap-4">
          <div>
            <h1 className="text-lg font-semibold">CDex Attachments</h1>
            <p className="text-sm text-muted-foreground">
              Provider view: fulfill documentation requests for pended prior
              authorizations via $submit-attachment
            </p>
          </div>
        </div>
      </div>

      <div className="flex-1 min-h-0 grid grid-cols-12 gap-4 p-4">
        <div className="col-span-3 min-h-0">
          <PendedClaimList
            claims={pendedQuery.data ?? []}
            selectedId={selectedClaimId}
            onSelect={handleSelectClaim}
            isLoading={pendedQuery.isFetching}
            onRefresh={refreshPended}
            headerAction={
              <InlinePasSubmit onPended={refreshPended} addEvent={addEvent} />
            }
          />
        </div>

        <div className="col-span-5 min-h-0 flex flex-col gap-4 overflow-y-auto">
          {selectedClaim && (
            <div className="flex flex-col gap-2">
              {selectedClaim.documentationRequests.map((request) => (
                <DocRequestCard
                  key={request.communicationRequestId}
                  request={request}
                  onViewRequest={(r) =>
                    openViewer(
                      r,
                      `CommunicationRequest/${r.communicationRequestId}`,
                    )
                  }
                  onViewTask={(r: DocumentationRequest) =>
                    openViewer(
                      buildCdexTask(
                        selectedClaim,
                        r,
                        r.status === "completed" ? "completed" : "requested",
                        serverUrl,
                      ),
                      "Provider-local CDex Task",
                      "The Task a provider system would build from this CommunicationRequest. Not stored on the payer server.",
                    )
                  }
                />
              ))}
              <div className="flex items-center gap-2">
                <Checkbox
                  id="final-submission"
                  checked={finalSubmission}
                  onCheckedChange={(checked) =>
                    setFinalSubmission(checked === true)
                  }
                />
                <Label htmlFor="final-submission" className="text-sm">
                  Final submission
                </Label>
                <Button
                  size="sm"
                  variant="secondary"
                  className="ml-auto"
                  onClick={handleGenerate}
                  disabled={isGenerating}
                >
                  <Send className="h-4 w-4 mr-1" />
                  Generate payload
                </Button>
              </div>
            </div>
          )}
          <RequestEditor
            scenarioName={selectedClaim ? `$submit-attachment` : null}
            headerDescription={
              selectedClaim
                ? `Documentation for ${pendedClaimLabel(selectedClaim)}`
                : null
            }
            requestJson={requestJson}
            onRequestJsonChange={setRequestJson}
            onExecute={handleExecute}
            onPreview={() => {
              try {
                openViewer(
                  JSON.parse(requestJson),
                  "$submit-attachment request",
                );
              } catch {
                toast.error("Invalid JSON in request editor");
              }
            }}
            isExecuting={submitAttachment.isPending}
            extractSummary={extractSummary}
          />
        </div>

        <div className="col-span-4 min-h-0">
          <CdexTimeline
            events={events}
            onView={(data, title) => openViewer(data, title)}
            onClear={() => setEvents([])}
          />
        </div>
      </div>

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

interface InlinePasSubmitProps {
  onPended: () => void;
  addEvent: (event: Omit<CdexTimelineEvent, "id" | "timestamp">) => void;
}

function InlinePasSubmit({ onPended, addEvent }: InlinePasSubmitProps) {
  const { serverUrl } = useFhirServer();
  const scenarios = usePasScenarios(serverUrl);
  const pasSubmit = usePasSubmit();
  const [open, setOpen] = useState(false);

  // PasVariant.bundle is serialized with @JsonRawValue on the server, so it
  // arrives as a JSON object, not a string.
  const handleSubmit = (scenarioId: string, bundle: object) => {
    addEvent({ kind: "info", label: `PAS $submit: ${scenarioId}` });
    pasSubmit.mutate(
      { serverUrl, bundle },
      {
        onSuccess: (responseBundle) => {
          const claimResponse = extractClaimResponseFromBundle(responseBundle);
          const reviewAction = claimResponse
            ? extractReviewAction(claimResponse)
            : null;
          addEvent({
            kind: reviewAction === "A4" ? "pending" : "success",
            label: `PAS response: ${reviewAction ?? "unknown"}`,
            detail:
              reviewAction === "A4"
                ? "Pended for documentation. It should now appear in the pended list."
                : "Not pended; pick a scenario that requires documentation.",
            data: responseBundle,
          });
          onPended();
          setOpen(false);
        },
        onError: (error) => {
          addEvent({
            kind: "error",
            label: "PAS $submit failed",
            detail: error instanceof Error ? error.message : String(error),
          });
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm">
          Submit PAS scenario
        </Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Submit a PAS scenario</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-1 max-h-96 overflow-y-auto">
          {scenarios.data?.every((s) => s.documentationNeeded !== true) && (
            <p className="text-sm text-muted-foreground px-2 py-4">
              No scenarios are expected to pend for documentation. Check the
              loaded library PlanDefinitions.
            </p>
          )}
          {scenarios.data
            ?.filter((scenario) => scenario.documentationNeeded === true)
            .map((scenario) => {
              const initial = scenario.variants.find(
                (v) => v.operation === "$submit",
              );
              if (!initial) return null;
              return (
                <Button
                  key={scenario.id}
                  variant="ghost"
                  className="justify-start h-auto py-2 text-left whitespace-normal"
                  disabled={pasSubmit.isPending}
                  onClick={() =>
                    handleSubmit(scenario.id, initial.bundle as object)
                  }
                >
                  <div className="min-w-0 w-full">
                    <div className="text-sm font-medium">{scenario.name}</div>
                    <div className="text-xs text-muted-foreground">
                      {scenario.description}
                    </div>
                  </div>
                </Button>
              );
            })}
        </div>
      </DialogContent>
    </Dialog>
  );
}
