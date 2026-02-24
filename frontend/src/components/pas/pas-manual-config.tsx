import { FileText, Loader2, Play } from "lucide-react";
import { lazy, Suspense, useMemo } from "react";
import { ErrorBoundary } from "@/components/error-boundary";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useTheme } from "@/hooks/use-theme";
import { PAS_TEMPLATES } from "@/lib/pas-templates";

const MonacoEditor = lazy(() =>
  import("@monaco-editor/react").then((mod) => ({ default: mod.Editor })),
);

interface PasManualConfigProps {
  requestJson: string;
  onRequestJsonChange: (json: string) => void;
  operation: "$submit" | "$inquire";
  onOperationChange: (operation: "$submit" | "$inquire") => void;
  onExecute: () => void;
  isExecuting: boolean;
}

export function PasManualConfig({
  requestJson,
  onRequestJsonChange,
  operation,
  onOperationChange,
  onExecute,
  isExecuting,
}: PasManualConfigProps) {
  const { effectiveTheme } = useTheme();

  const filteredTemplates = useMemo(
    () => PAS_TEMPLATES.filter((t) => t.operation === operation),
    [operation],
  );

  const handleTemplateSelect = (templateId: string) => {
    const template = PAS_TEMPLATES.find((t) => t.id === templateId);
    if (template) {
      onOperationChange(template.operation);
      onRequestJsonChange(JSON.stringify(template.create(), null, 2));
    }
  };

  return (
    <div className="flex flex-col h-full gap-4">
      {/* Operation selector */}
      <div className="space-y-2 shrink-0">
        <Label className="text-xs font-medium">Operation</Label>
        <div className="flex gap-2">
          <Button
            variant={operation === "$submit" ? "default" : "outline"}
            size="sm"
            className="h-7 text-xs flex-1"
            onClick={() => onOperationChange("$submit")}
          >
            $submit
          </Button>
          <Button
            variant={operation === "$inquire" ? "default" : "outline"}
            size="sm"
            className="h-7 text-xs flex-1"
            onClick={() => onOperationChange("$inquire")}
          >
            $inquire
          </Button>
        </div>
      </div>

      {/* Template selector */}
      <div className="space-y-2 shrink-0">
        <Label className="text-xs font-medium">Starter Template</Label>
        <Select onValueChange={handleTemplateSelect}>
          <SelectTrigger className="h-8 text-xs">
            <div className="flex items-center gap-1.5">
              <FileText className="h-3 w-3 text-muted-foreground" />
              <SelectValue placeholder="Load a template..." />
            </div>
          </SelectTrigger>
          <SelectContent>
            {filteredTemplates.map((t) => (
              <SelectItem key={t.id} value={t.id} className="text-xs">
                <div>
                  <div className="font-medium">{t.label}</div>
                  <div className="text-muted-foreground">{t.description}</div>
                </div>
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Bundle JSON editor */}
      <div className="flex-1 min-h-0 flex flex-col gap-2">
        <Label className="text-xs font-medium">Request Bundle (JSON)</Label>
        <div className="flex-1 border rounded-md overflow-hidden">
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
        </div>
      </div>

      {/* Execute button */}
      <Button
        size="sm"
        onClick={onExecute}
        disabled={isExecuting}
        className="shrink-0"
      >
        {isExecuting ? (
          <Loader2 className="h-4 w-4 animate-spin mr-1" />
        ) : (
          <Play className="h-4 w-4 mr-1" />
        )}
        Execute {operation}
      </Button>
    </div>
  );
}
