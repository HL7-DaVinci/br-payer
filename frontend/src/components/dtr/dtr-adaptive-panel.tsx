import type { Parameters, Questionnaire, QuestionnaireResponse } from "fhir/r4";
import {
  ArrowLeft,
  CheckCircle2,
  ChevronDown,
  Code,
  Loader2,
  Send,
} from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { type DtrError, useNextQuestion } from "@/hooks/use-dtr-api";
import type { ParsedPackageBundle } from "@/lib/dtr-types";
import {
  QuestionnaireForm,
  type QuestionnaireFormHandle,
} from "./questionnaire-form";

interface AdaptiveIteration {
  index: number;
  request: Parameters;
  response: Parameters;
  questionnaireResponse: QuestionnaireResponse;
  newTopLevelLinkIds: string[];
  totalTopLevelGroups: number;
}

interface DtrAdaptivePanelProps {
  bundle: ParsedPackageBundle;
  serverUrl: string;
  onViewJson: (data: unknown, title: string, description?: string) => void;
  onBack: () => void;
}

function extractQrFromResponse(
  params: Parameters,
): QuestionnaireResponse | null {
  let fallback: QuestionnaireResponse | null = null;

  for (const param of params.parameter ?? []) {
    if (
      param.resource?.resourceType === "QuestionnaireResponse"
    ) {
      const qr = param.resource as QuestionnaireResponse;

      // Prefer current server contract, but accept spec examples that use "return".
      if (
        param.name === "questionnaire-response" ||
        param.name === "return"
      ) {
        return qr;
      }

      if (!fallback) {
        fallback = qr;
      }
    }
  }

  return fallback;
}

function getContainedQuestionnaire(
  qr: QuestionnaireResponse,
): Questionnaire | null {
  const contained = qr.contained?.find(
    (r): r is Questionnaire => r.resourceType === "Questionnaire",
  );
  return contained ?? null;
}

function getTopLevelLinkIds(qr: QuestionnaireResponse): string[] {
  const containedQ = getContainedQuestionnaire(qr);
  return (
    containedQ?.item
      ?.map((item) => item.linkId)
      .filter((linkId): linkId is string => Boolean(linkId)) ?? []
  );
}

function getSourceCanonical(containedQ: Questionnaire | null): string | null {
  const canonical = containedQ?.derivedFrom?.[0];
  if (!canonical) return null;
  if (typeof canonical === "string") return canonical;
  const canonicalWithValue = canonical as unknown as { value?: unknown };
  if (typeof canonicalWithValue.value === "string") {
    return canonicalWithValue.value;
  }
  return null;
}

