import {
  AlertCircle,
  AlertTriangle,
  Code,
  ExternalLink,
  Info,
  Zap,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { CdsCard as CdsCardType } from "@/lib/cds-types";

interface CdsCardProps {
  card: CdsCardType;
  index: number;
  onViewJson: () => void;
}

const indicatorConfig = {
  info: {
    icon: Info,
    color: "text-blue-500",
    bgColor: "bg-blue-500/10",
    borderColor: "border-blue-500/20",
    label: "Info",
  },
  warning: {
    icon: AlertTriangle,
    color: "text-amber-500",
    bgColor: "bg-amber-500/10",
    borderColor: "border-amber-500/20",
    label: "Warning",
  },
  critical: {
    icon: AlertCircle,
    color: "text-red-500",
    bgColor: "bg-red-500/10",
    borderColor: "border-red-500/20",
    label: "Critical",
  },
};

export function CdsCard({ card, index, onViewJson }: CdsCardProps) {
  const config = indicatorConfig[card.indicator] || indicatorConfig.info;
  const IndicatorIcon = config.icon;

  return (
    <Card className={`${config.borderColor} border-l-4`}>
      <CardHeader className="pb-2">
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-start gap-3">
            <div
              className={`p-1.5 rounded-md ${config.bgColor} ${config.color}`}
            >
              <IndicatorIcon className="h-4 w-4" />
            </div>
            <div className="space-y-1">
              <CardTitle className="text-sm font-medium leading-tight">
                {card.summary}
              </CardTitle>
              {card.source && (
                <CardDescription className="text-xs flex items-center gap-1.5">
                  {card.source.icon && (
                    <img
                      src={card.source.icon}
                      alt=""
                      className="h-3 w-3 rounded"
                    />
                  )}
                  {card.source.label}
                  {card.source.topic?.display && (
                    <Badge variant="outline" className="text-[10px] px-1 py-0">
                      {card.source.topic.display}
                    </Badge>
                  )}
                </CardDescription>
              )}
            </div>
          </div>
          <div className="flex items-center gap-1">
            <Badge variant="secondary" className="text-[10px]">
              Card {index + 1}
            </Badge>
            <Button
              variant="ghost"
              size="icon"
              className="h-6 w-6"
              onClick={onViewJson}
              title="View JSON"
            >
              <Code className="h-3 w-3" />
            </Button>
          </div>
        </div>
      </CardHeader>

      {(card.detail || card.links?.length || card.suggestions?.length) && (
        <CardContent className="pt-0 space-y-3">
          {card.detail && (
            <div className="text-sm text-muted-foreground prose prose-sm dark:prose-invert max-w-none">
              {card.detail}
            </div>
          )}

          {/* Links */}
          {card.links && card.links.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {card.links.map((link) => (
                <Button
                  key={`${link.type}-${link.url}`}
                  variant="outline"
                  size="sm"
                  className="h-7 text-xs"
                  asChild
                >
                  <a href={link.url} target="_blank" rel="noopener noreferrer">
                    {link.type === "smart" ? (
                      <Zap className="h-3 w-3 mr-1" />
                    ) : (
                      <ExternalLink className="h-3 w-3 mr-1" />
                    )}
                    {link.label}
                  </a>
                </Button>
              ))}
            </div>
          )}

          {/* Suggestions */}
          {card.suggestions && card.suggestions.length > 0 && (
            <div className="space-y-2">
              <p className="text-xs font-medium text-muted-foreground">
                Suggestions:
              </p>
              <div className="flex flex-wrap gap-2">
                {card.suggestions.map((suggestion) => (
                  <Button
                    key={suggestion.uuid ?? suggestion.label}
                    variant={suggestion.isRecommended ? "default" : "secondary"}
                    size="sm"
                    className="h-7 text-xs"
                    title={
                      suggestion.actions
                        ? `${suggestion.actions.length} action(s)`
                        : undefined
                    }
                  >
                    {suggestion.label}
                    {suggestion.isRecommended && (
                      <Badge
                        variant="outline"
                        className="ml-1 text-[10px] px-1 py-0"
                      >
                        Recommended
                      </Badge>
                    )}
                  </Button>
                ))}
              </div>
            </div>
          )}
        </CardContent>
      )}
    </Card>
  );
}
