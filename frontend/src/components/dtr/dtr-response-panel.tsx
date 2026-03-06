import {
  AlertTriangle,
  ArrowLeft,
  BookOpen,
  Code,
  Library,
  Package,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { DtrError } from "@/hooks/use-dtr-api";
import type { DtrPackageResponse, ParsedPackageBundle } from "@/lib/dtr-types";
import { keyOf } from "@/lib/utils";

interface DtrResponsePanelProps {
  response: DtrPackageResponse | null;
  error: DtrError | null;
  onViewRawResponse: () => void;
  onViewBundle: (bundle: ParsedPackageBundle, index: number) => void;
  onOpenQuestionnaire: (bundle: ParsedPackageBundle) => void;
  onBack: () => void;
}

export function DtrResponsePanel({
  response,
  error,
  onViewRawResponse,
  onViewBundle,
  onOpenQuestionnaire,
  onBack,
}: DtrResponsePanelProps) {
  if (error) {
    return (
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">Response</h2>
          <Button variant="outline" size="sm" onClick={onBack}>
            <ArrowLeft className="h-4 w-4 mr-1" />
            Back
          </Button>
        </div>
        <Card className="border-destructive/50">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm text-destructive flex items-center gap-2">
              <AlertTriangle className="h-4 w-4" />
              Request Failed
              {error.status && (
                <Badge variant="destructive" className="text-[10px]">
                  HTTP {error.status}
                </Badge>
              )}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm">{error.message}</p>
            {error.operationOutcome && (
              <div className="mt-3 space-y-1">
                {error.operationOutcome.issue?.map((issue) => (
                  <div
                    key={keyOf(issue)}
                    className="text-xs p-2 bg-destructive/10 rounded"
                  >
                    <span className="font-medium">{issue.severity}:</span>{" "}
                    {issue.diagnostics ?? issue.details?.text ?? issue.code}
                  </div>
                ))}
              </div>
            )}
            <Button
              variant="outline"
              size="sm"
              className="mt-3"
              onClick={onViewRawResponse}
            >
              <Code className="h-3 w-3 mr-1" />
              View Error Details
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!response) {
    return (
      <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
        Execute a request to see results
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold">Response</h2>
          <p className="text-sm text-muted-foreground">
            {response.packageBundles.length} package bundle(s) returned
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={onViewRawResponse}>
            <Code className="h-4 w-4 mr-1" />
            View Raw
          </Button>
          <Button variant="outline" size="sm" onClick={onBack}>
            <ArrowLeft className="h-4 w-4 mr-1" />
            Back
          </Button>
        </div>
      </div>

      {/* Package Bundles */}
      <div className="space-y-3">
        {response.packageBundles.map((bundle, index) => (
          <Card key={bundle.questionnaire?.id ?? `bundle-${index}`}>
            <CardHeader className="pb-3">
              <div className="flex items-start justify-between">
                <div className="flex-1 min-w-0">
                  <CardTitle className="text-sm flex items-center gap-2">
                    <Package className="h-4 w-4 shrink-0" />
                    <span className="truncate">
                      {bundle.questionnaire?.title ??
                        bundle.questionnaire?.name ??
                        `Package ${index + 1}`}
                    </span>
                    {bundle.isAdaptive && (
                      <Badge
                        variant="outline"
                        className="text-[10px] shrink-0 border-amber-500/50 text-amber-600 dark:text-amber-400"
                      >
                        Adaptive
                      </Badge>
                    )}
                  </CardTitle>
                  {bundle.questionnaire?.url && (
                    <CardDescription className="text-xs font-mono truncate mt-0.5">
                      {bundle.questionnaire.url}
                    </CardDescription>
                  )}
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              {/* Resource counts */}
              <div className="flex gap-3 text-xs text-muted-foreground">
                {bundle.questionnaire && (
                  <div className="flex items-center gap-1">
                    <BookOpen className="h-3 w-3" />
                    Questionnaire
                  </div>
                )}
                {bundle.questionnaireResponse && (
                  <div className="flex items-center gap-1">
                    <BookOpen className="h-3 w-3" />
                    QR (prepopulated)
                  </div>
                )}
                {bundle.libraries.length > 0 && (
                  <div className="flex items-center gap-1">
                    <Library className="h-3 w-3" />
                    {bundle.libraries.length} libraries
                  </div>
                )}
                {bundle.valueSets.length > 0 && (
                  <div className="flex items-center gap-1">
                    <Library className="h-3 w-3" />
                    {bundle.valueSets.length} value sets
                  </div>
                )}
              </div>

              {/* Actions */}
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => onViewBundle(bundle, index)}
                >
                  <Code className="h-3 w-3 mr-1" />
                  View Bundle
                </Button>
                <Button size="sm" onClick={() => onOpenQuestionnaire(bundle)}>
                  <BookOpen className="h-3 w-3 mr-1" />
                  Open Questionnaire
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* OperationOutcome warnings */}
      {response.outcome && (
        <Card className="border-amber-500/50">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm flex items-center gap-2 text-amber-600 dark:text-amber-400">
              <AlertTriangle className="h-4 w-4" />
              Warnings
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-1">
              {response.outcome.issue?.map((issue) => (
                <div
                  key={keyOf(issue)}
                  className="text-xs p-2 bg-amber-500/10 rounded"
                >
                  <span className="font-medium">{issue.severity}:</span>{" "}
                  {issue.diagnostics ?? issue.details?.text ?? issue.code}
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
