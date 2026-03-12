import { Loader2, Radio } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { SubscriptionStatus } from "@/lib/pas-types";

interface PasSubscriptionControlProps {
  status: SubscriptionStatus;
  npi: string;
  onNpiChange: (npi: string) => void;
  onConnect: () => void;
  onDisconnect: () => void;
  error: string | null;
  subscriptionId: string | null;
}

const STATUS_DOT: Record<SubscriptionStatus, string> = {
  idle: "bg-gray-400",
  creating: "bg-yellow-400 animate-pulse",
  connecting: "bg-yellow-400 animate-pulse",
  binding: "bg-yellow-400 animate-pulse",
  active: "bg-green-500",
  disconnected: "bg-orange-400",
  error: "bg-red-500",
};

const isTransitioning = (s: SubscriptionStatus) =>
  s === "creating" || s === "connecting" || s === "binding";

export function PasSubscriptionControl({
  status,
  npi,
  onNpiChange,
  onConnect,
  onDisconnect,
  error,
  subscriptionId,
}: PasSubscriptionControlProps) {
  const showNpiInput =
    status === "idle" || status === "disconnected" || status === "error";

  return (
    <div className="flex items-center gap-3 p-3 mt-2 border rounded-lg bg-muted/30">
      <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider shrink-0">
        WS
      </span>

      {/* Status indicator */}
      <div className="flex items-center gap-1.5">
        <div
          className={`h-2 w-2 rounded-full shrink-0 ${STATUS_DOT[status]}`}
        />
      </div>

      {/* NPI input (shown when not connected) */}
      {showNpiInput && (
        <Input
          placeholder="Sender code"
          value={npi}
          onChange={(e) => onNpiChange(e.target.value)}
          className="h-7 w-32 text-xs font-mono"
        />
      )}

      {/* Connect/Disconnect button */}
      {status === "active" ? (
        <Button
          variant="outline"
          size="sm"
          className="h-7 text-xs gap-1.5"
          onClick={onDisconnect}
        >
          <Radio className="h-3 w-3" />
          Disconnect
        </Button>
      ) : isTransitioning(status) ? (
        <Button
          variant="default"
          size="sm"
          className="h-7 text-xs gap-1.5"
          disabled
        >
          <Loader2 className="h-3 w-3 animate-spin" />
          Connecting...
        </Button>
      ) : (
        <Button
          variant="outline"
          size="sm"
          className="h-7 text-xs gap-1.5"
          onClick={onConnect}
          disabled={!npi.trim()}
        >
          <Radio className="h-3 w-3" />
          {status === "disconnected" || status === "error"
            ? "Reconnect"
            : "Subscribe"}
        </Button>
      )}

      {/* Active subscription info */}
      {status === "active" && subscriptionId && (
        <Badge
          variant="outline"
          className="text-[10px] font-mono bg-cyan-100 text-cyan-700 border-cyan-300 dark:bg-cyan-900/30 dark:text-cyan-400 dark:border-cyan-700"
        >
          Sub: {subscriptionId}
        </Badge>
      )}

      {/* Error message */}
      {status === "error" && error && (
        <span className="text-[10px] text-destructive truncate max-w-64">
          {error}
        </span>
      )}
    </div>
  );
}
