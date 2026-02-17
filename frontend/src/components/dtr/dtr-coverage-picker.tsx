import type { Coverage, Resource } from "fhir/r4";
import { Eye, Loader2, Plus, Search, Shield, Trash2 } from "lucide-react";
import { useCallback, useMemo, useRef, useState } from "react";
import {
  JsonViewerDialog,
  useJsonViewer,
} from "@/components/json-viewer-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useResourceSearch } from "@/hooks/use-cds-api";
import { useClickOutside } from "@/hooks/use-click-outside";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { RESOURCE_TEMPLATES } from "@/lib/cds-types";

interface DtrCoveragePickerProps {
  sourceServerUrl: string;
  coverage: Coverage | null;
  onCoverageChange: (coverage: Coverage | null) => void;
}

function isCoverage(resource: Resource): resource is Coverage {
  return resource.resourceType === "Coverage";
}

function getCoverageLabel(coverage: Coverage): string {
  const type = coverage.type?.coding?.[0]?.display ?? coverage.type?.text;
  const payor = coverage.payor?.[0]?.display;
  if (type && payor) return `${type} - ${payor}`;
  if (type) return type;
  if (payor) return payor;
  return coverage.id ?? "Coverage";
}

export function DtrCoveragePicker({
  sourceServerUrl,
  coverage,
  onCoverageChange,
}: DtrCoveragePickerProps) {
  const [searchText, setSearchText] = useState("");
  const [showSearch, setShowSearch] = useState(false);
  const searchAreaRef = useRef<HTMLDivElement>(null);

  useClickOutside(searchAreaRef, () => setShowSearch(false), showSearch);

  const debouncedSearch = useDebouncedValue(searchText, 300);

  const searchParams = useMemo(() => {
    const params: Record<string, string> = {};
    if (debouncedSearch.trim()) {
      params._content = debouncedSearch.trim();
    }
    return params;
  }, [debouncedSearch]);

  const {
    data: searchResults,
    isLoading,
    isError,
  } = useResourceSearch(sourceServerUrl, "Coverage", searchParams, showSearch);

  const coverageResults = useMemo(() => {
    if (!searchResults?.entry) return [];
    const allCoverage = searchResults.entry
      .map((e) => e.resource)
      .filter((r): r is Coverage => !!r && isCoverage(r));
    const filter = searchText.trim().toLowerCase();
    if (!filter) return allCoverage;
    return allCoverage.filter((c) => {
      const label = getCoverageLabel(c).toLowerCase();
      const id = (c.id ?? "").toLowerCase();
      return label.includes(filter) || id.includes(filter);
    });
  }, [searchResults, searchText]);

  const { viewerData, openViewer, closeViewer } = useJsonViewer();

  const templates = RESOURCE_TEMPLATES.Coverage ?? [];

  const handleSelectFromSearch = useCallback(
    (c: Coverage) => {
      onCoverageChange(c);
      setShowSearch(false);
      setSearchText("");
    },
    [onCoverageChange],
  );

  const handleCreateFromTemplate = useCallback(
    (index: number) => {
      const template = templates[index];
      if (template) {
        const resource = template.create() as Coverage;
        onCoverageChange(resource);
      }
    },
    [templates, onCoverageChange],
  );

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Shield className="h-4 w-4 text-muted-foreground" />
            <CardTitle className="text-sm">Coverage</CardTitle>
          </div>
          <Badge
            variant={coverage ? "default" : "destructive"}
            className="text-[10px]"
          >
            {coverage ? "Set" : "Required"}
          </Badge>
        </div>
        <CardDescription className="text-xs">
          Select or create a Coverage resource
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {coverage ? (
          <div className="rounded-md border p-3 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">
                {getCoverageLabel(coverage)}
              </span>
            </div>
            {coverage.status && (
              <Badge variant="outline" className="text-[10px]">
                {coverage.status}
              </Badge>
            )}
            <div className="flex gap-1">
              <Button
                variant="ghost"
                size="sm"
                className="h-7 text-xs"
                onClick={() =>
                  openViewer(coverage, "Coverage", getCoverageLabel(coverage))
                }
              >
                <Eye className="h-3 w-3 mr-1" />
                View
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="h-7 text-xs text-destructive"
                onClick={() => onCoverageChange(null)}
              >
                <Trash2 className="h-3 w-3 mr-1" />
                Remove
              </Button>
            </div>
          </div>
        ) : (
          <div className="space-y-3">
            {/* Search existing */}
            <div className="space-y-2" ref={searchAreaRef}>
              <div className="flex gap-2">
                <Input
                  placeholder="Search Coverage resources..."
                  value={searchText}
                  onChange={(e) => {
                    setSearchText(e.target.value);
                    if (!showSearch) setShowSearch(true);
                  }}
                  onFocus={() => setShowSearch(true)}
                  className="h-8 text-xs"
                />
                <Button
                  variant="outline"
                  size="sm"
                  className="h-8 shrink-0"
                  onClick={() => setShowSearch(true)}
                >
                  <Search className="h-3 w-3" />
                </Button>
              </div>

              {showSearch && (
                <div className="max-h-40 overflow-y-auto rounded-md border">
                  {isLoading && (
                    <div className="flex items-center justify-center py-3">
                      <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                    </div>
                  )}
                  {isError && (
                    <div className="p-2 text-xs text-destructive">
                      Failed to search
                    </div>
                  )}
                  {!isLoading && coverageResults.length === 0 && (
                    <div className="p-2 text-xs text-muted-foreground">
                      No Coverage resources found
                    </div>
                  )}
                  {coverageResults.map((c, i) => (
                    <button
                      key={c.id ?? i}
                      type="button"
                      onClick={() => handleSelectFromSearch(c)}
                      className="w-full text-left px-3 py-2 text-xs hover:bg-accent transition-colors border-b last:border-b-0"
                    >
                      <div className="font-medium">{getCoverageLabel(c)}</div>
                      {c.id && (
                        <div className="text-muted-foreground">ID: {c.id}</div>
                      )}
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Create from template */}
            {templates.length > 0 && (
              <div className="space-y-1">
                <span className="text-xs text-muted-foreground">
                  Or create from template:
                </span>
                <Select
                  onValueChange={(v) => handleCreateFromTemplate(Number(v))}
                >
                  <SelectTrigger className="h-8 text-xs">
                    <SelectValue placeholder="Select template..." />
                  </SelectTrigger>
                  <SelectContent>
                    {templates.map((t, i) => (
                      <SelectItem key={t.label} value={String(i)}>
                        <div className="flex items-center gap-2">
                          <Plus className="h-3 w-3" />
                          {t.label}
                        </div>
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            )}
          </div>
        )}
      </CardContent>

      {viewerData && (
        <JsonViewerDialog
          data={viewerData.data}
          title={viewerData.title}
          description={viewerData.description}
          onClose={closeViewer}
        />
      )}
    </Card>
  );
}
