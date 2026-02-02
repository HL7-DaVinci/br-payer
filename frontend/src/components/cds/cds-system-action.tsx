import type { Resource } from "fhir/r4";
import { Code, FileEdit, FilePlus, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { CdsSystemAction as CdsSystemActionType } from "@/lib/cds-types";

interface CdsSystemActionProps {
  action: CdsSystemActionType;
  index: number;
  onViewJson: () => void;
  onViewResource?: () => void;
}

const actionConfig = {
  create: {
    icon: FilePlus,
    color: "text-green-500",
    bgColor: "bg-green-500/10",
    label: "Create",
  },
  update: {
    icon: FileEdit,
    color: "text-blue-500",
    bgColor: "bg-blue-500/10",
    label: "Update",
  },
  delete: {
    icon: Trash2,
    color: "text-red-500",
    bgColor: "bg-red-500/10",
    label: "Delete",
  },
};

function getResourceDisplay(resource?: Resource, resourceId?: string): string {
  if (resource) {
    const type = resource.resourceType || "Resource";
    const id = resource.id || "unknown";
    return `${type}/${id}`;
  }
  if (resourceId) {
    return resourceId;
  }
  return "Unknown resource";
}

export function CdsSystemAction({
  action,
  index,
  onViewJson,
  onViewResource,
}: CdsSystemActionProps) {
  const config = actionConfig[action.type] || actionConfig.update;
  const ActionIcon = config.icon;
  const resourceDisplay = getResourceDisplay(
    action.resource,
    action.resourceId,
  );

  return (
    <Card className="border-dashed">
      <CardHeader className="pb-2 pt-3">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-3">
            <div
              className={`p-1.5 rounded-md ${config.bgColor} ${config.color}`}
            >
              <ActionIcon className="h-4 w-4" />
            </div>
            <div>
              <CardTitle className="text-sm font-medium">
                {config.label}: {resourceDisplay}
              </CardTitle>
              {action.description && (
                <p className="text-xs text-muted-foreground mt-0.5">
                  {action.description}
                </p>
              )}
            </div>
          </div>
          <div className="flex items-center gap-1">
            <Badge variant="outline" className="text-[10px]">
              Action {index + 1}
            </Badge>
            <Button
              variant="ghost"
              size="icon"
              className="h-6 w-6"
              onClick={onViewJson}
              title="View Action JSON"
            >
              <Code className="h-3 w-3" />
            </Button>
          </div>
        </div>
      </CardHeader>

      {action.resource && (
        <CardContent className="pt-0 pb-3">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>
              Resource Type: <code>{action.resource.resourceType}</code>
            </span>
            {onViewResource && (
              <Button
                variant="ghost"
                size="sm"
                className="h-6 text-xs"
                onClick={onViewResource}
              >
                <Code className="h-3 w-3 mr-1" />
                View Resource
              </Button>
            )}
          </div>
        </CardContent>
      )}
    </Card>
  );
}
