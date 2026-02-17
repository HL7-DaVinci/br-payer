import { ScenarioList } from "@/components/shared/scenario-list";
import { Badge } from "@/components/ui/badge";
import type { CrdHookVariant, CrdScenario } from "@/lib/crd-types";

interface CrdScenarioListProps {
  scenarios: CrdScenario[];
  selectedScenario: CrdScenario | null;
  selectedVariant: CrdHookVariant | null;
  onSelectScenario: (scenario: CrdScenario) => void;
  onSelectVariant: (variant: CrdHookVariant) => void;
}

export function CrdScenarioList(props: CrdScenarioListProps) {
  return (
    <ScenarioList
      {...props}
      renderBadges={(scenario) => (
        <Badge variant="secondary" className="text-[10px] shrink-0">
          {scenario.hooks.length} hook
          {scenario.hooks.length !== 1 ? "s" : ""}
        </Badge>
      )}
      getVariants={(s) => s.hooks}
      shouldShowVariants={(s) => s.hooks.length > 0}
      variantLabel="Hook Type"
      variantContainerClassName="flex-wrap"
    />
  );
}
