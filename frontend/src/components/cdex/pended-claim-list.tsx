import { RefreshCw } from "lucide-react";
import type { ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { type PendedClaim, pendedClaimLabel } from "@/lib/cdex-types";
import { cn } from "@/lib/utils";

interface PendedClaimListProps {
  claims: PendedClaim[];
  selectedId: string | null;
  onSelect: (claim: PendedClaim) => void;
  isLoading: boolean;
  onRefresh: () => void;
  headerAction?: ReactNode;
}

export function PendedClaimList({
  claims,
  selectedId,
  onSelect,
  isLoading,
  onRefresh,
  headerAction,
}: PendedClaimListProps) {
  return (
    <Card className="flex flex-col h-full">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="text-base">Pended Claims</CardTitle>
          <div className="flex items-center gap-1">
            {headerAction}
            <Button
              variant="ghost"
              size="icon"
              onClick={onRefresh}
              title="Refresh"
            >
              <RefreshCw
                className={cn("h-4 w-4", isLoading && "animate-spin")}
              />
            </Button>
          </div>
        </div>
        <CardDescription>
          Prior authorizations awaiting documentation
        </CardDescription>
      </CardHeader>
      <CardContent className="flex-1 min-h-0 p-2 pt-0">
        <ScrollArea className="h-full">
          {claims.length === 0 && !isLoading && (
            <p className="text-sm text-muted-foreground px-2 py-4">
              No pended claims. Submit a PAS scenario that pends for
              documentation to get started.
            </p>
          )}
          <div className="flex flex-col gap-1">
            {claims.map((claim) => (
              <button
                key={claim.claimResponseId}
                type="button"
                onClick={() => onSelect(claim)}
                title={
                  claim.trackingIdValue
                    ? `Tracking ID: ${claim.trackingIdValue}`
                    : undefined
                }
                className={cn(
                  "w-full rounded-md border p-2 text-left text-sm transition-colors hover:bg-accent",
                  selectedId === claim.claimResponseId &&
                    "border-primary bg-accent",
                )}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium truncate">
                    {pendedClaimLabel(claim)}
                  </span>
                  <Badge variant="secondary">
                    {claim.documentationRequests.length} req
                  </Badge>
                </div>
                <div className="text-xs text-muted-foreground mt-1">
                  {claim.patientDisplay ??
                    claim.patientReference ??
                    "Unknown patient"}
                  {claim.memberId ? ` · Member ${claim.memberId}` : ""}
                </div>
                {claim.created && (
                  <div className="text-xs text-muted-foreground mt-0.5">
                    Pended {new Date(claim.created).toLocaleString()}
                  </div>
                )}
              </button>
            ))}
          </div>
        </ScrollArea>
      </CardContent>
    </Card>
  );
}
