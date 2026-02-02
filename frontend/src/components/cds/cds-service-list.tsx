import { Code, Loader2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { CdsService } from "@/lib/cds-types";

interface CdsServiceListProps {
  services: CdsService[] | undefined;
  selectedService: CdsService | null;
  onSelectService: (service: CdsService) => void;
  onViewDiscovery: () => void;
  onViewService: (service: CdsService) => void;
  isLoading: boolean;
  error: Error | null;
}

export function CdsServiceList({
  services,
  selectedService,
  onSelectService,
  onViewDiscovery,
  onViewService,
  isLoading,
  error,
}: CdsServiceListProps) {
  if (isLoading) {
    return (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm">Available Services</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        </CardContent>
      </Card>
    );
  }

  if (error) {
    return (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm">Available Services</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="text-sm text-destructive p-3 bg-destructive/10 rounded-md">
            Failed to discover services: {error.message}
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-sm">Available Services</CardTitle>
            <CardDescription className="text-xs">
              {services?.length ?? 0} service(s) found
            </CardDescription>
          </div>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 text-xs"
            onClick={onViewDiscovery}
            disabled={!services}
          >
            <Code className="h-3 w-3 mr-1" />
            View Discovery
          </Button>
        </div>
      </CardHeader>
      <CardContent className="p-0">
        <div className="p-3 pt-0 space-y-1">
          {services?.map((service) => (
            <div
              key={`${service.hook}-${service.id}`}
              className={`
                  relative w-full text-left p-2.5 rounded-lg border transition-colors
                  ${
                    selectedService?.id === service.id &&
                    selectedService?.hook === service.hook
                      ? "bg-primary/10 border-primary/30"
                      : "hover:bg-muted/50 border-transparent"
                  }
                `}
            >
              <button
                type="button"
                className="absolute inset-0 cursor-pointer"
                onClick={() => onSelectService(service)}
                aria-label={`Select ${service.title || service.id}`}
              />
              <div className="flex items-start justify-between gap-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-sm truncate">
                      {service.title || service.id}
                    </span>
                    <Badge variant="secondary" className="text-[10px] shrink-0">
                      {service.hook}
                    </Badge>
                  </div>
                  <p className="text-xs text-muted-foreground mt-0.5 line-clamp-2">
                    {service.description}
                  </p>
                  {service.prefetch && (
                    <div className="flex flex-wrap gap-1 mt-1.5">
                      {Object.keys(service.prefetch).map((key) => (
                        <Badge
                          key={key}
                          variant="outline"
                          className="text-[10px] px-1 py-0"
                        >
                          {key}
                        </Badge>
                      ))}
                    </div>
                  )}
                </div>
                <Button
                  variant="ghost"
                  size="icon"
                  className="relative z-10 h-6 w-6 shrink-0"
                  onClick={() => onViewService(service)}
                  title="View Service JSON"
                >
                  <Code className="h-3 w-3" />
                </Button>
              </div>
            </div>
          ))}

          {(!services || services.length === 0) && (
            <div className="text-center py-8 text-sm text-muted-foreground">
              No services available
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
