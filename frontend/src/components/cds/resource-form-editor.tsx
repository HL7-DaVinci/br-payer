import type { Resource } from "fhir/r4";
import { AlertCircle, Loader2 } from "lucide-react";
import { lazy, Suspense, useCallback, useEffect, useState } from "react";
import { ErrorBoundary } from "@/components/error-boundary";
import { Button } from "@/components/ui/button";
import { useTheme } from "@/hooks/use-theme";

const MonacoEditor = lazy(() =>
  import("@monaco-editor/react").then((mod) => ({ default: mod.Editor })),
);

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

// =============================================================================
// Types
// =============================================================================

interface CodeableConcept {
  coding?: Array<{
    system?: string;
    code?: string;
    display?: string;
  }>;
  text?: string;
}

interface ResourceFormEditorProps {
  resource: Resource;
  onClose: () => void;
  onSave: (resource: Resource) => void;
}

interface FormFieldProps {
  label: string;
  required?: boolean;
  description?: string;
  children: React.ReactNode;
  className?: string;
}

// =============================================================================
// Common Form Field Components
// =============================================================================

function FormField({
  label,
  required,
  description,
  children,
  className,
}: FormFieldProps) {
  return (
    <div className={cn("space-y-1.5", className)}>
      <Label className="text-xs font-medium">
        {label}
        {required && <span className="text-destructive ml-0.5">*</span>}
      </Label>
      {children}
      {description && (
        <p className="text-[10px] text-muted-foreground">{description}</p>
      )}
    </div>
  );
}

// =============================================================================
// Codeable Concept Helpers
// =============================================================================

interface CodeOption {
  system: string;
  code: string;
  display: string;
}

function getCodeableConceptDisplay(cc: CodeableConcept | undefined): string {
  if (!cc) return "";
  if (cc.text) return cc.text;
  if (cc.coding?.[0]?.display) return cc.coding[0].display;
  if (cc.coding?.[0]?.code) return cc.coding[0].code;
  return "";
}

function createCodeableConcept(
  text: string,
  coding?: { system: string; code: string; display: string },
): CodeableConcept {
  return {
    text,
    coding: coding ? [coding] : undefined,
  };
}

// =============================================================================
// ServiceRequest Form
// =============================================================================

const SERVICE_REQUEST_CATEGORIES: CodeOption[] = [
  {
    system: "http://snomed.info/sct",
    code: "108252007",
    display: "Laboratory procedure",
  },
  { system: "http://snomed.info/sct", code: "363679005", display: "Imaging" },
  {
    system: "http://snomed.info/sct",
    code: "387713003",
    display: "Surgical procedure",
  },
  {
    system: "http://snomed.info/sct",
    code: "409063005",
    display: "Counseling",
  },
  {
    system: "http://snomed.info/sct",
    code: "409073007",
    display: "Education",
  },
];

const SERVICE_REQUEST_INTENTS = [
  { value: "proposal", label: "Proposal" },
  { value: "plan", label: "Plan" },
  { value: "directive", label: "Directive" },
  { value: "order", label: "Order" },
  { value: "original-order", label: "Original Order" },
  { value: "reflex-order", label: "Reflex Order" },
  { value: "filler-order", label: "Filler Order" },
  { value: "instance-order", label: "Instance Order" },
  { value: "option", label: "Option" },
];

const SERVICE_REQUEST_PRIORITIES = [
  { value: "routine", label: "Routine" },
  { value: "urgent", label: "Urgent" },
  { value: "asap", label: "ASAP" },
  { value: "stat", label: "STAT" },
];

interface ServiceRequestFormData {
  status: string;
  intent: string;
  priority: string;
  category: string;
  codeText: string;
  codeSystem: string;
  codeCode: string;
  note: string;
}

