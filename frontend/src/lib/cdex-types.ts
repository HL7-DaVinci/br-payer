import type { OperationOutcome, Task } from "fhir/r4";

// =============================================================================
// DTOs mirrored from CdexScenarioService (server)
// =============================================================================

export interface PendedItem {
  sequence: number;
  reviewActionCode: string | null;
}

export interface DocumentationRequest {
  communicationRequestId: string;
  type: "questionnaire" | "attachment-code";
  code: string | null;
  questionnaireCanonical: string | null;
  questionnaireName: string | null;
  trn: string | null;
  lineNumber: number | null;
  status: string | null;
}

export interface PendedClaim {
  claimResponseId: string;
  trackingIdSystem: string | null;
  trackingIdValue: string | null;
  patientReference: string | null;
  patientDisplay: string | null;
  memberId: string | null;
  created: string | null;
  items: PendedItem[];
  documentationRequests: DocumentationRequest[];
}

/** Short human-readable label for a documentation request. */
export function documentationRequestLabel(
  request: DocumentationRequest,
): string {
  if (request.type === "questionnaire") {
    return (
      request.questionnaireName ??
      request.questionnaireCanonical ??
      "Questionnaire"
    );
  }
  return `Attachment ${request.code ?? "(uncoded)"}`;
}

/**
 * Human-readable label for a pended claim, favoring what is being requested
 * over generated identifiers.
 */
export function pendedClaimLabel(claim: PendedClaim): string {
  const requests = claim.documentationRequests;
  if (requests.length === 0) {
    return claim.trackingIdValue ?? claim.claimResponseId;
  }
  const first = documentationRequestLabel(requests[0]);
  return requests.length > 1 ? `${first} +${requests.length - 1} more` : first;
}

export interface CdexError extends Error {
  status?: number;
  operationOutcome?: OperationOutcome;
  body?: unknown;
}

export type CdexTaskStatus = "requested" | "in-progress" | "completed";

// =============================================================================
// Provider-local CDex Task visualization
// =============================================================================

const CDEX_TEMP_SYSTEM =
  "http://hl7.org/fhir/us/davinci-cdex/CodeSystem/cdex-temp";
const CDEX_TASK_ATTACHMENT_REQUEST_PROFILE =
  "http://hl7.org/fhir/us/davinci-cdex/StructureDefinition/cdex-task-attachment-request";
const PURPOSE_OF_USE_SYSTEM =
  "http://terminology.hl7.org/CodeSystem/v3-ActReason";

/**
 * Builds the CDex Task Attachment Request that a provider system would
 * construct locally from a PAS CommunicationRequest, per the CDex IG
 * "Requesting Attachments" workflow. The payer server never stores this Task;
 * it exists purely to visualize the provider-side view of the workflow.
 */
export function buildCdexTask(
  claim: PendedClaim,
  request: DocumentationRequest,
  status: CdexTaskStatus,
  payerUrl: string,
): Task {
  const isQuestionnaire = request.type === "questionnaire";

  const input: NonNullable<Task["input"]> = [
    {
      type: {
        coding: [{ system: CDEX_TEMP_SYSTEM, code: "payer-url" }],
      },
      valueUrl: payerUrl,
    },
    {
      type: {
        coding: [{ system: CDEX_TEMP_SYSTEM, code: "purpose-of-use" }],
      },
      valueCodeableConcept: {
        coding: [{ system: PURPOSE_OF_USE_SYSTEM, code: "COVAUTH" }],
      },
    },
  ];

  if (isQuestionnaire && request.questionnaireCanonical) {
    input.push({
      type: {
        coding: [{ system: CDEX_TEMP_SYSTEM, code: "questionnaire-context" }],
      },
      valueCanonical: request.questionnaireCanonical,
    });
  } else if (request.code) {
    input.push({
      type: {
        coding: [{ system: CDEX_TEMP_SYSTEM, code: "attachments-needed" }],
      },
      valueCodeableConcept: {
        coding: [{ system: "http://loinc.org", code: request.code }],
      },
    });
  }

  return {
    resourceType: "Task",
    meta: { profile: [CDEX_TASK_ATTACHMENT_REQUEST_PROFILE] },
    identifier: [
      {
        type: {
          coding: [{ system: CDEX_TEMP_SYSTEM, code: "tracking-id" }],
        },
        system: claim.trackingIdSystem ?? undefined,
        value: claim.trackingIdValue ?? undefined,
      },
    ],
    status,
    intent: "order",
    code: {
      coding: [
        {
          system: CDEX_TEMP_SYSTEM,
          code: isQuestionnaire
            ? "attachment-request-questionnaire"
            : "attachment-request-code",
        },
      ],
    },
    for: claim.patientReference
      ? { reference: claim.patientReference }
      : undefined,
    reasonCode: {
      coding: [{ system: CDEX_TEMP_SYSTEM, code: "preauthorization" }],
    },
    reasonReference: {
      identifier: {
        system: claim.trackingIdSystem ?? undefined,
        value: claim.trackingIdValue ?? undefined,
      },
    },
    input,
  };
}
