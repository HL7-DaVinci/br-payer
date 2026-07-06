import { useMutation, useQuery } from "@tanstack/react-query";
import type { ClaimResponse, OperationOutcome, Parameters } from "fhir/r4";
import type { PendedClaim } from "@/lib/cdex-types";
import { pasFetch } from "./use-pas-api";

function toBaseUrl(serverUrl: string): string {
  return serverUrl.replace(/\/fhir$/, "");
}

/**
 * Lists pended ClaimResponses awaiting documentation from the payer server.
 * Live data (not build-time scenarios), so callers invalidate/refetch after
 * any action that changes server state.
 */
export function usePendedClaims(serverUrl: string) {
  const baseUrl = toBaseUrl(serverUrl);
  return useQuery<PendedClaim[]>({
    queryKey: ["cdex-pended", baseUrl],
    queryFn: async () => {
      const response = await fetch(`${baseUrl}/api/cdex/pended`);
      if (!response.ok) {
        throw new Error(`Failed to load pended claims: ${response.status}`);
      }
      return response.json();
    },
    staleTime: 0,
  });
}

/** Fetches a server-generated $submit-attachment Parameters payload. */
export async function fetchGeneratedSubmitAttachment(
  serverUrl: string,
  claimResponseId: string,
  options?: { trns?: string[]; final?: boolean },
): Promise<Parameters> {
  const query = new URLSearchParams();
  for (const trn of options?.trns ?? []) {
    query.append("trn", trn);
  }
  if (options?.final !== undefined) {
    query.set("final", String(options.final));
  }
  const suffix = query.size > 0 ? `?${query.toString()}` : "";
  const response = await fetch(
    `${toBaseUrl(serverUrl)}/api/cdex/pended/${claimResponseId}/submit-attachment${suffix}`,
  );
  if (!response.ok) {
    throw new Error(`Failed to generate payload: ${response.status}`);
  }
  return response.json();
}

interface SubmitAttachmentParams {
  serverUrl: string;
  parameters: object;
}

/** POST /fhir/$submit-attachment with a CDex Parameters payload. */
export function useSubmitAttachment() {
  return useMutation({
    mutationFn: async ({
      serverUrl,
      parameters,
    }: SubmitAttachmentParams): Promise<OperationOutcome> =>
      pasFetch<OperationOutcome>(`${serverUrl}/$submit-attachment`, parameters),
  });
}

/** Reads a ClaimResponse to observe re-adjudication after $submit-attachment. */
export async function fetchClaimResponse(
  serverUrl: string,
  id: string,
): Promise<ClaimResponse> {
  const response = await fetch(`${serverUrl}/ClaimResponse/${id}`, {
    headers: { Accept: "application/fhir+json" },
  });
  if (!response.ok) {
    throw new Error(`Failed to read ClaimResponse/${id}: ${response.status}`);
  }
  return response.json();
}
