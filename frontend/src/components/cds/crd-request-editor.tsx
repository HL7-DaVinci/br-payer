import { Code, Loader2, Play } from "lucide-react";
import { lazy, Suspense, useMemo, useState } from "react";
import { toast } from "sonner";
import { ErrorBoundary } from "@/components/error-boundary";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useTheme } from "@/hooks/use-theme";
import type { CdsRequest } from "@/lib/cds-types";
import type { CrdHookVariant, CrdScenario } from "@/lib/crd-types";

const MonacoEditor = lazy(() =>
  import("@monaco-editor/react").then((mod) => ({ default: mod.Editor })),
);

interface CrdRequestEditorProps {
  scenario: CrdScenario | null;
  variant: CrdHookVariant | null;
  requestJson: string;
  onRequestJsonChange: (json: string) => void;
  onExecute: (request: CdsRequest) => void;
  onPreview: (request: CdsRequest) => void;
  isExecuting: boolean;
}

interface SummaryItem {
  label: string;
  value: string;
}

function extractSummary(request: Record<string, unknown>): SummaryItem[] {
  const summary: SummaryItem[] = [];

  if (typeof request.hook === "string") {
    summary.push({ label: "Hook", value: request.hook });
  }

  const context = request.context as Record<string, unknown> | undefined;
  if (context) {
    if (typeof context.patientId === "string") {
      summary.push({ label: "Patient", value: context.patientId });
    }

    // Extract order info from draftOrders bundle
    const draftOrders = context.draftOrders as
      | { entry?: Array<{ resource?: Record<string, unknown> }> }
      | undefined;
    if (draftOrders?.entry?.[0]?.resource) {
      const resource = draftOrders.entry[0].resource;
      if (resource.resourceType) {
        summary.push({
          label: "Order Type",
          value: String(resource.resourceType),
        });
      }
      const code =
        (resource.codeCodeableConcept as Record<string, unknown>) ??
        (resource.code as Record<string, unknown>) ??
        (resource.medicationCodeableConcept as Record<string, unknown>);
      if (code) {
        const coding =
          (code.coding as Array<{ display?: string; code?: string }>) ?? [];
        if (coding[0]?.display) {
          summary.push({ label: "Code", value: coding[0].display });
        } else if (coding[0]?.code) {
          summary.push({ label: "Code", value: coding[0].code });
        }
      }
    }

    // Extract from appointments bundle
    const appointments = context.appointments as
      | { entry?: Array<{ resource?: Record<string, unknown> }> }
      | undefined;
    if (appointments?.entry?.[0]?.resource) {
      const resource = appointments.entry[0].resource;
      if (resource.resourceType) {
        summary.push({
          label: "Resource Type",
          value: String(resource.resourceType),
        });
      }
    }
  }

  // Extract coverage subscriber from prefetch
  const prefetch = request.prefetch as Record<string, unknown> | undefined;
  if (prefetch) {
    const coverageBundle = prefetch.coverage as
      | { entry?: Array<{ resource?: Record<string, unknown> }> }
      | undefined;
    const coverage = coverageBundle?.entry?.[0]?.resource;
    if (coverage?.subscriberId) {
      summary.push({
        label: "Subscriber",
        value: String(coverage.subscriberId),
      });
    }
  }

  return summary;
}

export function CrdRequestEditor({
  scenario,
  variant,
  requestJson,
  onRequestJsonChange,
  onExecute,
  onPreview,
  isExecuting,
}: CrdRequestEditorProps) {
  const { effectiveTheme } = useTheme();
  const [activeTab, setActiveTab] = useState("summary");

  const summary = useMemo(() => {
    try {
      const parsed = JSON.parse(requestJson) as Record<string, unknown>;
      return extractSummary(parsed);
    } catch {
      return [];
    }
  }, [requestJson]);

  const handleExecute = () => {
    try {
      const parsed = JSON.parse(requestJson) as CdsRequest;
      onExecute(parsed);
    } catch {
      toast.error("Invalid JSON in request editor");
    }
  };

  const handlePreview = () => {
    try {
      const parsed = JSON.parse(requestJson) as CdsRequest;
      onPreview(parsed);
    } catch {
      toast.error("Invalid JSON in request editor");
    }
  };

  if (!scenario || !variant) {
    return (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm">Request</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="text-center py-8 text-sm text-muted-foreground">
            Select a scenario to configure the request
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="flex flex-col h-full">
      <CardHeader className="pb-3 shrink-0">
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-sm">{scenario.name}</CardTitle>
            <CardDescription className="text-xs">
              <Badge variant="outline" className="text-[10px]">
                {variant.hookName}
              </Badge>
            </CardDescription>
          </div>
        </div>
      </CardHeader>
      <CardContent className="flex-1 flex flex-col min-h-0 gap-3">
        <Tabs
          value={activeTab}
          onValueChange={setActiveTab}
          className="flex-1 flex flex-col min-h-0"
        >
          <TabsList className="shrink-0">
            <TabsTrigger value="summary">Summary</TabsTrigger>
            <TabsTrigger value="json">JSON Editor</TabsTrigger>
          </TabsList>

          <TabsContent
            value="summary"
            className="flex-1 overflow-y-auto min-h-0"
          >
            <div className="space-y-2 pt-2">
              {summary.map((item) => (
                <div key={item.label} className="space-y-0.5">
                  <div className="text-[10px] text-muted-foreground uppercase tracking-wider">
                    {item.label}
                  </div>
                  <div className="text-xs font-mono break-all">
                    {item.value}
                  </div>
                </div>
              ))}
              {summary.length === 0 && (
                <div className="text-xs text-muted-foreground">
                  Unable to parse request summary
                </div>
              )}
              <Button
                variant="link"
                size="sm"
                className="text-xs p-0 h-auto"
                onClick={() => setActiveTab("json")}
              >
                Edit JSON directly
              </Button>
            </div>
          </TabsContent>

          <TabsContent
            value="json"
            className="flex-1 min-h-0 border rounded-md overflow-hidden"
          >
            <ErrorBoundary
              fallback={
                <pre className="p-4 text-xs overflow-auto">{requestJson}</pre>
              }
            >
              <Suspense
                fallback={
                  <div className="flex items-center justify-center h-full">
                    <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
                  </div>
                }
              >
                <MonacoEditor
                  height="100%"
                  language="json"
                  value={requestJson}
                  onChange={(value) => onRequestJsonChange(value ?? "")}
                  theme={effectiveTheme === "dark" ? "vs-dark" : "light"}
                  options={{
                    minimap: { enabled: false },
                    fontSize: 12,
                    lineNumbers: "on",
                    scrollBeyondLastLine: false,
                    wordWrap: "on",
                    folding: true,
                    automaticLayout: true,
                    tabSize: 2,
                  }}
                />
              </Suspense>
            </ErrorBoundary>
          </TabsContent>
        </Tabs>

        <div className="grid grid-cols-2 gap-2 shrink-0">
          <Button variant="outline" size="sm" onClick={handlePreview}>
            <Code className="h-4 w-4 mr-1" />
            Preview
          </Button>
          <Button size="sm" onClick={handleExecute} disabled={isExecuting}>
            {isExecuting ? (
              <Loader2 className="h-4 w-4 animate-spin mr-1" />
            ) : (
              <Play className="h-4 w-4 mr-1" />
            )}
            Execute
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
