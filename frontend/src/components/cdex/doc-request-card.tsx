import { FileQuestion, Paperclip } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { DocumentationRequest } from "@/lib/cdex-types";

interface DocRequestCardProps {
  request: DocumentationRequest;
  onViewRequest: (request: DocumentationRequest) => void;
  onViewTask: (request: DocumentationRequest) => void;
}

export function DocRequestCard({
  request,
  onViewRequest,
  onViewTask,
}: DocRequestCardProps) {
  const isQuestionnaire = request.type === "questionnaire";
  const Icon = isQuestionnaire ? FileQuestion : Paperclip;
  const title = isQuestionnaire
    ? (request.questionnaireName ??
      request.questionnaireCanonical ??
      "Questionnaire")
    : `Attachment code ${request.code ?? "unknown"}`;

  return (
    <div className="rounded-md border p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <Icon className="h-4 w-4 shrink-0 text-muted-foreground" />
          <span className="text-sm font-medium truncate">{title}</span>
        </div>
        <Badge variant={request.status === "completed" ? "default" : "outline"}>
          {request.status ?? "unknown"}
        </Badge>
      </div>
      <div className="mt-1 text-xs text-muted-foreground">
        {isQuestionnaire
          ? "Complete the questionnaire"
          : "Provide the coded attachment"}
        {request.lineNumber !== null ? ` · Line ${request.lineNumber}` : ""}
        {request.trn ? ` · TRN ${request.trn}` : ""}
      </div>
      <div className="mt-2 flex gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => onViewRequest(request)}
        >
          CommunicationRequest
        </Button>
        <Button variant="outline" size="sm" onClick={() => onViewTask(request)}>
          View as CDex Task
        </Button>
      </div>
    </div>
  );
}
