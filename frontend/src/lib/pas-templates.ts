/**
 * Starter templates for PAS manual mode.
 * Each template is a minimal, valid PAS request bundle that users can customize.
 */

export interface PasTemplate {
  id: string;
  label: string;
  description: string;
  operation: "$submit" | "$inquire";
  create: () => object;
}

const BUNDLE_BASE = "http://example.org/fhir";

// Shared between initial-professional and cancel templates so cancel can
// reference the stored authorization created by the initial submission.
const INITIAL_PROFESSIONAL_TRACE = "pas-template-initial-trace";

function uuid(): string {
  return crypto.randomUUID();
}

function timestamp(): string {
  return new Date().toISOString();
}

const MEMBER_ID_TYPE = {
  coding: [
    {
      system: "http://terminology.hl7.org/CodeSystem/v2-0203",
      code: "MB",
      display: "Member Number",
    },
  ],
};

const SHARED_RESOURCES = {
  patient: () => ({
    fullUrl: `${BUNDLE_BASE}/Patient/patient-1`,
    resource: {
      resourceType: "Patient",
      id: "patient-1",
      meta: {
        profile: [
          "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-subscriber",
        ],
      },
      identifier: [
        {
          type: MEMBER_ID_TYPE,
          system: "http://example.org/MIN",
          value: "12345678901",
        },
      ],
      name: [{ family: "SMITH", given: ["JOE"] }],
      gender: "male",
    },
  }),
  insurer: () => ({
    fullUrl: `${BUNDLE_BASE}/Organization/insurer-1`,
    resource: {
      resourceType: "Organization",
      id: "insurer-1",
      meta: {
        profile: [
          "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-insurer",
        ],
      },
      identifier: [
        { system: "http://hl7.org/fhir/sid/us-npi", value: "1234567893" },
      ],
      active: true,
      type: [
        {
          coding: [
            { system: "https://codesystem.x12.org/005010/98", code: "PR" },
          ],
        },
      ],
      name: "EXAMPLE INSURANCE COMPANY",
    },
  }),
  provider: () => ({
    fullUrl: `${BUNDLE_BASE}/Organization/provider-1`,
    resource: {
      resourceType: "Organization",
      id: "provider-1",
      meta: {
        profile: [
          "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-requestor",
        ],
      },
      identifier: [
        { system: "http://hl7.org/fhir/sid/us-npi", value: "8189991234" },
      ],
      active: true,
      type: [
        {
          coding: [
            { system: "https://codesystem.x12.org/005010/98", code: "X3" },
          ],
        },
      ],
      name: "DR. SMITH MEDICAL GROUP",
    },
  }),
  coverage: () => ({
    fullUrl: `${BUNDLE_BASE}/Coverage/coverage-1`,
    resource: {
      resourceType: "Coverage",
      id: "coverage-1",
      meta: {
        profile: [
          "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-coverage",
        ],
      },
      status: "active",
      subscriberId: "1122334455",
      identifier: [
        {
          type: MEMBER_ID_TYPE,
          system: "http://example.org/MIN",
          value: "12345678901",
        },
      ],
      subscriber: { reference: "Patient/patient-1" },
      beneficiary: { reference: "Patient/patient-1" },
      relationship: {
        coding: [
          {
            system:
              "http://terminology.hl7.org/CodeSystem/subscriber-relationship",
            code: "self",
          },
        ],
      },
      payor: [{ reference: "Organization/insurer-1" }],
    },
  }),
};

