import { useCallback } from "react";
import {
  RequestEditor,
  type SummaryItem,
} from "@/components/shared/request-editor";
import { Badge } from "@/components/ui/badge";
import type { CdsRequest } from "@/lib/cds-types";
import type { CrdHookVariant, CrdScenario } from "@/lib/crd-types";

interface CrdRequestEditorProps {
  scenario: CrdScenario | null;
  variant: CrdHookVariant | null;
  requestJson: string;
  onRequestJsonChange: (json: string) => void;
  onExecute: (request: CdsRequest) => void;
  onPreview: (request: CdsRequest) => void;
  isExecuting: boolean;
}

function extractCrdSummary(request: Record<string, unknown>): SummaryItem[] {
  const summary: SummaryItem[] = [];

  if (typeof request.hook === "string") {
    summary.push({ label: "Hook", value: request.hook });
  }

  const context = request.context as Record<string, unknown> | undefined;
  if (context) {
    if (typeof context.patientId === "string") {
      summary.push({ label: "Patient", value: context.patientId });
    }

    // Extract order info from draftOrders bundle
    const draftOrders = context.draftOrders as
      | { entry?: Array<{ resource?: Record<string, unknown> }> }
      | undefined;
    if (draftOrders?.entry?.[0]?.resource) {
      const resource = draftOrders.entry[0].resource;
      if (resource.resourceType) {
        summary.push({
          label: "Order Type",
          value: String(resource.resourceType),
        });
      }
      const code =
        (resource.codeCodeableConcept as Record<string, unknown>) ??
        (resource.code as Record<string, unknown>) ??
        (resource.medicationCodeableConcept as Record<string, unknown>);
      if (code) {
        const coding =
          (code.coding as Array<{ display?: string; code?: string }>) ?? [];
        if (coding[0]?.display) {
          summary.push({ label: "Code", value: coding[0].display });
        } else if (coding[0]?.code) {
          summary.push({ label: "Code", value: coding[0].code });
        }
      }
    }

    // Extract from appointments bundle
    const appointments = context.appointments as
      | { entry?: Array<{ resource?: Record<string, unknown> }> }
      | undefined;
    if (appointments?.entry?.[0]?.resource) {
      const resource = appointments.entry[0].resource;
      if (resource.resourceType) {
        summary.push({
          label: "Resource Type",
          value: String(resource.resourceType),
        });
      }
    }
  }

  // Extract coverage subscriber from prefetch
  const prefetch = request.prefetch as Record<string, unknown> | undefined;
  if (prefetch) {
    const coverageBundle = prefetch.coverage as
      | { entry?: Array<{ resource?: Record<string, unknown> }> }
      | undefined;
    const coverage = coverageBundle?.entry?.[0]?.resource;
    if (coverage?.subscriberId) {
      summary.push({
        label: "Subscriber",
        value: String(coverage.subscriberId),
      });
    }
  }

  return summary;
}

export function CrdRequestEditor({
  scenario,
  variant,
  requestJson,
  onRequestJsonChange,
  onExecute,
  onPreview,
  isExecuting,
}: CrdRequestEditorProps) {
  const handleExecute = useCallback(() => {
    const parsed = JSON.parse(requestJson) as CdsRequest;
    onExecute(parsed);
  }, [requestJson, onExecute]);

  const handlePreview = useCallback(() => {
    const parsed = JSON.parse(requestJson) as CdsRequest;
    onPreview(parsed);
  }, [requestJson, onPreview]);

  return (
    <RequestEditor
      scenarioName={scenario && variant ? scenario.name : null}
      headerDescription={
        variant && (
          <Badge variant="outline" className="text-[10px]">
            {variant.hookName}
          </Badge>
        )
      }
      requestJson={requestJson}
      onRequestJsonChange={onRequestJsonChange}
      onExecute={handleExecute}
      onPreview={handlePreview}
      isExecuting={isExecuting}
      extractSummary={extractCrdSummary}
    />
  );
}
