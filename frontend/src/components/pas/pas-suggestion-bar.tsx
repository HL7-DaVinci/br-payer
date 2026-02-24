import { Lightbulb } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { SuggestedOperation } from "@/lib/pas-types";

interface PasSuggestionBarProps {
  suggestions: SuggestedOperation[];
  onSuggestionClick: (suggestion: SuggestedOperation) => void;
}

export function PasSuggestionBar({
  suggestions,
  onSuggestionClick,
}: PasSuggestionBarProps) {
  if (suggestions.length === 0) return null;

  return (
    <div className="flex items-center gap-2 px-4 py-2 border-b bg-amber-50/50 dark:bg-amber-950/10">
      <Lightbulb className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400 shrink-0" />
      <span className="text-[10px] text-muted-foreground shrink-0">
        Suggested:
      </span>
      <div className="flex items-center gap-1.5 flex-wrap">
        {suggestions.map((suggestion) => (
          <Button
            key={`${suggestion.operation}-${suggestion.payloadType}`}
            variant="outline"
            size="sm"
            className="h-6 text-[10px] px-2 bg-background"
            onClick={() => onSuggestionClick(suggestion)}
          >
            {suggestion.reason}
          </Button>
        ))}
      </div>
    </div>
  );
}
