import type { Bundle, Resource } from "fhir/r4";
import {
  AlertCircle,
  CheckCircle,
  ChevronDown,
  ChevronRight,
  Code,
  Loader2,
} from "lucide-react";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";

interface PrefetchResult {
  key: string;
  template: string;
  resolvedUrl: string;
  data: Resource | Bundle | null;
  isLoading: boolean;
  isError: boolean;
  error: Error | null;
}

interface PrefetchPanelProps {
  prefetchResults: PrefetchResult[];
  onViewPrefetchItem: (key: string, data: Resource | Bundle) => void;
  onViewAllPrefetch: (data: Record<string, Resource | Bundle | null>) => void;
  hasContext: boolean;
}

function getResourceSummary(data: Resource | Bundle | null): string {
  if (!data) return "No data";

  if (data.resourceType === "Bundle") {
    const bundle = data as Bundle;
    const count = bundle.entry?.length ?? 0;
    return `Bundle with ${count} ${count === 1 ? "entry" : "entries"}`;
  }

  const resource = data as Resource;
  return `${resource.resourceType}/${resource.id || "unknown"}`;
}

export function PrefetchPanel({
  prefetchResults,
  onViewPrefetchItem,
  onViewAllPrefetch,
  hasContext,
}: PrefetchPanelProps) {
  const [expandedItems, setExpandedItems] = useState<Set<string>>(new Set());

  const toggleExpanded = (key: string) => {
    setExpandedItems((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const allPrefetchData: Record<string, Resource | Bundle | null> = {};
  for (const result of prefetchResults) {
    allPrefetchData[result.key] = result.data;
  }

  const hasAnyData = prefetchResults.some((r) => r.data !== null);
  const isAnyLoading = prefetchResults.some((r) => r.isLoading);

  if (prefetchResults.length === 0) {
    return (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm">Prefetch Data</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            No prefetch templates defined for this service.
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <CardTitle className="text-sm">Prefetch Data</CardTitle>
            {isAnyLoading && (
              <Loader2 className="h-3 w-3 animate-spin text-muted-foreground" />
            )}
          </div>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 text-xs"
            onClick={() => onViewAllPrefetch(allPrefetchData)}
            disabled={!hasAnyData}
          >
            <Code className="h-3 w-3 mr-1" />
            View All
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-2 pt-0">
        {!hasContext && (
          <p className="text-xs text-muted-foreground mb-3">
            Select a patient to fetch prefetch data.
          </p>
        )}

        {prefetchResults.map((result) => (
          <Collapsible
            key={result.key}
            open={expandedItems.has(result.key)}
            onOpenChange={() => toggleExpanded(result.key)}
          >
            <div className="border rounded-lg">
              <CollapsibleTrigger asChild>
                <div className="flex items-center justify-between p-2.5 cursor-pointer hover:bg-muted/50 transition-colors">
                  <div className="flex items-center gap-2 min-w-0">
                    {expandedItems.has(result.key) ? (
                      <ChevronDown className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                    ) : (
                      <ChevronRight className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                    )}
                    <span className="font-medium text-sm">{result.key}</span>
                    {result.isLoading ? (
                      <Loader2 className="h-3 w-3 animate-spin text-muted-foreground" />
                    ) : result.isError ? (
                      <AlertCircle className="h-3 w-3 text-destructive" />
                    ) : result.data ? (
                      <CheckCircle className="h-3 w-3 text-green-500" />
                    ) : null}
                  </div>
                  <div className="flex items-center gap-2">
                    {result.data && (
                      <Badge variant="secondary" className="text-[10px]">
                        {getResourceSummary(result.data)}
                      </Badge>
                    )}
                    {result.data && (
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6"
                        onClick={(e) => {
                          e.stopPropagation();
                          if (result.data) {
                            onViewPrefetchItem(result.key, result.data);
                          }
                        }}
                        title="View JSON"
                      >
                        <Code className="h-3 w-3" />
                      </Button>
                    )}
                  </div>
                </div>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="px-2.5 pb-2.5 pt-0 space-y-1.5">
                  <div className="text-xs space-y-1">
                    <div>
                      <span className="text-muted-foreground">Template: </span>
                      <code className="bg-muted px-1 py-0.5 rounded text-[10px]">
                        {result.template}
                      </code>
                    </div>
                    <div>
                      <span className="text-muted-foreground">Resolved: </span>
                      <code className="bg-muted px-1 py-0.5 rounded text-[10px] break-all">
                        {result.resolvedUrl || "(context not complete)"}
                      </code>
                    </div>
                  </div>
                  {result.isError && result.error && (
                    <div className="text-xs text-destructive bg-destructive/10 p-2 rounded">
                      {result.error.message}
                    </div>
                  )}
                </div>
              </CollapsibleContent>
            </div>
          </Collapsible>
        ))}
      </CardContent>
    </Card>
  );
}
