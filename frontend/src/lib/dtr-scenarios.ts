import type { Coverage, FhirResource, Parameters } from "fhir/r4";
import type { DtrScenario } from "./dtr-types";

const SHARED_COVERAGE: Coverage = {
  resourceType: "Coverage",
  id: "coverage-1",
  meta: {
    profile: [
      "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/profile-coverage",
    ],
  },
  contained: [
    {
      resourceType: "Organization",
      id: "payor-org",
      identifier: [
        { system: "urn:oid:2.16.840.1.113883.6.300", value: "00001" },
      ],
      active: true,
      type: [
        {
          coding: [
            {
              system: "http://terminology.hl7.org/CodeSystem/organization-type",
              code: "pay",
              display: "Payer",
            },
          ],
        },
      ],
      name: "Centers for Medicare and Medicaid Services",
    },
  ],
  status: "active",
  subscriberId: "10A3D58WH456",
  beneficiary: { reference: "Patient/example" },
  relationship: {
    coding: [
      {
        system: "http://terminology.hl7.org/CodeSystem/subscriber-relationship",
        code: "self",
        display: "Self",
      },
    ],
  },
  period: { start: "2025-01-01", end: "2026-12-31" },
  payor: [{ reference: "#payor-org" }],
};

const DTR_PROFILE =
  "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-qpackage-input-parameters";

function canonicalParams(canonical: string): Parameters {
  return {
    resourceType: "Parameters",
    meta: { profile: [DTR_PROFILE] },
    parameter: [
      { name: "coverage", resource: SHARED_COVERAGE },
      { name: "questionnaire", valueCanonical: canonical },
    ],
  };
}

function orderParams(orderResource: Record<string, unknown>): Parameters {
  return {
    resourceType: "Parameters",
    meta: { profile: [DTR_PROFILE] },
    parameter: [
      { name: "coverage", resource: SHARED_COVERAGE },
      { name: "order", resource: orderResource as unknown as FhirResource },
    ],
  };
}

function combinedParams(
  canonical: string,
  orderResource: Record<string, unknown>,
): Parameters {
  return {
    resourceType: "Parameters",
    meta: { profile: [DTR_PROFILE] },
    parameter: [
      { name: "coverage", resource: SHARED_COVERAGE },
      { name: "order", resource: orderResource as unknown as FhirResource },
      { name: "questionnaire", valueCanonical: canonical },
    ],
  };
}

