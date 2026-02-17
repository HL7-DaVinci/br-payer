import type { Questionnaire } from "fhir/r4";
import { BookOpen, Loader2, Plus, Search, X } from "lucide-react";
import { useCallback, useMemo, useRef, useState } from "react";
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
import { useResourceSearch } from "@/hooks/use-cds-api";
import { useClickOutside } from "@/hooks/use-click-outside";
import { useDebouncedValue } from "@/hooks/use-debounced-value";

interface DtrQuestionnairePickerProps {
  sourceServerUrl: string;
  canonicalUrls: string[];
  onCanonicalUrlsChange: (urls: string[]) => void;
}

function isQuestionnaire(r: unknown): r is Questionnaire {
  return (
    typeof r === "object" &&
    r !== null &&
    "resourceType" in r &&
    (r as { resourceType: string }).resourceType === "Questionnaire"
  );
}

export function DtrQuestionnairePicker({
  sourceServerUrl,
  canonicalUrls,
  onCanonicalUrlsChange,
}: DtrQuestionnairePickerProps) {
  const [searchText, setSearchText] = useState("");
  const [manualUrl, setManualUrl] = useState("");
  const [showSearch, setShowSearch] = useState(false);
  const searchAreaRef = useRef<HTMLDivElement>(null);

  useClickOutside(searchAreaRef, () => setShowSearch(false), showSearch);

  const debouncedSearch = useDebouncedValue(searchText, 300);

  const searchParams = useMemo(() => {
    const params: Record<string, string> = {};
    if (debouncedSearch.trim()) {
      params.title = debouncedSearch.trim();
    }
    return params;
  }, [debouncedSearch]);

  const {
    data: searchResults,
    isLoading,
    isError,
  } = useResourceSearch(
    sourceServerUrl,
    "Questionnaire",
    searchParams,
    showSearch,
  );

  const questionnaireResults = useMemo(() => {
    if (!searchResults?.entry) return [];
    const allQuestionnaires = searchResults.entry
      .map((e) => e.resource)
      .filter((r): r is Questionnaire => !!r && isQuestionnaire(r))
      .filter((q) => !!q.url);
    const filter = searchText.trim().toLowerCase();
    if (!filter) return allQuestionnaires;
    return allQuestionnaires.filter((q) => {
      const title = (q.title ?? q.name ?? "").toLowerCase();
      const url = (q.url ?? "").toLowerCase();
      return title.includes(filter) || url.includes(filter);
    });
  }, [searchResults, searchText]);

  const handleToggleUrl = useCallback(
    (url: string) => {
      if (canonicalUrls.includes(url)) {
        onCanonicalUrlsChange(canonicalUrls.filter((u) => u !== url));
      } else {
        onCanonicalUrlsChange([...canonicalUrls, url]);
      }
    },
    [canonicalUrls, onCanonicalUrlsChange],
  );

  const handleAddManualUrl = useCallback(() => {
    const url = manualUrl.trim();
    if (url && !canonicalUrls.includes(url)) {
      onCanonicalUrlsChange([...canonicalUrls, url]);
      setManualUrl("");
    }
  }, [manualUrl, canonicalUrls, onCanonicalUrlsChange]);

  const handleRemoveUrl = useCallback(
    (url: string) => {
      onCanonicalUrlsChange(canonicalUrls.filter((u) => u !== url));
    },
    [canonicalUrls, onCanonicalUrlsChange],
  );

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <BookOpen className="h-4 w-4 text-muted-foreground" />
            <CardTitle className="text-sm">Questionnaires</CardTitle>
          </div>
          {canonicalUrls.length > 0 && (
            <Badge variant="secondary" className="text-[10px]">
              {canonicalUrls.length}
            </Badge>
          )}
        </div>
        <CardDescription className="text-xs">
          Select canonical URLs or enter manually
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {/* Selected URLs */}
        {canonicalUrls.length > 0 && (
          <div className="flex flex-wrap gap-1">
            {canonicalUrls.map((url) => (
              <Badge
                key={url}
                variant="outline"
                className="text-[10px] gap-1 pr-1"
              >
                <span className="max-w-[180px] truncate">{url}</span>
                <button
                  type="button"
                  onClick={() => handleRemoveUrl(url)}
                  className="hover:text-destructive transition-colors"
                >
                  <X className="h-3 w-3" />
                </button>
              </Badge>
            ))}
          </div>
        )}

        {/* Search */}
        <div className="space-y-2" ref={searchAreaRef}>
          <div className="flex gap-2">
            <Input
              placeholder="Search Questionnaires..."
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
            <div className="max-h-48 overflow-y-auto rounded-md border">
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
              {!isLoading && questionnaireResults.length === 0 && (
                <div className="p-2 text-xs text-muted-foreground">
                  No Questionnaires found
                </div>
              )}
              {questionnaireResults.map((q) => {
                const url = q.url as string;
                const isSelected = canonicalUrls.includes(url);
                return (
                  <button
                    key={url}
                    type="button"
                    onClick={() => handleToggleUrl(url)}
                    className={`w-full text-left px-3 py-2 text-xs transition-colors border-b last:border-b-0 ${
                      isSelected
                        ? "bg-primary/10 text-primary"
                        : "hover:bg-accent"
                    }`}
                  >
                    <div className="font-medium">
                      {q.title ?? q.name ?? "Untitled"}
                    </div>
                    <div className="text-muted-foreground truncate">{url}</div>
                    {q.version && (
                      <Badge variant="outline" className="text-[9px] mt-1">
                        v{q.version}
                      </Badge>
                    )}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Manual URL entry */}
        <div className="flex gap-2">
          <Input
            placeholder="Enter canonical URL..."
            value={manualUrl}
            onChange={(e) => setManualUrl(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") handleAddManualUrl();
            }}
            className="h-8 text-xs"
          />
          <Button
            variant="outline"
            size="sm"
            className="h-8 shrink-0"
            onClick={handleAddManualUrl}
            disabled={!manualUrl.trim()}
          >
            <Plus className="h-3 w-3" />
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
