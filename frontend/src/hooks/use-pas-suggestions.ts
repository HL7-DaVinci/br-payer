import { useMemo } from "react";
import type {
  AuthorizationGroup,
  PasScenario,
  SuggestedOperation,
} from "@/lib/pas-types";

/**
 * Derives context-aware suggestions for the next PAS operation based on
 * the most recent authorization group's review action.
 */
export function usePasSuggestions(
  authorizationGroups: AuthorizationGroup[],
  selectedScenario: PasScenario | null,
): SuggestedOperation[] {
  return useMemo(() => {
    if (!selectedScenario || authorizationGroups.length === 0) return [];

    // Use the group with the newest timeline entry, not array position.
    let latestGroup: AuthorizationGroup | null = null;
    let latestTimestamp = Number.NEGATIVE_INFINITY;
    for (const group of authorizationGroups) {
      const groupLatestTimestamp = group.entries.reduce(
        (max, entry) => Math.max(max, entry.timestamp.getTime()),
        Number.NEGATIVE_INFINITY,
      );
      if (groupLatestTimestamp > latestTimestamp) {
        latestTimestamp = groupLatestTimestamp;
        latestGroup = group;
      }
    }

    if (!latestGroup?.currentReviewAction) return [];

    switch (latestGroup.currentReviewAction) {
      case "A4":
        return [
          {
            operation: "$inquire",
            payloadType: "inquiry",
            reason: "Check authorization status",
          },
        ];
      case "A1":
        return [
          {
            operation: "$submit",
            payloadType: "renewal",
            reason: "Renew authorization",
          },
          {
            operation: "$submit",
            payloadType: "cancel",
            reason: "Cancel authorization",
          },
        ];
      case "A2":
        return [
          {
            operation: "$submit",
            payloadType: "update",
            reason: "Resubmit with updated information",
          },
        ];
      case "A6":
        return [
          {
            operation: "$submit",
            payloadType: "update",
            reason: "Update authorization details",
          },
          {
            operation: "$submit",
            payloadType: "cancel",
            reason: "Cancel authorization",
          },
        ];
      case "A3":
        return [];
      default:
        return [];
    }
  }, [authorizationGroups, selectedScenario]);
}
