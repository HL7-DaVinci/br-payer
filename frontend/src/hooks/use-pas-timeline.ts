import { useCallback, useMemo, useReducer } from "react";
import type {
  AuthorizationGroup,
  ReviewActionCode,
  TimelineEntry,
} from "@/lib/pas-types";

// =============================================================================
// Reducer
// =============================================================================

type TimelineAction =
  | { type: "ADD_ENTRY"; entry: TimelineEntry }
  | { type: "CLEAR_ALL" };

interface TimelineState {
  entries: TimelineEntry[];
}

const initialState: TimelineState = { entries: [] };

function timelineReducer(
  state: TimelineState,
  action: TimelineAction,
): TimelineState {
  switch (action.type) {
    case "ADD_ENTRY":
      return { entries: [...state.entries, action.entry] };
    case "CLEAR_ALL":
      return initialState;
  }
}

// =============================================================================
// Authorization Grouping
// =============================================================================

function buildAuthorizationGroups(
  entries: TimelineEntry[],
): AuthorizationGroup[] {
  const groupMap = new Map<string, TimelineEntry[]>();

  for (const entry of entries) {
    if (!entry.authorizationId) continue;
    const group = groupMap.get(entry.authorizationId);
    if (group) {
      group.push(entry);
    } else {
      groupMap.set(entry.authorizationId, [entry]);
    }
  }

  return Array.from(groupMap.entries()).map(
    ([authorizationId, groupEntries]) => {
      // Current review action is from the most recent non-error entry
      let currentReviewAction: ReviewActionCode | null = null;
      for (let i = groupEntries.length - 1; i >= 0; i--) {
        if (!groupEntries[i].error && groupEntries[i].reviewAction) {
          currentReviewAction = groupEntries[i].reviewAction;
          break;
        }
      }

      return {
        authorizationId,
        entries: groupEntries,
        currentReviewAction,
        isPended: currentReviewAction === "A4",
      };
    },
  );
}

// =============================================================================
// Hook
// =============================================================================

export function usePasTimeline() {
  const [state, dispatch] = useReducer(timelineReducer, initialState);

  const addEntry = useCallback((entry: TimelineEntry) => {
    dispatch({ type: "ADD_ENTRY", entry });
  }, []);

  const clearAll = useCallback(() => {
    dispatch({ type: "CLEAR_ALL" });
  }, []);

  const authorizationGroups = useMemo(
    () => buildAuthorizationGroups(state.entries),
    [state.entries],
  );

  const pendedAuthorizationIds = useMemo(
    () =>
      authorizationGroups
        .filter((g) => g.isPended)
        .map((g) => g.authorizationId),
    [authorizationGroups],
  );

  return {
    entries: state.entries,
    addEntry,
    clearAll,
    authorizationGroups,
    pendedAuthorizationIds,
  };
}
