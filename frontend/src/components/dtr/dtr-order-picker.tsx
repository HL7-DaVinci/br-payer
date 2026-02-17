import type { Resource } from "fhir/r4";
import {
  ClipboardList,
  Eye,
  Loader2,
  Plus,
  Search,
  Trash2,
} from "lucide-react";
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
import { getResourceTemplates } from "@/lib/cds-types";

const ORDER_RESOURCE_TYPES = [
  "ServiceRequest",
  "DeviceRequest",
  "MedicationRequest",
  "Appointment",
  "CommunicationRequest",
  "Encounter",
  "NutritionOrder",
  "SupplyRequest",
  "VisionPrescription",
] as const;

interface DtrOrderPickerProps {
  sourceServerUrl: string;
  orders: Resource[];
  onOrdersChange: (orders: Resource[]) => void;
}

function getOrderLabel(resource: Resource): string {
  const r = resource as Record<string, unknown>;
  if (typeof r.code === "object" && r.code !== null) {
    const code = r.code as {
      coding?: Array<{ display?: string }>;
      text?: string;
    };
    const display = code.coding?.[0]?.display ?? code.text;
    if (display) return display;
  }
  if (
    typeof r.medicationCodeableConcept === "object" &&
    r.medicationCodeableConcept !== null
  ) {
    const med = r.medicationCodeableConcept as {
      coding?: Array<{ display?: string }>;
      text?: string;
    };
    const display = med.coding?.[0]?.display ?? med.text;
    if (display) return display;
  }
  return resource.id ?? resource.resourceType;
}

export function DtrOrderPicker({
  sourceServerUrl,
  orders,
  onOrdersChange,
}: DtrOrderPickerProps) {
  const [selectedType, setSelectedType] = useState<string>("ServiceRequest");
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
  } = useResourceSearch(
    sourceServerUrl,
    selectedType,
    searchParams,
    showSearch,
  );

  const resourceResults = useMemo(() => {
    if (!searchResults?.entry) return [];
    const allResources = searchResults.entry
      .map((e) => e.resource)
      .filter((r): r is Resource => !!r && r.resourceType === selectedType);
    const filter = searchText.trim().toLowerCase();
    if (!filter) return allResources;
    return allResources.filter((r) => {
      const label = getOrderLabel(r).toLowerCase();
      const id = (r.id ?? "").toLowerCase();
      return label.includes(filter) || id.includes(filter);
    });
  }, [searchResults, selectedType, searchText]);

  const templates = useMemo(
    () => getResourceTemplates(selectedType),
    [selectedType],
  );

  const { viewerData, openViewer, closeViewer } = useJsonViewer();

  const handleAddFromSearch = useCallback(
    (resource: Resource) => {
      onOrdersChange([...orders, resource]);
      setShowSearch(false);
      setSearchText("");
    },
    [orders, onOrdersChange],
  );

  const handleCreateFromTemplate = useCallback(
    (templateIndex: string) => {
      const template = templates[Number(templateIndex)];
      if (template) {
        const resource = template.create();
        onOrdersChange([...orders, resource]);
      }
    },
    [templates, orders, onOrdersChange],
  );

  const handleRemoveOrder = useCallback(
    (index: number) => {
      onOrdersChange(orders.filter((_, i) => i !== index));
    },
    [orders, onOrdersChange],
  );

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ClipboardList className="h-4 w-4 text-muted-foreground" />
            <CardTitle className="text-sm">Orders</CardTitle>
          </div>
          {orders.length > 0 && (
            <Badge variant="secondary" className="text-[10px]">
              {orders.length}
            </Badge>
          )}
        </div>
        <CardDescription className="text-xs">
          Add order resources for the request
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {/* Selected orders list */}
        {orders.length > 0 && (
          <div className="space-y-1">
            {orders.map((order, i) => (
              <div
                key={`${order.resourceType}-${order.id ?? i}`}
                className="flex items-center justify-between rounded-md border px-2 py-1.5"
              >
                <div className="min-w-0">
                  <div className="text-xs font-medium truncate">
                    {getOrderLabel(order)}
                  </div>
                  <div className="text-[10px] text-muted-foreground">
                    {order.resourceType}
                  </div>
                </div>
                <div className="flex gap-0.5 shrink-0">
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 w-6 p-0"
                    onClick={() =>
                      openViewer(
                        order,
                        `${order.resourceType}`,
                        getOrderLabel(order),
                      )
                    }
                  >
                    <Eye className="h-3 w-3" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 w-6 p-0 text-destructive"
                    onClick={() => handleRemoveOrder(i)}
                  >
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Resource type selector */}
        <Select
          value={selectedType}
          onValueChange={(v) => {
            setSelectedType(v);
            setShowSearch(false);
            setSearchText("");
          }}
        >
          <SelectTrigger className="h-8 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {ORDER_RESOURCE_TYPES.map((type) => (
              <SelectItem key={type} value={type}>
                {type}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {/* Search */}
        <div className="space-y-2" ref={searchAreaRef}>
          <div className="flex gap-2">
            <Input
              placeholder={`Search ${selectedType}...`}
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
            <div className="max-h-36 overflow-y-auto rounded-md border">
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
              {!isLoading && resourceResults.length === 0 && (
                <div className="p-2 text-xs text-muted-foreground">
                  No {selectedType} resources found
                </div>
              )}
              {resourceResults.map((r, i) => (
                <button
                  key={r.id ?? i}
                  type="button"
                  onClick={() => handleAddFromSearch(r)}
                  className="w-full text-left px-3 py-2 text-xs hover:bg-accent transition-colors border-b last:border-b-0"
                >
                  <div className="font-medium">{getOrderLabel(r)}</div>
                  {r.id && (
                    <div className="text-muted-foreground">ID: {r.id}</div>
                  )}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Create from template */}
        {templates.length > 0 && (
          <Select onValueChange={handleCreateFromTemplate}>
            <SelectTrigger className="h-8 text-xs">
              <SelectValue placeholder="New from template..." />
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
