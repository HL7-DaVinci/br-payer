library CoverageExtensions version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers

// ============================================================
// EXTENSION URL CONSTANTS
// ============================================================

define "Coverage Info Extension URL":
  'http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information'

// Reason CodeSystem URL for standard reason codes
define "Reason CodeSystem URL":
  'http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp'

// ============================================================
// INNER EXTENSION BUILDERS
// ============================================================

define function "Coverage Extension"(coverageRef FHIR.Reference):
  FHIR.Extension {
    url: FHIR.uri { value: 'coverage' },
    value: coverageRef
  }

// covered: code - required binding to CRD Coverage Information Covered Value Set
// Values: covered | not-covered | conditional
define function "Covered Extension"(status String):
  FHIR.Extension {
    url: FHIR.uri { value: 'covered' },
    value: FHIR.code { value: status }
  }

// pa-needed: code - required binding to CRD Coverage Information Prior Authorization Value Set
// Values: no-auth | auth-needed | satisfied | performpa | conditional
define function "PA Needed Extension"(status String):
  FHIR.Extension {
    url: FHIR.uri { value: 'pa-needed' },
    value: FHIR.code { value: status }
  }

// doc-needed: code - required binding to CRD Coverage Information Additional Documentation Value Set
// Values: clinical | admin | patient | conditional
define function "Doc Needed Extension"(docType String):
  if docType is not null then
    FHIR.Extension {
      url: FHIR.uri { value: 'doc-needed' },
      value: FHIR.code { value: docType }
    }
  else
    null

// doc-purpose: code - required binding to CRD Coverage Information Documentation Reason Value Set
// Values: withpa | withclaim | withorder | retain-doc | OTH
define function "Doc Purpose Extension"(purpose String):
  if purpose is not null then
    FHIR.Extension {
      url: FHIR.uri { value: 'doc-purpose' },
      value: FHIR.code { value: purpose }
    }
  else
    null

// info-needed: code - required binding to CRD Information Needed Value Set
// Values: performer | location | timeframe | contract-window | detail-code | OTH
define function "Info Needed Extension"(infoType String):
  if infoType is not null then
    FHIR.Extension {
      url: FHIR.uri { value: 'info-needed' },
      value: FHIR.code { value: infoType }
    }
  else
    null

// questionnaire: canonical (to Questionnaire)
define function "Questionnaire Extension"(url String):
  FHIR.Extension {
    url: FHIR.uri { value: 'questionnaire' },
    value: FHIR.canonical { value: url }
  }

// reason: CodeableConcept - extensible binding to CRD Coverage Assertion Reasons Value Set
// Per spec: reason.text must be present if coding is not from the extensible value set
define function "Reason Extension"(reason FHIR.CodeableConcept):
  if reason is not null then
    FHIR.Extension {
      url: FHIR.uri { value: 'reason' },
      value: reason
    }
  else
    null

// Helper to create a reason CodeableConcept from text only (for custom reasons)
define function "Reason From Text"(reasonText String):
  if reasonText is not null then
    FHIR.CodeableConcept {
      text: FHIR.string { value: reasonText }
    }
  else
    null

// Helper to create a reason CodeableConcept from a code (for standard reason codes)
// Codes are from http://hl7.org/fhir/us/davinci-crd/ValueSet/coverageAssertionReasons
define function "Reason From Code"(code String, display String):
  if code is not null then
    FHIR.CodeableConcept {
      coding: {
        FHIR.Coding {
          system: FHIR.uri { value: 'http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp' },
          code: FHIR.code { value: code },
          display: FHIR.string { value: display }
        }
      }
    }
  else
    null

// ============================================================
// STANDARD COVERAGE ASSERTION REASON CODES
// ============================================================
// These are the standard codes from the coverageAssertionReasons ValueSet:
// http://hl7.org/fhir/us/davinci-crd/ValueSet/coverageAssertionReasons

// Gold card - Ordering Practitioner has been granted 'gold card' status with this payer/coverage type
define function "Gold Card Reason"():
  FHIR.CodeableConcept {
    coding: {
      FHIR.Coding {
        system: FHIR.uri { value: 'http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp' },
        code: FHIR.code { value: 'gold-card' },
        display: FHIR.string { value: 'Gold card' }
      }
    }
  }

// No member found - The CRD server was unable to find a matching member
define function "No Member Found Reason"():
  FHIR.CodeableConcept {
    coding: {
      FHIR.Coding {
        system: FHIR.uri { value: 'http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp' },
        code: FHIR.code { value: 'no-member-found' },
        display: FHIR.string { value: 'Member not found' }
      }
    }
  }

// No active coverage - The referenced insurance coverage for the member is not active
define function "No Active Coverage Reason"():
  FHIR.CodeableConcept {
    coding: {
      FHIR.Coding {
        system: FHIR.uri { value: 'http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp' },
        code: FHIR.code { value: 'no-active-coverage' },
        display: FHIR.string { value: 'Coverage not active' }
      }
    }
  }

// Auth out of network - Authorization is necessary if out-of-network
define function "Auth Out Network Reason"():
  FHIR.CodeableConcept {
    coding: {
      FHIR.Coding {
        system: FHIR.uri { value: 'http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp' },
        code: FHIR.code { value: 'auth-out-network' },
        display: FHIR.string { value: 'Authorization needed out-of-network' }
      }
    }
  }

// billingCode: Coding - example binding
define function "Billing Code Extension"(coding FHIR.Coding):
  if coding is not null then
    FHIR.Extension {
      url: FHIR.uri { value: 'billingCode' },
      value: coding
    }
  else
    null

