import type {
  Bundle,
  ClaimResponse,
  OperationOutcome,
  Parameters,
} from "fhir/r4";

// =============================================================================
// PAS Extension URLs (mirrors PasConstants.java)
// =============================================================================

export const PAS_EXT = {
  REVIEW_ACTION:
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-reviewAction",
  REVIEW_ACTION_CODE:
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-reviewActionCode",
  AUTHORIZATION_NUMBER:
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-authorizationNumber",
  ADMIN_REF_NUMBER:
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-administrationReferenceNumber",
  ITEM_PREAUTH_PERIOD:
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemPreAuthPeriod",
  ITEM_TRACE_NUMBER:
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemTraceNumber",
  CERTIFICATION_TYPE:
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-certificationType",
  INFO_CHANGED:
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-infoChanged",
  INFO_CANCELLED:
    "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/modifierextension-infoCancelled",
} as const;

export const X12_REVIEW_ACTION_SYSTEM = "https://codesystem.x12.org/005010/306";
export const X12_CERT_TYPE_SYSTEM = "https://codesystem.x12.org/005010/1322";

// =============================================================================
// Review Action Codes
// =============================================================================

export type ReviewActionCode = "A1" | "A2" | "A3" | "A4" | "A6";

interface ReviewActionConfig {
  code: ReviewActionCode;
  label: string;
  bgClass: string;
  textClass: string;
  borderClass: string;
  dotClass: string;
}

export const REVIEW_ACTIONS: Record<ReviewActionCode, ReviewActionConfig> = {
  A1: {
    code: "A1",
    label: "Certified",

    bgClass: "bg-green-100 dark:bg-green-900/30",
    textClass: "text-green-700 dark:text-green-400",
    borderClass: "border-green-300 dark:border-green-700",
    dotClass: "bg-green-500",
  },
  A2: {
    code: "A2",
    label: "Not Certified",

    bgClass: "bg-red-100 dark:bg-red-900/30",
    textClass: "text-red-700 dark:text-red-400",
    borderClass: "border-red-300 dark:border-red-700",
    dotClass: "bg-red-500",
  },
  A3: {
    code: "A3",
    label: "Not Required",

    bgClass: "bg-gray-100 dark:bg-gray-800/30",
    textClass: "text-gray-700 dark:text-gray-400",
    borderClass: "border-gray-300 dark:border-gray-700",
    dotClass: "bg-gray-400",
  },
  A4: {
    code: "A4",
    label: "Pended",

    bgClass: "bg-amber-100 dark:bg-amber-900/30",
    textClass: "text-amber-700 dark:text-amber-400",
    borderClass: "border-amber-300 dark:border-amber-700",
    dotClass: "bg-amber-500",
  },
  A6: {
    code: "A6",
    label: "Modified",

    bgClass: "bg-blue-100 dark:bg-blue-900/30",
    textClass: "text-blue-700 dark:text-blue-400",
    borderClass: "border-blue-300 dark:border-blue-700",
    dotClass: "bg-blue-500",
  },
};

// =============================================================================
// Server DTOs (mirror PasScenarioService Java records)
// =============================================================================

export interface PasScenario {
  id: string;
  name: string;
  description: string;
  orderType: string;
  variants: PasVariant[];
}

export interface PasVariant {
  id: string;
  label: string;
  operation: "$submit" | "$inquire";
  payloadType: string;
  bundle: object;
}

export type PasMode = "scenarios" | "manual";

// =============================================================================
// Timeline Types
// =============================================================================

export type TimelineEntrySource = "user" | "auto-poll" | "subscription";

export interface PasError extends Error {
  status?: number;
  operationOutcome?: OperationOutcome;
  body?: unknown;
}

export interface ItemReviewAction {
  sequence: number;
  code: ReviewActionCode;
  authNumber?: string;
}

export interface TimelineEntry {
  id: string;
  timestamp: Date;
  source: TimelineEntrySource;
  operation: "$submit" | "$inquire";
  payloadType: string;
  requestBundle: object;
  responseData: object | null;
  error: PasError | null;
  authorizationId: string | null;
  reviewAction: ReviewActionCode | null;
  authorizationNumber: string | null;
  adminRefNumber: string | null;
  preAuthPeriod: { start?: string; end?: string } | null;
  itemReviewActions: ItemReviewAction[] | null;
  durationMs: number;
}

export interface AuthorizationGroup {
  authorizationId: string;
  entries: TimelineEntry[];
  currentReviewAction: ReviewActionCode | null;
  isPended: boolean;
}

// =============================================================================
// Auto-Poll Config
// =============================================================================

export interface AutoPollConfig {
  enabled: boolean;
  intervalSeconds: number;
}

// =============================================================================
// Suggestions
// =============================================================================

export interface SuggestedOperation {
  operation: "$submit" | "$inquire";
  payloadType?: string;
  reason: string;
}

// =============================================================================
// FHIR Response Extractors
// =============================================================================

interface FhirExtension {
  url: string;
  extension?: FhirExtension[];
  valueCodeableConcept?: {
    coding?: Array<{ system?: string; code?: string; display?: string }>;
  };
  valueString?: string;
  valuePeriod?: { start?: string; end?: string };
}

interface FhirAdjudication {
  extension?: FhirExtension[];
}

interface FhirClaimResponseItem {
  sequence?: number;
  extension?: FhirExtension[];
  adjudication?: FhirAdjudication[];
}

interface FhirClaimResponse {
  resourceType: "ClaimResponse";
  id?: string;
  extension?: FhirExtension[];
  item?: FhirClaimResponseItem[];
}

