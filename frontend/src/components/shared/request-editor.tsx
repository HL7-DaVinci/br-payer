import { Code, Loader2, Play } from "lucide-react";
import type { ReactNode } from "react";
import { lazy, Suspense, useMemo, useState } from "react";
import { toast } from "sonner";
import { ErrorBoundary } from "@/components/error-boundary";
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

const MonacoEditor = lazy(() =>
  import("@monaco-editor/react").then((mod) => ({ default: mod.Editor })),
);

export interface SummaryItem {
  label: string;
  value: string;
}

interface RequestEditorProps {
  /** Scenario name displayed in the header. null triggers the empty state. */
  scenarioName: string | null;
  /** Domain-specific content rendered below the scenario name */
  headerDescription: ReactNode;
  requestJson: string;
  onRequestJsonChange: (json: string) => void;
  onExecute: () => void;
  onPreview: () => void;
  isExecuting: boolean;
  /** Extract summary fields from the parsed JSON for the Summary tab */
  extractSummary: (parsed: Record<string, unknown>) => SummaryItem[];
}

export function RequestEditor({
  scenarioName,
  headerDescription,
  requestJson,
  onRequestJsonChange,
  onExecute,
  onPreview,
  isExecuting,
  extractSummary,
}: RequestEditorProps) {
  const { effectiveTheme } = useTheme();
  const [activeTab, setActiveTab] = useState("summary");

  const summary = useMemo(() => {
    try {
      const parsed = JSON.parse(requestJson) as Record<string, unknown>;
      return extractSummary(parsed);
    } catch {
      return [];
    }
  }, [requestJson, extractSummary]);

  const handleAction = (action: () => void) => {
    try {
      JSON.parse(requestJson);
      action();
    } catch {
      toast.error("Invalid JSON in request editor");
    }
  };

  if (!scenarioName) {
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
            <CardTitle className="text-sm">{scenarioName}</CardTitle>
            <CardDescription className="text-xs">
              {headerDescription}
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
          <Button
            variant="outline"
            size="sm"
            onClick={() => handleAction(onPreview)}
          >
            <Code className="h-4 w-4 mr-1" />
            Preview
          </Button>
          <Button
            size="sm"
            onClick={() => handleAction(onExecute)}
            disabled={isExecuting}
          >
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
