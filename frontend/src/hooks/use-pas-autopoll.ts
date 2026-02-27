import { useEffect, useRef } from "react";
import { inquireFetch } from "@/hooks/use-pas-api";
import type {
  AutoPollConfig,
  PasError,
  ReviewActionCode,
  TimelineEntry,
} from "@/lib/pas-types";
import {
  extractAdminRefNumber,
  extractAllItemReviewActions,
  extractAuthorizationNumber,
  extractClaimResponseFromBundle,
  extractPreAuthPeriod,
  extractResponseBundlesFromParameters,
  extractReviewAction,
  findResponseBundleByClaimResponseId,
} from "@/lib/pas-types";

interface UseAutoPollOptions {
  serverUrl: string;
  config: AutoPollConfig;
  pendedAuthorizationIds: string[];
  /** Returns the inquiry variant bundle for a given authorization ID */
  getInquiryBundle: (authId: string) => object | null;
  onResult: (entry: TimelineEntry) => void;
}

/**
 * Interval-based polling loop that fires $inquire for pended authorizations
 * and feeds results into the timeline via onResult.
 */
export function usePasAutoPoll({
  serverUrl,
  config,
  pendedAuthorizationIds,
  getInquiryBundle,
  onResult,
}: UseAutoPollOptions): void {
  const isPollingRef = useRef(false);

  // Stable refs to avoid stale closures in the interval callback
  const pendedIdsRef = useRef(pendedAuthorizationIds);
  pendedIdsRef.current = pendedAuthorizationIds;

  const getInquiryBundleRef = useRef(getInquiryBundle);
  getInquiryBundleRef.current = getInquiryBundle;

  const onResultRef = useRef(onResult);
  onResultRef.current = onResult;

  const serverUrlRef = useRef(serverUrl);
  serverUrlRef.current = serverUrl;

  useEffect(() => {
    if (!config.enabled || config.intervalSeconds < 5) return;

    const intervalMs = config.intervalSeconds * 1000;

    const poll = async () => {
      if (isPollingRef.current) return;
      isPollingRef.current = true;

      try {
        const ids = [...pendedIdsRef.current];
        // Process sequentially to avoid server overload
        for (const authId of ids) {
          const bundle = getInquiryBundleRef.current(authId);
          if (!bundle) continue;

          const startTime = performance.now();
          try {
            const params = await inquireFetch(serverUrlRef.current, bundle);
            const durationMs = Math.round(performance.now() - startTime);

            const responseBundles =
              extractResponseBundlesFromParameters(params);
            const matchedBundle = findResponseBundleByClaimResponseId(
              responseBundles,
              authId,
            );
            const cr = matchedBundle
              ? extractClaimResponseFromBundle(matchedBundle)
              : null;

            const reviewAction: ReviewActionCode | null = cr
              ? extractReviewAction(cr)
              : null;

            const entry: TimelineEntry = {
              id: crypto.randomUUID(),
              timestamp: new Date(),
              source: "auto-poll",
              operation: "$inquire",
              payloadType: "inquiry",
              requestBundle: bundle,
              responseData: params,
              error: null,
              authorizationId: authId,
              reviewAction,
              authorizationNumber: cr ? extractAuthorizationNumber(cr) : null,
              adminRefNumber: cr ? extractAdminRefNumber(cr) : null,
              preAuthPeriod: cr ? extractPreAuthPeriod(cr) : null,
              itemReviewActions: cr ? extractAllItemReviewActions(cr) : null,
              durationMs,
            };

            onResultRef.current(entry);
          } catch (err) {
            const durationMs = Math.round(performance.now() - startTime);
            const pasError: PasError =
              err instanceof Error
                ? Object.assign(new Error(err.message), {
                    status: (err as PasError).status,
                    body: (err as PasError).body,
                    stack: err.stack,
                  })
                : new Error(String(err));

            const entry: TimelineEntry = {
              id: crypto.randomUUID(),
              timestamp: new Date(),
              source: "auto-poll",
              operation: "$inquire",
              payloadType: "inquiry",
              requestBundle: bundle,
              responseData: (pasError.body as object) ?? null,
              error: pasError,
              authorizationId: authId,
              reviewAction: null,
              authorizationNumber: null,
              adminRefNumber: null,
              preAuthPeriod: null,
              itemReviewActions: null,
              durationMs,
            };

            onResultRef.current(entry);
          }
        }
      } finally {
        isPollingRef.current = false;
      }
    };

    const intervalId = setInterval(poll, intervalMs);
    return () => clearInterval(intervalId);
  }, [config.enabled, config.intervalSeconds]);
}
