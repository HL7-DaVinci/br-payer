import {
  RequestEditor,
  type SummaryItem,
} from "@/components/shared/request-editor";
import { Badge } from "@/components/ui/badge";
import type { PasScenario, PasVariant } from "@/lib/pas-types";

interface PasRequestEditorProps {
  scenario: PasScenario | null;
  variant: PasVariant | null;
  requestJson: string;
  onRequestJsonChange: (json: string) => void;
  onExecute: () => void;
  onPreview: () => void;
  isExecuting: boolean;
}

interface BundleEntry {
  resource?: {
    resourceType?: string;
    type?: { coding?: Array<{ code?: string; display?: string }> };
    patient?: { reference?: string };
    provider?: { reference?: string };
    insurer?: { reference?: string };
    item?: Array<{
      productOrService?: {
        coding?: Array<{ code?: string; display?: string }>;
      };
    }>;
    extension?: Array<{
      url?: string;
      valueCodeableConcept?: {
        coding?: Array<{ code?: string; display?: string }>;
      };
    }>;
  };
}

function extractPasSummary(raw: Record<string, unknown>): SummaryItem[] {
  const summary: SummaryItem[] = [];
  const entries = (raw.entry as BundleEntry[] | undefined) ?? [];

  const claim = entries.find(
    (e) => e.resource?.resourceType === "Claim",
  )?.resource;
  if (!claim) return summary;

  if (claim.type?.coding) {
    const display = claim.type.coding[0]?.display ?? claim.type.coding[0]?.code;
    if (display) summary.push({ label: "Claim Type", value: display });
  }

  if (claim.patient?.reference) {
    summary.push({ label: "Patient", value: claim.patient.reference });
  }

  if (claim.insurer?.reference) {
    summary.push({ label: "Insurer", value: claim.insurer.reference });
  }

  if (claim.provider?.reference) {
    summary.push({ label: "Provider", value: claim.provider.reference });
  }

  // Extract product/service codes from items
  const codes: string[] = [];
  for (const item of claim.item ?? []) {
    const coding = item.productOrService?.coding;
    if (coding?.[0]) {
      codes.push(coding[0].display ?? coding[0].code ?? "unknown");
    }
  }
  if (codes.length > 0) {
    summary.push({ label: "Items", value: codes.join(", ") });
  }

  // Certification type from Claim extensions
  const certTypeUrl =
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-certificationType";
  const certExt = claim.extension?.find((e) => e.url === certTypeUrl);
  if (certExt?.valueCodeableConcept?.coding?.[0]) {
    const coding = certExt.valueCodeableConcept.coding[0];
    summary.push({
      label: "Certification Type",
      value: coding.display ?? coding.code ?? "unknown",
    });
  }

  return summary;
}

export function PasRequestEditor({
  scenario,
  variant,
  requestJson,
  onRequestJsonChange,
  onExecute,
  onPreview,
  isExecuting,
}: PasRequestEditorProps) {
  const headerDescription = variant ? (
    <span className="flex items-center gap-2">
      <Badge variant="outline" className="text-[10px] font-mono px-1.5 py-0">
        {variant.operation}
      </Badge>
      <span className="capitalize">{variant.payloadType}</span>
    </span>
  ) : undefined;

  return (
    <RequestEditor
      scenarioName={scenario && variant ? scenario.name : null}
      headerDescription={headerDescription}
      requestJson={requestJson}
      onRequestJsonChange={onRequestJsonChange}
      onExecute={onExecute}
      onPreview={onPreview}
      isExecuting={isExecuting}
      extractSummary={extractPasSummary}
      collapsible
    />
  );
}
