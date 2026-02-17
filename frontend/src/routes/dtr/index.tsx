import { createFileRoute } from "@tanstack/react-router";
import type { Parameters } from "fhir/r4";
import {
  AlertCircle,
  ChevronDown,
  Loader2,
  Server,
  Settings,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import { DtrAdaptivePanel } from "@/components/dtr/dtr-adaptive-panel";
import { DtrQuestionnaireRenderer } from "@/components/dtr/dtr-questionnaire-renderer";
import { DtrRequestEditor } from "@/components/dtr/dtr-request-editor";
import { DtrResponsePanel } from "@/components/dtr/dtr-response-panel";
import { DtrScenarioList } from "@/components/dtr/dtr-scenario-list";
import {
  JsonViewerDialog,
  useJsonViewer,
} from "@/components/json-viewer-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { type DtrError, useQuestionnairePackage } from "@/hooks/use-dtr-api";
import { useDtrScenarios } from "@/hooks/use-dtr-scenarios";
import { useServerStatus } from "@/hooks/use-fhir-api";
import { useFhirServer, useServerSelection } from "@/hooks/use-fhir-server";
import type {
  DtrPackageResponse,
  DtrRequestVariant,
  DtrScenario,
  DtrWorkflowStep,
  ParsedPackageBundle,
} from "@/lib/dtr-types";

export const Route = createFileRoute("/dtr/")({
  component: DtrPage,
});

const STEP_LABELS: Record<DtrWorkflowStep, { number: number; label: string }> =
  {
    configure: { number: 1, label: "Configure" },
    response: { number: 2, label: "Response" },
    questionnaire: { number: 3, label: "Questionnaire" },
  };

function StepIndicator({
  currentStep,
  onStepClick,
}: {
  currentStep: DtrWorkflowStep;
  onStepClick: (step: DtrWorkflowStep) => void;
}) {
  const steps: DtrWorkflowStep[] = ["configure", "response", "questionnaire"];
  const currentIndex = steps.indexOf(currentStep);

  return (
    <div className="flex items-center gap-1">
      {steps.map((step, index) => {
        const { number, label } = STEP_LABELS[step];
        const isActive = step === currentStep;
        const isCompleted = index < currentIndex;
        const isClickable = index <= currentIndex;

        return (
          <div key={step} className="flex items-center">
            {index > 0 && (
              <div
                className={`w-6 h-px mx-1 ${isCompleted ? "bg-primary" : "bg-border"}`}
              />
            )}
            <button
              type="button"
              onClick={() => isClickable && onStepClick(step)}
              disabled={!isClickable}
              className={`
                flex items-center gap-1.5 px-2 py-1 rounded-md text-xs font-medium transition-colors
                ${isActive ? "bg-primary text-primary-foreground" : ""}
                ${isCompleted ? "text-primary cursor-pointer hover:bg-primary/10" : ""}
                ${!isActive && !isCompleted ? "text-muted-foreground" : ""}
                ${isClickable ? "cursor-pointer" : "cursor-default"}
              `}
            >
              <span
                className={`
                  flex items-center justify-center h-5 w-5 rounded-full text-[10px] font-bold
                  ${isActive ? "bg-primary-foreground text-primary" : ""}
                  ${isCompleted ? "bg-primary text-primary-foreground" : ""}
                  ${!isActive && !isCompleted ? "bg-muted text-muted-foreground" : ""}
                `}
              >
                {number}
              </span>
              {label}
            </button>
          </div>
        );
      })}
    </div>
  );
}

function DtrPage() {
  // Server configuration
  const { serverUrl, presetServers, setServerUrl, isCustomServer } =
    useFhirServer();

  const serverSelection = useServerSelection(
    setServerUrl,
    isCustomServer,
    serverUrl,
  );

  const { isConnected, latency } = useServerStatus(serverUrl);

  // Dynamic scenarios from the server
  const {
    data: scenarios = [],
    isLoading: scenariosLoading,
    error: scenariosError,
  } = useDtrScenarios(serverUrl);

  // Workflow state
  const [step, setStep] = useState<DtrWorkflowStep>("configure");
  const [selectedScenario, setSelectedScenario] = useState<DtrScenario | null>(
    null,
  );
  const [selectedVariant, setSelectedVariant] =
    useState<DtrRequestVariant | null>(null);
  const [requestJson, setRequestJson] = useState("");
  const [responseData, setResponseData] = useState<DtrPackageResponse | null>(
    null,
  );
  const [selectedBundle, setSelectedBundle] =
    useState<ParsedPackageBundle | null>(null);

  // API mutation
  const {
    mutate: executePackage,
    error: packageError,
    isPending: isExecuting,
    reset: resetPackage,
  } = useQuestionnairePackage();

  // JSON viewer
  const { viewerData, openViewer, closeViewer } = useJsonViewer();

  const fhirServerId = useId();
  const previousServerUrlRef = useRef(serverUrl);

  useEffect(() => {
    if (previousServerUrlRef.current === serverUrl) {
      return;
    }

    previousServerUrlRef.current = serverUrl;
    setSelectedScenario(null);
    setSelectedVariant(null);
    setRequestJson("");
    setResponseData(null);
    setSelectedBundle(null);
    resetPackage();
    setStep("configure");
  }, [serverUrl, resetPackage]);

  // Scenario selection
  const handleSelectScenario = useCallback(
    (scenario: DtrScenario) => {
      setSelectedScenario(scenario);
      const defaultVariant = scenario.variants[0];
      setSelectedVariant(defaultVariant);
      setRequestJson(JSON.stringify(defaultVariant.parameters, null, 2));
      setResponseData(null);
      setSelectedBundle(null);
      resetPackage();
      if (step !== "configure") {
        setStep("configure");
      }
    },
    [step, resetPackage],
  );

  const handleSelectVariant = useCallback((variant: DtrRequestVariant) => {
    setSelectedVariant(variant);
    setRequestJson(JSON.stringify(variant.parameters, null, 2));
  }, []);

  // Execute $questionnaire-package
  const handleExecute = useCallback(
    (parameters: Parameters) => {
      executePackage(
        { serverUrl, parameters },
        {
          onSuccess: (data) => {
            setResponseData(data);
          },
          onError: () => {
            setResponseData(null);
          },
          onSettled: () => {
            setStep("response");
          },
        },
      );
    },
    [serverUrl, executePackage],
  );

  // Preview request
  const handlePreview = useCallback(
    (parameters: Parameters) => {
      openViewer(
        parameters,
        "Questionnaire Package Request",
        `POST ${serverUrl}/Questionnaire/$questionnaire-package`,
      );
    },
    [serverUrl, openViewer],
  );

  // Open questionnaire from response
  const handleOpenQuestionnaire = useCallback((bundle: ParsedPackageBundle) => {
    setSelectedBundle(bundle);
    setStep("questionnaire");
  }, []);

  // Step navigation
  const handleStepClick = useCallback((targetStep: DtrWorkflowStep) => {
    if (targetStep === "configure") {
      setSelectedBundle(null);
    }
    setStep(targetStep);
  }, []);

  // Memoized error cast
  const typedError = useMemo(
    () => (packageError ? (packageError as DtrError) : null),
    [packageError],
  );

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="border-b bg-background/95 backdrop-blur supports-backdrop-filter:bg-background/60">
        <Collapsible defaultOpen={false}>
          <div className="flex items-center justify-between px-6 py-3">
            <div className="flex items-center gap-6">
              <div className="flex items-center gap-2">
                <Server className="h-4 w-4 text-muted-foreground" />
                <div className="flex items-center gap-2 text-sm">
                  <span className="text-muted-foreground">FHIR:</span>
                  <div
                    className={`h-2 w-2 rounded-full ${isConnected ? "bg-green-500" : "bg-red-500"}`}
                  />
                  {isConnected && latency !== undefined && (
                    <Badge variant="outline" className="text-[10px] h-5">
                      {latency}ms
                    </Badge>
                  )}
                </div>
              </div>
              <StepIndicator currentStep={step} onStepClick={handleStepClick} />
            </div>
            <CollapsibleTrigger asChild>
              <Button variant="ghost" size="sm" className="h-7">
                <Settings className="h-3 w-3 mr-2" />
                Configure Server
                <ChevronDown className="h-3 w-3 ml-2" />
              </Button>
            </CollapsibleTrigger>
          </div>
          <CollapsibleContent>
            <div className="px-6 pb-4 border-t bg-muted/40">
              <div className="max-w-md space-y-2 pt-4">
                <label
                  htmlFor={fhirServerId}
                  className="text-xs font-medium text-muted-foreground"
                >
                  Payer FHIR Server
                </label>
                <Select
                  value={isCustomServer ? "custom" : serverUrl}
                  onValueChange={serverSelection.handleServerChange}
                >
                  <SelectTrigger id={fhirServerId} className="h-8">
                    <SelectValue placeholder="Select FHIR server" />
                  </SelectTrigger>
                  <SelectContent>
                    {presetServers.map((s) => (
                      <SelectItem key={s.url} value={s.url}>
                        {s.name}
                      </SelectItem>
                    ))}
                    <SelectItem value="custom">Custom URL...</SelectItem>
                  </SelectContent>
                </Select>
                {serverSelection.showCustomInput && (
                  <div className="flex gap-2">
                    <Input
                      placeholder="https://payer-server.example.com/fhir"
                      value={serverSelection.customUrl}
                      onChange={(e) =>
                        serverSelection.setCustomUrl(e.target.value)
                      }
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          serverSelection.handleCustomUrlSubmit();
                        }
                      }}
                      className="h-8 text-xs"
                    />
                    {serverSelection.isEditing && (
                      <Button
                        size="sm"
                        className="h-8"
                        onClick={serverSelection.handleCustomUrlSubmit}
                      >
                        Connect
                      </Button>
                    )}
                  </div>
                )}
              </div>
            </div>
          </CollapsibleContent>
        </Collapsible>
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-hidden">
        {step === "configure" && (
          <div className="grid grid-cols-12 gap-0 h-full">
            {/* Left: Scenario List */}
            <div className="col-span-4 border-r overflow-y-auto p-4">
              {scenariosLoading && (
                <div className="flex items-center justify-center py-8">
                  <Loader2 className="h-5 w-5 animate-spin text-muted-foreground mr-2" />
                  <span className="text-sm text-muted-foreground">
                    Loading scenarios...
                  </span>
                </div>
              )}
              {scenariosError && (
                <div className="flex items-center gap-2 p-4 text-sm text-destructive">
                  <AlertCircle className="h-4 w-4 shrink-0" />
                  <span>Failed to load scenarios. Is the server running?</span>
                </div>
              )}
              {!scenariosLoading && !scenariosError && (
                <DtrScenarioList
                  scenarios={scenarios}
                  selectedScenario={selectedScenario}
                  selectedVariant={selectedVariant}
                  onSelectScenario={handleSelectScenario}
                  onSelectVariant={handleSelectVariant}
                />
              )}
            </div>

            {/* Right: Request Editor */}
            <div className="col-span-8 overflow-y-auto p-4">
              <DtrRequestEditor
                scenario={selectedScenario}
                variant={selectedVariant}
                requestJson={requestJson}
                onRequestJsonChange={setRequestJson}
                onExecute={handleExecute}
                onPreview={handlePreview}
                isExecuting={isExecuting}
              />
              {isExecuting && (
                <div className="flex items-center justify-center py-4">
                  <Loader2 className="h-5 w-5 animate-spin text-muted-foreground mr-2" />
                  <span className="text-sm text-muted-foreground">
                    Executing $questionnaire-package...
                  </span>
                </div>
              )}
            </div>
          </div>
        )}

        {step === "response" && (
          <div className="h-full overflow-y-auto p-6">
            <DtrResponsePanel
              response={responseData}
              error={typedError}
              onViewRawResponse={() =>
                openViewer(
                  typedError
                    ? (typedError.operationOutcome ??
                        typedError.body ?? {
                          message: typedError.message,
                        })
                    : responseData?.rawParameters,
                  typedError ? "Error Details" : "Raw Response",
                  typedError
                    ? `HTTP ${typedError.status ?? "Error"}`
                    : "Full $questionnaire-package response",
                )
              }
              onViewBundle={(bundle, index) =>
                openViewer(
                  bundle.rawBundle,
                  `Package Bundle ${index + 1}`,
                  bundle.questionnaire?.url,
                )
              }
              onOpenQuestionnaire={handleOpenQuestionnaire}
              onBack={() => setStep("configure")}
            />
          </div>
        )}

        {step === "questionnaire" && selectedBundle && (
          <div className="h-full overflow-y-auto p-6">
            {selectedBundle.isAdaptive ? (
              <DtrAdaptivePanel
                bundle={selectedBundle}
                serverUrl={serverUrl}
                onViewJson={openViewer}
                onBack={() => setStep("response")}
              />
            ) : (
              <DtrQuestionnaireRenderer
                bundle={selectedBundle}
                onViewJson={openViewer}
                onBack={() => setStep("response")}
              />
            )}
          </div>
        )}
      </div>

      {/* JSON Viewer Dialog */}
      {viewerData && (
        <JsonViewerDialog
          data={viewerData.data}
          title={viewerData.title}
          description={viewerData.description}
          onClose={closeViewer}
        />
      )}
    </div>
  );
}
