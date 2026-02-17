import { useQuery } from "@tanstack/react-query";

/**
 * Generic hook for fetching test scenarios from a payer server endpoint.
 * Both CRD and DTR scenarios share the same fetch-and-cache pattern;
 * they differ only in the base URL derivation and endpoint path.
 */
export function useScenarios<T>(options: {
  queryKeyPrefix: string;
  baseUrl: string;
  endpointPath: string;
}) {
  return useQuery<T[]>({
    queryKey: [options.queryKeyPrefix, options.baseUrl],
    queryFn: async () => {
      const response = await fetch(`${options.baseUrl}${options.endpointPath}`);
      if (!response.ok) {
        throw new Error(`Failed to load scenarios: ${response.status}`);
      }
      return response.json();
    },
    staleTime: 10 * 60 * 1000,
  });
}