function ServiceRequestForm({
  resource,
  onChange,
}: {
  resource: Record<string, unknown>;
  onChange: (updated: Record<string, unknown>) => void;
}) {
  const [formData, setFormData] = useState<ServiceRequestFormData>(() => {
    const category = resource.category as CodeableConcept[] | undefined;
    const code = resource.code as CodeableConcept | undefined;
    const note = resource.note as Array<{ text?: string }> | undefined;

    return {
      status: (resource.status as string) || "draft",
      intent: (resource.intent as string) || "order",
      priority: (resource.priority as string) || "routine",
      category: category?.[0]?.coding?.[0]?.code || "",
      codeText: code?.text || code?.coding?.[0]?.display || "",
      codeSystem: code?.coding?.[0]?.system || "",
      codeCode: code?.coding?.[0]?.code || "",
      note: note?.[0]?.text || "",
    };
  });

  const updateField = useCallback(
    (field: keyof ServiceRequestFormData, value: string) => {
      setFormData((prev) => ({ ...prev, [field]: value }));

      // Build updated resource
      const selectedCategory = SERVICE_REQUEST_CATEGORIES.find(
        (c) => c.code === (field === "category" ? value : formData.category),
      );

      const updated = {
        ...resource,
        status: field === "status" ? value : formData.status,
        intent: field === "intent" ? value : formData.intent,
        priority: field === "priority" ? value : formData.priority,
        category: selectedCategory
          ? [{ coding: [selectedCategory] }]
          : resource.category,
        code: {
          text: field === "codeText" ? value : formData.codeText,
          coding:
            (field === "codeCode" ? value : formData.codeCode) ||
            (field === "codeSystem" ? value : formData.codeSystem)
              ? [
                  {
                    system:
                      field === "codeSystem" ? value : formData.codeSystem,
                    code: field === "codeCode" ? value : formData.codeCode,
                    display: field === "codeText" ? value : formData.codeText,
                  },
                ]
              : undefined,
        },
        note: (field === "note" ? value : formData.note)
          ? [{ text: field === "note" ? value : formData.note }]
          : undefined,
      };
      onChange(updated);
    },
    [resource, onChange, formData],
  );

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-3">
        <FormField label="Status" required>
          <Select
            value={formData.status}
            onValueChange={(v) => updateField("status", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="draft">Draft</SelectItem>
              <SelectItem value="active">Active</SelectItem>
              <SelectItem value="on-hold">On Hold</SelectItem>
              <SelectItem value="completed">Completed</SelectItem>
              <SelectItem value="cancelled">Cancelled</SelectItem>
            </SelectContent>
          </Select>
        </FormField>

        <FormField label="Intent" required>
          <Select
            value={formData.intent}
            onValueChange={(v) => updateField("intent", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {SERVICE_REQUEST_INTENTS.map((intent) => (
                <SelectItem key={intent.value} value={intent.value}>
                  {intent.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </FormField>

        <FormField label="Priority">
          <Select
            value={formData.priority}
            onValueChange={(v) => updateField("priority", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {SERVICE_REQUEST_PRIORITIES.map((p) => (
                <SelectItem key={p.value} value={p.value}>
                  {p.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </FormField>
      </div>

      <FormField label="Category">
        <Select
          value={formData.category}
          onValueChange={(v) => updateField("category", v)}
        >
          <SelectTrigger className="h-8 text-xs">
            <SelectValue placeholder="Select category" />
          </SelectTrigger>
          <SelectContent>
            {SERVICE_REQUEST_CATEGORIES.map((cat) => (
              <SelectItem key={cat.code} value={cat.code}>
                {cat.display}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </FormField>

      <div className="space-y-2 p-3 rounded-md bg-muted/50">
        <Label className="text-xs font-medium">Code / Procedure</Label>
        <FormField label="Display Text" required>
          <Input
            className="h-8 text-xs"
            value={formData.codeText}
            onChange={(e) => updateField("codeText", e.target.value)}
            placeholder="e.g., Blood Glucose Test"
          />
        </FormField>
        <div className="grid grid-cols-2 gap-2">
          <FormField label="Code System">
            <Input
              className="h-8 text-xs"
              value={formData.codeSystem}
              onChange={(e) => updateField("codeSystem", e.target.value)}
              placeholder="http://loinc.org"
            />
          </FormField>
          <FormField label="Code">
            <Input
              className="h-8 text-xs"
              value={formData.codeCode}
              onChange={(e) => updateField("codeCode", e.target.value)}
              placeholder="2339-0"
            />
          </FormField>
        </div>
      </div>

      <FormField label="Notes">
        <Textarea
          className="text-xs min-h-[60px]"
          value={formData.note}
          onChange={(e) => updateField("note", e.target.value)}
          placeholder="Additional notes..."
        />
      </FormField>
    </div>
  );
}

// =============================================================================
// MedicationRequest Form
// =============================================================================

interface MedicationRequestFormData {
  status: string;
  intent: string;
  priority: string;
  medicationText: string;
  medicationSystem: string;
  medicationCode: string;
  dosageText: string;
  quantity: string;
  refills: string;
}

function MedicationRequestForm({
  resource,
  onChange,
}: {
  resource: Record<string, unknown>;
  onChange: (updated: Record<string, unknown>) => void;
}) {
  const [formData, setFormData] = useState<MedicationRequestFormData>(() => {
    const medication = resource.medicationCodeableConcept as
      | CodeableConcept
      | undefined;
    const dosage = resource.dosageInstruction as
      | Array<{ text?: string }>
      | undefined;
    const dispenseRequest = resource.dispenseRequest as
      | {
          quantity?: { value?: number };
          numberOfRepeatsAllowed?: number;
        }
      | undefined;

    return {
      status: (resource.status as string) || "draft",
      intent: (resource.intent as string) || "order",
      priority: (resource.priority as string) || "routine",
      medicationText:
        medication?.text || medication?.coding?.[0]?.display || "",
      medicationSystem: medication?.coding?.[0]?.system || "",
      medicationCode: medication?.coding?.[0]?.code || "",
      dosageText: dosage?.[0]?.text || "",
      quantity: dispenseRequest?.quantity?.value?.toString() || "",
      refills: dispenseRequest?.numberOfRepeatsAllowed?.toString() || "0",
    };
  });

  const updateField = useCallback(
    (field: keyof MedicationRequestFormData, value: string) => {
      setFormData((prev) => ({ ...prev, [field]: value }));

      const updated = {
        ...resource,
        status: field === "status" ? value : formData.status,
        intent: field === "intent" ? value : formData.intent,
        priority: field === "priority" ? value : formData.priority,
        medicationCodeableConcept: {
          text: field === "medicationText" ? value : formData.medicationText,
          coding:
            (field === "medicationCode" ? value : formData.medicationCode) ||
            (field === "medicationSystem" ? value : formData.medicationSystem)
              ? [
                  {
                    system:
                      field === "medicationSystem"
                        ? value
                        : formData.medicationSystem,
                    code:
                      field === "medicationCode"
                        ? value
                        : formData.medicationCode,
                    display:
                      field === "medicationText"
                        ? value
                        : formData.medicationText,
                  },
                ]
              : undefined,
        },
        dosageInstruction: (
          field === "dosageText"
            ? value
            : formData.dosageText
        )
          ? [{ text: field === "dosageText" ? value : formData.dosageText }]
          : undefined,
        dispenseRequest:
          (field === "quantity" ? value : formData.quantity) ||
          (field === "refills" ? value : formData.refills)
            ? {
                quantity: (field === "quantity" ? value : formData.quantity)
                  ? {
                      value: parseInt(
                        field === "quantity" ? value : formData.quantity,
                        10,
                      ),
                      unit: "tablet",
                    }
                  : undefined,
                numberOfRepeatsAllowed: parseInt(
                  field === "refills" ? value : formData.refills,
                  10,
                ),
              }
            : undefined,
      };
      onChange(updated);
    },
    [resource, onChange, formData],
  );

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-3">
        <FormField label="Status" required>
          <Select
            value={formData.status}
            onValueChange={(v) => updateField("status", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="draft">Draft</SelectItem>
              <SelectItem value="active">Active</SelectItem>
              <SelectItem value="on-hold">On Hold</SelectItem>
              <SelectItem value="completed">Completed</SelectItem>
              <SelectItem value="cancelled">Cancelled</SelectItem>
              <SelectItem value="stopped">Stopped</SelectItem>
            </SelectContent>
          </Select>
        </FormField>

        <FormField label="Intent" required>
          <Select
            value={formData.intent}
            onValueChange={(v) => updateField("intent", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="proposal">Proposal</SelectItem>
              <SelectItem value="plan">Plan</SelectItem>
              <SelectItem value="order">Order</SelectItem>
              <SelectItem value="original-order">Original Order</SelectItem>
              <SelectItem value="reflex-order">Reflex Order</SelectItem>
              <SelectItem value="filler-order">Filler Order</SelectItem>
              <SelectItem value="instance-order">Instance Order</SelectItem>
              <SelectItem value="option">Option</SelectItem>
            </SelectContent>
          </Select>
        </FormField>

        <FormField label="Priority">
          <Select
            value={formData.priority}
            onValueChange={(v) => updateField("priority", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="routine">Routine</SelectItem>
              <SelectItem value="urgent">Urgent</SelectItem>
              <SelectItem value="asap">ASAP</SelectItem>
              <SelectItem value="stat">STAT</SelectItem>
            </SelectContent>
          </Select>
        </FormField>
      </div>

      <div className="space-y-2 p-3 rounded-md bg-muted/50">
        <Label className="text-xs font-medium">Medication</Label>
        <FormField label="Medication Name" required>
          <Input
            className="h-8 text-xs"
            value={formData.medicationText}
            onChange={(e) => updateField("medicationText", e.target.value)}
            placeholder="e.g., Acetaminophen 325mg"
          />
        </FormField>
        <div className="grid grid-cols-2 gap-2">
          <FormField label="Code System">
            <Input
              className="h-8 text-xs"
              value={formData.medicationSystem}
              onChange={(e) => updateField("medicationSystem", e.target.value)}
              placeholder="http://www.nlm.nih.gov/research/umls/rxnorm"
            />
          </FormField>
          <FormField label="RxNorm Code">
            <Input
              className="h-8 text-xs"
              value={formData.medicationCode}
              onChange={(e) => updateField("medicationCode", e.target.value)}
              placeholder="1049502"
            />
          </FormField>
        </div>
      </div>

      <FormField label="Dosage Instructions">
        <Textarea
          className="text-xs min-h-[60px]"
          value={formData.dosageText}
          onChange={(e) => updateField("dosageText", e.target.value)}
          placeholder="e.g., Take 1-2 tablets every 4-6 hours as needed"
        />
      </FormField>

      <div className="grid grid-cols-2 gap-3">
        <FormField label="Quantity">
          <Input
            className="h-8 text-xs"
            type="number"
            value={formData.quantity}
            onChange={(e) => updateField("quantity", e.target.value)}
            placeholder="30"
          />
        </FormField>
        <FormField label="Refills">
          <Input
            className="h-8 text-xs"
            type="number"
            value={formData.refills}
            onChange={(e) => updateField("refills", e.target.value)}
            placeholder="0"
          />
        </FormField>
      </div>
    </div>
  );
}

// =============================================================================
// DeviceRequest Form
// =============================================================================

interface DeviceRequestFormData {
  status: string;
  intent: string;
  priority: string;
  deviceText: string;
  deviceSystem: string;
  deviceCode: string;
  quantity: string;
  note: string;
}

function DeviceRequestForm({
  resource,
  onChange,
}: {
  resource: Record<string, unknown>;
  onChange: (updated: Record<string, unknown>) => void;
}) {
  const [formData, setFormData] = useState<DeviceRequestFormData>(() => {
    const device = resource.codeCodeableConcept as CodeableConcept | undefined;
    const note = resource.note as Array<{ text?: string }> | undefined;

    return {
      status: (resource.status as string) || "draft",
      intent: (resource.intent as string) || "order",
      priority: (resource.priority as string) || "routine",
      deviceText: device?.text || device?.coding?.[0]?.display || "",
      deviceSystem: device?.coding?.[0]?.system || "",
      deviceCode: device?.coding?.[0]?.code || "",
      quantity: (
        (resource.quantity as { value?: number })?.value || 1
      ).toString(),
      note: note?.[0]?.text || "",
    };
  });

  const updateField = useCallback(
    (field: keyof DeviceRequestFormData, value: string) => {
      setFormData((prev) => ({ ...prev, [field]: value }));

      const updated = {
        ...resource,
        status: field === "status" ? value : formData.status,
        intent: field === "intent" ? value : formData.intent,
        priority: field === "priority" ? value : formData.priority,
        codeCodeableConcept: {
          text: field === "deviceText" ? value : formData.deviceText,
          coding:
            (field === "deviceCode" ? value : formData.deviceCode) ||
            (field === "deviceSystem" ? value : formData.deviceSystem)
              ? [
                  {
                    system:
                      field === "deviceSystem" ? value : formData.deviceSystem,
                    code: field === "deviceCode" ? value : formData.deviceCode,
                    display:
                      field === "deviceText" ? value : formData.deviceText,
                  },
                ]
              : undefined,
        },
        quantity: {
          value: parseInt(field === "quantity" ? value : formData.quantity, 10),
        },
        note: (field === "note" ? value : formData.note)
          ? [{ text: field === "note" ? value : formData.note }]
          : undefined,
      };
      onChange(updated);
    },
    [resource, onChange, formData],
  );

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-3">
        <FormField label="Status" required>
          <Select
            value={formData.status}
            onValueChange={(v) => updateField("status", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="draft">Draft</SelectItem>
              <SelectItem value="active">Active</SelectItem>
              <SelectItem value="on-hold">On Hold</SelectItem>
              <SelectItem value="completed">Completed</SelectItem>
              <SelectItem value="cancelled">Cancelled</SelectItem>
            </SelectContent>
          </Select>
        </FormField>

        <FormField label="Intent" required>
          <Select
            value={formData.intent}
            onValueChange={(v) => updateField("intent", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="proposal">Proposal</SelectItem>
              <SelectItem value="plan">Plan</SelectItem>
              <SelectItem value="order">Order</SelectItem>
              <SelectItem value="original-order">Original Order</SelectItem>
              <SelectItem value="filler-order">Filler Order</SelectItem>
              <SelectItem value="instance-order">Instance Order</SelectItem>
            </SelectContent>
          </Select>
        </FormField>

        <FormField label="Priority">
          <Select
            value={formData.priority}
            onValueChange={(v) => updateField("priority", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="routine">Routine</SelectItem>
              <SelectItem value="urgent">Urgent</SelectItem>
              <SelectItem value="asap">ASAP</SelectItem>
              <SelectItem value="stat">STAT</SelectItem>
            </SelectContent>
          </Select>
        </FormField>
      </div>

      <div className="space-y-2 p-3 rounded-md bg-muted/50">
        <Label className="text-xs font-medium">Device</Label>
        <FormField label="Device Name" required>
          <Input
            className="h-8 text-xs"
            value={formData.deviceText}
            onChange={(e) => updateField("deviceText", e.target.value)}
            placeholder="e.g., CPAP Machine"
          />
        </FormField>
        <div className="grid grid-cols-2 gap-2">
          <FormField label="Code System">
            <Input
              className="h-8 text-xs"
              value={formData.deviceSystem}
              onChange={(e) => updateField("deviceSystem", e.target.value)}
              placeholder="http://snomed.info/sct"
            />
          </FormField>
          <FormField label="Code">
            <Input
              className="h-8 text-xs"
              value={formData.deviceCode}
              onChange={(e) => updateField("deviceCode", e.target.value)}
              placeholder="37874008"
            />
          </FormField>
        </div>
      </div>

      <FormField label="Quantity">
        <Input
          className="h-8 text-xs w-24"
          type="number"
          value={formData.quantity}
          onChange={(e) => updateField("quantity", e.target.value)}
          placeholder="1"
        />
      </FormField>

      <FormField label="Notes">
        <Textarea
          className="text-xs min-h-[60px]"
          value={formData.note}
          onChange={(e) => updateField("note", e.target.value)}
          placeholder="Additional notes..."
        />
      </FormField>
    </div>
  );
}

// =============================================================================
// Appointment Form
// =============================================================================

const APPOINTMENT_TYPES: CodeOption[] = [
  {
    system: "http://terminology.hl7.org/CodeSystem/v2-0276",
    code: "ROUTINE",
    display: "Routine appointment",
  },
  {
    system: "http://terminology.hl7.org/CodeSystem/v2-0276",
    code: "WALKIN",
    display: "Walk-in",
  },
  {
    system: "http://terminology.hl7.org/CodeSystem/v2-0276",
    code: "CHECKUP",
    display: "Check-up",
  },
  {
    system: "http://terminology.hl7.org/CodeSystem/v2-0276",
    code: "FOLLOWUP",
    display: "Follow-up",
  },
  {
    system: "http://terminology.hl7.org/CodeSystem/v2-0276",
    code: "EMERGENCY",
    display: "Emergency",
  },
];

interface AppointmentFormData {
  status: string;
  appointmentType: string;
  description: string;
  startDate: string;
  startTime: string;
  duration: string;
  comment: string;
}

function AppointmentForm({
  resource,
  onChange,
}: {
  resource: Record<string, unknown>;
  onChange: (updated: Record<string, unknown>) => void;
}) {
  const [formData, setFormData] = useState<AppointmentFormData>(() => {
    const appointmentType = resource.appointmentType as
      | CodeableConcept
      | undefined;
    const start = resource.start as string | undefined;
    const end = resource.end as string | undefined;

    // Parse start date/time
    let startDate = "";
    let startTime = "";
    if (start) {
      const date = new Date(start);
      startDate = date.toISOString().split("T")[0];
      startTime = date.toTimeString().slice(0, 5);
    }

    // Calculate duration
    let duration = "30";
    if (start && end) {
      const startMs = new Date(start).getTime();
      const endMs = new Date(end).getTime();
      duration = Math.round((endMs - startMs) / 60000).toString();
    }

    return {
      status: (resource.status as string) || "proposed",
      appointmentType: appointmentType?.coding?.[0]?.code || "ROUTINE",
      description: (resource.description as string) || "",
      startDate,
      startTime,
      duration,
      comment: (resource.comment as string) || "",
    };
  });

  const updateField = useCallback(
    (field: keyof AppointmentFormData, value: string) => {
      setFormData((prev) => ({ ...prev, [field]: value }));

      const selectedType = APPOINTMENT_TYPES.find(
        (t) =>
          t.code ===
          (field === "appointmentType" ? value : formData.appointmentType),
      );

      // Calculate start and end times
      const startDateVal = field === "startDate" ? value : formData.startDate;
      const startTimeVal = field === "startTime" ? value : formData.startTime;
      const durationVal = parseInt(
        field === "duration" ? value : formData.duration,
        10,
      );

      let start: string | undefined;
      let end: string | undefined;
      if (startDateVal && startTimeVal) {
        const startDate = new Date(`${startDateVal}T${startTimeVal}`);
        start = startDate.toISOString();
        const endDate = new Date(startDate.getTime() + durationVal * 60000);
        end = endDate.toISOString();
      }

      const updated = {
        ...resource,
        status: field === "status" ? value : formData.status,
        appointmentType: selectedType
          ? { coding: [selectedType] }
          : resource.appointmentType,
        description:
          field === "description" ? value : formData.description || undefined,
        start,
        end,
        comment: (field === "comment" ? value : formData.comment) || undefined,
      };
      onChange(updated);
    },
    [resource, onChange, formData],
  );

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3">
        <FormField label="Status" required>
          <Select
            value={formData.status}
            onValueChange={(v) => updateField("status", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="proposed">Proposed</SelectItem>
              <SelectItem value="pending">Pending</SelectItem>
              <SelectItem value="booked">Booked</SelectItem>
              <SelectItem value="arrived">Arrived</SelectItem>
              <SelectItem value="fulfilled">Fulfilled</SelectItem>
              <SelectItem value="cancelled">Cancelled</SelectItem>
              <SelectItem value="noshow">No Show</SelectItem>
            </SelectContent>
          </Select>
        </FormField>

        <FormField label="Appointment Type">
          <Select
            value={formData.appointmentType}
            onValueChange={(v) => updateField("appointmentType", v)}
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {APPOINTMENT_TYPES.map((type) => (
                <SelectItem key={type.code} value={type.code}>
                  {type.display}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </FormField>
      </div>

      <FormField label="Description">
        <Input
          className="h-8 text-xs"
          value={formData.description}
          onChange={(e) => updateField("description", e.target.value)}
          placeholder="e.g., Annual Physical"
        />
      </FormField>

      <div className="space-y-2 p-3 rounded-md bg-muted/50">
        <Label className="text-xs font-medium">Schedule</Label>
        <div className="grid grid-cols-3 gap-2">
          <FormField label="Date">
            <Input
              className="h-8 text-xs"
              type="date"
              value={formData.startDate}
              onChange={(e) => updateField("startDate", e.target.value)}
            />
          </FormField>
          <FormField label="Time">
            <Input
              className="h-8 text-xs"
              type="time"
              value={formData.startTime}
              onChange={(e) => updateField("startTime", e.target.value)}
            />
          </FormField>
          <FormField label="Duration (min)">
            <Input
              className="h-8 text-xs"
              type="number"
              value={formData.duration}
              onChange={(e) => updateField("duration", e.target.value)}
              placeholder="30"
            />
          </FormField>
        </div>
      </div>

      <FormField label="Comment">
        <Textarea
          className="text-xs min-h-[60px]"
          value={formData.comment}
          onChange={(e) => updateField("comment", e.target.value)}
          placeholder="Additional comments..."
        />
      </FormField>
    </div>
  );
}

// =============================================================================
// Generic Form (Fallback)
// =============================================================================

function GenericResourceForm({
  resource,
  onChange,
}: {
  resource: Record<string, unknown>;
  onChange: (updated: Record<string, unknown>) => void;
}) {
  const status = resource.status as string | undefined;
  const code = resource.code as CodeableConcept | undefined;

  const [formData, setFormData] = useState({
    status: status || "",
    codeText: getCodeableConceptDisplay(code),
  });

  const updateField = useCallback(
    (field: "status" | "codeText", value: string) => {
      setFormData((prev) => ({ ...prev, [field]: value }));

      const updated = {
        ...resource,
        status: field === "status" ? value : formData.status || undefined,
        code:
          field === "codeText"
            ? createCodeableConcept(value)
            : resource.code || createCodeableConcept(formData.codeText),
      };
      onChange(updated);
    },
    [resource, onChange, formData],
  );

  return (
    <div className="space-y-4">
      <p className="text-xs text-muted-foreground">
        Form editing for {String(resource.resourceType)} is limited. Use the
        JSON tab for full control.
      </p>

      {status !== undefined && (
        <FormField label="Status">
          <Input
            className="h-8 text-xs"
            value={formData.status}
            onChange={(e) => updateField("status", e.target.value)}
            placeholder="Status"
          />
        </FormField>
      )}

      {code !== undefined && (
        <FormField label="Code/Type">
          <Input
            className="h-8 text-xs"
            value={formData.codeText}
            onChange={(e) => updateField("codeText", e.target.value)}
            placeholder="Code display text"
          />
        </FormField>
      )}
    </div>
  );
}

// =============================================================================
// JSON Editor (Tab)
// =============================================================================

function JsonEditorTab({
  resource,
  onChange,
  error,
  setError,
}: {
  resource: Record<string, unknown>;
  onChange: (updated: Record<string, unknown>) => void;
  error: string | null;
  setError: (error: string | null) => void;
}) {
  const { effectiveTheme } = useTheme();
  const [jsonText, setJsonText] = useState(() =>
    JSON.stringify(resource, null, 2),
  );

  // Update JSON text when resource changes externally
  useEffect(() => {
    setJsonText(JSON.stringify(resource, null, 2));
  }, [resource]);

  const handleJsonChange = useCallback(
    (value: string | undefined) => {
      const newValue = value ?? "";
      setJsonText(newValue);
      try {
        const parsed = JSON.parse(newValue);
        setError(null);
        onChange(parsed);
      } catch {
        setError("Invalid JSON");
      }
    },
    [onChange, setError],
  );

  const monacoTheme = effectiveTheme === "dark" ? "vs-dark" : "light";

  return (
    <div className="space-y-2">
      {error && (
        <div className="flex items-center gap-2 px-3 py-2 bg-destructive/10 border border-destructive/30 rounded-md text-destructive text-sm">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span className="truncate">{error}</span>
        </div>
      )}
      <div className="border rounded-md overflow-hidden h-[50vh]">
        <ErrorBoundary
          fallback={
            <div className="flex flex-col items-center justify-center h-full text-muted-foreground">
              <p className="text-sm">Failed to load JSON editor</p>
              <textarea
                className="mt-2 p-4 bg-muted rounded text-xs w-full h-64 font-mono"
                value={jsonText}
                onChange={(e) => handleJsonChange(e.target.value)}
              />
            </div>
          }
        >
          <Suspense
            fallback={
              <div className="flex items-center justify-center h-full">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
              </div>
            }
          >
            <MonacoEditor
              height="100%"
              language="json"
              value={jsonText}
              onChange={handleJsonChange}
              theme={monacoTheme}
              options={{
                readOnly: false,
                minimap: { enabled: false },
                fontSize: 13,
                lineNumbers: "on",
                scrollBeyondLastLine: false,
                wordWrap: "on",
                folding: true,
                automaticLayout: true,
                formatOnPaste: true,
                formatOnType: true,
              }}
            />
          </Suspense>
        </ErrorBoundary>
      </div>
    </div>
  );
}

// =============================================================================
// Main Component
// =============================================================================

/**
 * Supported resource types for form editing
 */
const FORM_SUPPORTED_TYPES = [
  "ServiceRequest",
  "MedicationRequest",
  "DeviceRequest",
  "Appointment",
];

/**
 * Check if a resource type has form support
 */
export function hasFormSupport(resourceType: string): boolean {
  return FORM_SUPPORTED_TYPES.includes(resourceType);
}

export function ResourceFormEditorDialog({
  resource,
  onClose,
  onSave,
}: ResourceFormEditorProps) {
  const [editedResource, setEditedResource] = useState<Record<string, unknown>>(
    () => ({ ...resource }) as Record<string, unknown>,
  );
  const [activeTab, setActiveTab] = useState<"form" | "json">("form");
  const [jsonError, setJsonError] = useState<string | null>(null);

  const resourceType = resource.resourceType;
  const supportsForm = hasFormSupport(resourceType);

  const handleSave = useCallback(() => {
    if (jsonError) return;
    onSave(editedResource as unknown as Resource);
    onClose();
  }, [editedResource, jsonError, onSave, onClose]);

  const renderForm = () => {
    switch (resourceType) {
      case "ServiceRequest":
        return (
          <ServiceRequestForm
            resource={editedResource}
            onChange={setEditedResource}
          />
        );
      case "MedicationRequest":
        return (
          <MedicationRequestForm
            resource={editedResource}
            onChange={setEditedResource}
          />
        );
      case "DeviceRequest":
        return (
          <DeviceRequestForm
            resource={editedResource}
            onChange={setEditedResource}
          />
        );
      case "Appointment":
        return (
          <AppointmentForm
            resource={editedResource}
            onChange={setEditedResource}
          />
        );
      default:
        return (
          <GenericResourceForm
            resource={editedResource}
            onChange={setEditedResource}
          />
        );
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-full h-[90vh] w-[90vw] overflow-hidden flex flex-col">
        <DialogHeader>
          <DialogTitle className="text-sm">Edit {resourceType}</DialogTitle>
          <DialogDescription className="text-xs">
            {supportsForm
              ? "Edit resource fields using the form or switch to JSON for full control."
              : "Edit the resource JSON directly."}
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-auto">
          {supportsForm ? (
            <Tabs
              value={activeTab}
              onValueChange={(v) => setActiveTab(v as "form" | "json")}
            >
              <TabsList className="grid w-full grid-cols-2 h-8">
                <TabsTrigger value="form" className="text-xs">
                  Form
                </TabsTrigger>
                <TabsTrigger value="json" className="text-xs">
                  JSON
                </TabsTrigger>
              </TabsList>
              <TabsContent value="form" className="mt-4">
                {renderForm()}
              </TabsContent>
              <TabsContent value="json" className="mt-4">
                <JsonEditorTab
                  resource={editedResource}
                  onChange={setEditedResource}
                  error={jsonError}
                  setError={setJsonError}
                />
              </TabsContent>
            </Tabs>
          ) : (
            <div className="py-4">
              <JsonEditorTab
                resource={editedResource}
                onChange={setEditedResource}
                error={jsonError}
                setError={setJsonError}
              />
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button size="sm" onClick={handleSave} disabled={!!jsonError}>
            Save Changes
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
