import type {
  Bundle,
  CodeableConcept,
  Coding,
  ContactDetail,
  Quantity,
  Reference,
  Resource,
} from "fhir/r4";

// =============================================================================
// CDS Hooks Core Types
// =============================================================================

/**
 * CDS Service descriptor from discovery endpoint
 */
export interface CdsService {
  hook: string;
  id: string;
  title?: string;
  description: string;
  prefetch?: Record<string, string>;
  usageRequirements?: string;
}

/**
 * Response from GET /cds-services
 */
export interface CdsDiscoveryResponse {
  services: CdsService[];
}

/**
 * Request body for POST /cds-services/{id}
 */
export interface CdsRequest {
  hook: string;
  hookInstance: string;
  fhirServer?: string;
  fhirAuthorization?: FhirAuthorization;
  context: Record<string, unknown>;
  prefetch?: Record<string, Resource | Bundle | null>;
}

/**
 * FHIR authorization info passed to CDS service
 */
export interface FhirAuthorization {
  access_token: string;
  token_type: string;
  expires_in: number;
  scope: string;
  subject: string;
}

/**
 * Response from POST /cds-services/{id}
 */
export interface CdsResponse {
  cards?: CdsCard[];
  systemActions?: CdsSystemAction[];
}

/**
 * CDS Hooks Card
 */
export interface CdsCard {
  uuid?: string;
  summary: string;
  detail?: string;
  indicator: "info" | "warning" | "critical";
  source: CdsSource;
  suggestions?: CdsSuggestion[];
  selectionBehavior?: "at-most-one" | "any";
  overrideReasons?: CdsOverrideReason[];
  links?: CdsLink[];
}

/**
 * Card source attribution
 */
export interface CdsSource {
  label: string;
  url?: string;
  icon?: string;
  topic?: Coding;
}

/**
 * Suggestion within a card
 */
export interface CdsSuggestion {
  label: string;
  uuid?: string;
  isRecommended?: boolean;
  actions?: CdsAction[];
}

/**
 * Action within a suggestion
 */
export interface CdsAction {
  type: "create" | "update" | "delete";
  description: string;
  resource?: Resource;
  resourceId?: string;
}

/**
 * System action (auto-applied, not shown as card)
 */
export interface CdsSystemAction {
  type: "create" | "update" | "delete";
  description?: string;
  resource?: Resource;
  resourceId?: string;
}

/**
 * Link within a card
 */
export interface CdsLink {
  label: string;
  url: string;
  type: "absolute" | "smart";
  appContext?: string;
}

/**
 * Override reason for card rejection
 */
export interface CdsOverrideReason {
  code?: Coding;
  system?: string;
  display?: string;
}

// =============================================================================
// Hook Context Types
// =============================================================================

/**
 * Context for patient-view hook
 */
export interface PatientViewContext {
  userId: string;
  patientId: string;
  encounterId?: string;
}

/**
 * Context for order-sign hook
 */
export interface OrderSignContext {
  userId: string;
  patientId: string;
  encounterId?: string;
  draftOrders: Bundle;
}

/**
 * Context for order-select hook
 */
export interface OrderSelectContext {
  userId: string;
  patientId: string;
  encounterId?: string;
  draftOrders: Bundle;
  selections: string[];
}

/**
 * Context for appointment-book hook
 */
export interface AppointmentBookContext {
  userId: string;
  patientId: string;
  encounterId?: string;
  appointments: Bundle;
}

/**
 * Context for encounter-start hook
 */
export interface EncounterStartContext {
  userId: string;
  patientId: string;
  encounterId: string;
}

/**
 * Context for encounter-discharge hook
 */
export interface EncounterDischargeContext {
  userId: string;
  patientId: string;
  encounterId: string;
}

/**
 * Context for order-dispatch hook
 */
export interface OrderDispatchContext {
  patientId: string;
  encounterId?: string;
  performer: string;
  order: string;
  task?: Resource;
}

/**
 * Union type of all hook contexts
 */
export type HookContext =
  | PatientViewContext
  | OrderSignContext
  | OrderSelectContext
  | AppointmentBookContext
  | EncounterStartContext
  | EncounterDischargeContext
  | OrderDispatchContext;

// =============================================================================
// CRD Coverage Information Extension Types
// =============================================================================

/**
 * Coverage status values
 */
export type CoverageStatus = "covered" | "not-covered" | "conditional";

/**
 * Prior authorization status values
 */
export type PriorAuthStatus =
  | "no-auth"
  | "auth-needed"
  | "satisfied"
  | "performpa"
  | "conditional";

/**
 * Documentation needed status values
 */
export type DocNeededStatus =
  | "no-doc"
  | "clinical"
  | "admin"
  | "patient"
  | "conditional";

/**
 * Coverage detail information
 */
export interface CoverageDetail {
  category: string;
  code?: CodeableConcept;
  value?: Quantity | string | boolean;
  qualification?: string;
}

/**
 * Parsed CRD coverage-information extension
 */
export interface CoverageInformationExtension {
  coverage?: Reference;
  covered?: CoverageStatus;
  paNeeded?: PriorAuthStatus;
  docNeeded?: DocNeededStatus;
  infoNeeded?: string[];
  billingCodes?: Coding[];
  reasons?: CodeableConcept[];
  details?: CoverageDetail[];
  dependency?: Reference;
  date?: string;
  coverageAssertionId?: string;
  satisfiedPaId?: string;
  contact?: ContactDetail;
  questionnaires?: string[];
}

// =============================================================================
// Helper Types
// =============================================================================

/**
 * Supported hook types
 */