function findExtension(
  extensions: FhirExtension[] | undefined,
  url: string,
): FhirExtension | undefined {
  return extensions?.find((ext) => ext.url === url);
}

/**
 * Extracts the X12 306 review action code from a ClaimResponse.
 * Walks item adjudication extensions looking for the reviewAction extension.
 */
export function extractReviewAction(
  claimResponse: unknown,
): ReviewActionCode | null {
  const cr = claimResponse as FhirClaimResponse | undefined;
  if (!cr?.item?.length) return null;

  // Check item-level adjudication extensions first
  for (const item of cr.item) {
    for (const adj of item.adjudication ?? []) {
      const reviewAction = findExtension(adj.extension, PAS_EXT.REVIEW_ACTION);
      if (!reviewAction) continue;

      const codeExt = findExtension(
        reviewAction.extension,
        PAS_EXT.REVIEW_ACTION_CODE,
      );
      const code = codeExt?.valueCodeableConcept?.coding?.[0]?.code;
      if (code && code in REVIEW_ACTIONS) {
        return code as ReviewActionCode;
      }
    }
  }

  return null;
}

/**
 * Extracts the authorization number from a ClaimResponse's item adjudication extensions.
 */
export function extractAuthorizationNumber(
  claimResponse: unknown,
): string | null {
  const cr = claimResponse as FhirClaimResponse | undefined;
  if (!cr?.item?.length) return null;

  for (const item of cr.item) {
    for (const adj of item.adjudication ?? []) {
      const reviewAction = findExtension(adj.extension, PAS_EXT.REVIEW_ACTION);
      if (!reviewAction) continue;

      const numberExt = findExtension(reviewAction.extension, "number");
      if (numberExt?.valueString) return numberExt.valueString;
    }
  }

  return null;
}

/**
 * Extracts the administration reference number from a pended ClaimResponse item.
 * Walks item extensions for extension-administrationReferenceNumber -> valueString.
 */
export function extractAdminRefNumber(claimResponse: unknown): string | null {
  const cr = claimResponse as FhirClaimResponse | undefined;
  if (!cr?.item?.length) return null;

  for (const item of cr.item) {
    const adminRef = findExtension(item.extension, PAS_EXT.ADMIN_REF_NUMBER);
    if (adminRef?.valueString) return adminRef.valueString;
  }

  return null;
}

/**
 * Extracts the preAuth validity period from a ClaimResponse item.
 * Walks item extensions for extension-itemPreAuthPeriod -> valuePeriod.
 */
export function extractPreAuthPeriod(
  claimResponse: unknown,
): { start?: string; end?: string } | null {
  const cr = claimResponse as FhirClaimResponse | undefined;
  if (!cr?.item?.length) return null;

  for (const item of cr.item) {
    const periodExt = findExtension(
      item.extension,
      PAS_EXT.ITEM_PREAUTH_PERIOD,
    );
    if (periodExt?.valuePeriod) return periodExt.valuePeriod;
  }

  return null;
}

/**
 * Extracts review action codes for all items in a ClaimResponse.
 * Returns null if there are fewer than 2 items with review actions.
 */
export function extractAllItemReviewActions(
  claimResponse: unknown,
): ItemReviewAction[] | null {
  const cr = claimResponse as FhirClaimResponse | undefined;
  if (!cr?.item?.length) return null;

  const actions: ItemReviewAction[] = [];

  for (const item of cr.item) {
    const sequence = item.sequence ?? actions.length + 1;

    for (const adj of item.adjudication ?? []) {
      const reviewAction = findExtension(adj.extension, PAS_EXT.REVIEW_ACTION);
      if (!reviewAction) continue;

      const codeExt = findExtension(
        reviewAction.extension,
        PAS_EXT.REVIEW_ACTION_CODE,
      );
      const code = codeExt?.valueCodeableConcept?.coding?.[0]?.code;
      if (code && code in REVIEW_ACTIONS) {
        const numberExt = findExtension(reviewAction.extension, "number");
        actions.push({
          sequence,
          code: code as ReviewActionCode,
          authNumber: numberExt?.valueString ?? undefined,
        });
      }
      break;
    }
  }

  return actions.length >= 2 ? actions : null;
}

/**
 * Extracts the first ClaimResponse from a PAS response Bundle.
 */
export function extractClaimResponseFromBundle(
  bundle: unknown,
): ClaimResponse | null {
  const b = bundle as Bundle | undefined;
  if (!b?.entry) return null;

  for (const entry of b.entry) {
    if (entry.resource?.resourceType === "ClaimResponse") {
      return entry.resource as ClaimResponse;
    }
  }

  return null;
}

/**
 * Extracts response Bundles from a $inquire Parameters response.
 * Each responseBundle parameter contains a Bundle resource.
 */
export function extractResponseBundlesFromParameters(
  params: unknown,
): Bundle[] {
  const p = params as Parameters | undefined;
  if (!p?.parameter) return [];

  return p.parameter
    .filter(
      (param) =>
        param.name === "responseBundle" &&
        param.resource?.resourceType === "Bundle",
    )
    .map((param) => param.resource as Bundle);
}

/**
 * Finds the responseBundle whose ClaimResponse ID matches the target.
 * Falls back to the first responseBundle if no match is found.
 */
export function findResponseBundleByClaimResponseId(
  responseBundles: Bundle[],
  targetId: string | null,
): Bundle | null {
  if (responseBundles.length === 0) return null;
  if (!targetId) return responseBundles[0] ?? null;

  for (const bundle of responseBundles) {
    const cr = extractClaimResponseFromBundle(bundle);
    if (cr && cr.id === targetId) return bundle;
  }

  return responseBundles[0] ?? null;
}
