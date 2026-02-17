import { useQuery } from "@tanstack/react-query";
import type { CrdScenario } from "@/lib/crd-types";

/**
 * Fetches CRD test scenarios from the payer server. Scenarios are derived
 * at startup from the loaded PlanDefinition resources, so they stay in
 * sync with the library without manual maintenance.
 */
export function useCrdScenarios(cdsServerUrl: string) {
  return useQuery<CrdScenario[]>({
    queryKey: ["crd-scenarios", cdsServerUrl],
    queryFn: async () => {
      const response = await fetch(`${cdsServerUrl}/api/crd/scenarios`);
      if (!response.ok) {
        throw new Error(`Failed to load scenarios: ${response.status}`);
      }
      return response.json();
    },
    staleTime: 10 * 60 * 1000,
  });
}
