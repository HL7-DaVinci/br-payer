import { AlertCircle, Clock, Eye, Inbox } from "lucide-react";
import { memo } from "react";
import { PasReviewActionBadge } from "@/components/pas/pas-review-action-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  type PasError,
  REVIEW_ACTIONS,
  type ReviewActionCode,
  type TimelineEntry,
} from "@/lib/pas-types";

interface PasTimelineEntryProps {
  entry: TimelineEntry;
  onViewJson: (data: unknown, title: string, description?: string) => void;
}

const SOURCE_LABELS: Record<string, { label: string; className: string }> = {
  user: {
    label: "Manual",
    className:
      "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400",
  },
  "auto-poll": {
    label: "Auto-poll",
    className:
      "bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400",
  },
  subscription: {
    label: "Subscription",
    className:
      "bg-cyan-100 text-cyan-600 dark:bg-cyan-900/30 dark:text-cyan-400",
  },
};

function formatShortDate(iso: string): string {
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()}/${String(d.getFullYear()).slice(2)}`;
}

function hasMultipleDistinctActions(
  actions: Array<{ code: ReviewActionCode }>,
): boolean {
  if (actions.length < 2) return false;
  return actions.some((a) => a.code !== actions[0].code);
}

function extractClaimId(bundle: object): string | null {
  const b = bundle as {
    entry?: Array<{ resource?: { resourceType?: string; id?: string } }>;
  };
  for (const e of b.entry ?? []) {
    if (e.resource?.resourceType === "Claim") return e.resource.id ?? null;
  }
  return null;
}

export const PasTimelineEntry = memo(function PasTimelineEntry({
  entry,
  onViewJson,
}: PasTimelineEntryProps) {
  const sourceConfig = SOURCE_LABELS[entry.source] ?? SOURCE_LABELS.user;
  const dotClass = entry.reviewAction
    ? REVIEW_ACTIONS[entry.reviewAction].dotClass
    : entry.error
      ? "bg-red-500"
      : "bg-gray-300";
  const claimId = entry.requestBundle
    ? extractClaimId(entry.requestBundle)
    : null;
  const claimResponseId = entry.authorizationId;

  return (
    <div className="flex gap-3 py-3">
      {/* Left gutter: colored dot */}
      <div className="flex flex-col items-center pt-1.5">
        <div className={`h-2.5 w-2.5 rounded-full shrink-0 ${dotClass}`} />
        <div className="w-px flex-1 bg-border mt-1" />
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0 space-y-1.5">
        {/* Header row */}
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-[10px] text-muted-foreground tabular-nums">
            {entry.timestamp.toLocaleTimeString()}
          </span>
          {entry.operation && (
            <Badge
              variant="outline"
              className="text-[10px] font-mono px-1.5 py-0"
            >
              {entry.operation}
            </Badge>
          )}
          <span className="text-[10px] text-muted-foreground capitalize">
            {entry.payloadType}
          </span>
          <Badge
            variant="secondary"
            className={`text-[10px] px-1.5 py-0 ${sourceConfig.className}`}
          >
            {sourceConfig.label}
          </Badge>
          {entry.durationMs > 0 && (
            <span className="text-[10px] text-muted-foreground flex items-center gap-0.5">
              <Clock className="h-2.5 w-2.5" />
              {entry.durationMs}ms
            </span>
          )}
        </div>

        {/* Result */}
        {entry.error ? (
          <div className="flex items-start gap-1.5 text-xs text-destructive">
            <AlertCircle className="h-3.5 w-3.5 shrink-0 mt-0.5" />
            {(entry.error as PasError).status && (
              <Badge
                variant="outline"
                className="text-[10px] px-1.5 py-0 border-destructive/50 text-destructive shrink-0"
              >
                {(entry.error as PasError).status}
              </Badge>
            )}
            <span className="break-all">{entry.error.message}</span>
          </div>
        ) : entry.reviewAction || claimResponseId ? (
          <div className="space-y-1">
            <div className="flex items-center gap-2 flex-wrap">
              {/* Multi-item summary or single review action */}
              {entry.itemReviewActions &&
              hasMultipleDistinctActions(entry.itemReviewActions) ? (
                entry.itemReviewActions.map((item) => (
                  <span
                    key={item.sequence}
                    className={`text-[10px] font-medium px-1.5 py-0 rounded border ${REVIEW_ACTIONS[item.code].bgClass} ${REVIEW_ACTIONS[item.code].textClass} ${REVIEW_ACTIONS[item.code].borderClass}`}
                  >
                    Item {item.sequence}: {item.code}
                  </span>
                ))
              ) : entry.reviewAction ? (
                <PasReviewActionBadge code={entry.reviewAction} size="sm" />
              ) : null}
              {entry.authorizationNumber && (
                <span className="text-[10px] text-muted-foreground font-mono">
                  Auth# {entry.authorizationNumber}
                </span>
              )}
              {entry.reviewAction === "A4" && entry.adminRefNumber && (
                <span className="text-[10px] text-muted-foreground font-mono">
                  Admin# {entry.adminRefNumber}
                </span>
              )}
              {claimId && (
                <span className="text-[10px] text-muted-foreground font-mono">
                  Claim: {claimId}
                </span>
              )}
              {claimResponseId && (
                <span className="text-[10px] text-muted-foreground font-mono">
                  CR: {claimResponseId}
                </span>
              )}
            </div>
            {/* PreAuth validity period for approved items */}
            {(entry.reviewAction === "A1" || entry.reviewAction === "A6") &&
              entry.preAuthPeriod && (
                <div className="text-[10px] text-muted-foreground">
                  Valid:{" "}
                  {entry.preAuthPeriod.start
                    ? formatShortDate(entry.preAuthPeriod.start)
                    : "?"}
                  {" - "}
                  {entry.preAuthPeriod.end
                    ? formatShortDate(entry.preAuthPeriod.end)
                    : "?"}
                </div>
              )}
          </div>
        ) : (
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <Inbox className="h-3.5 w-3.5 shrink-0" />
            <span>No matching authorizations found</span>
          </div>
        )}

        {/* Actions */}
        <div className="flex items-center gap-2">
          {entry.requestBundle && (
            <Button
              variant="ghost"
              size="sm"
              className="h-6 text-[10px] px-2"
              onClick={() =>
                onViewJson(
                  entry.requestBundle,
                  `${entry.operation} Request`,
                  `${entry.payloadType} - ${entry.timestamp.toLocaleString()}`,
                )
              }
            >
              <Eye className="h-3 w-3 mr-1" />
              Request
            </Button>
          )}
          {entry.responseData && (
            <Button
              variant="ghost"
              size="sm"
              className="h-6 text-[10px] px-2"
              onClick={() => {
                const status = entry.error
                  ? `Error ${(entry.error as PasError).status ?? ""}`
                  : entry.reviewAction
                    ? REVIEW_ACTIONS[entry.reviewAction].label
                    : "";
                const title = entry.operation
                  ? `${entry.operation} Response`
                  : "Notification";
                onViewJson(
                  entry.responseData,
                  title,
                  `${status} - ${entry.timestamp.toLocaleString()}`.replace(
                    /^ - /,
                    "",
                  ),
                );
              }}
            >
              <Eye className="h-3 w-3 mr-1" />
              {entry.operation ? "Response" : "View"}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
});
