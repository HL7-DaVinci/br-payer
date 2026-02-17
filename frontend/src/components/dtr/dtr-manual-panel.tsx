import type { Coverage, Resource } from "fhir/r4";
import { AlertCircle, CheckCircle2, Code, Loader2, Play } from "lucide-react";
import { useMemo } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { buildDtrParameters, validateDtrParameters } from "@/lib/dtr-types";

interface DtrManualPanelProps {
  coverage: Coverage | null;
  canonicalUrls: string[];
  orders: Resource[];
  onExecute: (parameters: ReturnType<typeof buildDtrParameters>) => void;
  onPreview: (parameters: ReturnType<typeof buildDtrParameters>) => void;
  isExecuting: boolean;
}

export function DtrManualPanel({
  coverage,
  canonicalUrls,
  orders,
  onExecute,
  onPreview,
  isExecuting,
}: DtrManualPanelProps) {
  const validation = useMemo(
    () => validateDtrParameters(coverage, canonicalUrls, orders),
    [coverage, canonicalUrls, orders],
  );

  const handleExecute = () => {
    if (!coverage || !validation.isValid) return;
    const params = buildDtrParameters(coverage, canonicalUrls, orders);
    onExecute(params);
  };

  const handlePreview = () => {
    if (!coverage || !validation.isValid) return;
    const params = buildDtrParameters(coverage, canonicalUrls, orders);
    onPreview(params);
  };

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-sm">Execute</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {/* Validation checklist */}
        <div className="space-y-1.5">
          <ValidationItem
            label="Coverage"
            valid={validation.hasCoverage}
            detail={coverage ? "Selected" : "Required"}
          />
          <ValidationItem
            label="Questionnaire or Order"
            valid={validation.hasQuestionnaireOrOrder}
            detail={
              canonicalUrls.length > 0 && orders.length > 0
                ? `${canonicalUrls.length} URL(s) + ${orders.length} order(s)`
                : canonicalUrls.length > 0
                  ? `${canonicalUrls.length} URL(s)`
                  : orders.length > 0
                    ? `${orders.length} order(s)`
                    : "At least one required"
            }
          />
        </div>

        {/* Action buttons */}
        <div className="grid grid-cols-2 gap-2">
          <Button
            onClick={handleExecute}
            disabled={!validation.isValid || isExecuting}
            className="h-9"
          >
            {isExecuting ? (
              <Loader2 className="h-4 w-4 mr-2 animate-spin" />
            ) : (
              <Play className="h-4 w-4 mr-2" />
            )}
            Execute
          </Button>
          <Button
            variant="outline"
            onClick={handlePreview}
            disabled={!validation.isValid}
            className="h-9"
          >
            <Code className="h-4 w-4 mr-2" />
            Preview
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

function ValidationItem({
  label,
  valid,
  detail,
}: {
  label: string;
  valid: boolean;
  detail: string;
}) {
  return (
    <div className="flex items-center justify-between text-xs">
      <div className="flex items-center gap-1.5">
        {valid ? (
          <CheckCircle2 className="h-3.5 w-3.5 text-green-500" />
        ) : (
          <AlertCircle className="h-3.5 w-3.5 text-destructive" />
        )}
        <span className={valid ? "text-foreground" : "text-muted-foreground"}>
          {label}
        </span>
      </div>
      <Badge variant={valid ? "secondary" : "outline"} className="text-[10px]">
        {detail}
      </Badge>
    </div>
  );
}
