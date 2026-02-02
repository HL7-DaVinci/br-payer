import {
  AlertCircle,
  CheckCircle,
  Clock,
  Code,
  FileQuestion,
  HelpCircle,
  Link as LinkIcon,
  XCircle,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import type {
  CoverageInformationExtension,
  CoverageStatus,
  DocNeededStatus,
  PriorAuthStatus,
} from "@/lib/cds-types";

interface CoverageInformationPanelProps {
  coverageInfo: CoverageInformationExtension;
  resourceType: string;
  resourceId: string;
  onViewJson: () => void;
}

// Coverage status configuration
const coverageStatusConfig: Record<
  CoverageStatus,
  { icon: typeof CheckCircle; color: string; bgColor: string; label: string }
> = {
  covered: {
    icon: CheckCircle,
    color: "text-green-600",
    bgColor: "bg-green-500/10",
    label: "Covered",
  },
  "not-covered": {
    icon: XCircle,
    color: "text-red-600",
    bgColor: "bg-red-500/10",
    label: "Not Covered",
  },
  conditional: {
    icon: HelpCircle,
    color: "text-amber-600",
    bgColor: "bg-amber-500/10",
    label: "Conditional",
  },
};

// Prior auth status configuration
const paStatusConfig: Record<
  PriorAuthStatus,
  { icon: typeof CheckCircle; color: string; bgColor: string; label: string }
> = {
  "no-auth": {
    icon: CheckCircle,
    color: "text-green-600",
    bgColor: "bg-green-500/10",
    label: "No Auth Required",
  },
  "auth-needed": {
    icon: AlertCircle,
    color: "text-orange-600",
    bgColor: "bg-orange-500/10",
    label: "Auth Needed",
  },
  satisfied: {
    icon: CheckCircle,
    color: "text-green-600",
    bgColor: "bg-green-500/10",
    label: "Satisfied",
  },
  performpa: {
    icon: Clock,
    color: "text-blue-600",
    bgColor: "bg-blue-500/10",
    label: "Perform PA",
  },
  conditional: {
    icon: HelpCircle,
    color: "text-amber-600",
    bgColor: "bg-amber-500/10",
    label: "Conditional",
  },
};

// Doc needed status configuration
const docStatusConfig: Record<
  DocNeededStatus,
  { icon: typeof CheckCircle; color: string; bgColor: string; label: string }
> = {
  "no-doc": {
    icon: CheckCircle,
    color: "text-green-600",
    bgColor: "bg-green-500/10",
    label: "No Docs Required",
  },
  clinical: {
    icon: FileQuestion,
    color: "text-orange-600",
    bgColor: "bg-orange-500/10",
    label: "Clinical Docs",
  },
  admin: {
    icon: FileQuestion,
    color: "text-orange-600",
    bgColor: "bg-orange-500/10",
    label: "Admin Docs",
  },
  patient: {
    icon: FileQuestion,
    color: "text-orange-600",
    bgColor: "bg-orange-500/10",
    label: "Patient Docs",
  },
  conditional: {
    icon: HelpCircle,
    color: "text-amber-600",
    bgColor: "bg-amber-500/10",
    label: "Conditional",
  },
};

function StatusBadge({
  config,
  label,
}: {
  config: { icon: typeof CheckCircle; color: string; bgColor: string };
  label: string;
}) {
  const Icon = config.icon;
  return (
    <div
      className={`flex flex-col items-center justify-center p-3 rounded-lg border ${config.bgColor}`}
    >
      <Icon className={`h-5 w-5 ${config.color}`} />
      <span className={`text-xs font-medium mt-1 ${config.color}`}>
        {label}
      </span>
    </div>
  );
}

export function CoverageInformationPanel({
  coverageInfo,
  resourceType,
  resourceId,
  onViewJson,
}: CoverageInformationPanelProps) {
  const coverageConfig = coverageInfo.covered
    ? coverageStatusConfig[coverageInfo.covered]
    : null;
  const paConfig = coverageInfo.paNeeded
    ? paStatusConfig[coverageInfo.paNeeded]
    : null;
  const docConfig = coverageInfo.docNeeded
    ? docStatusConfig[coverageInfo.docNeeded]
    : null;

  return (
    <Card className="border-primary/20 border-l-4 border-l-primary">
      <CardHeader className="pb-2">
        <div className="flex items-start justify-between">
          <div>
            <CardTitle className="text-sm font-medium flex items-center gap-2">
              Coverage Information
              <Badge variant="outline" className="text-[10px]">
                {resourceType}/{resourceId}
              </Badge>
            </CardTitle>
            <div className="flex items-center gap-3 mt-1 text-xs text-muted-foreground">
              {coverageInfo.coverageAssertionId && (
                <span>ID: {coverageInfo.coverageAssertionId}</span>
              )}
              {coverageInfo.date && <span>Date: {coverageInfo.date}</span>}
            </div>
          </div>
          <Button
            variant="ghost"
            size="icon"
            className="h-6 w-6"
            onClick={onViewJson}
            title="View Raw JSON"
          >
            <Code className="h-3 w-3" />
          </Button>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {/* Status Badges Row */}
        <div className="grid grid-cols-3 gap-3">
          {coverageConfig && (
            <StatusBadge config={coverageConfig} label={coverageConfig.label} />
          )}
          {paConfig && <StatusBadge config={paConfig} label={paConfig.label} />}
          {docConfig && (
            <StatusBadge config={docConfig} label={docConfig.label} />
          )}
        </div>

        {/* Coverage Reference */}
        {coverageInfo.coverage && (
          <div className="text-sm">
            <span className="font-medium">Coverage: </span>
            <code className="text-xs bg-muted px-1.5 py-0.5 rounded">
              {coverageInfo.coverage.reference || coverageInfo.coverage.display}
            </code>
          </div>
        )}

        {/* Reasons */}
        {coverageInfo.reasons && coverageInfo.reasons.length > 0 && (
          <div className="space-y-1.5">
            <p className="text-xs font-medium text-muted-foreground">Reasons</p>
            <ul className="text-sm space-y-1 list-disc list-inside">
              {coverageInfo.reasons.map((reason) => (
                <li
                  key={
                    reason.coding?.[0]?.code ?? reason.text ?? reason.toString()
                  }
                  className="text-muted-foreground"
                >
                  {reason.text ||
                    reason.coding?.[0]?.display ||
                    reason.coding?.[0]?.code}
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* Billing Codes */}
        {coverageInfo.billingCodes && coverageInfo.billingCodes.length > 0 && (
          <div className="space-y-1.5">
            <p className="text-xs font-medium text-muted-foreground">
              Applicable Billing Codes
            </p>
            <div className="flex flex-wrap gap-1.5">
              {coverageInfo.billingCodes.map((code) => (
                <Badge
                  key={`${code.system ?? "code"}-${code.code}`}
                  variant="secondary"
                  className="text-xs"
                >
                  {code.code}
                  {code.system && (
                    <span className="ml-1 text-muted-foreground">
                      ({code.system.split("/").pop()})
                    </span>
                  )}
                </Badge>
              ))}
            </div>
          </div>
        )}

        {/* Coverage Details */}
        {coverageInfo.details && coverageInfo.details.length > 0 && (
          <div className="space-y-1.5">
            <p className="text-xs font-medium text-muted-foreground">
              Coverage Details
            </p>
            <div className="space-y-2">
              {coverageInfo.details.map((detail) => (
                <div
                  key={`${detail.category ?? "detail"}-${detail.code?.coding?.[0]?.code ?? detail.code?.text ?? "item"}`}
                  className="text-sm p-2 bg-muted/50 rounded-md space-y-1"
                >
                  {detail.code && (
                    <div className="font-medium">
                      {detail.code.text ||
                        detail.code.coding?.[0]?.display ||
                        detail.category}
                    </div>
                  )}
                  {detail.value !== undefined && (
                    <div className="text-muted-foreground">
                      {typeof detail.value === "object" &&
                      "value" in detail.value
                        ? `${detail.value.value}${detail.value.unit || ""}`
                        : String(detail.value)}
                    </div>
                  )}
                  {detail.qualification && (
                    <div className="text-xs text-muted-foreground italic">
                      {detail.qualification}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Prior Authorization */}
        {coverageInfo.satisfiedPaId && (
          <>
            <Separator />
            <div className="flex items-center gap-2">
              <CheckCircle className="h-4 w-4 text-green-600" />
              <span className="text-sm font-medium">Prior Authorization</span>
              <Badge variant="default" className="text-xs">
                {coverageInfo.satisfiedPaId}
              </Badge>
            </div>
          </>
        )}

        {/* Info Needed */}
        {coverageInfo.infoNeeded && coverageInfo.infoNeeded.length > 0 && (
          <div className="space-y-1.5">
            <p className="text-xs font-medium text-muted-foreground">
              Additional Information Needed
            </p>
            <div className="flex flex-wrap gap-1.5">
              {coverageInfo.infoNeeded.map((info) => (
                <Badge key={info} variant="outline" className="text-xs">
                  {info}
                </Badge>
              ))}
            </div>
          </div>
        )}

        {/* Required Questionnaires (DTR) */}
        {coverageInfo.questionnaires &&
          coverageInfo.questionnaires.length > 0 && (
            <div className="space-y-1.5">
              <p className="text-xs font-medium text-muted-foreground">
                Required Questionnaires (DTR)
              </p>
              <ul className="text-xs space-y-1">
                {coverageInfo.questionnaires.map((q) => (
                  <li
                    key={q}
                    className="font-mono text-muted-foreground truncate"
                  >
                    {q}
                  </li>
                ))}
              </ul>
            </div>
          )}

        {/* Payer Contact */}
        {coverageInfo.contact && (
          <div className="flex items-center gap-2 text-sm">
            <LinkIcon className="h-3.5 w-3.5 text-muted-foreground" />
            <span className="text-muted-foreground">Contact:</span>
            {coverageInfo.contact.telecom?.[0]?.value ? (
              <a
                href={coverageInfo.contact.telecom[0].value}
                target="_blank"
                rel="noopener noreferrer"
                className="text-primary hover:underline truncate"
              >
                {coverageInfo.contact.telecom[0].value}
              </a>
            ) : (
              <span className="text-muted-foreground">
                {coverageInfo.contact.name || "Contact payer"}
              </span>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
