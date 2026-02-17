import type { CrdScenario } from "@/lib/crd-types";
import { useScenarios } from "./use-scenarios";

/**
 * Fetches CRD test scenarios from the payer server. Scenarios are derived
 * at startup from the loaded PlanDefinition resources, so they stay in
 * sync with the library without manual maintenance.
 */
export function useCrdScenarios(cdsServerUrl: string) {
  return useScenarios<CrdScenario>({
    queryKeyPrefix: "crd-scenarios",
    baseUrl: cdsServerUrl,
    endpointPath: "/api/crd/scenarios",
  });
}