export type SupportedHookType =
  | "patient-view"
  | "order-sign"
  | "order-select"
  | "appointment-book"
  | "encounter-start"
  | "encounter-discharge"
  | "order-dispatch";

/**
 * Hook metadata
 */
export interface HookMetadata {
  id: SupportedHookType;
  name: string;
  description: string;
  contextFields: ContextFieldDefinition[];
}

/**
 * Context field definition for UI building
 */
export interface ContextFieldDefinition {
  name: string;
  label: string;
  type: "string" | "reference" | "bundle" | "bundleSelect";
  resourceType?: string | string[];
  required: boolean;
  description?: string;
  /** For bundleSelect type: the name of the bundle field to select from */
  sourceBundle?: string;
}

// =============================================================================
// Constants
// =============================================================================

/**
 * Metadata for all supported hooks
 */
export const HOOK_DEFINITIONS: HookMetadata[] = [
  {
    id: "patient-view",
    name: "Patient View",
    description: "Triggered when a patient record is opened",
    contextFields: [
      {
        name: "userId",
        label: "User",
        type: "reference",
        resourceType: ["Practitioner", "PractitionerRole"],
        required: true,
        description: "The ID of the current user",
      },
      {
        name: "patientId",
        label: "Patient",
        type: "reference",
        resourceType: "Patient",
        required: true,
        description: "The ID of the patient whose record is being viewed",
      },
      {
        name: "encounterId",
        label: "Encounter",
        type: "reference",
        resourceType: "Encounter",
        required: false,
        description: "The ID of the current encounter, if applicable",
      },
    ],
  },
  {
    id: "order-sign",
    name: "Order Sign",
    description: "Triggered when orders are being signed",
    contextFields: [
      {
        name: "userId",
        label: "User",
        type: "reference",
        resourceType: ["Practitioner", "PractitionerRole"],
        required: true,
      },
      {
        name: "patientId",
        label: "Patient",
        type: "reference",
        resourceType: "Patient",
        required: true,
      },
      {
        name: "encounterId",
        label: "Encounter",
        type: "reference",
        resourceType: "Encounter",
        required: false,
      },
      {
        name: "draftOrders",
        label: "Draft Orders",
        type: "bundle",
        resourceType: [
          "ServiceRequest",
          "MedicationRequest",
          "DeviceRequest",
          "NutritionOrder",
          "VisionPrescription",
        ],
        required: true,
        description: "Bundle of draft orders being signed",
      },
    ],
  },
  {
    id: "order-select",
    name: "Order Select",
    description: "Triggered when orders are selected for editing",
    contextFields: [
      {
        name: "userId",
        label: "User",
        type: "reference",
        resourceType: ["Practitioner", "PractitionerRole"],
        required: true,
      },
      {
        name: "patientId",
        label: "Patient",
        type: "reference",
        resourceType: "Patient",
        required: true,
      },
      {
        name: "encounterId",
        label: "Encounter",
        type: "reference",
        resourceType: "Encounter",
        required: false,
      },
      {
        name: "draftOrders",
        label: "Draft Orders",
        type: "bundle",
        resourceType: [
          "ServiceRequest",
          "MedicationRequest",
          "DeviceRequest",
          "NutritionOrder",
          "VisionPrescription",
        ],
        required: true,
      },
      {
        name: "selections",
        label: "Selections",
        type: "bundleSelect",
        required: true,
        sourceBundle: "draftOrders",
        description: "Select orders from the draft orders bundle",
      },
    ],
  },
  {
    id: "appointment-book",
    name: "Appointment Book",
    description: "Triggered when booking an appointment",
    contextFields: [
      {
        name: "userId",
        label: "User",
        type: "reference",
        resourceType: ["Practitioner", "PractitionerRole"],
        required: true,
      },
      {
        name: "patientId",
        label: "Patient",
        type: "reference",
        resourceType: "Patient",
        required: true,
      },
      {
        name: "encounterId",
        label: "Encounter",
        type: "reference",
        resourceType: "Encounter",
        required: false,
      },
      {
        name: "appointments",
        label: "Appointments",
        type: "bundle",
        resourceType: "Appointment",
        required: true,
        description: "Bundle of appointments being booked",
      },
    ],
  },
  {
    id: "encounter-start",
    name: "Encounter Start",
    description: "Triggered when an encounter begins",
    contextFields: [
      {
        name: "userId",
        label: "User",
        type: "reference",
        resourceType: ["Practitioner", "PractitionerRole"],
        required: true,
      },
      {
        name: "patientId",
        label: "Patient",
        type: "reference",
        resourceType: "Patient",
        required: true,
      },
      {
        name: "encounterId",
        label: "Encounter",
        type: "reference",
        resourceType: "Encounter",
        required: true,
      },
    ],
  },
  {
    id: "encounter-discharge",
    name: "Encounter Discharge",
    description: "Triggered when discharging a patient",
    contextFields: [
      {
        name: "userId",
        label: "User",
        type: "reference",
        resourceType: ["Practitioner", "PractitionerRole"],
        required: true,
      },
      {
        name: "patientId",
        label: "Patient",
        type: "reference",
        resourceType: "Patient",
        required: true,
      },
      {
        name: "encounterId",
        label: "Encounter",
        type: "reference",
        resourceType: "Encounter",
        required: true,
      },
    ],
  },
  {
    id: "order-dispatch",
    name: "Order Dispatch",
    description: "Triggered when dispatching orders to a performer",
    contextFields: [
      {
        name: "patientId",
        label: "Patient",
        type: "reference",
        resourceType: "Patient",
        required: true,
      },
      {
        name: "encounterId",
        label: "Encounter",
        type: "reference",
        resourceType: "Encounter",
        required: false,
        description: "The encounter associated with this dispatch",
      },
      {
        name: "performer",
        label: "Performer",
        type: "reference",
        resourceType: ["Practitioner", "PractitionerRole", "Organization"],
        required: true,
        description: "The performer receiving the order",
      },
      {
        name: "order",
        label: "Order",
        type: "reference",
        resourceType: [
          "ServiceRequest",
          "MedicationRequest",
          "DeviceRequest",
          "NutritionOrder",
          "VisionPrescription",
        ],
        required: true,
        description: "The order being dispatched",
      },
      {
        name: "task",
        label: "Task",
        type: "reference",
        resourceType: "Task",
        required: false,
        description: "The fulfillment task, if applicable",
      },
    ],
  },
];

