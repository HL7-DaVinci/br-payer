import { useQuery } from "@tanstack/react-query";
import type { DtrScenario } from "@/lib/dtr-types";

/**
 * Fetches DTR test scenarios from the payer server. Scenarios are derived
 * at startup from the loaded PlanDefinition and Questionnaire resources,
 * so they stay in sync with the library without manual maintenance.
 */
export function useDtrScenarios(serverUrl: string) {
  const baseUrl = serverUrl.replace(/\/fhir$/, "");

  return useQuery<DtrScenario[]>({
    queryKey: ["dtr-scenarios", baseUrl],
    queryFn: async () => {
      const response = await fetch(`${baseUrl}/api/dtr/scenarios`);
      if (!response.ok) {
        throw new Error(`Failed to load scenarios: ${response.status}`);
      }
      return response.json();
    },
    staleTime: 10 * 60 * 1000,
  });
}
