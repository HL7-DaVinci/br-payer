import type { PasScenario } from "@/lib/pas-types";
import { useScenarios } from "./use-scenarios";

/**
 * Fetches PAS test scenarios from the payer server. Scenarios are derived
 * at startup from PlanDefinition resources and include 5 variants each
 * (initial, renewal, update, cancel, inquiry).
 */
export function usePasScenarios(serverUrl: string) {
  const baseUrl = serverUrl.replace(/\/fhir$/, "");

  return useScenarios<PasScenario>({
    queryKeyPrefix: "pas-scenarios",
    baseUrl,
    endpointPath: "/api/pas/scenarios",
  });
}