function makeSubmitBundle(
  certType: { code: string; display: string },
  productOrService: { code: string; display: string; system: string },
  claimType: string,
  extras?: {
    claimProfile?: string;
    claimExtensions?: object[];
    related?: object[];
    itemExtras?: object[];
    priorClaim?: object;
    traceNumber?: string;
  },
): object {
  const traceNumber = extras?.traceNumber ?? uuid();
  return {
    resourceType: "Bundle",
    meta: {
      profile: [
        "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-request-bundle",
      ],
    },
    identifier: {
      system: "http://example.org/SUBMITTER_TRANSACTION_IDENTIFIER",
      value: traceNumber,
    },
    type: "collection",
    timestamp: timestamp(),
    entry: [
      {
        fullUrl: `${BUNDLE_BASE}/Claim/claim-1`,
        resource: {
          resourceType: "Claim",
          id: "claim-1",
          meta: {
            profile: [
              extras?.claimProfile ??
                "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim",
            ],
          },
          ...(extras?.claimExtensions
            ? { extension: extras.claimExtensions }
            : {}),
          identifier: [
            {
              system: "http://example.org/PATIENT_EVENT_TRACE_NUMBER",
              value: traceNumber,
            },
          ],
          status: "active",
          type: {
            coding: [
              {
                system: "http://terminology.hl7.org/CodeSystem/claim-type",
                code: claimType,
              },
            ],
          },
          use: "preauthorization",
          patient: { reference: "Patient/patient-1" },
          created: timestamp(),
          insurer: { reference: "Organization/insurer-1" },
          provider: { reference: "Organization/provider-1" },
          priority: {
            coding: [
              {
                system: "http://terminology.hl7.org/CodeSystem/processpriority",
                code: "normal",
              },
            ],
          },
          ...(extras?.related ? { related: extras.related } : {}),
          insurance: [
            {
              sequence: 1,
              focal: true,
              coverage: { reference: "Coverage/coverage-1" },
            },
          ],
          item: [
            {
              extension: [
                {
                  url: "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-serviceItemRequestType",
                  valueCodeableConcept: {
                    coding: [
                      {
                        system: "https://codesystem.x12.org/005010/1525",
                        code: "HS",
                        display: "Health Services Review",
                      },
                    ],
                  },
                },
                {
                  url: "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-certificationType",
                  valueCodeableConcept: {
                    coding: [
                      {
                        system: "https://codesystem.x12.org/005010/1322",
                        ...certType,
                      },
                    ],
                  },
                },
                ...(extras?.itemExtras ?? []),
              ],
              sequence: 1,
              productOrService: {
                coding: [productOrService],
              },
            },
          ],
        },
      },
      SHARED_RESOURCES.patient(),
      SHARED_RESOURCES.insurer(),
      SHARED_RESOURCES.provider(),
      SHARED_RESOURCES.coverage(),
      ...(extras?.priorClaim ? [extras.priorClaim] : []),
    ],
  };
}

