import type { Parameters } from "fhir/r4";
import { useCallback } from "react";
import {
  RequestEditor,
  type SummaryItem,
} from "@/components/shared/request-editor";
import type { DtrRequestVariant, DtrScenario } from "@/lib/dtr-types";

interface DtrRequestEditorProps {
  scenario: DtrScenario | null;
  variant: DtrRequestVariant | null;
  requestJson: string;
  onRequestJsonChange: (json: string) => void;
  onExecute: (parameters: Parameters) => void;
  onPreview: (parameters: Parameters) => void;
  isExecuting: boolean;
}

function extractDtrSummary(raw: Record<string, unknown>): SummaryItem[] {
  const params = raw as unknown as Parameters;
  const summary: SummaryItem[] = [];

  for (const param of params.parameter ?? []) {
    if (param.name === "coverage" && param.resource) {
      const coverage = param.resource as unknown as Record<string, unknown>;
      if (coverage.subscriberId) {
        summary.push({
          label: "Subscriber",
          value: String(coverage.subscriberId),
        });
      }
      const contained = coverage.contained as
        | Array<Record<string, unknown>>
        | undefined;
      const payor = contained?.find((r) => r.resourceType === "Organization");
      if (payor?.name) {
        summary.push({ label: "Payor", value: String(payor.name) });
      }
      const beneficiary = coverage.beneficiary as
        | { reference?: string }
        | undefined;
      if (beneficiary?.reference) {
        summary.push({ label: "Beneficiary", value: beneficiary.reference });
      }
    }
    if (param.name === "questionnaire" && param.valueCanonical) {
      summary.push({ label: "Questionnaire", value: param.valueCanonical });
    }
    if (param.name === "order" && param.resource) {
      const order = param.resource as unknown as Record<string, unknown>;
      summary.push({ label: "Order Type", value: String(order.resourceType) });
      const code =
        (order.codeCodeableConcept as Record<string, unknown>) ??
        (order.medicationCodeableConcept as Record<string, unknown>);
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
  }

  return summary;
}

export function DtrRequestEditor({
  scenario,
  variant,
  requestJson,
  onRequestJsonChange,
  onExecute,
  onPreview,
  isExecuting,
}: DtrRequestEditorProps) {
  const handleExecute = useCallback(() => {
    const parsed = JSON.parse(requestJson) as Parameters;
    onExecute(parsed);
  }, [requestJson, onExecute]);

  const handlePreview = useCallback(() => {
    const parsed = JSON.parse(requestJson) as Parameters;
    onPreview(parsed);
  }, [requestJson, onPreview]);

  return (
    <RequestEditor
      scenarioName={scenario && variant ? scenario.name : null}
      headerDescription={variant ? variant.label : undefined}
      requestJson={requestJson}
      onRequestJsonChange={onRequestJsonChange}
      onExecute={handleExecute}
      onPreview={handlePreview}
      isExecuting={isExecuting}
      extractSummary={extractDtrSummary}
    />
  );
}
