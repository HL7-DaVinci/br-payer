import type {
  DomainResource,
  Extension,
  OperationOutcome,
  Resource,
} from "fhir/r4";
import { AlertCircle, Code, Inbox } from "lucide-react";
import { useMemo } from "react";
import { CdsCard } from "@/components/cds/cds-card";
import { CdsSystemAction } from "@/components/cds/cds-system-action";
import { CoverageInformationPanel } from "@/components/cds/coverage-information-panel";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import type { CdsError } from "@/hooks/use-cds-api";
import type {
  CdsCard as CdsCardType,
  CdsResponse,
  CdsSystemAction as CdsSystemActionType,
  CoverageInformationExtension,
} from "@/lib/cds-types";
import { parseCoverageInformation } from "@/lib/cds-types";
import { keyOf } from "@/lib/utils";

interface CdsResponsePanelProps {
  response: CdsResponse | null;
  error: CdsError | null;
  onViewRawResponse: () => void;
  onViewCard: (card: CdsCardType, index: number) => void;
  onViewSystemAction: (action: CdsSystemActionType, index: number) => void;
  onViewResource: (resource: Resource) => void;
  onViewCoverageInfo: (
    rawExtension: Extension,
    resourceType: string,
    resourceId: string,
  ) => void;
}

interface ParsedCoverageAction {
  action: CdsSystemActionType;
  coverageInfo: CoverageInformationExtension[];
  rawExtensions: Extension[];
  resourceType: string;
  resourceId: string;
}

