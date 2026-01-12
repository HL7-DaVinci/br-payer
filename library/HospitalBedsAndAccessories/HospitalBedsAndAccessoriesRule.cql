library HospitalBedsAndAccessoriesRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// RULE-SPECIFIC CONSTANTS
// ============================================================

define "Assertion Prefix": 'hosp-beds'

// ============================================================
// CONTEXT RESOURCES
// ============================================================

define "Coverage": First([Coverage])
define "Device Request": First([DeviceRequest])

define "Device Coding":
  if "Device Request" is not null and "Device Request".code is CodeableConcept then
    ("Device Request".code as CodeableConcept).coding
  else
    {} as List<FHIR.Coding>

define "Order Code":
  if exists("Device Coding") then CRD."Get First Code"("Device Coding") else null

// ============================================================
// COVERAGE DETERMINATION LOGIC
// ============================================================

define "Prior Authorization Required": false
define "Documentation Required": AgeInYears() is null or AgeInYears() <= 70

// ============================================================
// ALTERNATIVE THERAPY DETECTION
// ============================================================

define "Is Basic Bed Without Rails": "Order Code" = 'E0250'
define "Patient Is Elderly": AgeInYears() >= 65
define "Has Lower Cost Alternative": "Is Basic Bed Without Rails" and "Patient Is Elderly"
define "Alternative Code": if "Is Basic Bed Without Rails" then 'E0251' else null
define "Alternative Description": if "Is Basic Bed Without Rails" then 'Hospital bed with side rails' else null

define "Requires Coverage Determination":
  "Coverage" is not null

// ============================================================
// CARD CONTENT
// ============================================================

define "Device Code Display":
  if exists("Device Coding") then CRD."Codes To String"("Device Coding") else 'unspecified'

define "Get Summary":
  case
    when "Documentation Required" then 'Documentation required'
    else 'Covered - no additional requirements'
  end

define "Get Detail":
  'Hospital beds (' + "Device Code Display" + '): ' +
  case
    when "Documentation Required" then
      'Documentation required for patients 70 or younger. Patient is ' +
      Coalesce(ToString(AgeInYears()), 'unknown age') + ' years old.'
    else
      'No additional documentation required for patients over 70.'
  end

define "Alternative Summary":
  if "Has Lower Cost Alternative" then 'Lower-cost alternative available' else null

define "Alternative Detail":
  if "Has Lower Cost Alternative" then
    'Consider ' + "Alternative Code" + ' (' + "Alternative Description" + ') - for patients over 65. ' +
    'Patient is ' + ToString(AgeInYears()) + ' years old.'
  else null

// ============================================================
// COVERAGE EXTENSION VALUES
// ============================================================

define "Covered": 'covered'

define "Questionnaire URLs":
  CRD."Questionnaire URLs Value"("Documentation Required",
    { 'Questionnaire/HospitalBedsAndAccessories', 'Questionnaire/HospitalBedsAndAccessoriesFaceToFace' })

// ============================================================
// FINAL COVERAGE EXTENSION
// ============================================================

define "Coverage Info Extension":
  if "Coverage" is not null and "Coverage".id is not null then
    Ext."Build Coverage Info Extension"(
      CRD."Build Coverage Reference"("Coverage"),
      "Covered",
      Today(),
      CRD."Build Assertion ID"("Assertion Prefix", "Coverage"),
      CRD."PA Needed Value"("Prior Authorization Required"),
      CRD."Doc Needed Value"("Documentation Required", 'admin'),
      null as List<String>,
      null as List<String>,
      "Questionnaire URLs",
      null as List<FHIR.CodeableConcept>,
      "Device Coding"
    )
  else null as FHIR.Extension
