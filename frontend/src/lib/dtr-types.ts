import type {
  Bundle,
  OperationOutcome,
  Parameters,
  Questionnaire,
  QuestionnaireResponse,
  Resource,
} from "fhir/r4";

export type DtrWorkflowStep = "configure" | "response" | "questionnaire";

export interface DtrScenario {
  id: string;
  name: string;
  description: string;
  orderType: string;
  isAdaptive: boolean;
  variants: DtrRequestVariant[];
}

export interface DtrRequestVariant {
  id: string;
  label: string;
  pathType: "canonical" | "order" | "combined";
  parameters: Parameters;
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