/** Display OperationOutcome issues in a readable format */
function OperationOutcomeDisplay({ outcome }: { outcome: OperationOutcome }) {
  const issues = outcome.issue ?? [];

  if (issues.length === 0) {
    return (
      <div className="text-sm text-muted-foreground">
        OperationOutcome returned with no issues
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {issues.map((issue) => {
        const severityColors: Record<string, string> = {
          fatal: "text-destructive",
          error: "text-destructive",
          warning: "text-yellow-600 dark:text-yellow-500",
          information: "text-blue-600 dark:text-blue-500",
        };
        const colorClass =
          severityColors[issue.severity ?? "error"] ?? "text-destructive";

        return (
          <div
            key={keyOf(issue)}
            className="rounded-md border border-destructive/30 bg-destructive/5 p-3 space-y-1"
          >
            <div className="flex items-center gap-2">
              <Badge variant="outline" className={`text-[10px] ${colorClass}`}>
                {issue.severity ?? "error"}
              </Badge>
              {issue.code && (
                <Badge variant="secondary" className="text-[10px]">
                  {issue.code}
                </Badge>
              )}
            </div>
            {issue.diagnostics && (
              <p className="text-sm">{issue.diagnostics}</p>
            )}
            {issue.details?.text && !issue.diagnostics && (
              <p className="text-sm">{issue.details.text}</p>
            )}
            {issue.expression && issue.expression.length > 0 && (
              <p className="text-xs text-muted-foreground font-mono">
                {issue.expression.join(", ")}
              </p>
            )}
          </div>
        );
      })}
    </div>
  );
}

export function CdsResponsePanel({
  response,
  error,
  onViewRawResponse,
  onViewCard,
  onViewSystemAction,
  onViewResource,
  onViewCoverageInfo,
}: CdsResponsePanelProps) {
  // Separate coverage-info system actions from other system actions
  const { coverageInfoActions, otherSystemActions } = useMemo(() => {
    const coverageInfoActions: ParsedCoverageAction[] = [];
    const otherSystemActions: CdsSystemActionType[] = [];
    const COVERAGE_INFO_URL =
      "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

    for (const action of response?.systemActions ?? []) {
      if (action.resource) {
        const coverageInfo = parseCoverageInformation(action.resource);
        if (coverageInfo.length > 0) {
          const domainResource = action.resource as DomainResource;
          const rawExtensions =
            domainResource.extension?.filter(
              (ext) => ext.url === COVERAGE_INFO_URL,
            ) ?? [];
          coverageInfoActions.push({
            action,
            coverageInfo,
            rawExtensions,
            resourceType: action.resource.resourceType || "Resource",
            resourceId: action.resource.id || "unknown",
          });
        } else {
          otherSystemActions.push(action);
        }
      } else {
        otherSystemActions.push(action);
      }
    }

    return { coverageInfoActions, otherSystemActions };
  }, [response?.systemActions]);

  // Show error state
  if (error) {
    const operationOutcome = error.operationOutcome;
    return (
      <Card>
        <CardHeader className="pb-3">
          <div className="flex items-center justify-between">
            <CardTitle className="text-sm flex items-center gap-2 text-destructive">
              <AlertCircle className="h-4 w-4" />
              Error
              {error.status && (
                <Badge variant="destructive" className="text-[10px]">
                  {error.status}
                </Badge>
              )}
            </CardTitle>
            {Boolean(operationOutcome ?? error.body) && (
              <Button
                variant="ghost"
                size="sm"
                className="h-7 text-xs"
                onClick={onViewRawResponse}
              >
                <Code className="h-3 w-3 mr-1" />
                View Raw
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          {operationOutcome ? (
            <OperationOutcomeDisplay outcome={operationOutcome} />
          ) : (
            <div className="text-sm text-muted-foreground">{error.message}</div>
          )}
        </CardContent>
      </Card>
    );
  }

  if (!response) {
    return (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm">Response</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-col items-center justify-center py-8 text-muted-foreground">
            <Inbox className="h-8 w-8 mb-2" />
            <p className="text-sm">No response yet</p>
            <p className="text-xs">Execute a hook to see the response</p>
          </div>
        </CardContent>
      </Card>
    );
  }

  const hasCards = response.cards && response.cards.length > 0;
  const hasCoverageInfo = coverageInfoActions.length > 0;
  const hasOtherActions = otherSystemActions.length > 0;
  const isEmpty = !hasCards && !hasCoverageInfo && !hasOtherActions;

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-sm">Response</CardTitle>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 text-xs"
            onClick={onViewRawResponse}
          >
            <Code className="h-3 w-3 mr-1" />
            View Raw
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {isEmpty && (
          <div className="text-center py-4 text-sm text-muted-foreground">
            No cards or actions returned
          </div>
        )}

        {/* Coverage Information Section */}
        {hasCoverageInfo && (
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                Coverage Information
              </h4>
              <Badge variant="secondary" className="text-[10px]">
                {coverageInfoActions.length}
              </Badge>
            </div>
            {coverageInfoActions.map((item) =>
              item.coverageInfo.map((ci, ciIndex) => (
                <CoverageInformationPanel
                  key={keyOf(ci)}
                  coverageInfo={ci}
                  resourceType={item.resourceType}
                  resourceId={item.resourceId}
                  onViewJson={() =>
                    onViewCoverageInfo(
                      item.rawExtensions[ciIndex],
                      item.resourceType,
                      item.resourceId,
                    )
                  }
                />
              )),
            )}
          </div>
        )}

        {/* Cards Section */}
        {hasCards && (
          <>
            {hasCoverageInfo && <Separator />}
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  Cards
                </h4>
                <Badge variant="secondary" className="text-[10px]">
                  {response.cards?.length ?? 0}
                </Badge>
              </div>
              {response.cards?.map((card, index) => (
                <CdsCard
                  key={card.uuid ?? `card-${index}`}
                  card={card}
                  index={index}
                  onViewJson={() => onViewCard(card, index)}
                />
              ))}
            </div>
          </>
        )}

        {/* Other System Actions Section */}
        {hasOtherActions && (
          <>
            {(hasCoverageInfo || hasCards) && <Separator />}
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  System Actions
                </h4>
                <Badge variant="secondary" className="text-[10px]">
                  {otherSystemActions.length}
                </Badge>
              </div>
              {otherSystemActions.map((action, index) => {
                const resource = action.resource;
                const actionKey = `${action.type}-${resource?.resourceType ?? "unknown"}-${resource?.id ?? index}`;
                return (
                  <CdsSystemAction
                    key={actionKey}
                    action={action}
                    index={index}
                    onViewJson={() => onViewSystemAction(action, index)}
                    onViewResource={
                      resource ? () => onViewResource(resource) : undefined
                    }
                  />
                );
              })}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