export function DtrAdaptivePanel({
  bundle,
  serverUrl,
  onViewJson,
  onBack,
}: DtrAdaptivePanelProps) {
  const formRef = useRef<QuestionnaireFormHandle>(null);
  const [currentQr, setCurrentQr] = useState<QuestionnaireResponse | null>(
    bundle.questionnaireResponse ?? null,
  );
  const [iterations, setIterations] = useState<AdaptiveIteration[]>([]);
  const [initialized, setInitialized] = useState(false);

  const {
    mutateAsync: callNextQuestion,
    isPending: isNextPending,
    error: nextError,
    reset: resetNextQuestion,
  } = useNextQuestion();

  useEffect(() => {
    setCurrentQr(bundle.questionnaireResponse ?? null);
    setIterations([]);
    setInitialized(false);
    resetNextQuestion();
  }, [bundle.questionnaireResponse, resetNextQuestion]);

  // Perform the initial $next-question call to get the first batch
  const initializeAdaptive = useCallback(async () => {
    if (initialized || !currentQr || currentQr.status === "completed") return;
    setInitialized(true);
    const inputQr = currentQr;

    try {
      const requestParams: Parameters = {
        resourceType: "Parameters",
        meta: {
          profile: [
            "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-next-question-input-parameters",
          ],
        },
        parameter: [{ name: "questionnaire-response", resource: inputQr }],
      };

      const result = await callNextQuestion({
        serverUrl,
        questionnaireResponse: inputQr,
      });

      const responseQr = extractQrFromResponse(result);
      if (responseQr) {
        const previousLinkIds = new Set(getTopLevelLinkIds(inputQr));
        const nextLinkIds = getTopLevelLinkIds(responseQr);
        const newTopLevelLinkIds = nextLinkIds.filter(
          (linkId) => !previousLinkIds.has(linkId),
        );

        setCurrentQr(responseQr);
        setIterations([
          {
            index: 0,
            request: requestParams,
            response: result,
            questionnaireResponse: responseQr,
            newTopLevelLinkIds,
            totalTopLevelGroups: nextLinkIds.length,
          },
        ]);
      }
    } catch {
      // Error is captured by the mutation state
    }
  }, [initialized, currentQr, callNextQuestion, serverUrl]);

  useEffect(() => {
    initializeAdaptive();
  }, [initializeAdaptive]);

  const handleSubmitAndNext = async () => {
    if (!currentQr || isComplete) return;

    // Extract answers from LHC Forms
    const formQr = formRef.current?.getQuestionnaireResponse();
    if (!formQr) return;

    // Merge: take items from the form but keep metadata from the server's QR
    const mergedQr: QuestionnaireResponse = {
      ...currentQr,
      item: formQr.item,
    };

    try {
      const previousLinkIds = new Set(getTopLevelLinkIds(mergedQr));
      const requestParams: Parameters = {
        resourceType: "Parameters",
        meta: {
          profile: [
            "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-next-question-input-parameters",
          ],
        },
        parameter: [{ name: "questionnaire-response", resource: mergedQr }],
      };

      const result = await callNextQuestion({
        serverUrl,
        questionnaireResponse: mergedQr,
      });

      const responseQr = extractQrFromResponse(result);
      if (responseQr) {
        const nextLinkIds = getTopLevelLinkIds(responseQr);
        const newTopLevelLinkIds = nextLinkIds.filter(
          (linkId) => !previousLinkIds.has(linkId),
        );

        setCurrentQr(responseQr);
        setIterations((prev) => [
          ...prev,
          {
            index: prev.length,
            request: requestParams,
            response: result,
            questionnaireResponse: responseQr,
            newTopLevelLinkIds,
            totalTopLevelGroups: nextLinkIds.length,
          },
        ]);
      }
    } catch {
      // Error is captured by the mutation state
    }
  };

  const containedQ = currentQr ? getContainedQuestionnaire(currentQr) : null;
  const sourceCanonical = getSourceCanonical(containedQ);
  const latestIteration = iterations[iterations.length - 1];
  const isComplete = currentQr?.status === "completed";

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold flex items-center gap-2">
            Adaptive Questionnaire
            {isComplete ? (
              <Badge className="bg-green-600 text-white">
                <CheckCircle2 className="h-3 w-3 mr-1" />
                Complete
              </Badge>
            ) : (
              <Badge variant="secondary">Iteration {iterations.length}</Badge>
            )}
          </h2>
          {containedQ && (
            <p className="text-xs font-mono text-muted-foreground">
              {sourceCanonical ?? "Adaptive session"}
            </p>
          )}
        </div>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() =>
              onViewJson(
                currentQr,
                "Current QuestionnaireResponse",
                "Adaptive session state",
              )
            }
            disabled={!currentQr}
          >
            <Code className="h-4 w-4 mr-1" />
            View QuestionnaireResponse
          </Button>
          <Button variant="outline" size="sm" onClick={onBack}>
            <ArrowLeft className="h-4 w-4 mr-1" />
            Back
          </Button>
        </div>
      </div>

      {/* Error state */}
      {nextError && (
        <div className="mb-4 p-3 bg-destructive/10 border border-destructive/30 rounded-md text-sm">
          <span className="font-medium text-destructive">Error: </span>
          {(nextError as DtrError).message}
          {(nextError as DtrError).operationOutcome && (
            <Button
              variant="link"
              size="sm"
              className="text-xs p-0 h-auto ml-2"
              onClick={() =>
                onViewJson(
                  (nextError as DtrError).operationOutcome ??
                    (nextError as DtrError).body,
                  "Error Details",
                )
              }
            >
              View details
            </Button>
          )}
          {iterations.length === 0 && currentQr && (
            <div className="mt-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  resetNextQuestion();
                  setInitialized(false);
                }}
              >
                Retry Initial Load
              </Button>
            </div>
          )}
        </div>
      )}

      {/* Form rendering area */}
      <div>
        {isNextPending && iterations.length === 0 ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground mr-2" />
            <span className="text-sm text-muted-foreground">
              Loading initial questions...
            </span>
          </div>
        ) : containedQ && currentQr ? (
          <div className="py-4">
            <QuestionnaireForm
              ref={formRef}
              questionnaire={containedQ}
              questionnaireResponse={currentQr}
            />
          </div>
        ) : (
          <div className="text-center py-12 text-sm text-muted-foreground">
            No questionnaire content available
          </div>
        )}

        {isComplete && (
          <div className="p-4 bg-green-500/10 border border-green-500/30 rounded-md text-sm">
            <CheckCircle2 className="h-4 w-4 inline mr-2 text-green-600" />
            All questions have been answered. The questionnaire is complete.
          </div>
        )}
        {latestIteration && (
          <div className="p-3 bg-muted/40 border rounded-md text-xs">
            {latestIteration.newTopLevelLinkIds.length > 0 ? (
              <>
                Last <code>$next-question</code> call delivered group(s):{" "}
                <span className="font-mono">
                  {latestIteration.newTopLevelLinkIds.join(", ")}
                </span>{" "}
                (total delivered: {latestIteration.totalTopLevelGroups})
              </>
            ) : (
              <>
                Last <code>$next-question</code> call delivered no new top-level groups.
                This usually means a gating question is still unanswered.
              </>
            )}
          </div>
        )}
      </div>

      {/* Submit button */}
      <div className="pt-4 border-t">
        <div className="flex items-center justify-between">
          <Button
            size="sm"
            onClick={handleSubmitAndNext}
            disabled={isComplete || isNextPending || !containedQ}
          >
            {isNextPending ? (
              <Loader2 className="h-4 w-4 animate-spin mr-1" />
            ) : (
              <Send className="h-4 w-4 mr-1" />
            )}
            Submit & Get Next Questions
          </Button>
        </div>
      </div>

      {/* Iteration history */}
      {iterations.length > 0 && (
        <div className="pt-4 space-y-1">
          <h3 className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
            History ({iterations.length} iterations)
          </h3>
          {iterations.map((iter) => (
            <Collapsible key={iter.index}>
              <CollapsibleTrigger asChild>
                <button
                  type="button"
                  className="flex items-center gap-2 w-full text-left text-xs p-2 hover:bg-muted/50 rounded cursor-pointer"
                >
                  <ChevronDown className="h-3 w-3" />
                  Iteration {iter.index + 1}
                  <Badge variant="outline" className="text-[10px]">
                    {iter.questionnaireResponse.status}
                  </Badge>
                </button>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="flex gap-2 pl-7 pb-2">
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-6 text-[10px]"
                    onClick={() =>
                      onViewJson(
                        iter.request,
                        `Request (Iteration ${iter.index + 1})`,
                      )
                    }
                  >
                    Request
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-6 text-[10px]"
                    onClick={() =>
                      onViewJson(
                        iter.response,
                        `Response (Iteration ${iter.index + 1})`,
                      )
                    }
                  >
                    Response
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-6 text-[10px]"
                    onClick={() =>
                      onViewJson(
                        iter.questionnaireResponse,
                        `QR (Iteration ${iter.index + 1})`,
                      )
                    }
                  >
                    QR
                  </Button>
                </div>
              </CollapsibleContent>
            </Collapsible>
          ))}
        </div>
      )}
    </div>
  );
}
