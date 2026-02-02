import { useMutation, useQueries, useQuery } from "@tanstack/react-query";
import type { Bundle, OperationOutcome, Resource } from "fhir/r4";
import { useMemo } from "react";
import type {
  CdsDiscoveryResponse,
  CdsRequest,
  CdsResponse,
  CdsService,
} from "@/lib/cds-types";
import { buildPrefetchUrl, generateHookInstance } from "@/lib/cds-types";

// =============================================================================
// Error Types
// =============================================================================

export interface CdsError extends Error {
  status?: number;
  operationOutcome?: OperationOutcome;
  body?: unknown;
}

/** Type guard to check if response body is an OperationOutcome */
function isOperationOutcome(body: unknown): body is OperationOutcome {
  return (
    typeof body === "object" &&
    body !== null &&
    "resourceType" in body &&
    (body as Record<string, unknown>).resourceType === "OperationOutcome"
  );
}

// =============================================================================
// Fetch Helpers
// =============================================================================

async function cdsFetch<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...options,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...options?.headers,
    },
  });

  if (!response.ok) {
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      // Ignore JSON parse errors
    }

    const error: CdsError = new Error(
      `CDS request failed: ${response.status} ${response.statusText}`,
    );
    error.status = response.status;
    error.body = body;

    // Extract OperationOutcome for better error display
    if (isOperationOutcome(body)) {
      error.operationOutcome = body;
      // Use first issue diagnostic as error message if available
      const firstIssue = body.issue?.[0];
      if (firstIssue?.diagnostics) {
        error.message = firstIssue.diagnostics;
      } else if (firstIssue?.details?.text) {
        error.message = firstIssue.details.text;
      }
    }

    throw error;
  }

  return response.json();
}

async function fhirFetchForPrefetch<T>(url: string): Promise<T> {
  const response = await fetch(url, {
    headers: {
      Accept: "application/fhir+json",
    },
  });

  if (!response.ok) {
    throw new Error(
      `Prefetch failed: ${response.status} ${response.statusText}`,
    );
  }

  return response.json();
}

// =============================================================================
// Discovery Hook
// =============================================================================

/**
 * Fetch available CDS services from discovery endpoint
 */
export function useCdsDiscovery(serverUrl: string) {
  return useQuery({
    queryKey: ["cds", "discovery", serverUrl],
    queryFn: () => cdsFetch<CdsDiscoveryResponse>(`${serverUrl}/cds-services`),
    staleTime: 5 * 60 * 1000, // 5 minutes
    retry: 1,
    enabled: !!serverUrl,
  });
}

// =============================================================================
// Service Call Hook
// =============================================================================

interface CallCdsServiceParams {
  cdsServerUrl: string;
  serviceId: string;
  request: CdsRequest;
}

/**
 * Call a CDS service (mutation)
 */
export function useCdsServiceCall() {
  return useMutation({
    mutationFn: async ({
      cdsServerUrl,
      serviceId,
      request,
    }: CallCdsServiceParams): Promise<CdsResponse> => {
      return cdsFetch<CdsResponse>(
        `${cdsServerUrl}/cds-services/${serviceId}`,
        {
          method: "POST",
          body: JSON.stringify(request),
        },
      );
    },
  });
}

// =============================================================================
// Prefetch Hooks
// =============================================================================

interface PrefetchResult {
  key: string;
  template: string;
  resolvedUrl: string;
  data: Resource | Bundle | null;
  isLoading: boolean;
  isError: boolean;
  error: Error | null;
}

/**
 * Execute all prefetch queries for a CDS service
 */
