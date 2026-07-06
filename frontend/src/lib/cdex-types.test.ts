import { describe, expect, it } from "vitest";
import {
  buildCdexTask,
  type DocumentationRequest,
  type PendedClaim,
  pendedClaimLabel,
} from "./cdex-types";

const claim: PendedClaim = {
  claimResponseId: "cr-1",
  trackingIdSystem: "http://example.org/PATIENT_EVENT_TRACE_NUMBER",
  trackingIdValue: "ACN-1",
  patientReference: "Patient/p1",
  patientDisplay: "Cdex Demo",
  memberId: "M-1",
  created: "2026-07-01T00:00:00Z",
  items: [{ sequence: 1, reviewActionCode: "A4" }],
  documentationRequests: [],
};

const questionnaireRequest: DocumentationRequest = {
  communicationRequestId: "comm-1",
  type: "questionnaire",
  code: null,
  questionnaireCanonical: "http://example.org/Questionnaire/HomeOxygenTherapy",
  questionnaireName: "Home Oxygen Therapy",
  trn: "q-hot-1",
  lineNumber: 1,
  status: "active",
};

describe("buildCdexTask", () => {
  it("builds an IG-shaped attachment-request Task for a questionnaire request", () => {
    const task = buildCdexTask(
      claim,
      questionnaireRequest,
      "requested",
      "http://payer.example/fhir",
    );

    expect(task.resourceType).toBe("Task");
    expect(task.status).toBe("requested");
    expect(task.intent).toBe("order");
    expect(task.code?.coding?.[0]?.code).toBe(
      "attachment-request-questionnaire",
    );
    expect(task.identifier?.[0]?.value).toBe("ACN-1");
    expect(task.reasonCode?.coding?.[0]?.code).toBe("preauthorization");
    expect(task.for?.reference).toBe("Patient/p1");

    const inputTypes = (task.input ?? []).map((i) => i.type?.coding?.[0]?.code);
    expect(inputTypes).toContain("questionnaire-context");
    expect(inputTypes).toContain("payer-url");
    const questionnaireInput = (task.input ?? []).find(
      (i) => i.type?.coding?.[0]?.code === "questionnaire-context",
    );
    expect(questionnaireInput?.valueCanonical).toBe(
      "http://example.org/Questionnaire/HomeOxygenTherapy",
    );
  });

  it("uses the attachment-request-code Task code and attachments-needed input for code requests", () => {
    const codeRequest: DocumentationRequest = {
      ...questionnaireRequest,
      type: "attachment-code",
      code: "18748-4",
      questionnaireCanonical: null,
      questionnaireName: null,
    };
    const task = buildCdexTask(
      claim,
      codeRequest,
      "completed",
      "http://payer.example/fhir",
    );

    expect(task.status).toBe("completed");
    expect(task.code?.coding?.[0]?.code).toBe("attachment-request-code");
    const needed = (task.input ?? []).find(
      (i) => i.type?.coding?.[0]?.code === "attachments-needed",
    );
    expect(needed?.valueCodeableConcept?.coding?.[0]?.code).toBe("18748-4");
  });
});

describe("pendedClaimLabel", () => {
  it("labels by the requested questionnaire name", () => {
    const labeled: PendedClaim = {
      ...claim,
      documentationRequests: [questionnaireRequest],
    };
    expect(pendedClaimLabel(labeled)).toBe("Home Oxygen Therapy");
  });

  it("summarizes multiple requests and falls back to identifiers", () => {
    const multi: PendedClaim = {
      ...claim,
      documentationRequests: [
        questionnaireRequest,
        { ...questionnaireRequest, type: "attachment-code", code: "18748-4" },
      ],
    };
    expect(pendedClaimLabel(multi)).toBe("Home Oxygen Therapy +1 more");
    expect(pendedClaimLabel(claim)).toBe("ACN-1");
  });
});
