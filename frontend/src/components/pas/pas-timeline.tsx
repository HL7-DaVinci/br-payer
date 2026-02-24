import { Clock, Trash2 } from "lucide-react";
import { PasTimelineEntry } from "@/components/pas/pas-timeline-entry";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { AuthorizationGroup, TimelineEntry } from "@/lib/pas-types";
import { REVIEW_ACTIONS } from "@/lib/pas-types";

interface PasTimelineProps {
  entries: TimelineEntry[];
  authorizationGroups: AuthorizationGroup[];
  onViewJson: (data: unknown, title: string, description?: string) => void;
  onClear: () => void;
}

export function PasTimeline({
  entries,
  authorizationGroups,
  onViewJson,
  onClear,
}: PasTimelineProps) {
  if (entries.length === 0) {
    return (
      <div className="border-2 border-dashed rounded-lg p-8 text-center">
        <Clock className="h-8 w-8 mx-auto text-muted-foreground/40 mb-3" />
        <p className="text-sm font-medium text-muted-foreground">
          No operations yet
        </p>
        <p className="text-xs text-muted-foreground/70 mt-1">
          Select a scenario and execute an operation to see results here
        </p>
      </div>
    );
  }

  // Reverse chronological order (newest first)
  const reversedEntries = [...entries].reverse();

  // Build a lookup of authorizationId -> group color for left border
  const authBorderColors = new Map<string, string>();
  for (const group of authorizationGroups) {
    const color = group.currentReviewAction
      ? REVIEW_ACTIONS[group.currentReviewAction].borderClass
      : "border-gray-300 dark:border-gray-700";
    authBorderColors.set(group.authorizationId, color);
  }

  return (
    <div>
      {/* Header */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium">Timeline</span>
          <Badge variant="secondary" className="text-[10px]">
            {entries.length}
          </Badge>
        </div>
        <Button
          variant="ghost"
          size="sm"
          className="h-7 text-xs text-muted-foreground"
          onClick={onClear}
        >
          <Trash2 className="h-3 w-3 mr-1" />
          Clear
        </Button>
      </div>

      {/* Entries */}
      <div className="space-y-0">
        {reversedEntries.map((entry) => {
          const borderColor = entry.authorizationId
            ? (authBorderColors.get(entry.authorizationId) ?? "")
            : "";

          return (
            <div
              key={entry.id}
              className={`${borderColor ? `border-l-2 pl-2 ${borderColor}` : "pl-4"}`}
            >
              <PasTimelineEntry entry={entry} onViewJson={onViewJson} />
            </div>
          );
        })}
      </div>
    </div>
  );
}
