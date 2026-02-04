import type { Bundle, BundleEntry, FhirResource, Resource } from "fhir/r4";
import { Code, FilePlus, Pencil, Plus, Sparkles, X } from "lucide-react";
import { useCallback, useId, useMemo, useState } from "react";
import {
  hasFormSupport,
  ResourceFormEditorDialog,
} from "@/components/cds/resource-form-editor";
import { JsonEditorDialog } from "@/components/json-editor-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

import { useResourceSearch } from "@/hooks/use-cds-api";
import type {
  CdsService,
  ContextFieldDefinition,
  ResourceTemplate,
} from "@/lib/cds-types";
import { extractFhirReferenceId } from "@/lib/cds-types";
import { getHookDefinition, getResourceTemplates } from "@/lib/cds-types";

// Simple resource type for our purposes
interface SimpleResource {
  resourceType: string;
  id?: string;
  name?: Array<{ given?: string[]; family?: string; text?: string }>;
  code?: { text?: string; coding?: Array<{ display?: string }> };
  medicationCodeableConcept?: {
    text?: string;
    coding?: Array<{ display?: string }>;
  };
}

interface HookContextBuilderProps {
  service: CdsService | null;
  fhirServerUrl: string;
  context: Record<string, unknown>;
  onContextChange: (context: Record<string, unknown>) => void;
  onViewContextResource: (key: string, resource: unknown) => void;
}

interface ResourcePickerProps {
  field: ContextFieldDefinition;
  fhirServerUrl: string;
  value: string;
  onChange: (value: string) => void;
  onViewResource?: (resource: unknown) => void;
}

function ResourcePicker({
  field,
  fhirServerUrl,
  value,
  onChange,
  onViewResource,
}: ResourcePickerProps) {
  const id = useId();
  const resourceTypes = Array.isArray(field.resourceType)
    ? field.resourceType
    : field.resourceType
      ? [field.resourceType]
      : [];

  const primaryType = resourceTypes[0] || "Resource";

  const { data: searchResults, isLoading } = useResourceSearch(
    fhirServerUrl,
    primaryType,
    {},
    !!fhirServerUrl && resourceTypes.length > 0,
  );

  const resources = useMemo((): SimpleResource[] => {
    if (!searchResults?.entry) return [];
    return searchResults.entry
      .map((e) => e.resource as SimpleResource | undefined)
      .filter((r): r is SimpleResource => r != null && r.resourceType != null);
  }, [searchResults]);

  const selectedResource = useMemo(() => {
    if (!value || !resources.length) return null;
    // Value could be "Patient/123" or just "123"
    const resourceId = extractFhirReferenceId(value) ?? value;
    return resources.find((r) => r.id === resourceId) ?? null;
  }, [value, resources]);

  const getResourceDisplay = (resource: SimpleResource): string => {
    const id = resource.id ?? "unknown";
    if (resource.name?.[0]) {
      const name = resource.name[0];
      const displayName = name.text || `${name.given?.join(" ") || ""} ${name.family || ""}`.trim();
      if (displayName) {
        return `${displayName} (${id})`;
      }
    }
    return `${resource.resourceType}/${id}`;
  };

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <Label htmlFor={id} className="text-xs">
          {field.label}
          {field.required && <span className="text-destructive ml-0.5">*</span>}
        </Label>
        {selectedResource && onViewResource && (
          <Button
            variant="ghost"
            size="icon"
            className="h-5 w-5"
            onClick={() => onViewResource(selectedResource)}
            title="View JSON"
          >
            <Code className="h-3 w-3" />
          </Button>
        )}
      </div>
      <Select value={value} onValueChange={onChange} disabled={isLoading}>
        <SelectTrigger id={id} className="h-8 text-xs">
          <SelectValue
            placeholder={
              isLoading ? "Loading..." : `Select ${field.label.toLowerCase()}`
            }
          />
        </SelectTrigger>
        <SelectContent>
          {resources.map((resource) => (
            <SelectItem
              key={resource.id ?? "unknown"}
              value={`${resource.resourceType}/${resource.id}`}
              className="text-xs"
            >
              {getResourceDisplay(resource)}
            </SelectItem>
          ))}
          {resources.length === 0 && !isLoading && (
            <div className="p-2 text-xs text-muted-foreground text-center">
              No {primaryType} resources found
            </div>
          )}
        </SelectContent>
      </Select>
      {field.description && (
        <p className="text-[10px] text-muted-foreground">{field.description}</p>
      )}
    </div>
  );
}