// =============================================================================
// Helper Functions
// =============================================================================

/**
 * Coverage information extension URL
 */
const COVERAGE_INFO_EXTENSION_URL =
  "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

/**
 * Parse coverage-information extensions from a FHIR resource
 */
export function parseCoverageInformation(
  resource: unknown,
): CoverageInformationExtension[] {
  if (!resource || typeof resource !== "object") {
    return [];
  }

  const res = resource as {
    extension?: Array<{ url: string; extension?: unknown[] }>;
  };
  if (!Array.isArray(res.extension)) {
    return [];
  }

  const coverageExtensions = res.extension.filter(
    (ext) => ext.url === COVERAGE_INFO_EXTENSION_URL,
  );

  return coverageExtensions.map((ext) => parseExtension(ext.extension || []));
}

function parseExtension(extensions: unknown[]): CoverageInformationExtension {
  const result: CoverageInformationExtension = {};

  for (const ext of extensions) {
    if (!ext || typeof ext !== "object") continue;
    const e = ext as { url?: string; [key: string]: unknown };

    switch (e.url) {
      case "coverage":
        result.coverage = e.valueReference as Reference;
        break;
      case "covered":
        result.covered = e.valueCode as CoverageStatus;
        break;
      case "pa-needed":
        result.paNeeded = e.valueCode as PriorAuthStatus;
        break;
      case "doc-needed":
        result.docNeeded = e.valueCode as DocNeededStatus;
        break;
      case "info-needed":
        if (!result.infoNeeded) result.infoNeeded = [];
        result.infoNeeded.push(e.valueCode as string);
        break;
      case "billingCode":
        if (!result.billingCodes) result.billingCodes = [];
        result.billingCodes.push(e.valueCoding as Coding);
        break;
      case "reason":
        if (!result.reasons) result.reasons = [];
        result.reasons.push(e.valueCodeableConcept as CodeableConcept);
        break;
      case "detail":
        if (!result.details) result.details = [];
        result.details.push(parseDetailExtension(e.extension as unknown[]));
        break;
      case "dependency":
        result.dependency = e.valueReference as Reference;
        break;
      case "date":
        result.date = e.valueDate as string;
        break;
      case "coverage-assertion-id":
        result.coverageAssertionId = e.valueString as string;
        break;
      case "satisfied-pa-id":
        result.satisfiedPaId = e.valueString as string;
        break;
      case "contact":
        result.contact = e.valueContactDetail as ContactDetail;
        break;
      case "questionnaire":
        if (!result.questionnaires) result.questionnaires = [];
        result.questionnaires.push(e.valueCanonical as string);
        break;
    }
  }

  return result;
}

function parseDetailExtension(extensions: unknown[]): CoverageDetail {
  const detail: CoverageDetail = { category: "" };

  for (const ext of extensions || []) {
    if (!ext || typeof ext !== "object") continue;
    const e = ext as { url?: string; [key: string]: unknown };

    switch (e.url) {
      case "category":
        detail.category = e.valueCode as string;
        break;
      case "code":
        detail.code = e.valueCodeableConcept as CodeableConcept;
        break;
      case "value":
        detail.value =
          (e.valueQuantity as Quantity) ||
          (e.valueString as string) ||
          (e.valueBoolean as boolean);
        break;
      case "qualification":
        detail.qualification = e.valueString as string;
        break;
    }
  }

  return detail;
}

/**
 * Build prefetch URL by replacing tokens with context values.
 * Handles FHIR references (e.g., "Patient/123") by extracting just the ID
 * when the template already includes the resource type.
 */
export function buildPrefetchUrl(
  template: string,
  context: Record<string, unknown>,
): string {
  return template.replace(/\{\{context\.(\w+)\}\}/g, (match, key) => {
    const value = context[key];
    if (typeof value !== "string") return "";

    // Check if value is a FHIR reference (e.g., "Patient/123")
    if (value.includes("/")) {
      const [resourceType, id] = value.split("/");
      // Check if the template already has the resource type before the token
      // e.g., "Patient/{{context.patientId}}" should become "Patient/123" not "Patient/Patient/123"
      const tokenIndex = template.indexOf(match);
      const beforeToken = template.substring(0, tokenIndex);
      if (beforeToken.endsWith(`${resourceType}/`)) {
        return id;
      }
    }

    return value;
  });
}

/**
 * Generate a UUID v4 for hookInstance
 */
export function generateHookInstance(): string {
  return crypto.randomUUID();
}

/**
 * Get hook definition by ID
 */
export function getHookDefinition(hookId: string): HookMetadata | undefined {
  return HOOK_DEFINITIONS.find((h) => h.id === hookId);
}

// =============================================================================
// Resource Templates for Creating New Resources
// =============================================================================

export interface ResourceTemplate {
  resourceType: string;
  label: string;
  description: string;
  /** Function to create a new resource, optionally using patient context */
  create: (patientId?: string) => Resource;
}

/**
 * Generate a temporary ID for new resources (urn:uuid format)
 */
