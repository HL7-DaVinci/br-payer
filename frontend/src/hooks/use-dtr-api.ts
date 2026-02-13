import { useMutation } from "@tanstack/react-query";
import type {
  Bundle,
  OperationOutcome,
  Parameters,
  Questionnaire,
  QuestionnaireResponse,
  Resource,
} from "fhir/r4";
import type { DtrPackageResponse, ParsedPackageBundle } from "@/lib/dtr-types";
import { isOperationOutcome } from "@/lib/fhir-types";

// =============================================================================
// Error Types
// =============================================================================

export interface DtrError extends Error {
  status?: number;
  operationOutcome?: OperationOutcome;
  body?: unknown;
}

// =============================================================================
// Fetch Helper
// =============================================================================

async function dtrFetch<T>(url: string, body: unknown): Promise<T> {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      Accept: "application/fhir+json",
      "Content-Type": "application/fhir+json",
    },
    body: JSON.stringify(body),
  });

  const responseBody = await response.json();

  if (!response.ok) {
    const error: DtrError = new Error(
      `DTR request failed: ${response.status} ${response.statusText}`,
    );
    error.status = response.status;
    error.body = responseBody;

    if (isOperationOutcome(responseBody)) {
      error.operationOutcome = responseBody;
      const firstIssue = responseBody.issue?.[0];
      if (firstIssue?.diagnostics) {
        error.message = firstIssue.diagnostics;
      } else if (firstIssue?.details?.text) {
        error.message = firstIssue.details.text;
      }
    }

    throw error;
  }

  return responseBody as T;
}

// =============================================================================
// Response Parsers
// =============================================================================

const ADAPTIVE_EXTENSION_URL =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive";

function isAdaptiveQuestionnaire(questionnaire: Questionnaire): boolean {
  return (
    questionnaire.extension?.some(
      (ext) => ext.url === ADAPTIVE_EXTENSION_URL,
    ) ?? false
  );
}

export function parsePackageBundle(bundle: Bundle): ParsedPackageBundle {
  let questionnaire: Questionnaire | null = null;
  let questionnaireResponse: QuestionnaireResponse | null = null;
  const libraries: Resource[] = [];
  const valueSets: Resource[] = [];

  for (const entry of bundle.entry ?? []) {
    const resource = entry.resource;
    if (!resource) continue;

    switch (resource.resourceType) {
      case "Questionnaire":
        questionnaire = resource as Questionnaire;
        break;
      case "QuestionnaireResponse":
        questionnaireResponse = resource as QuestionnaireResponse;
        break;
      case "Library":
        libraries.push(resource);
        break;
      case "ValueSet":
        valueSets.push(resource);
        break;
    }
  }

  // For adaptive questionnaires, check the contained Questionnaire in the QR
  let isAdaptive = false;
  if (questionnaire) {
    isAdaptive = isAdaptiveQuestionnaire(questionnaire);
  }
  if (!isAdaptive && questionnaireResponse?.contained) {
    const containedQ = questionnaireResponse.contained.find(
      (r) => r.resourceType === "Questionnaire",
    ) as Questionnaire | undefined;
    if (containedQ) {
      isAdaptive = isAdaptiveQuestionnaire(containedQ);
    }
  }

  return {
    questionnaire,
    questionnaireResponse,
    libraries,
    valueSets,
    isAdaptive,
    rawBundle: bundle,
  };
}

export function parsePackageResponse(params: Parameters): DtrPackageResponse {
  const packageBundles: ParsedPackageBundle[] = [];
  let outcome: OperationOutcome | null = null;

  for (const param of params.parameter ?? []) {
    if (param.name === "packagebundle" && param.resource) {
      packageBundles.push(parsePackageBundle(param.resource as Bundle));
    }
    if (param.name === "outcome" && param.resource) {
      if (isOperationOutcome(param.resource)) {
        outcome = param.resource;
      }
    }
  }

  return { packageBundles, outcome, rawParameters: params };
}

// =============================================================================
// Mutations
// =============================================================================

interface QuestionnairePackageParams {
  serverUrl: string;
  parameters: Parameters;
}

export function useQuestionnairePackage() {
  return useMutation({
    mutationFn: async ({
      serverUrl,
      parameters,
    }: QuestionnairePackageParams): Promise<DtrPackageResponse> => {
      const result = await dtrFetch<Parameters>(
        `${serverUrl}/Questionnaire/$questionnaire-package`,
        parameters,
      );
      return parsePackageResponse(result);
    },
  });
}

interface NextQuestionParams {
  serverUrl: string;
  questionnaireResponse: QuestionnaireResponse;
}

export function useNextQuestion() {
  return useMutation({
    mutationFn: async ({
      serverUrl,
      questionnaireResponse,
    }: NextQuestionParams): Promise<Parameters> => {
      const parameters: Parameters = {
        resourceType: "Parameters",
        meta: {
          profile: [
            "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-next-question-input-parameters",
          ],
        },
        parameter: [
          {
            name: "questionnaire-response",
            resource: questionnaireResponse,
          },
        ],
      };
      return dtrFetch<Parameters>(
        `${serverUrl}/Questionnaire/$next-question`,
        parameters,
      );
    },
  });
}