interface BundleBuilderProps {
  field: ContextFieldDefinition;
  fhirServerUrl: string;
  value: Bundle | undefined;
  onChange: (value: Bundle) => void;
  onViewResource?: (resource: unknown) => void;
  /** Patient ID from context for populating new resource subjects */
  contextPatientId?: string;
}

interface SelectedResource extends SimpleResource {
  /** Whether this is a new resource created from a template */
  isNew?: boolean;
  /** Template label if created from template */
  templateLabel?: string;
}

function BundleBuilder({
  field,
  fhirServerUrl,
  value,
  onChange,
  onViewResource,
  contextPatientId,
}: BundleBuilderProps) {
  // Key to force reset the Select after each selection
  const [selectKey, setSelectKey] = useState(0);
  // State for editing a resource
  const [editingResource, setEditingResource] = useState<{
    resource: SimpleResource;
    index: number;
  } | null>(null);

  const resourceTypes = Array.isArray(field.resourceType)
    ? field.resourceType
    : field.resourceType
      ? [field.resourceType]
      : [];

  const primaryType = resourceTypes[0] || "ServiceRequest";

  // Get templates for ALL allowed resource types, grouped by type
  const allTemplates = useMemo(() => {
    const result: Array<{ resourceType: string; templates: ResourceTemplate[] }> =
      [];
    for (const type of resourceTypes) {
      const typeTemplates = getResourceTemplates(type);
      if (typeTemplates.length > 0) {
        result.push({ resourceType: type, templates: typeTemplates });
      }
    }
    return result;
  }, [resourceTypes]);

  const hasTemplates = allTemplates.some((g) => g.templates.length > 0);

  const { data: searchResults, isLoading } = useResourceSearch(
    fhirServerUrl,
    primaryType,
    {},
    !!fhirServerUrl && resourceTypes.length > 0,
  );

  const availableResources = useMemo((): SimpleResource[] => {
    if (!searchResults?.entry) return [];
    return searchResults.entry
      .map((e) => e.resource as SimpleResource | undefined)
      .filter((r): r is SimpleResource => r != null && r.resourceType != null);
  }, [searchResults]);

  const selectedResources = useMemo((): SelectedResource[] => {
    if (!value?.entry) return [];
    return value.entry
      .map((e) => {
        const resource = e.resource as SimpleResource | undefined;
        if (!resource || !resource.resourceType) return null;
        // Check if this is a new resource (has urn:uuid id)
        const isNew = resource.id?.startsWith("urn:uuid:") ?? false;
        return {
          ...resource,
          isNew,
        } as SelectedResource;
      })
      .filter((r): r is SelectedResource => r != null);
  }, [value]);

  // Get patient ID - prefer context, fallback to extracting from existing entries
  const patientId = useMemo(() => {
    // First prefer the context patient ID passed from parent
    if (contextPatientId) {
      // Handle "Patient/123" or just "123" format
      return extractFhirReferenceId(contextPatientId) ?? contextPatientId;
    }

    // Fallback: try to extract patient ID from existing entries
    for (const entry of value?.entry ?? []) {
      const resource = entry.resource as SimpleResource | undefined;
      if (!resource) continue;
      const subject = (resource as unknown as Record<string, unknown>)
        .subject as { reference?: string } | undefined;
      if (subject?.reference?.startsWith("Patient/")) {
        return extractFhirReferenceId(subject.reference) ?? undefined;
      }
    }
    return undefined;
  }, [contextPatientId, value]);

  const addExistingResource = useCallback(
    (resourceId: string) => {
      const resource = availableResources.find((r) => r.id === resourceId);
      if (!resource) return;

      const newEntry: BundleEntry = {
        fullUrl: `${fhirServerUrl}/${resource.resourceType}/${resource.id}`,
        resource: resource as unknown as FhirResource,
      };

      const existingEntries: BundleEntry[] = value?.entry || [];
      const alreadyAdded = existingEntries.some(
        (e) => (e.resource as SimpleResource | undefined)?.id === resource.id,
      );

      if (alreadyAdded) return;

      onChange({
        resourceType: "Bundle",
        type: "collection",
        entry: [...existingEntries, newEntry],
      });
    },
    [availableResources, value, onChange, fhirServerUrl],
  );

  const addNewResourceFromTemplate = useCallback(
    (template: ResourceTemplate) => {
      const newResource = template.create(patientId);
      const newEntry: BundleEntry = {
        fullUrl: newResource.id,
        resource: newResource as FhirResource,
      };

      const existingEntries: BundleEntry[] = value?.entry || [];

      onChange({
        resourceType: "Bundle",
        type: "collection",
        entry: [...existingEntries, newEntry],
      });
    },
    [patientId, value, onChange],
  );

  const handleSelectChange = useCallback(
    (selectedValue: string) => {
      if (selectedValue.startsWith("template:")) {
        // Format: template:ResourceType:index
        const [, resourceType, indexStr] = selectedValue.split(":");
        const templateIndex = parseInt(indexStr, 10);
        const typeTemplates = getResourceTemplates(resourceType);
        const template = typeTemplates[templateIndex];
        if (template) {
          addNewResourceFromTemplate(template);
        }
      } else if (selectedValue.startsWith("existing:")) {
        const resourceId = selectedValue.replace("existing:", "");
        addExistingResource(resourceId);
      }
      // Force reset the Select by changing its key
      setSelectKey((k) => k + 1);
    },
    [addNewResourceFromTemplate, addExistingResource],
  );

  const removeResource = useCallback(
    (resourceId: string) => {
      if (!value?.entry) return;

      onChange({
        ...value,
        entry: value.entry.filter(
          (e) => (e.resource as SimpleResource | undefined)?.id !== resourceId,
        ),
      });
    },
    [value, onChange],
  );

  const updateResource = useCallback(
    (index: number, updatedResource: unknown) => {
      if (!value?.entry) return;

      const newEntries = [...value.entry];
      if (newEntries[index]) {
        newEntries[index] = {
          ...newEntries[index],
          resource: updatedResource as FhirResource,
        };
        onChange({
          ...value,
          entry: newEntries,
        });
      }
      setEditingResource(null);
    },
    [value, onChange],
  );

  const getResourceDisplay = (resource: SelectedResource): string => {
    const id = resource.id ?? "unknown";
    const codeDisplay = resource.code?.text || resource.code?.coding?.[0]?.display;
    const medDisplay = resource.medicationCodeableConcept?.text ||
      resource.medicationCodeableConcept?.coding?.[0]?.display;

    // For new resources (urn:uuid), show just the display name without the UUID
    if (resource.isNew) {
      return codeDisplay || medDisplay || resource.resourceType;
    }

    // For existing resources, include the ID
    if (codeDisplay) {
      return `${codeDisplay} (${id})`;
    }
    if (medDisplay) {
      return `${medDisplay} (${id})`;
    }
    return `${resource.resourceType}/${id}`;
  };

  const hasExistingResources =
    availableResources.filter(
      (r) => !selectedResources.some((s) => s.id === r.id),
    ).length > 0;

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <Label className="text-xs">
          {field.label}
          {field.required && <span className="text-destructive ml-0.5">*</span>}
        </Label>
        <Badge variant="secondary" className="text-[10px]">
          {selectedResources.length} selected
        </Badge>
      </div>

      {/* Selected resources */}
      {selectedResources.length > 0 && (
        <div className="space-y-1">
          {selectedResources.map((resource, index) => (
            <div
              key={resource.id ?? `resource-${index}`}
              className={`flex items-center justify-between p-1.5 rounded text-xs ${
                resource.isNew
                  ? "bg-primary/10 border border-primary/20"
                  : "bg-muted/50"
              }`}
            >
              <div className="flex items-center gap-1.5 min-w-0">
                {resource.isNew && (
                  <Sparkles className="h-3 w-3 text-primary shrink-0" />
                )}
                <span className="truncate">{getResourceDisplay(resource)}</span>
                {resource.isNew && (
                  <Badge variant="outline" className="text-[9px] shrink-0">
                    new
                  </Badge>
                )}
              </div>
              <div className="flex items-center gap-1 shrink-0">
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-5 w-5"
                  onClick={() => setEditingResource({ resource, index })}
                  title="Edit JSON"
                >
                  <Pencil className="h-3 w-3" />
                </Button>
                {onViewResource && (
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-5 w-5"
                    onClick={() => onViewResource(resource)}
                    title="View JSON"
                  >
                    <Code className="h-3 w-3" />
                  </Button>
                )}
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-5 w-5 text-destructive"
                  onClick={() => resource.id && removeResource(resource.id)}
                  title="Remove"
                >
                  <X className="h-3 w-3" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Add resource dropdown */}
      <Select
        key={selectKey}
        onValueChange={handleSelectChange}
        disabled={isLoading}
      >
        <SelectTrigger className="h-8 text-xs">
          <Plus className="h-3 w-3 mr-1" />
          <SelectValue
            placeholder={isLoading ? "Loading..." : "Add resource"}
          />
        </SelectTrigger>
        <SelectContent>
          {/* New resource templates - grouped by resource type */}
          {allTemplates.map(({ resourceType, templates }, groupIndex) => (
            <SelectGroup key={resourceType}>
              <SelectLabel className="text-[10px] flex items-center gap-1">
                <FilePlus className="h-3 w-3" />
                New {resourceType}
              </SelectLabel>
              {templates.map((template, index) => (
                <SelectItem
                  key={`${resourceType}-${template.label}`}
                  value={`template:${resourceType}:${index}`}
                  className="text-xs"
                >
                  <div className="flex items-center gap-1.5">
                    <Sparkles className="h-3 w-3 text-primary" />
                    {template.label}
                  </div>
                </SelectItem>
              ))}
              {/* Add separator between template groups */}
              {groupIndex < allTemplates.length - 1 && <SelectSeparator />}
            </SelectGroup>
          ))}

          {/* Separator if we have both templates and existing resources */}
          {hasTemplates && hasExistingResources && <SelectSeparator />}

          {/* Existing resources from server */}
          {hasExistingResources && (
            <SelectGroup>
              <SelectLabel className="text-[10px]">From Server</SelectLabel>
              {availableResources
                .filter((r) => !selectedResources.some((s) => s.id === r.id))
                .map((resource) => (
                  <SelectItem
                    key={resource.id ?? "unknown"}
                    value={`existing:${resource.id}`}
                    className="text-xs"
                  >
                    {getResourceDisplay(resource as SelectedResource)}
                  </SelectItem>
                ))}
            </SelectGroup>
          )}

          {/* Empty state */}
          {!hasTemplates &&
            availableResources.length === 0 &&
            !isLoading && (
              <div className="p-2 text-xs text-muted-foreground text-center">
                No resources or templates available
              </div>
            )}
        </SelectContent>
      </Select>

      {field.description && (
        <p className="text-[10px] text-muted-foreground">{field.description}</p>
      )}

      {/* Edit resource dialog - use form editor for supported types, JSON for others */}
      {editingResource &&
        (hasFormSupport(editingResource.resource.resourceType) ? (
          <ResourceFormEditorDialog
            resource={editingResource.resource as Resource}
            onClose={() => setEditingResource(null)}
            onSave={(updatedData) =>
              updateResource(editingResource.index, updatedData)
            }
          />
        ) : (
          <JsonEditorDialog
            data={editingResource.resource}
            title={`Edit ${editingResource.resource.resourceType}`}
            description="Modify the resource JSON and save changes"
            onClose={() => setEditingResource(null)}
            onSave={(updatedData) =>
              updateResource(editingResource.index, updatedData)
            }
          />
        ))}
    </div>
  );
}

interface BundleSelectPickerProps {
  field: ContextFieldDefinition;
  bundle: Bundle | undefined;
  value: string[];
  onChange: (value: string[]) => void;
}

function BundleSelectPicker({
  field,
  bundle,
  value,
  onChange,
}: BundleSelectPickerProps) {
  const entries = useMemo((): SimpleResource[] => {
    if (!bundle?.entry) return [];
    return bundle.entry
      .map((e) => e.resource as SimpleResource | undefined)
      .filter((r): r is SimpleResource => r != null && r.id != null);
  }, [bundle]);

  const getResourceDisplay = (resource: SimpleResource): string => {
    const id = resource.id ?? "unknown";
    const isNew = id.startsWith("urn:uuid:");
    const codeDisplay = resource.code?.text || resource.code?.coding?.[0]?.display;
    const medDisplay = resource.medicationCodeableConcept?.text ||
      resource.medicationCodeableConcept?.coding?.[0]?.display;

    // For new resources (urn:uuid), show just the display name without the UUID
    if (isNew) {
      return codeDisplay || medDisplay || resource.resourceType;
    }

    // For existing resources, include the ID
    if (codeDisplay) {
      return `${codeDisplay} (${id})`;
    }
    if (medDisplay) {
      return `${medDisplay} (${id})`;
    }
    return `${resource.resourceType}/${id}`;
  };

  const toggleSelection = useCallback(
    (resourceId: string) => {
      if (value.includes(resourceId)) {
        onChange(value.filter((id) => id !== resourceId));
      } else {
        onChange([...value, resourceId]);
      }
    },
    [value, onChange],
  );

  const selectAll = useCallback(() => {
    const allIds = entries.map((r) => r.id!);
    onChange(allIds);
  }, [entries, onChange]);

  const selectNone = useCallback(() => {
    onChange([]);
  }, [onChange]);

  if (entries.length === 0) {
    return (
      <div className="space-y-1.5">
        <Label className="text-xs">
          {field.label}
          {field.required && <span className="text-destructive ml-0.5">*</span>}
        </Label>
        <p className="text-xs text-muted-foreground">
          Add items to {field.sourceBundle} first to make selections.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <Label className="text-xs">
          {field.label}
          {field.required && <span className="text-destructive ml-0.5">*</span>}
        </Label>
        <div className="flex items-center gap-2">
          <Badge variant="secondary" className="text-[10px]">
            {value.length}/{entries.length} selected
          </Badge>
          <Button
            variant="ghost"
            size="sm"
            className="h-5 px-1.5 text-[10px]"
            onClick={selectAll}
          >
            All
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="h-5 px-1.5 text-[10px]"
            onClick={selectNone}
          >
            None
          </Button>
        </div>
      </div>

      <div className="space-y-1 rounded-md border p-2">
        {entries.map((resource) => (
          <div
            key={resource.id}
            className="flex items-center gap-2 p-1.5 rounded hover:bg-muted/50"
          >
            <Checkbox
              id={`select-${resource.id}`}
              checked={value.includes(resource.id!)}
              onCheckedChange={() => toggleSelection(resource.id!)}
            />
            <label
              htmlFor={`select-${resource.id}`}
              className="flex-1 text-xs cursor-pointer"
            >
              {getResourceDisplay(resource)}
            </label>
          </div>
        ))}
      </div>

      {field.description && (
        <p className="text-[10px] text-muted-foreground">{field.description}</p>
      )}
    </div>
  );
}

export function HookContextBuilder({
  service,
  fhirServerUrl,
  context,
  onContextChange,
  onViewContextResource,
}: HookContextBuilderProps) {
  const hookDefinition = service ? getHookDefinition(service.hook) : null;

  // Extract patient ID from context for use in creating new resources
  const contextPatientId = useMemo(() => {
    const patientId = context.patientId;
    if (typeof patientId === "string") return patientId;
    return undefined;
  }, [context.patientId]);

  const updateContext = useCallback(
    (key: string, value: unknown) => {
      onContextChange({
        ...context,
        [key]: value,
      });
    },
    [context, onContextChange],
  );

  if (!service) {
    return (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm">Hook Context</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            Select a service to configure the hook context.
          </p>
        </CardContent>
      </Card>
    );
  }

  const fields = hookDefinition?.contextFields || [];

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-sm">
          Hook Context
          <Badge variant="outline" className="ml-2 text-[10px]">
            {service.hook}
          </Badge>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {fields.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No context fields defined for this hook type.
          </p>
        ) : (
          fields.map((field) => {
            if (field.type === "bundle") {
              return (
                <BundleBuilder
                  key={field.name}
                  field={field}
                  fhirServerUrl={fhirServerUrl}
                  value={context[field.name] as Bundle | undefined}
                  onChange={(bundleValue) =>
                    updateContext(field.name, bundleValue)
                  }
                  onViewResource={(resource) =>
                    onViewContextResource(field.name, resource)
                  }
                  contextPatientId={contextPatientId}
                />
              );
            }

            if (field.type === "bundleSelect" && field.sourceBundle) {
              return (
                <BundleSelectPicker
                  key={field.name}
                  field={field}
                  bundle={context[field.sourceBundle] as Bundle | undefined}
                  value={(context[field.name] as string[]) || []}
                  onChange={(selections) =>
                    updateContext(field.name, selections)
                  }
                />
              );
            }

            if (field.type === "reference") {
              return (
                <ResourcePicker
                  key={field.name}
                  field={field}
                  fhirServerUrl={fhirServerUrl}
                  value={(context[field.name] as string) || ""}
                  onChange={(refValue) => updateContext(field.name, refValue)}
                  onViewResource={(resource) =>
                    onViewContextResource(field.name, resource)
                  }
                />
              );
            }

            // String field
            return (
              <div key={field.name} className="space-y-1.5">
                <Label className="text-xs">
                  {field.label}
                  {field.required && (
                    <span className="text-destructive ml-0.5">*</span>
                  )}
                </Label>
                <Input
                  className="h-8 text-xs"
                  value={(context[field.name] as string) || ""}
                  onChange={(e) => updateContext(field.name, e.target.value)}
                  placeholder={field.description}
                />
              </div>
            );
          })
        )}
      </CardContent>
    </Card>
  );
}
