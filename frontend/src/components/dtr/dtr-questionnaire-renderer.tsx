import { ArrowLeft, Code } from "lucide-react";
import { useRef } from "react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { ParsedPackageBundle } from "@/lib/dtr-types";
import {
  QuestionnaireForm,
  type QuestionnaireFormHandle,
} from "./questionnaire-form";

interface DtrQuestionnaireRendererProps {
  bundle: ParsedPackageBundle;
  onViewJson: (data: unknown, title: string, description?: string) => void;
  onBack: () => void;
}

export function DtrQuestionnaireRenderer({
  bundle,
  onViewJson,
  onBack,
}: DtrQuestionnaireRendererProps) {
  const formRef = useRef<QuestionnaireFormHandle>(null);

  const handleViewCurrentQr = () => {
    const qr = formRef.current?.getQuestionnaireResponse();
    if (qr) {
      onViewJson(qr, "Current QuestionnaireResponse", "Extracted from form");
    }
  };

  if (!bundle.questionnaire) {
    return (
      <div className="space-y-4">
        <Button variant="outline" size="sm" onClick={onBack}>
          <ArrowLeft className="h-4 w-4 mr-1" />
          Back to Response
        </Button>
        <div className="text-center py-12 text-sm text-muted-foreground">
          No Questionnaire found in this package bundle
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold">
            {bundle.questionnaire.title ??
              bundle.questionnaire.name ??
              "Questionnaire"}
          </h2>
          {bundle.questionnaire.url && (
            <p className="text-xs font-mono text-muted-foreground">
              {bundle.questionnaire.url}
            </p>
          )}
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={handleViewCurrentQr}>
            <Code className="h-4 w-4 mr-1" />
            View QuestionnaireResponse
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() =>
              onViewJson(bundle.rawBundle, "Package Bundle", "Full FHIR Bundle")
            }
          >
            <Code className="h-4 w-4 mr-1" />
            View Bundle
          </Button>
          <Button variant="outline" size="sm" onClick={onBack}>
            <ArrowLeft className="h-4 w-4 mr-1" />
            Back
          </Button>
        </div>
      </div>

      <Tabs defaultValue="form">
        <TabsList>
          <TabsTrigger value="form">Form</TabsTrigger>
          <TabsTrigger value="questionnaire">Questionnaire JSON</TabsTrigger>
          <TabsTrigger value="qr">QuestionnaireResponse JSON</TabsTrigger>
        </TabsList>

        <TabsContent value="form">
          <QuestionnaireForm
            ref={formRef}
            questionnaire={bundle.questionnaire}
            questionnaireResponse={bundle.questionnaireResponse}
          />
        </TabsContent>

        <TabsContent value="questionnaire">
          <Button
            variant="outline"
            size="sm"
            className="mb-2"
            onClick={() =>
              onViewJson(
                bundle.questionnaire,
                "Questionnaire",
                bundle.questionnaire?.url,
              )
            }
          >
            <Code className="h-3 w-3 mr-1" />
            Open in Viewer
          </Button>
          <pre className="text-xs p-4 bg-muted rounded-md overflow-auto max-h-[60vh]">
            {JSON.stringify(bundle.questionnaire, null, 2)}
          </pre>
        </TabsContent>

        <TabsContent value="qr">
          {bundle.questionnaireResponse ? (
            <>
              <Button
                variant="outline"
                size="sm"
                className="mb-2"
                onClick={() =>
                  onViewJson(
                    bundle.questionnaireResponse,
                    "QuestionnaireResponse",
                    "Prepopulated from server",
                  )
                }
              >
                <Code className="h-3 w-3 mr-1" />
                Open in Viewer
              </Button>
              <pre className="text-xs p-4 bg-muted rounded-md overflow-auto max-h-[60vh]">
                {JSON.stringify(bundle.questionnaireResponse, null, 2)}
              </pre>
            </>
          ) : (
            <div className="text-center py-8 text-sm text-muted-foreground">
              No QuestionnaireResponse in this package
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}
