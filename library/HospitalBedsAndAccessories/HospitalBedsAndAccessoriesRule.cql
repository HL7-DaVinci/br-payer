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
define "Has Clinical Justification":
  "Device Request" is not null and exists("Device Request".reasonCode)

define "Documentation Required": not "Has Clinical Justification"

// ============================================================
// ALTERNATIVE THERAPY DETECTION
// ============================================================

define "Is Bed With Rails": "Order Code" = 'E0251'
define "Has Lower Cost Alternative": "Is Bed With Rails"
define "Alternative Code": if "Is Bed With Rails" then 'E0250' else null
define "Alternative Description": if "Is Bed With Rails" then 'Hospital bed without side rails' else null

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
      'Documentation required when clinical indication is not specified. ' +
      'Include diagnosis and functional limitations.'
    else
      'No additional documentation required when clinical indication is present.'
  end

define "Alternative Summary":
  if "Has Lower Cost Alternative" then 'Lower-cost alternative available' else null

define "Alternative Detail":
  if "Has Lower Cost Alternative" then
    'Consider ' + "Alternative Code" + ' (' + "Alternative Description" + ') if side rails are not medically necessary.'
  else null

define "Info Summary":
  'Document medical necessity for hospital bed'

define "Info Detail":
  'Hospital bed request (' + "Device Code Display" + '): document mobility limitations, positioning needs, home setting, and expected duration. ' +
  case
    when "Documentation Required" then
      'Include diagnosis/condition and functional limitations supporting medical necessity.'
    else
      'Include any recent assessments that support the need for a hospital bed.'
  end

// ============================================================
// COVERAGE EXTENSION VALUES
// ============================================================

define "Covered": 'covered'

define "Questionnaire URLs":
  CRD."Questionnaire URLs Value"("Documentation Required",
    { 'http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HospitalBedsAndAccessories' })

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
