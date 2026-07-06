import {
  AlertCircle,
  CheckCircle2,
  Circle,
  Loader2,
  Trash2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";

export interface CdexTimelineEvent {
  id: string;
  timestamp: Date;
  kind: "info" | "success" | "error" | "pending";
  label: string;
  detail?: string;
  data?: unknown;
}

interface CdexTimelineProps {
  events: CdexTimelineEvent[];
  onView: (data: unknown, title: string) => void;
  onClear: () => void;
}

const ICONS = {
  info: Circle,
  success: CheckCircle2,
  error: AlertCircle,
  pending: Loader2,
} as const;

const ICON_CLASSES = {
  info: "text-muted-foreground",
  success: "text-success",
  error: "text-destructive",
  pending: "text-muted-foreground animate-spin",
} as const;

export function CdexTimeline({ events, onView, onClear }: CdexTimelineProps) {
  const newestFirst = [...events].reverse();
  return (
    <Card className="flex flex-col h-full">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="text-base">Timeline</CardTitle>
          {events.length > 0 && (
            <Button
              variant="ghost"
              size="sm"
              className="h-7 text-xs text-muted-foreground"
              onClick={onClear}
            >
              <Trash2 className="h-3 w-3 mr-1" />
              Clear
            </Button>
          )}
        </div>
        <CardDescription>Workflow events, newest first</CardDescription>
      </CardHeader>
      <CardContent className="flex-1 min-h-0 p-2 pt-0">
        <ScrollArea className="h-full">
          {events.length === 0 && (
            <p className="text-sm text-muted-foreground px-2 py-4">
              Select a pended claim and send documentation to see events here.
            </p>
          )}
          <div className="flex flex-col gap-2">
            {newestFirst.map((event) => {
              const Icon = ICONS[event.kind];
              return (
                <div
                  key={event.id}
                  className="flex items-start gap-2 rounded-md border p-2"
                >
                  <Icon
                    className={`h-4 w-4 mt-0.5 shrink-0 ${ICON_CLASSES[event.kind]}`}
                  />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-medium">{event.label}</span>
                      <span className="text-xs text-muted-foreground">
                        {event.timestamp.toLocaleTimeString()}
                      </span>
                    </div>
                    {event.detail && (
                      <p className="text-xs text-muted-foreground mt-0.5">
                        {event.detail}
                      </p>
                    )}
                    {event.data !== undefined && (
                      <Button
                        variant="link"
                        size="sm"
                        className="h-auto p-0 text-xs"
                        onClick={() => onView(event.data, event.label)}
                      >
                        View JSON
                      </Button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </ScrollArea>
      </CardContent>
    </Card>
  );
}