// satisfied-pa-id: string
define function "Satisfied PA Extension"(paId String):
  if paId is not null then
    FHIR.Extension {
      url: FHIR.uri { value: 'satisfied-pa-id' },
      value: FHIR.string { value: paId }
    }
  else
    null

// date: date (required)
define function "Date Extension"(assertionDate Date):
  FHIR.Extension {
    url: FHIR.uri { value: 'date' },
    value: FHIR.date { value: assertionDate }
  }

// coverage-assertion-id: string (required)
define function "Coverage Assertion ID Extension"(assertionId String):
  FHIR.Extension {
    url: FHIR.uri { value: 'coverage-assertion-id' },
    value: FHIR.string { value: assertionId }
  }

// expiry-date: date (optional)
define function "Expiry Date Extension"(expiryDate Date):
  if expiryDate is not null then
    FHIR.Extension {
      url: FHIR.uri { value: 'expiry-date' },
      value: FHIR.date { value: expiryDate }
    }
  else
    null

// contact: ContactPoint (optional)
define function "Contact Extension"(contact FHIR.ContactPoint):
  if contact is not null then
    FHIR.Extension {
      url: FHIR.uri { value: 'contact' },
      value: contact
    }
  else
    null

// ============================================================
// LIST BUILDERS
// ============================================================

define function "Build Doc Needed Extensions"(docTypes List<String>):
  if docTypes is not null then
    docTypes D return "Doc Needed Extension"(D)
  else
    {} as List<FHIR.Extension>

define function "Build Doc Purpose Extensions"(purposes List<String>):
  if purposes is not null then
    purposes P return "Doc Purpose Extension"(P)
  else
    {} as List<FHIR.Extension>

define function "Build Info Needed Extensions"(infoTypes List<String>):
  if infoTypes is not null then
    infoTypes I return "Info Needed Extension"(I)
  else
    {} as List<FHIR.Extension>

define function "Build Questionnaire Extensions"(urls List<String>):
  if urls is not null then
    urls U return "Questionnaire Extension"(U)
  else
    {} as List<FHIR.Extension>

define function "Build Billing Code Extensions"(codings List<FHIR.Coding>):
  if codings is not null then
    codings C return "Billing Code Extension"(C)
  else
    {} as List<FHIR.Extension>

// Build reason extensions from CodeableConcepts (spec-compliant)
define function "Build Reason Extensions"(reasons List<FHIR.CodeableConcept>):
  if reasons is not null then
    reasons R return "Reason Extension"(R)
  else
    {} as List<FHIR.Extension>

// Convenience: Build reason extensions from text strings (creates CodeableConcept with text only)
define function "Build Reason Extensions From Text"(reasonTexts List<String>):
  if reasonTexts is not null then
    reasonTexts R return "Reason Extension"("Reason From Text"(R))
  else
    {} as List<FHIR.Extension>

// ============================================================
// FULL EXTENSION BUILDER
// ============================================================

define function "Build Coverage Info Extension"(
  coverageRef FHIR.Reference,
  covered String,
  assertionDate Date,
  assertionId String,
  paNeeded String,
  docNeeded List<String>,
  docPurpose List<String>,
  infoNeeded List<String>,
  questionnaireUrls List<String>,
  reasons List<FHIR.CodeableConcept>,
  billingCodes List<FHIR.Coding>
):
  FHIR.Extension {
    url: FHIR.uri { value: "Coverage Info Extension URL" },
    extension: Flatten({
      { "Coverage Extension"(coverageRef) },
      { "Covered Extension"(covered) },
      if paNeeded is not null then { "PA Needed Extension"(paNeeded) } else {},
      "Build Doc Needed Extensions"(docNeeded),
      "Build Doc Purpose Extensions"(docPurpose),
      "Build Info Needed Extensions"(infoNeeded),
      "Build Questionnaire Extensions"(questionnaireUrls),
      "Build Reason Extensions"(reasons),
      "Build Billing Code Extensions"(billingCodes),
      { "Date Extension"(assertionDate) },
      { "Coverage Assertion ID Extension"(assertionId) }
    })
  }

// ============================================================
// FULL EXTENSION BUILDER WITH SATISFIED PA
// ============================================================

define function "Build Coverage Info Extension With PA"(
  coverageRef FHIR.Reference,
  covered String,
  assertionDate Date,
  assertionId String,
  paNeeded String,
  satisfiedPaId String,
  docNeeded List<String>,
  docPurpose List<String>,
  infoNeeded List<String>,
  questionnaireUrls List<String>,
  reasons List<FHIR.CodeableConcept>,
  billingCodes List<FHIR.Coding>
):
  FHIR.Extension {
    url: FHIR.uri { value: "Coverage Info Extension URL" },
    extension: Flatten({
      { "Coverage Extension"(coverageRef) },
      { "Covered Extension"(covered) },
      if paNeeded is not null then { "PA Needed Extension"(paNeeded) } else {},
      if satisfiedPaId is not null then { "Satisfied PA Extension"(satisfiedPaId) } else {},
      "Build Doc Needed Extensions"(docNeeded),
      "Build Doc Purpose Extensions"(docPurpose),
      "Build Info Needed Extensions"(infoNeeded),
      "Build Questionnaire Extensions"(questionnaireUrls),
      "Build Reason Extensions"(reasons),
      "Build Billing Code Extensions"(billingCodes),
      { "Date Extension"(assertionDate) },
      { "Coverage Assertion ID Extension"(assertionId) }
    })
  }
