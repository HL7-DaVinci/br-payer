import type {
  Bundle,
  Coverage,
  FhirResource,
  OperationOutcome,
  Parameters,
  ParametersParameter,
  Questionnaire,
  QuestionnaireResponse,
  Resource,
} from "fhir/r4";

export type DtrWorkflowStep = "configure" | "response" | "questionnaire";

export type DtrMode = "scenarios" | "manual";

export interface DtrScenario {
  id: string;
  name: string;
  description: string;
  orderType: string;
  isAdaptive: boolean;
  isAdaptiveSearch: boolean;
  variants: DtrRequestVariant[];
}

export interface DtrRequestVariant {
  id: string;
  label: string;
  pathType: "canonical" | "order" | "combined";
  parameters: Parameters;
  headers?: Record<string, string>;
}

export interface DtrPackageResponse {
  packageBundles: ParsedPackageBundle[];
  outcome: OperationOutcome | null;
  rawParameters: Parameters;
}

export interface ParsedPackageBundle {
  questionnaire: Questionnaire | null;
  questionnaireResponse: QuestionnaireResponse | null;
  libraries: Resource[];
  valueSets: Resource[];
  isAdaptive: boolean;
  rawBundle: Bundle;
}

// =============================================================================
// Manual Mode Parameters Builder
// =============================================================================

const DTR_PACKAGE_INPUT_PROFILE =
  "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-qpackage-input-parameters";

/**
 * Assemble a FHIR Parameters resource for $questionnaire-package.
 * Follows the DTR IG input parameters profile.
 */
export function buildDtrParameters(
  coverage: Coverage,
  canonicalUrls: string[],
  orders: FhirResource[],
): Parameters {
  const parameter: ParametersParameter[] = [
    { name: "coverage", resource: coverage },
  ];

  for (const url of canonicalUrls) {
    parameter.push({ name: "questionnaire", valueCanonical: url });
  }

  for (const order of orders) {
    parameter.push({ name: "order", resource: order });
  }

  return {
    resourceType: "Parameters",
    meta: { profile: [DTR_PACKAGE_INPUT_PROFILE] },
    parameter,
  };
}

/**
 * Validate that minimum parameters are met for $questionnaire-package.
 * Requires coverage and at least one of questionnaire or order.
 */
export function validateDtrParameters(
  coverage: Coverage | null,
  canonicalUrls: string[],
  orders: FhirResource[],
): {
  isValid: boolean;
  hasCoverage: boolean;
  hasQuestionnaireOrOrder: boolean;
} {
  const hasCoverage = coverage !== null;
  const hasQuestionnaireOrOrder = canonicalUrls.length > 0 || orders.length > 0;
  return {
    isValid: hasCoverage && hasQuestionnaireOrOrder,
    hasCoverage,
    hasQuestionnaireOrOrder,
  };
}
