import type { DtrScenario } from "@/lib/dtr-types";
import { useScenarios } from "./use-scenarios";

/**
 * Fetches DTR test scenarios from the payer server. Scenarios are derived
 * at startup from the loaded PlanDefinition and Questionnaire resources,
 * so they stay in sync with the library without manual maintenance.
 */
export function useDtrScenarios(serverUrl: string) {
  const baseUrl = serverUrl.replace(/\/fhir$/, "");

  return useScenarios<DtrScenario>({
    queryKeyPrefix: "dtr-scenarios",
    baseUrl,
    endpointPath: "/api/dtr/scenarios",
  });
}
