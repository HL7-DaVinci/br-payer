import { Loader2, Webhook } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { RestHookEndpointMode } from "@/hooks/use-pas-resthook-subscription";
import type { RestHookSubscriptionStatus } from "@/lib/pas-types";

interface PasResthookControlProps {
  status: RestHookSubscriptionStatus;
  npi: string;
  onNpiChange: (npi: string) => void;
  endpointMode: RestHookEndpointMode;
  onEndpointModeChange: (mode: RestHookEndpointMode) => void;
  customEndpoint: string;
  onCustomEndpointChange: (url: string) => void;
  pollIntervalSeconds: number;
  onPollIntervalChange: (seconds: number) => void;
  onConnect: () => void;
  onDisconnect: () => void;
  error: string | null;
  subscriptionId: string | null;
}

const STATUS_DOT: Record<RestHookSubscriptionStatus, string> = {
  idle: "bg-gray-400",
  creating: "bg-yellow-400 animate-pulse",
  activating: "bg-yellow-400 animate-pulse",
  active: "bg-green-500",
  disconnected: "bg-orange-400",
  error: "bg-red-500",
};

const isTransitioning = (s: RestHookSubscriptionStatus) =>
  s === "creating" || s === "activating";

export function PasResthookControl({
  status,
  npi,
  onNpiChange,
  endpointMode,
  onEndpointModeChange,
  customEndpoint,
  onCustomEndpointChange,
  pollIntervalSeconds,
  onPollIntervalChange,
  onConnect,
  onDisconnect,
  error,
  subscriptionId,
}: PasResthookControlProps) {
  const showInputs =
    status === "idle" || status === "disconnected" || status === "error";

  return (
    <div className="flex flex-col gap-2 p-3 mt-2 border rounded-lg bg-muted/30">
      <div className="flex items-center gap-3 flex-wrap">
        <span className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider shrink-0">
          REST
        </span>

        {/* Status indicator */}
        <div className="flex items-center gap-1.5">
          <div
            className={`h-2 w-2 rounded-full shrink-0 ${STATUS_DOT[status]}`}
          />
        </div>

        {/* NPI input */}
        {showInputs && (
          <Input
            placeholder="Sender code"
            value={npi}
            onChange={(e) => onNpiChange(e.target.value)}
            className="h-7 w-32 text-xs font-mono"
          />
        )}

        {/* Endpoint mode toggle */}
        {showInputs && (
          <div className="flex items-center h-7 border rounded-md overflow-hidden text-xs">
            <button
              type="button"
              className={`px-2 h-full cursor-pointer ${
                endpointMode === "inbox"
                  ? "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400"
                  : "hover:bg-muted"
              }`}
              onClick={() => onEndpointModeChange("inbox")}
            >
              Inbox
            </button>
            <button
              type="button"
              className={`px-2 h-full border-l cursor-pointer ${
                endpointMode === "custom"
                  ? "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400"
                  : "hover:bg-muted"
              }`}
              onClick={() => onEndpointModeChange("custom")}
            >
              Custom
            </button>
          </div>
        )}

        {/* Custom endpoint URL input */}
        {showInputs && endpointMode === "custom" && (
          <Input
            placeholder="https://example.com/webhook"
            value={customEndpoint}
            onChange={(e) => onCustomEndpointChange(e.target.value)}
            className="h-7 w-56 text-xs font-mono"
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
            <Webhook className="h-3 w-3" />
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
            Subscribing...
          </Button>
        ) : (
          <Button
            variant="outline"
            size="sm"
            className="h-7 text-xs gap-1.5"
            onClick={onConnect}
            disabled={
              !npi.trim() ||
              (endpointMode === "custom" && !customEndpoint.trim())
            }
          >
            <Webhook className="h-3 w-3" />
            {status === "disconnected" || status === "error"
              ? "Reconnect"
              : "Subscribe"}
          </Button>
        )}

        {/* Active subscription info */}
        {status === "active" && subscriptionId && (
          <Badge
            variant="outline"
            className="text-[10px] font-mono bg-purple-100 text-purple-700 border-purple-300 dark:bg-purple-900/30 dark:text-purple-400 dark:border-purple-700"
          >
            Sub: {subscriptionId}
          </Badge>
        )}

        {/* Custom mode active indicator */}
        {status === "active" && endpointMode === "custom" && (
          <span className="text-[10px] text-muted-foreground">
            Active (external)
          </span>
        )}

        {/* Error message */}
        {status === "error" && error && (
          <span className="text-[10px] text-destructive truncate max-w-64">
            {error}
          </span>
        )}
      </div>

      {/* Poll interval slider (only when active in inbox mode) */}
      {status === "active" && endpointMode === "inbox" && (
        <div className="flex items-center gap-3 pl-5">
          <span className="text-[10px] text-muted-foreground whitespace-nowrap">
            Poll: {pollIntervalSeconds}s
          </span>
          <input
            type="range"
            value={pollIntervalSeconds}
            onChange={(e) => onPollIntervalChange(Number(e.target.value))}
            min={3}
            max={30}
            step={1}
            className="w-32 h-1 accent-purple-500"
          />
        </div>
      )}
    </div>
  );
}
