import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import type { DtrRequestVariant, DtrScenario } from "@/lib/dtr-types";

interface DtrScenarioListProps {
  scenarios: DtrScenario[];
  selectedScenario: DtrScenario | null;
  selectedVariant: DtrRequestVariant | null;
  onSelectScenario: (scenario: DtrScenario) => void;
  onSelectVariant: (variant: DtrRequestVariant) => void;
}

export function DtrScenarioList({
  scenarios,
  selectedScenario,
  selectedVariant,
  onSelectScenario,
  onSelectVariant,
}: DtrScenarioListProps) {
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
                        <Badge
                          variant="secondary"
                          className="text-[10px] shrink-0"
                        >
                          {scenario.orderType}
                        </Badge>
                        {scenario.isAdaptive && (
                          <Badge
                            variant="outline"
                            className="text-[10px] shrink-0 border-amber-500/50 text-amber-600 dark:text-amber-400"
                          >
                            Adaptive
                          </Badge>
                        )}
                      </div>
                      <p className="text-xs text-muted-foreground mt-0.5 line-clamp-2">
                        {scenario.description}
                      </p>
                    </div>
                  </div>
                </div>

                {/* Variant selector shown when scenario is selected */}
                {isSelected && scenario.variants.length > 1 && (
                  <div className="ml-4 mt-1.5 mb-1 space-y-1.5">
                    <Label className="text-[10px] text-muted-foreground uppercase tracking-wider">
                      Request Path
                    </Label>
                    <div className="flex gap-1.5">
                      {scenario.variants.map((variant) => (
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
