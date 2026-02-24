import { RefreshCw } from "lucide-react";
import { ScenarioList } from "@/components/shared/scenario-list";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type {
  PasScenario,
  PasVariant,
  SuggestedOperation,
} from "@/lib/pas-types";

interface PasScenarioListProps {
  scenarios: PasScenario[];
  selectedScenario: PasScenario | null;
  selectedVariant: PasVariant | null;
  onSelectScenario: (scenario: PasScenario) => void;
  onSelectVariant: (variant: PasVariant) => void;
  suggestions?: SuggestedOperation[];
  onRefresh?: () => void;
  isRefreshing?: boolean;
}

export function PasScenarioList({
  suggestions = [],
  onRefresh,
  isRefreshing = false,
  ...props
}: PasScenarioListProps) {
  // Track which payload types are suggested for visual indication
  const suggestedPayloadTypes = new Set(
    suggestions.map((s) => s.payloadType).filter(Boolean),
  );

  return (
    <ScenarioList
      {...props}
      renderBadges={(scenario) => (
        <Badge variant="secondary" className="text-[10px] shrink-0">
          {scenario.orderType}
        </Badge>
      )}
      getVariants={(s) => s.variants}
      shouldShowVariants={() => true}
      variantLabel="Operation"
      variantContainerClassName="flex-wrap"
      headerAction={
        onRefresh ? (
          <Button
            variant="ghost"
            size="sm"
            className="h-7 text-xs text-muted-foreground"
            onClick={onRefresh}
            disabled={isRefreshing}
          >
            <RefreshCw
              className={`h-3 w-3 mr-1.5 ${isRefreshing ? "animate-spin" : ""}`}
            />
            Refresh
          </Button>
        ) : undefined
      }
      renderVariant={(variant, isSelected) => {
        const isInquiry = variant.operation === "$inquire";
        const isSuggested = suggestedPayloadTypes.has(variant.payloadType);

        return (
          <span className="flex items-center gap-1">
            {isSuggested && (
              <span className="h-1.5 w-1.5 rounded-full bg-amber-500 animate-pulse" />
            )}
            <span
              className={isInquiry && !isSelected ? "opacity-70" : undefined}
            >
              {variant.label}
            </span>
          </span>
        );
      }}
    />
  );
}
