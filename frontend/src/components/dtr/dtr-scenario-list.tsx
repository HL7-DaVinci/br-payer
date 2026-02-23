import { ScenarioList } from "@/components/shared/scenario-list";
import { Badge } from "@/components/ui/badge";
import type { DtrRequestVariant, DtrScenario } from "@/lib/dtr-types";

interface DtrScenarioListProps {
  scenarios: DtrScenario[];
  selectedScenario: DtrScenario | null;
  selectedVariant: DtrRequestVariant | null;
  onSelectScenario: (scenario: DtrScenario) => void;
  onSelectVariant: (variant: DtrRequestVariant) => void;
}

export function DtrScenarioList(props: DtrScenarioListProps) {
  return (
    <ScenarioList
      {...props}
      renderBadges={(scenario) => (
        <>
          <Badge variant="secondary" className="text-[10px] shrink-0">
            {scenario.orderType}
          </Badge>
          {scenario.isAdaptive && (
            <Badge
              variant="outline"
              className="text-[10px] shrink-0 border-amber-500/50 text-amber-600 dark:text-amber-400"
            >
              {scenario.isAdaptiveSearch ? "Adaptive Search" : "Adaptive"}
            </Badge>
          )}
        </>
      )}
      getVariants={(s) => s.variants}
      shouldShowVariants={(s) => s.variants.length > 1}
      variantLabel="Request Parameters"
    />
  );
}
