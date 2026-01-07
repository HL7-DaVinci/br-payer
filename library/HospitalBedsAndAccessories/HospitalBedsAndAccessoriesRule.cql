library HospitalBedsAndAccessoriesRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// ORDER EXTRACTION
// ============================================================

define "Device Requests":
  [DeviceRequest]

define "Device Request":
  First("Device Requests")

define "Device Request Code":
  "Device Request".code as CodeableConcept

define "Device Coding":
  "Device Request Code".coding

define "Order Code":
  CRD."Get First Code"("Device Coding")

// ============================================================
// COVERAGE INFORMATION
// ============================================================

define "Coverage":
  First([Coverage])

define "Coverage Reference":
  FHIR.Reference {
    reference: FHIR.string { value: 'Coverage/' + "Coverage".id.value }
  }

define "Assertion Date":
  Today()

define "Assertion ID":
  'hosp-beds-' + ToString("Assertion Date") + '-' + "Coverage".id.value

// ============================================================
// COVERAGE DETERMINATION LOGIC
// ============================================================

define "Requires Coverage Determination":
  true

define "Prior Authorization Required":
  false

define "Documentation Required":
  AgeInYears() is null or AgeInYears() <= 70

// ============================================================
// ALTERNATIVE THERAPY DETECTION
// Use a simple example:
// E0250 (fixed height bed) -> suggest E0251 (bed with side rails) for elderly patients
// ============================================================

define "Is Basic Bed Without Rails":
  "Order Code" = 'E0250'

define "Patient Is Elderly":
  AgeInYears() >= 65

define "Has Lower Cost Alternative":
  "Is Basic Bed Without Rails" and "Patient Is Elderly"

define "Alternative Code":
  if "Is Basic Bed Without Rails" then 'E0251' else null

define "Alternative Description":
  if "Is Basic Bed Without Rails" then 'Hospital bed with side rails' else null

// ============================================================
// MISSING DOCUMENTATION DETECTION
// ============================================================

define "Has Reason Code":
  "Device Request".reasonCode is not null and exists("Device Request".reasonCode)

define "Has Reason Reference":
  "Device Request".reasonReference is not null and exists("Device Request".reasonReference)

define "Has Medical Necessity":
  "Has Reason Code" or "Has Reason Reference"

define "Missing Required Documentation":
  "Documentation Required" and not "Has Medical Necessity"

// ============================================================
// ALTERNATIVE SUGGESTION CONTENT
// ============================================================

define "Alternative Summary":
  if "Has Lower Cost Alternative" then
    'Lower-cost alternative available'
  else
    null

define "Alternative Detail":
  if "Has Lower Cost Alternative" then
    'Consider ' + "Alternative Code" + ' (' + "Alternative Description" + ') - for patients over 65. ' +
    'Patient is ' + ToString(AgeInYears()) + ' years old.'
  else
    null

// ============================================================
// DOCUMENTATION WARNING CONTENT
// ============================================================

define "Documentation Warning Summary":
  'Documentation required before signing'

define "Documentation Warning Detail":
  'Hospital beds (' + CRD."Codes To String"("Device Coding") + ') for patients ' +
  ToString(AgeInYears()) + ' years old require medical necessity documentation.'

// ============================================================
// COVERAGE INFORMATION CONTENT
// ============================================================

define "Get Summary":
  case
    when "Documentation Required" then 'Documentation required'
    else 'Covered - no additional requirements'
  end

define "Get Detail":
  'Hospital beds (' + CRD."Codes To String"("Device Coding") + '): ' +
  case
    when "Documentation Required" then
      'Documentation required for patients 70 or younger. Patient is ' +
      Coalesce(ToString(AgeInYears()), 'unknown age') + ' years old.'
    else
      'No additional documentation required for patients over 70.'
  end

// ============================================================
// COVERAGE EXTENSION VALUES
// ============================================================

define "Covered":
  'covered'

define "PA Needed":
  if "Prior Authorization Required" then 'auth-needed' else null

define "Doc Needed":
  if "Documentation Required" then { 'admin' } else null

// doc-purpose: withpa | withclaim | withorder | retain-doc | OTH
define "Doc Purpose":
  null

define "Info Needed":
  null as List<String>

define "Questionnaire URLs":
  if "Documentation Required" then
    { 
      'Questionnaire/HospitalBedsAndAccessories',
      'Questionnaire/HospitalBedsAndAccessoriesFaceToFace' 
    }
  else
    null

// reason: CodeableConcept (use text-only for custom reasons)
define "Coverage Reason":
  null

// ============================================================
// FINAL EXTENSION (using shared builder)
// ============================================================

define "Coverage Info Extension":
  Ext."Build Coverage Info Extension"(
    "Coverage Reference",
    "Covered",
    "Assertion Date",
    "Assertion ID",
    "PA Needed",
    "Doc Needed",
    "Doc Purpose",
    "Info Needed",
    "Questionnaire URLs",
    "Coverage Reason",
    "Device Coding"
  )
