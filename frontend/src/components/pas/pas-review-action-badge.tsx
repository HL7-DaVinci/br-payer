import { Badge } from "@/components/ui/badge";
import { REVIEW_ACTIONS, type ReviewActionCode } from "@/lib/pas-types";

interface PasReviewActionBadgeProps {
  code: ReviewActionCode;
  size?: "sm" | "default";
}

export function PasReviewActionBadge({
  code,
  size = "default",
}: PasReviewActionBadgeProps) {
  const config = REVIEW_ACTIONS[code];

  return (
    <Badge
      variant="outline"
      className={`${config.bgClass} ${config.textClass} ${config.borderClass} ${
        size === "sm" ? "text-[10px] px-1.5 py-0" : "text-xs"
      }`}
    >
      {config.code} {config.label}
    </Badge>
  );
}