export const DTR_SCENARIOS: DtrScenario[] = [
  {
    id: "home-oxygen",
    name: "Home Oxygen Dispatch",
    description:
      "Stationary compressed gaseous oxygen system (E0424). Tests DeviceRequest-based questionnaire resolution with CQL prepopulation.",
    orderType: "DeviceRequest",
    isAdaptive: false,
    variants: [
      {
        id: "home-oxygen-canonical",
        label: "Canonical",
        pathType: "canonical",
        parameters: canonicalParams(
          "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HomeOxygenDispatch",
        ),
      },
      {
        id: "home-oxygen-order",
        label: "Order",
        pathType: "order",
        parameters: orderParams({
          resourceType: "DeviceRequest",
          id: "device-request-home-oxygen",
          meta: {
            profile: [
              "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/profile-devicerequest",
            ],
          },
          status: "draft",
          intent: "original-order",
          codeCodeableConcept: {
            coding: [
              {
                system:
                  "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
                code: "E0424",
                display: "Stationary compressed gaseous oxygen system, rental",
              },
            ],
          },
          subject: { reference: "Patient/example" },
          authoredOn: "2026-01-15",
          requester: { reference: "Practitioner/example" },
          insurance: [{ reference: "Coverage/coverage-1" }],
        }),
      },
    ],
  },
  {
    id: "hospital-beds",
    name: "Hospital Beds & Accessories",
    description:
      "Hospital bed with side rails and mattress (E0250). Tests standard questionnaire packaging with canonical, order, and combined paths.",
    orderType: "DeviceRequest",
    isAdaptive: false,
    variants: [
      {
        id: "hospital-beds-canonical",
        label: "Canonical",
        pathType: "canonical",
        parameters: canonicalParams(
          "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HospitalBedsAndAccessories",
        ),
      },
      {
        id: "hospital-beds-order",
        label: "Order",
        pathType: "order",
        parameters: orderParams({
          resourceType: "DeviceRequest",
          id: "device-request-hospital-bed",
          meta: {
            profile: [
              "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/profile-devicerequest",
            ],
          },
          status: "draft",
          intent: "original-order",
          codeCodeableConcept: {
            coding: [
              {
                system:
                  "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
                code: "E0250",
                display:
                  "Hospital bed fixed height with any type of side rails, mattress",
              },
            ],
          },
          subject: { reference: "Patient/example" },
          authoredOn: "2026-01-15",
          requester: { reference: "Practitioner/example" },
          insurance: [{ reference: "Coverage/coverage-1" }],
        }),
      },
      {
        id: "hospital-beds-combined",
        label: "Combined",
        pathType: "combined",
        parameters: combinedParams(
          "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HospitalBedsAndAccessories",
          {
            resourceType: "DeviceRequest",
            id: "device-request-hospital-bed",
            meta: {
              profile: [
                "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/profile-devicerequest",
              ],
            },
            status: "active",
            intent: "original-order",
            codeCodeableConcept: {
              coding: [
                {
                  system:
                    "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
                  code: "E0250",
                  display:
                    "Hospital bed fixed height with any type of side rails, mattress",
                },
              ],
            },
            subject: { reference: "Patient/example" },
            authoredOn: "2026-01-15",
            requester: { reference: "Practitioner/example" },
            insurance: [{ reference: "Coverage/coverage-1" }],
          },
        ),
      },
    ],
  },
  {
    id: "immunosuppressive",
    name: "Immunosuppressive Drugs",
    description:
      "Tacrolimus 1 MG oral capsule (RxNorm 105585). Tests MedicationRequest-based questionnaire resolution.",
    orderType: "MedicationRequest",
    isAdaptive: false,
    variants: [
      {
        id: "immunosuppressive-canonical",
        label: "Canonical",
        pathType: "canonical",
        parameters: canonicalParams(
          "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/ImmunosuppressiveDrugs",
        ),
      },
      {
        id: "immunosuppressive-order",
        label: "Order",
        pathType: "order",
        parameters: orderParams({
          resourceType: "MedicationRequest",
          id: "med-request-tacrolimus",
          meta: {
            profile: [
              "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/profile-medicationrequest",
            ],
          },
          status: "active",
          intent: "order",
          medicationCodeableConcept: {
            coding: [
              {
                system: "http://www.nlm.nih.gov/research/umls/rxnorm",
                code: "105585",
                display: "Tacrolimus 1 MG Oral Capsule",
              },
            ],
          },
          subject: { reference: "Patient/example" },
          authoredOn: "2026-01-15",
          requester: { reference: "Practitioner/example" },
          dosageInstruction: [
            {
              text: "Take 1 capsule twice daily",
              timing: { repeat: { frequency: 2, period: 1, periodUnit: "d" } },
              route: {
                coding: [
                  {
                    system: "http://snomed.info/sct",
                    code: "26643006",
                    display: "Oral route",
                  },
                ],
              },
              doseAndRate: [
                {
                  doseQuantity: {
                    value: 1,
                    unit: "mg",
                    system: "http://unitsofmeasure.org",
                    code: "mg",
                  },
                },
              ],
            },
          ],
          dispenseRequest: {
            numberOfRepeatsAllowed: 3,
            quantity: {
              value: 60,
              unit: "capsule",
              system: "http://unitsofmeasure.org",
              code: "{capsule}",
            },
          },
          insurance: [{ reference: "Coverage/coverage-1" }],
        }),
      },
    ],
  },
  {
    id: "immunosuppressive-progress",
    name: "Immunosuppressive Progress Note",
    description:
      "Progress note questionnaire for immunosuppressive drug therapy monitoring.",
    orderType: "MedicationRequest",
    isAdaptive: false,
    variants: [
      {
        id: "immunosuppressive-progress-canonical",
        label: "Canonical",
        pathType: "canonical",
        parameters: canonicalParams(
          "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/ImmunosuppressiveDrugsProgressNote",
        ),
      },
    ],
  },
  {
    id: "physical-therapy",
    name: "Physical Therapy Extension",
    description:
      "Therapeutic exercises (CPT 97110). Tests appointment-based questionnaire resolution.",
    orderType: "Appointment",
    isAdaptive: false,
    variants: [
      {
        id: "physical-therapy-canonical",
        label: "Canonical",
        pathType: "canonical",
        parameters: canonicalParams(
          "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/PhysicalTherapyExtension",
        ),
      },
      {
        id: "physical-therapy-order",
        label: "Order",
        pathType: "order",
        parameters: orderParams({
          resourceType: "Appointment",
          id: "appointment-pt-session",
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
          start: "2026-02-20T09:00:00Z",
          end: "2026-02-20T09:45:00Z",
          participant: [
            {
              actor: { reference: "Patient/example" },
              status: "accepted",
            },
            {
              actor: { reference: "Practitioner/example" },
              status: "accepted",
            },
          ],
        }),
      },
    ],
  },
  {
    id: "cardiology",
    name: "Cardiology Consultation",
    description:
      "Cardiology consultation for chronic ischemic heart disease. Tests SNOMED-coded appointment resolution.",
    orderType: "Appointment",
    isAdaptive: false,
    variants: [
      {
        id: "cardiology-canonical",
        label: "Canonical",
        pathType: "canonical",
        parameters: canonicalParams(
          "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/CardiologyConsultation",
        ),
      },
      {
        id: "cardiology-order",
        label: "Order",
        pathType: "order",
        parameters: orderParams({
          resourceType: "Appointment",
          id: "appointment-cardiology-consult",
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
          reasonCode: [
            {
              coding: [
                {
                  system: "http://snomed.info/sct",
                  code: "413844008",
                  display: "Chronic ischemic heart disease",
                },
              ],
            },
          ],
          start: "2026-03-01T10:00:00Z",
          end: "2026-03-01T10:30:00Z",
          participant: [
            {
              actor: { reference: "Patient/example" },
              status: "accepted",
            },
            {
              actor: { reference: "Practitioner/example" },
              status: "accepted",
            },
          ],
        }),
      },
    ],
  },
  {
    id: "opioid-justification",
    name: "Opioid Prescribing Justification",
    description:
      "Adaptive questionnaire for opioid prescribing justification. Drives interactive $next-question loop with conditional question groups.",
    orderType: "MedicationRequest",
    isAdaptive: true,
    variants: [
      {
        id: "opioid-justification-canonical",
        label: "Canonical",
        pathType: "canonical",
        parameters: canonicalParams(
          "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/OpioidPrescribingJustification",
        ),
      },
      {
        id: "opioid-justification-order",
        label: "Order",
        pathType: "order",
        parameters: orderParams({
          resourceType: "MedicationRequest",
          id: "med-request-oxycodone",
          meta: {
            profile: [
              "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/profile-medicationrequest",
            ],
          },
          status: "active",
          intent: "order",
          medicationCodeableConcept: {
            coding: [
              {
                system: "http://www.nlm.nih.gov/research/umls/rxnorm",
                code: "197696",
                display: "Oxycodone Hydrochloride 5 MG Oral Tablet",
              },
            ],
          },
          subject: { reference: "Patient/example" },
          authoredOn: "2026-01-20",
          requester: { reference: "Practitioner/example" },
          dosageInstruction: [
            {
              text: "Take 1 tablet every 4-6 hours as needed for pain",
              timing: { repeat: { frequency: 4, period: 1, periodUnit: "d" } },
              route: {
                coding: [
                  {
                    system: "http://snomed.info/sct",
                    code: "26643006",
                    display: "Oral route",
                  },
                ],
              },
              doseAndRate: [
                {
                  doseQuantity: {
                    value: 5,
                    unit: "mg",
                    system: "http://unitsofmeasure.org",
                    code: "mg",
                  },
                },
              ],
            },
          ],
          dispenseRequest: {
            expectedSupplyDuration: {
              value: 7,
              unit: "days",
              system: "http://unitsofmeasure.org",
              code: "d",
            },
            quantity: {
              value: 28,
              unit: "tablet",
              system: "http://unitsofmeasure.org",
              code: "{tablet}",
            },
          },
          insurance: [{ reference: "Coverage/coverage-1" }],
        }),
      },
    ],
  },
  {
    id: "opioid-pdmp",
    name: "Opioid PDMP",
    description:
      "Prescription Drug Monitoring Program questionnaire for opioid prescriptions.",
    orderType: "MedicationRequest",
    isAdaptive: false,
    variants: [
      {
        id: "opioid-pdmp-canonical",
        label: "Canonical",
        pathType: "canonical",
        parameters: canonicalParams(
          "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/OpioidPDMP",
        ),
      },
    ],
  },
];