export const PAS_TEMPLATES: PasTemplate[] = [
  {
    id: "initial-professional",
    label: "Initial Submit (Professional)",
    description: "Professional prior auth request with initial certification",
    operation: "$submit",
    create: () =>
      makeSubmitBundle(
        { code: "I", display: "Initial" },
        {
          system: "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
          code: "E0260",
          display: "Hospital bed, semi-electric",
        },
        "professional",
        { traceNumber: INITIAL_PROFESSIONAL_TRACE },
      ),
  },
  {
    id: "initial-institutional",
    label: "Initial Submit (Institutional)",
    description: "Institutional prior auth request for inpatient procedure",
    operation: "$submit",
    create: () =>
      makeSubmitBundle(
        { code: "I", display: "Initial" },
        {
          system: "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
          code: "33510",
          display: "Coronary artery bypass, vein only; single graft",
        },
        "institutional",
      ),
  },
  {
    id: "renewal",
    label: "Renewal Submit",
    description: "Renewal request referencing a prior authorization",
    operation: "$submit",
    create: () =>
      makeSubmitBundle(
        { code: "R", display: "Renewal" },
        {
          system: "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
          code: "E0260",
          display: "Hospital bed, semi-electric",
        },
        "professional",
      ),
  },
  {
    id: "cancel",
    label: "Cancel Submit",
    description:
      "Cancel request with certification type 3 and related prior claim",
    operation: "$submit",
    create: () =>
      makeSubmitBundle(
        { code: "I", display: "Initial" },
        {
          system: "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
          code: "E0260",
          display: "Hospital bed, semi-electric",
        },
        "professional",
        {
          claimProfile:
            "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim-update",
          claimExtensions: [
            {
              url: "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-certificationType",
              valueCodeableConcept: {
                coding: [
                  {
                    system: "https://codesystem.x12.org/005010/1322",
                    code: "3",
                    display: "Cancel",
                  },
                ],
              },
            },
          ],
          related: [
            {
              claim: { reference: "Claim/prior-claim-1" },
              relationship: {
                coding: [
                  {
                    system:
                      "http://terminology.hl7.org/CodeSystem/ex-relatedclaimrelationship",
                    code: "prior",
                  },
                ],
              },
            },
          ],
          priorClaim: {
            fullUrl: `${BUNDLE_BASE}/Claim/prior-claim-1`,
            resource: {
              resourceType: "Claim",
              id: "prior-claim-1",
              status: "active",
              use: "preauthorization",
              identifier: [
                {
                  system: "http://example.org/PATIENT_EVENT_TRACE_NUMBER",
                  value: INITIAL_PROFESSIONAL_TRACE,
                },
              ],
              type: {
                coding: [
                  {
                    system: "http://terminology.hl7.org/CodeSystem/claim-type",
                    code: "professional",
                  },
                ],
              },
              patient: { reference: "Patient/patient-1" },
              created: timestamp(),
              insurer: { reference: "Organization/insurer-1" },
              provider: { reference: "Organization/provider-1" },
              priority: {
                coding: [
                  {
                    system:
                      "http://terminology.hl7.org/CodeSystem/processpriority",
                    code: "normal",
                  },
                ],
              },
              insurance: [
                {
                  sequence: 1,
                  focal: true,
                  coverage: { reference: "Coverage/coverage-1" },
                },
              ],
            },
          },
          itemExtras: [
            {
              url: "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/modifierextension-infoCancelled",
              valueBoolean: true,
            },
          ],
        },
      ),
  },
  {
    id: "inquiry",
    label: "Inquiry",
    description: "Check status of a prior authorization",
    operation: "$inquire",
    create: () => ({
      resourceType: "Bundle",
      meta: {
        profile: [
          "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-inquiry-request-bundle",
        ],
      },
      identifier: {
        system: "http://example.org/SUBMITTER_TRANSACTION_IDENTIFIER",
        value: uuid(),
      },
      type: "collection",
      timestamp: timestamp(),
      entry: [
        {
          fullUrl: `${BUNDLE_BASE}/Claim/inquiry-1`,
          resource: {
            resourceType: "Claim",
            id: "inquiry-1",
            meta: {
              profile: [
                "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim-inquiry",
              ],
            },
            identifier: [
              {
                system: "http://example.org/PATIENT_EVENT_TRACE_NUMBER",
                value: "REPLACE-WITH-TRACE-NUMBER",
              },
            ],
            status: "active",
            type: {
              coding: [
                {
                  system: "http://terminology.hl7.org/CodeSystem/claim-type",
                  code: "professional",
                },
              ],
            },
            use: "preauthorization",
            patient: { reference: "Patient/patient-1" },
            created: timestamp(),
            insurer: { reference: "Organization/insurer-1" },
            provider: { reference: "Organization/provider-1" },
            priority: {
              coding: [
                {
                  system:
                    "http://terminology.hl7.org/CodeSystem/processpriority",
                  code: "normal",
                },
              ],
            },
            insurance: [
              {
                sequence: 1,
                focal: true,
                coverage: { reference: "Coverage/coverage-1" },
              },
            ],
            item: [
              {
                extension: [
                  {
                    url: "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-serviceItemRequestType",
                    valueCodeableConcept: {
                      coding: [
                        {
                          system: "https://codesystem.x12.org/005010/1525",
                          code: "HS",
                          display: "Health Services Review",
                        },
                      ],
                    },
                  },
                  {
                    url: "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-certificationType",
                    valueCodeableConcept: {
                      coding: [
                        {
                          system: "https://codesystem.x12.org/005010/1322",
                          code: "I",
                          display: "Initial",
                        },
                      ],
                    },
                  },
                ],
                sequence: 1,
                productOrService: {
                  coding: [
                    {
                      system:
                        "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
                      code: "E0260",
                      display: "Hospital bed, semi-electric",
                    },
                  ],
                },
              },
            ],
          },
        },
        SHARED_RESOURCES.patient(),
        SHARED_RESOURCES.insurer(),
        SHARED_RESOURCES.provider(),
        SHARED_RESOURCES.coverage(),
      ],
    }),
  },
];