function generateTempId(): string {
  return `urn:uuid:${crypto.randomUUID()}`;
}

/**
 * Templates for creating new resources in CDS hook contexts
 */
export const RESOURCE_TEMPLATES: Record<string, ResourceTemplate[]> = {
  ServiceRequest: [
    {
      resourceType: "ServiceRequest",
      label: "MRI Brain",
      description:
        "MRI brain with and without contrast (triggers ImagingNetworkDispatch)",
      create: (patientId) => ({
        resourceType: "ServiceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        category: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "363679005",
                display: "Imaging",
              },
            ],
          },
        ],
        code: {
          coding: [
            {
              system: "http://www.ama-assn.org/go/cpt",
              code: "70553",
              display: "MRI brain with and without contrast",
            },
          ],
          text: "MRI Brain w/ and w/o Contrast",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
      }),
    },
    {
      resourceType: "ServiceRequest",
      label: "MRI Lumbar Spine",
      description:
        "MRI lumbar spine without contrast (triggers ImagingNetworkDispatch)",
      create: (patientId) => ({
        resourceType: "ServiceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        category: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "363679005",
                display: "Imaging",
              },
            ],
          },
        ],
        code: {
          coding: [
            {
              system: "http://www.ama-assn.org/go/cpt",
              code: "72148",
              display: "MRI lumbar spine without contrast",
            },
          ],
          text: "MRI Lumbar Spine w/o Contrast",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
      }),
    },
    {
      resourceType: "ServiceRequest",
      label: "CT Head",
      description: "CT head without contrast (triggers ImagingNetworkDispatch)",
      create: (patientId) => ({
        resourceType: "ServiceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        category: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "363679005",
                display: "Imaging",
              },
            ],
          },
        ],
        code: {
          coding: [
            {
              system: "http://www.ama-assn.org/go/cpt",
              code: "70450",
              display: "CT head without contrast",
            },
          ],
          text: "CT Head w/o Contrast",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
      }),
    },
    {
      resourceType: "ServiceRequest",
      label: "Home Health Services",
      description:
        "Home health services certification (G0180) - Use with pat015",
      create: (patientId) => ({
        resourceType: "ServiceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        category: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "385763009",
                display: "Home health care",
              },
            ],
          },
        ],
        code: {
          coding: [
            {
              system: "https://bluebutton.cms.gov/resources/codesystem/hcpcs",
              code: "G0180",
              display:
                "Physician certification for Medicare-covered home health services",
            },
          ],
          text: "Home Health Services Certification",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
      }),
    },
  ],
  MedicationRequest: [
    {
      resourceType: "MedicationRequest",
      label: "Oxycodone 5mg",
      description: "Oxycodone 5 MG Oral Tablet (triggers OpioidPrescribing)",
      create: (patientId) => ({
        resourceType: "MedicationRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        medicationCodeableConcept: {
          coding: [
            {
              system: "http://www.nlm.nih.gov/research/umls/rxnorm",
              code: "1049502",
              display: "Oxycodone 5 MG Oral Tablet",
            },
          ],
          text: "Oxycodone 5mg",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        dosageInstruction: [
          {
            text: "Take 1 tablet every 4-6 hours as needed for pain",
            timing: {
              repeat: {
                frequency: 1,
                period: 4,
                periodUnit: "h",
              },
            },
            doseAndRate: [
              {
                doseQuantity: {
                  value: 1,
                  unit: "tablet",
                },
              },
            ],
          },
        ],
      }),
    },
    {
      resourceType: "MedicationRequest",
      label: "Hydrocodone/APAP",
      description:
        "Hydrocodone 5 MG / Acetaminophen 325 MG (triggers OpioidPrescribing)",
      create: (patientId) => ({
        resourceType: "MedicationRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        medicationCodeableConcept: {
          coding: [
            {
              system: "http://www.nlm.nih.gov/research/umls/rxnorm",
              code: "197696",
              display: "Hydrocodone 5 MG / Acetaminophen 325 MG",
            },
          ],
          text: "Hydrocodone/Acetaminophen 5-325mg",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        dosageInstruction: [
          {
            text: "Take 1 tablet every 4-6 hours as needed for pain",
          },
        ],
      }),
    },
    {
      resourceType: "MedicationRequest",
      label: "Fentanyl Patch 25mcg",
      description:
        "Fentanyl 25 MCG/HR Transdermal (triggers OpioidPrescribing)",
      create: (patientId) => ({
        resourceType: "MedicationRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        medicationCodeableConcept: {
          coding: [
            {
              system: "http://www.nlm.nih.gov/research/umls/rxnorm",
              code: "197446",
              display: "Fentanyl 25 MCG/HR Transdermal",
            },
          ],
          text: "Fentanyl Patch 25mcg/hr",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        dosageInstruction: [
          {
            text: "Apply 1 patch every 72 hours",
          },
        ],
      }),
    },
    {
      resourceType: "MedicationRequest",
      label: "Morphine 15mg",
      description:
        "Morphine Sulfate 15 MG Oral Tablet (triggers OpioidPrescribing)",
      create: (patientId) => ({
        resourceType: "MedicationRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        medicationCodeableConcept: {
          coding: [
            {
              system: "http://www.nlm.nih.gov/research/umls/rxnorm",
              code: "1014599",
              display: "Morphine Sulfate 15 MG Oral Tablet",
            },
          ],
          text: "Morphine Sulfate 15mg",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        dosageInstruction: [
          {
            text: "Take 1 tablet every 4 hours as needed for pain",
          },
        ],
      }),
    },
    {
      resourceType: "MedicationRequest",
      label: "Methotrexate 2.5mg",
      description:
        "Methotrexate for SLE/RA (triggers ImmunosuppressiveDrugs) - Use with pat014",
      create: (patientId) => ({
        resourceType: "MedicationRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        medicationCodeableConcept: {
          coding: [
            {
              system: "http://www.nlm.nih.gov/research/umls/rxnorm",
              code: "105585",
              display: "Methotrexate 2.5 MG Oral Tablet",
            },
          ],
          text: "Methotrexate 2.5mg",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        reasonCode: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "52042003",
                display: "Systemic lupus erythematosus glomerulonephritis",
              },
            ],
          },
        ],
        dosageInstruction: [
          {
            text: "Take 2.5mg once weekly",
          },
        ],
      }),
    },
    {
      resourceType: "MedicationRequest",
      label: "Azathioprine 50mg",
      description:
        "Azathioprine for immunosuppression (triggers ImmunosuppressiveDrugs) - Use with pat014",
      create: (patientId) => ({
        resourceType: "MedicationRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        medicationCodeableConcept: {
          coding: [
            {
              system: "http://www.nlm.nih.gov/research/umls/rxnorm",
              code: "105611",
              display: "Azathioprine 50 MG Oral Tablet",
            },
          ],
          text: "Azathioprine 50mg",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        reasonCode: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "52042003",
                display: "Systemic lupus erythematosus glomerulonephritis",
              },
            ],
          },
        ],
        dosageInstruction: [
          {
            text: "Take 50mg once daily",
          },
        ],
      }),
    },
  ],
  DeviceRequest: [
    {
      resourceType: "DeviceRequest",
      label: "Hospital Bed",
      description:
        "Hospital bed with side rails (triggers HospitalBedsAndAccessories)",
      create: (patientId) => ({
        resourceType: "DeviceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "original-order",
        codeCodeableConcept: {
          coding: [
            {
              system: "https://bluebutton.cms.gov/resources/codesystem/hcpcs",
              code: "E0250",
              display:
                "Hospital bed fixed height with any type of side rails, mattress",
            },
          ],
          text: "Hospital Bed with Side Rails",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
      }),
    },
    {
      resourceType: "DeviceRequest",
      label: "Hospital Bed (Variable Height)",
      description:
        "Hospital bed variable height (triggers HospitalBedsAndAccessories)",
      create: (patientId) => ({
        resourceType: "DeviceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "original-order",
        codeCodeableConcept: {
          coding: [
            {
              system: "https://bluebutton.cms.gov/resources/codesystem/hcpcs",
              code: "E0251",
              display:
                "Hospital bed variable height with any type of side rails, mattress",
            },
          ],
          text: "Hospital Bed Variable Height",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
      }),
    },
    {
      resourceType: "DeviceRequest",
      label: "Stationary Oxygen",
      description:
        "Stationary compressed gaseous oxygen (triggers HomeOxygenDispatch)",
      create: (patientId) => ({
        resourceType: "DeviceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        codeCodeableConcept: {
          coding: [
            {
              system: "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
              code: "E0424",
              display: "Stationary compressed gaseous oxygen system",
            },
          ],
          text: "Stationary Oxygen System",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
      }),
    },
    {
      resourceType: "DeviceRequest",
      label: "Portable Oxygen",
      description:
        "Portable gaseous oxygen system (triggers HomeOxygenDispatch)",
      create: (patientId) => ({
        resourceType: "DeviceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        codeCodeableConcept: {
          coding: [
            {
              system: "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
              code: "E0431",
              display: "Portable gaseous oxygen system",
            },
          ],
          text: "Portable Oxygen System",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
      }),
    },
    {
      resourceType: "DeviceRequest",
      label: "Oxygen Concentrator",
      description: "Oxygen concentrator (triggers HomeOxygenDispatch)",
      create: (patientId) => ({
        resourceType: "DeviceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        codeCodeableConcept: {
          coding: [
            {
              system: "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
              code: "E1390",
              display: "Oxygen concentrator",
            },
          ],
          text: "Oxygen Concentrator",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
      }),
    },
    {
      resourceType: "DeviceRequest",
      label: "CPAP Machine",
      description: "CPAP for sleep apnea - Use with pat014 (has OSA diagnosis)",
      create: (patientId) => ({
        resourceType: "DeviceRequest",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        codeCodeableConcept: {
          coding: [
            {
              system: "https://bluebutton.cms.gov/resources/codesystem/hcpcs",
              code: "E0601",
              display: "Continuous positive airway pressure (CPAP) device",
            },
          ],
          text: "CPAP Machine",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        reasonCode: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "78275009",
                display: "Obstructive sleep apnea syndrome",
              },
            ],
          },
        ],
      }),
    },
  ],
  Appointment: [
    {
      resourceType: "Appointment",
      label: "Cardiology Consultation",
      description:
        "Cardiology consultation appointment (triggers CardiologyConsultation)",
      create: (patientId) => ({
        resourceType: "Appointment",
        id: generateTempId(),
        status: "proposed",
        serviceType: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "394579002",
                display: "Cardiology",
              },
            ],
          },
        ],
        description: "Cardiology consultation for cardiac evaluation",
        start: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString(),
        end: new Date(
          Date.now() + 7 * 24 * 60 * 60 * 1000 + 30 * 60 * 1000,
        ).toISOString(),
        participant: patientId
          ? [
              {
                actor: { reference: `Patient/${patientId}` },
                status: "accepted",
              },
            ]
          : [],
      }),
    },
    {
      resourceType: "Appointment",
      label: "Physical Therapy (CPT 97110)",
      description:
        "Therapeutic exercises session (triggers PhysicalTherapy session tracking)",
      create: (patientId) => ({
        resourceType: "Appointment",
        id: generateTempId(),
        status: "proposed",
        serviceType: [
          {
            coding: [
              {
                system: "http://www.ama-assn.org/go/cpt",
                code: "97110",
                display: "Therapeutic exercises",
              },
            ],
          },
        ],
        reasonCode: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "161570007",
                display: "H/O: back problem",
              },
            ],
            text: "Low back pain rehabilitation",
          },
        ],
        description: "Physical therapy session",
        start: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString(),
        end: new Date(
          Date.now() + 7 * 24 * 60 * 60 * 1000 + 45 * 60 * 1000,
        ).toISOString(),
        participant: patientId
          ? [
              {
                actor: { reference: `Patient/${patientId}` },
                status: "accepted",
              },
            ]
          : [],
      }),
    },
  ],
  Encounter: [
    {
      resourceType: "Encounter",
      label: "Ambulatory Encounter",
      description: "Outpatient office encounter",
      create: (patientId) => ({
        resourceType: "Encounter",
        id: generateTempId(),
        status: "in-progress",
        class: {
          system: "http://terminology.hl7.org/CodeSystem/v3-ActCode",
          code: "AMB",
          display: "ambulatory",
        },
        type: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "308335008",
                display: "Patient encounter procedure",
              },
            ],
          },
        ],
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        period: {
          start: new Date().toISOString(),
        },
      }),
    },
    {
      resourceType: "Encounter",
      label: "Inpatient - Heart Failure",
      description:
        "Inpatient encounter for heart failure (triggers CareManagement, CareTransition) - Use with pat014",
      create: (patientId) => ({
        resourceType: "Encounter",
        id: generateTempId(),
        status: "in-progress",
        class: {
          system: "http://terminology.hl7.org/CodeSystem/v3-ActCode",
          code: "IMP",
          display: "inpatient encounter",
        },
        type: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "183452005",
                display: "Emergency hospital admission",
              },
            ],
          },
        ],
        reasonCode: [
          {
            coding: [
              {
                system: "http://hl7.org/fhir/sid/icd-10-cm",
                code: "I50.9",
                display: "Heart failure, unspecified",
              },
            ],
            text: "Congestive heart failure",
          },
        ],
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        period: {
          start: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString(),
        },
      }),
    },
    {
      resourceType: "Encounter",
      label: "Inpatient - COPD",
      description:
        "Inpatient encounter for COPD exacerbation (triggers CareManagement) - Use with pat015",
      create: (patientId) => ({
        resourceType: "Encounter",
        id: generateTempId(),
        status: "in-progress",
        class: {
          system: "http://terminology.hl7.org/CodeSystem/v3-ActCode",
          code: "IMP",
          display: "inpatient encounter",
        },
        type: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "32485007",
                display: "Hospital admission",
              },
            ],
          },
        ],
        reasonCode: [
          {
            coding: [
              {
                system: "http://hl7.org/fhir/sid/icd-10-cm",
                code: "J44.1",
                display:
                  "Chronic obstructive pulmonary disease with acute exacerbation",
              },
            ],
            text: "COPD exacerbation",
          },
        ],
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        period: {
          start: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString(),
        },
      }),
    },
    {
      resourceType: "Encounter",
      label: "Inpatient - Diabetes",
      description:
        "Inpatient encounter for diabetes (triggers CareManagement) - Use with pat016",
      create: (patientId) => ({
        resourceType: "Encounter",
        id: generateTempId(),
        status: "in-progress",
        class: {
          system: "http://terminology.hl7.org/CodeSystem/v3-ActCode",
          code: "IMP",
          display: "inpatient encounter",
        },
        type: [
          {
            coding: [
              {
                system: "http://snomed.info/sct",
                code: "32485007",
                display: "Hospital admission",
              },
            ],
          },
        ],
        reasonCode: [
          {
            coding: [
              {
                system: "http://hl7.org/fhir/sid/icd-10-cm",
                code: "E11.9",
                display: "Type 2 diabetes mellitus without complications",
              },
            ],
            text: "Type 2 diabetes",
          },
        ],
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        period: {
          start: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString(),
        },
      }),
    },
  ],
  NutritionOrder: [
    {
      resourceType: "NutritionOrder",
      label: "Diet Order",
      description: "Basic nutrition/diet order",
      create: (patientId) => ({
        resourceType: "NutritionOrder",
        id: generateTempId(),
        status: "draft",
        intent: "order",
        patient: patientId ? { reference: `Patient/${patientId}` } : undefined,
        dateTime: new Date().toISOString(),
        oralDiet: {
          type: [
            {
              coding: [
                {
                  system: "http://snomed.info/sct",
                  code: "160674004",
                  display: "Normal diet",
                },
              ],
              text: "Regular Diet",
            },
          ],
        },
      }),
    },
  ],
  VisionPrescription: [
    {
      resourceType: "VisionPrescription",
      label: "Eyeglass Prescription",
      description: "Basic vision correction prescription",
      create: (patientId) => ({
        resourceType: "VisionPrescription",
        id: generateTempId(),
        status: "draft",
        created: new Date().toISOString().split("T")[0],
        patient: patientId ? { reference: `Patient/${patientId}` } : undefined,
        dateWritten: new Date().toISOString().split("T")[0],
        lensSpecification: [
          {
            product: {
              coding: [
                {
                  system:
                    "http://terminology.hl7.org/CodeSystem/ex-visionprescriptionproduct",
                  code: "lens",
                },
              ],
            },
            eye: "right",
            sphere: -2.0,
            cylinder: -0.5,
            axis: 180,
          },
        ],
      }),
    },
  ],
  Procedure: [
    {
      resourceType: "Procedure",
      label: "Surgical Procedure",
      description: "Planned surgical procedure",
      create: (patientId) => ({
        resourceType: "Procedure",
        id: generateTempId(),
        status: "preparation",
        category: {
          coding: [
            {
              system: "http://snomed.info/sct",
              code: "387713003",
              display: "Surgical procedure",
            },
          ],
        },
        code: {
          coding: [
            {
              system: "http://www.ama-assn.org/go/cpt",
              code: "27447",
              display: "Total knee replacement",
            },
          ],
          text: "Total Knee Arthroplasty",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
      }),
    },
    {
      resourceType: "Procedure",
      label: "Diagnostic Procedure",
      description: "Diagnostic procedure order",
      create: (patientId) => ({
        resourceType: "Procedure",
        id: generateTempId(),
        status: "preparation",
        category: {
          coding: [
            {
              system: "http://snomed.info/sct",
              code: "103693007",
              display: "Diagnostic procedure",
            },
          ],
        },
        code: {
          coding: [
            {
              system: "http://www.ama-assn.org/go/cpt",
              code: "43239",
              display: "Upper GI endoscopy with biopsy",
            },
          ],
          text: "EGD with Biopsy",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
      }),
    },
  ],
  Claim: [
    {
      resourceType: "Claim",
      label: "Professional Claim",
      description: "Professional services claim",
      create: (patientId) => ({
        resourceType: "Claim",
        id: generateTempId(),
        status: "draft",
        type: {
          coding: [
            {
              system: "http://terminology.hl7.org/CodeSystem/claim-type",
              code: "professional",
              display: "Professional",
            },
          ],
        },
        use: "preauthorization",
        patient: patientId ? { reference: `Patient/${patientId}` } : undefined,
        created: new Date().toISOString(),
        provider: {
          display: "Example Provider",
        },
        priority: {
          coding: [
            {
              system: "http://terminology.hl7.org/CodeSystem/processpriority",
              code: "normal",
            },
          ],
        },
        insurance: [
          {
            sequence: 1,
            focal: true,
            coverage: {
              display: "Primary Insurance",
            },
          },
        ],
        item: [
          {
            sequence: 1,
            productOrService: {
              coding: [
                {
                  system: "http://www.ama-assn.org/go/cpt",
                  code: "99213",
                  display: "Office visit, established patient",
                },
              ],
            },
            servicedDate: new Date().toISOString().split("T")[0],
            unitPrice: {
              value: 150.0,
              currency: "USD",
            },
          },
        ],
      }),
    },
    {
      resourceType: "Claim",
      label: "Institutional Claim",
      description: "Hospital/facility claim",
      create: (patientId) => ({
        resourceType: "Claim",
        id: generateTempId(),
        status: "draft",
        type: {
          coding: [
            {
              system: "http://terminology.hl7.org/CodeSystem/claim-type",
              code: "institutional",
              display: "Institutional",
            },
          ],
        },
        use: "preauthorization",
        patient: patientId ? { reference: `Patient/${patientId}` } : undefined,
        created: new Date().toISOString(),
        provider: {
          display: "Example Hospital",
        },
        priority: {
          coding: [
            {
              system: "http://terminology.hl7.org/CodeSystem/processpriority",
              code: "normal",
            },
          ],
        },
        insurance: [
          {
            sequence: 1,
            focal: true,
            coverage: {
              display: "Primary Insurance",
            },
          },
        ],
        item: [
          {
            sequence: 1,
            productOrService: {
              coding: [
                {
                  system: "http://www.cms.gov/Medicare/Coding/MedHCPCSGenInfo",
                  code: "99223",
                  display: "Hospital admission",
                },
              ],
            },
            servicedDate: new Date().toISOString().split("T")[0],
            unitPrice: {
              value: 500.0,
              currency: "USD",
            },
          },
        ],
      }),
    },
  ],
  Coverage: [
    {
      resourceType: "Coverage",
      label: "Health Insurance",
      description: "Commercial health insurance coverage",
      create: (patientId) => ({
        resourceType: "Coverage",
        id: generateTempId(),
        status: "active",
        type: {
          coding: [
            {
              system: "http://terminology.hl7.org/CodeSystem/v3-ActCode",
              code: "HIP",
              display: "health insurance plan policy",
            },
          ],
        },
        subscriber: patientId
          ? { reference: `Patient/${patientId}` }
          : undefined,
        beneficiary: patientId
          ? { reference: `Patient/${patientId}` }
          : undefined,
        relationship: {
          coding: [
            {
              system:
                "http://terminology.hl7.org/CodeSystem/subscriber-relationship",
              code: "self",
            },
          ],
        },
        period: {
          start: new Date().toISOString().split("T")[0],
          end: new Date(Date.now() + 365 * 24 * 60 * 60 * 1000)
            .toISOString()
            .split("T")[0],
        },
        payor: [
          {
            display: "Example Insurance Company",
          },
        ],
        class: [
          {
            type: {
              coding: [
                {
                  system:
                    "http://terminology.hl7.org/CodeSystem/coverage-class",
                  code: "plan",
                },
              ],
            },
            value: "GOLD",
            name: "Gold Plan",
          },
        ],
      }),
    },
    {
      resourceType: "Coverage",
      label: "Medicare",
      description: "Medicare coverage",
      create: (patientId) => ({
        resourceType: "Coverage",
        id: generateTempId(),
        status: "active",
        type: {
          coding: [
            {
              system: "http://terminology.hl7.org/CodeSystem/v3-ActCode",
              code: "MEDICARE",
              display: "Medicare",
            },
          ],
        },
        subscriber: patientId
          ? { reference: `Patient/${patientId}` }
          : undefined,
        beneficiary: patientId
          ? { reference: `Patient/${patientId}` }
          : undefined,
        relationship: {
          coding: [
            {
              system:
                "http://terminology.hl7.org/CodeSystem/subscriber-relationship",
              code: "self",
            },
          ],
        },
        period: {
          start: new Date().toISOString().split("T")[0],
        },
        payor: [
          {
            display: "Centers for Medicare & Medicaid Services",
          },
        ],
      }),
    },
  ],
  Task: [
    {
      resourceType: "Task",
      label: "Fulfillment Task",
      description: "Task for order fulfillment",
      create: (patientId) => ({
        resourceType: "Task",
        id: generateTempId(),
        status: "requested",
        intent: "order",
        code: {
          coding: [
            {
              system: "http://hl7.org/fhir/CodeSystem/task-code",
              code: "fulfill",
              display: "Fulfill the focal request",
            },
          ],
        },
        description: "Fulfill order",
        for: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        lastModified: new Date().toISOString(),
        requester: {
          display: "Ordering Provider",
        },
      }),
    },
    {
      resourceType: "Task",
      label: "Prior Auth Task",
      description: "Task for prior authorization",
      create: (patientId) => ({
        resourceType: "Task",
        id: generateTempId(),
        status: "requested",
        intent: "order",
        code: {
          coding: [
            {
              system:
                "http://hl7.org/fhir/us/davinci-crd/CodeSystem/task-reason",
              code: "prior-auth-needed",
              display: "Prior Authorization Needed",
            },
          ],
        },
        description: "Complete prior authorization",
        for: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        lastModified: new Date().toISOString(),
        requester: {
          display: "CRD Service",
        },
      }),
    },
  ],
  Condition: [
    {
      resourceType: "Condition",
      label: "Diagnosis",
      description: "Clinical diagnosis condition",
      create: (patientId) => ({
        resourceType: "Condition",
        id: generateTempId(),
        clinicalStatus: {
          coding: [
            {
              system:
                "http://terminology.hl7.org/CodeSystem/condition-clinical",
              code: "active",
            },
          ],
        },
        verificationStatus: {
          coding: [
            {
              system:
                "http://terminology.hl7.org/CodeSystem/condition-ver-status",
              code: "confirmed",
            },
          ],
        },
        category: [
          {
            coding: [
              {
                system:
                  "http://terminology.hl7.org/CodeSystem/condition-category",
                code: "encounter-diagnosis",
                display: "Encounter Diagnosis",
              },
            ],
          },
        ],
        code: {
          coding: [
            {
              system: "http://snomed.info/sct",
              code: "44054006",
              display: "Type 2 diabetes mellitus",
            },
          ],
          text: "Type 2 Diabetes",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        recordedDate: new Date().toISOString().split("T")[0],
      }),
    },
    {
      resourceType: "Condition",
      label: "Problem List Item",
      description: "Chronic condition on problem list",
      create: (patientId) => ({
        resourceType: "Condition",
        id: generateTempId(),
        clinicalStatus: {
          coding: [
            {
              system:
                "http://terminology.hl7.org/CodeSystem/condition-clinical",
              code: "active",
            },
          ],
        },
        verificationStatus: {
          coding: [
            {
              system:
                "http://terminology.hl7.org/CodeSystem/condition-ver-status",
              code: "confirmed",
            },
          ],
        },
        category: [
          {
            coding: [
              {
                system:
                  "http://terminology.hl7.org/CodeSystem/condition-category",
                code: "problem-list-item",
                display: "Problem List Item",
              },
            ],
          },
        ],
        code: {
          coding: [
            {
              system: "http://snomed.info/sct",
              code: "38341003",
              display: "Hypertensive disorder",
            },
          ],
          text: "Essential Hypertension",
        },
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        recordedDate: new Date().toISOString().split("T")[0],
      }),
    },
  ],
  CommunicationRequest: [
    {
      resourceType: "CommunicationRequest",
      label: "Documentation Request",
      description: "Request for additional documentation",
      create: (patientId) => ({
        resourceType: "CommunicationRequest",
        id: generateTempId(),
        status: "draft",
        category: [
          {
            coding: [
              {
                system:
                  "http://terminology.hl7.org/CodeSystem/communication-category",
                code: "notification",
                display: "Notification",
              },
            ],
          },
        ],
        priority: "routine",
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        authoredOn: new Date().toISOString(),
        payload: [
          {
            contentString:
              "Additional clinical documentation is required for prior authorization.",
          },
        ],
      }),
    },
  ],
  CarePlan: [
    {
      resourceType: "CarePlan",
      label: "Treatment Plan",
      description: "Patient treatment care plan",
      create: (patientId) => ({
        resourceType: "CarePlan",
        id: generateTempId(),
        status: "draft",
        intent: "plan",
        category: [
          {
            coding: [
              {
                system:
                  "http://hl7.org/fhir/us/core/CodeSystem/careplan-category",
                code: "assess-plan",
                display: "Assessment and Plan of Treatment",
              },
            ],
          },
        ],
        title: "Treatment Plan",
        description: "Care plan for patient treatment",
        subject: patientId ? { reference: `Patient/${patientId}` } : undefined,
        period: {
          start: new Date().toISOString().split("T")[0],
        },
        created: new Date().toISOString(),
      }),
    },
  ],
};

/**
 * Get available resource templates for a given resource type
 */
export function getResourceTemplates(resourceType: string): ResourceTemplate[] {
  return RESOURCE_TEMPLATES[resourceType] ?? [];
}

/**
 * Get all resource types that have templates available
 */
export function getTemplateResourceTypes(): string[] {
  return Object.keys(RESOURCE_TEMPLATES);
}
