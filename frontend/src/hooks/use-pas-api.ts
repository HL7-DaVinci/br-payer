import { useMutation } from "@tanstack/react-query";
import type { Bundle, OperationOutcome, Parameters } from "fhir/r4";
import { isOperationOutcome } from "@/lib/fhir-types";
import type { PasError } from "@/lib/pas-types";

// =============================================================================
// Fetch Helper
// =============================================================================

export async function pasFetch<T>(url: string, body: unknown): Promise<T> {
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
    const error: PasError = new Error(
      `PAS request failed: ${response.status} ${response.statusText}`,
    );
    error.status = response.status;
    error.body = responseBody;

    if (isOperationOutcome(responseBody)) {
      const outcome = responseBody as OperationOutcome;
      error.operationOutcome = outcome;
      const firstIssue = outcome.issue?.[0];
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
// Parameters Wrapper
// =============================================================================

function wrapBundleInParameters(bundle: object): Parameters {
  return {
    resourceType: "Parameters",
    parameter: [{ name: "resource", resource: bundle as Bundle }],
  };
}

// =============================================================================
// Mutations
// =============================================================================

interface PasSubmitParams {
  serverUrl: string;
  bundle: object;
}

/**
 * $submit: POST /fhir/Claim/$submit with Parameters wrapping the Bundle.
 * Returns the PAS Response Bundle.
 */
export function usePasSubmit() {
  return useMutation({
    mutationFn: async ({
      serverUrl,
      bundle,
    }: PasSubmitParams): Promise<Bundle> => {
      const parameters = wrapBundleInParameters(bundle);
      return pasFetch<Bundle>(`${serverUrl}/Claim/$submit`, parameters);
    },
  });
}

interface PasInquireParams {
  serverUrl: string;
  bundle: object;
}

/**
 * $inquire: POST /fhir/Claim/$inquire with Parameters wrapping the Bundle.
 * Returns Parameters containing responseBundle entries.
 */
export function usePasInquire() {
  return useMutation({
    mutationFn: async ({
      serverUrl,
      bundle,
    }: PasInquireParams): Promise<Parameters> => {
      const parameters = wrapBundleInParameters(bundle);
      return pasFetch<Parameters>(`${serverUrl}/Claim/$inquire`, parameters);
    },
  });
}

/**
 * Standalone fetch for auto-poll (not a hook -- called imperatively).
 * Wraps bundle in Parameters and POSTs to $inquire.
 */
export async function inquireFetch(
  serverUrl: string,
  bundle: object,
): Promise<Parameters> {
  const parameters = wrapBundleInParameters(bundle);
  return pasFetch<Parameters>(`${serverUrl}/Claim/$inquire`, parameters);
}