export function usePrefetchQueries(
  fhirServerUrl: string,
  prefetchTemplates: Record<string, string> | undefined,
  context: Record<string, unknown>,
): {
  prefetchResults: PrefetchResult[];
  prefetchData: Record<string, Resource | Bundle | null>;
  isLoading: boolean;
  isError: boolean;
} {
  const templates = useMemo(() => {
    if (!prefetchTemplates) return [];
    return Object.entries(prefetchTemplates).map(([key, template]) => ({
      key,
      template,
      resolvedUrl: buildPrefetchUrl(template, context),
    }));
  }, [prefetchTemplates, context]);

  const hasRequiredContext = useMemo(() => {
    // Check if we have at least patientId in context
    return !!context.patientId;
  }, [context]);

  const queries = useQueries({
    queries: templates.map(({ key, template, resolvedUrl }) => ({
      queryKey: ["cds", "prefetch", fhirServerUrl, key, resolvedUrl],
      queryFn: async (): Promise<Resource | Bundle> => {
        // Build full URL
        const fullUrl = resolvedUrl.startsWith("http")
          ? resolvedUrl
          : `${fhirServerUrl}/${resolvedUrl}`;
        return fhirFetchForPrefetch(fullUrl);
      },
      staleTime: 30 * 1000, // 30 seconds
      retry: 0,
      enabled: !!fhirServerUrl && hasRequiredContext && !!resolvedUrl,
      // Store template info in meta for later access
      meta: { key, template, resolvedUrl },
    })),
  });

  const prefetchResults: PrefetchResult[] = useMemo(() => {
    return templates.map((t, index) => ({
      key: t.key,
      template: t.template,
      resolvedUrl: t.resolvedUrl,
      data: queries[index]?.data ?? null,
      isLoading: queries[index]?.isLoading ?? false,
      isError: queries[index]?.isError ?? false,
      error: queries[index]?.error ?? null,
    }));
  }, [templates, queries]);

  const prefetchData: Record<string, Resource | Bundle | null> = useMemo(() => {
    const data: Record<string, Resource | Bundle | null> = {};
    for (const result of prefetchResults) {
      data[result.key] = result.data;
    }
    return data;
  }, [prefetchResults]);

  const isLoading = queries.some((q) => q.isLoading);
  const isError = queries.some((q) => q.isError);

  return {
    prefetchResults,
    prefetchData,
    isLoading,
    isError,
  };
}

// =============================================================================
// Request Building Helpers
// =============================================================================

/**
 * Build a complete CDS request object
 */
export function buildCdsRequest(
  service: CdsService,
  context: Record<string, unknown>,
  prefetchData: Record<string, Resource | Bundle | null>,
  fhirServerUrl?: string,
): CdsRequest {
  const request: CdsRequest = {
    hook: service.hook,
    hookInstance: generateHookInstance(),
    context,
  };

  if (fhirServerUrl) {
    request.fhirServer = fhirServerUrl;
  }

  // Only include prefetch data that was successfully fetched
  const validPrefetch: Record<string, Resource | Bundle> = {};
  for (const [key, value] of Object.entries(prefetchData)) {
    if (value !== null) {
      validPrefetch[key] = value;
    }
  }

  if (Object.keys(validPrefetch).length > 0) {
    request.prefetch = validPrefetch;
  }

  return request;
}

// =============================================================================
// Resource Search for Context Building
// =============================================================================

/**
 * Search for resources to use in hook context (e.g., find patients, encounters)
 */
export function useResourceSearch(
  fhirServerUrl: string,
  resourceType: string,
  searchParams: Record<string, string> = {},
  enabled = true,
) {
  const url = useMemo(() => {
    const params = new URLSearchParams();
    params.set("_count", "20");

    for (const [key, value] of Object.entries(searchParams)) {
      if (value) {
        params.set(key, value);
      }
    }

    return `${fhirServerUrl}/${resourceType}?${params.toString()}`;
  }, [fhirServerUrl, resourceType, searchParams]);

  return useQuery({
    queryKey: ["fhir", "search", fhirServerUrl, resourceType, searchParams],
    queryFn: () => fhirFetchForPrefetch<Bundle>(url),
    staleTime: 30 * 1000,
    retry: 1,
    enabled: enabled && !!fhirServerUrl && !!resourceType,
  });
}

// =============================================================================
// CDS Server Status
// =============================================================================

/**
 * Check CDS server connectivity
 */
export function useCdsServerStatus(serverUrl: string) {
  const query = useQuery({
    queryKey: ["cds", "status", serverUrl],
    queryFn: async () => {
      const start = Date.now();
      const discovery = await cdsFetch<CdsDiscoveryResponse>(
        `${serverUrl}/cds-services`,
      );
      const latency = Date.now() - start;
      return {
        connected: true,
        latency,
        serviceCount: discovery.services?.length ?? 0,
      };
    },
    staleTime: 30 * 1000,
    retry: 0,
    enabled: !!serverUrl,
  });

  return {
    ...query,
    isConnected: query.isSuccess,
    isDisconnected: query.isError,
    latency: query.data?.latency,
    serviceCount: query.data?.serviceCount,
  };
}
