import type { ReactNode } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Label } from "@/components/ui/label";

interface BaseScenario {
  id: string;
  name: string;
  description: string;
}

interface BaseVariant {
  id: string;
  label: string;
}

interface ScenarioListProps<
  TScenario extends BaseScenario,
  TVariant extends BaseVariant,
> {
  scenarios: TScenario[];
  selectedScenario: TScenario | null;
  selectedVariant: TVariant | null;
  onSelectScenario: (scenario: TScenario) => void;
  onSelectVariant: (variant: TVariant) => void;
  /** Render badges next to the scenario name (e.g. hook count, order type) */
  renderBadges: (scenario: TScenario) => ReactNode;
  /** Extract variant list from a scenario */
  getVariants: (scenario: TScenario) => TVariant[];
  /** Whether to show the variant selector for a given scenario */
  shouldShowVariants: (scenario: TScenario) => boolean;
  /** Label for the variant selector section */
  variantLabel: string;
  /** Extra classes on the variant button container */
  variantContainerClassName?: string;
}

export function ScenarioList<
  TScenario extends BaseScenario,
  TVariant extends BaseVariant,
>({
  scenarios,
  selectedScenario,
  selectedVariant,
  onSelectScenario,
  onSelectVariant,
  renderBadges,
  getVariants,
  shouldShowVariants,
  variantLabel,
  variantContainerClassName,
}: ScenarioListProps<TScenario, TVariant>) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-sm">Test Scenarios</CardTitle>
        <CardDescription className="text-xs">
          {scenarios.length} scenario(s) available
        </CardDescription>
      </CardHeader>
      <CardContent className="p-0">
        <div className="p-3 pt-0 space-y-1">
          {scenarios.map((scenario) => {
            const isSelected = selectedScenario?.id === scenario.id;
            return (
              <div key={scenario.id}>
                <div
                  className={`
                    relative w-full text-left p-2.5 rounded-lg border transition-colors
                    ${isSelected ? "bg-primary/10 border-primary/30" : "hover:bg-muted/50 border-transparent"}
                  `}
                >
                  <button
                    type="button"
                    className="absolute inset-0 cursor-pointer"
                    onClick={() => onSelectScenario(scenario)}
                    aria-label={`Select ${scenario.name}`}
                  />
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-medium text-sm">
                          {scenario.name}
                        </span>
                        {renderBadges(scenario)}
                      </div>
                      <p className="text-xs text-muted-foreground mt-0.5 line-clamp-2">
                        {scenario.description}
                      </p>
                    </div>
                  </div>
                </div>

                {isSelected && shouldShowVariants(scenario) && (
                  <div className="ml-4 mt-1.5 mb-1 space-y-1.5">
                    <Label className="text-[10px] text-muted-foreground uppercase tracking-wider">
                      {variantLabel}
                    </Label>
                    <div
                      className={`flex gap-1.5 ${variantContainerClassName ?? ""}`}
                    >
                      {getVariants(scenario).map((variant) => (
                        <button
                          key={variant.id}
                          type="button"
                          onClick={() => onSelectVariant(variant)}
                          className={`
                            px-2.5 py-1 rounded-md text-xs font-medium border transition-colors cursor-pointer
                            ${
                              selectedVariant?.id === variant.id
                                ? "bg-primary text-primary-foreground border-primary"
                                : "bg-muted/50 hover:bg-muted border-transparent"
                            }
                          `}
                        >
                          {variant.label}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </CardContent>
    </Card>
  );
}
