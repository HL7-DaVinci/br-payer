import { RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { AutoPollConfig } from "@/lib/pas-types";

interface PasAutoPollControlProps {
  config: AutoPollConfig;
  onToggle: () => void;
  onIntervalChange: (seconds: number) => void;
  pendedCount: number;
}

export function PasAutoPollControl({
  config,
  onToggle,
  onIntervalChange,
  pendedCount,
}: PasAutoPollControlProps) {
  const [intervalInput, setIntervalInput] = useState(
    String(config.intervalSeconds),
  );

  useEffect(() => {
    setIntervalInput(String(config.intervalSeconds));
  }, [config.intervalSeconds]);

  const isDisabled = pendedCount === 0 && !config.enabled;

  return (
    <div className="flex items-center gap-3 p-3 mt-3 border rounded-lg bg-muted/30">
      <Button
        variant={config.enabled ? "default" : "outline"}
        size="sm"
        className="h-7 text-xs gap-1.5"
        onClick={onToggle}
        disabled={isDisabled}
      >
        <RefreshCw
          className={`h-3 w-3 ${config.enabled ? "animate-spin" : ""}`}
        />
        {config.enabled ? "Polling" : "Auto-poll"}
      </Button>

      {config.enabled && (
        <div className="flex items-center gap-1.5">
          <span className="text-[10px] text-muted-foreground">every</span>
          <Input
            type="number"
            min={5}
            max={60}
            value={intervalInput}
            onChange={(e) => {
              const value = e.target.value;
              setIntervalInput(value);

              const parsed = Number.parseInt(value, 10);
              if (Number.isNaN(parsed)) return;
              if (parsed >= 5 && parsed <= 60) {
                onIntervalChange(parsed);
              }
            }}
            onBlur={() => {
              const parsed = Number.parseInt(intervalInput, 10);
              if (Number.isNaN(parsed)) {
                setIntervalInput(String(config.intervalSeconds));
                return;
              }

              const clamped = Math.min(60, Math.max(5, parsed));
              setIntervalInput(String(clamped));
              if (clamped !== config.intervalSeconds) {
                onIntervalChange(clamped);
              }
            }}
            className="h-6 min-w-14 text-[10px] text-center"
          />
          <span className="text-[10px] text-muted-foreground">sec</span>
        </div>
      )}

      {pendedCount > 0 && (
        <Badge
          variant="outline"
          className="text-[10px] bg-amber-100 text-amber-700 border-amber-300 dark:bg-amber-900/30 dark:text-amber-400 dark:border-amber-700"
        >
          {pendedCount} pended
        </Badge>
      )}
    </div>
  );
}
