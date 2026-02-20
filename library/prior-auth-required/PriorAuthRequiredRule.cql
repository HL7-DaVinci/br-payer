library PriorAuthRequiredRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// RULE-SPECIFIC CONSTANTS
// ============================================================

define "Assertion Prefix": 'prior-auth'

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

// PA is required, but no additional documentation is needed.
// This combination produces A1 (Certified in total) at PAS $submit time:
//   covered='covered' + pa-needed='auth-needed' + doc-needed='no-doc'
define "Prior Authorization Required": true

// ============================================================
// CARD CONTENT
// ============================================================

define "Device Code Display":
  if exists("Device Coding") then CRD."Codes To String"("Device Coding") else 'unspecified'

define "Get Summary":
  'Prior Authorization Required'

define "Get Detail":
  'Breast prostheses (' + "Device Code Display" +
  ') require prior authorization. No additional clinical documentation is needed.'

define "Get Info Link":
  'https://www.cms.gov/medicare-coverage-database/details/lcd-details.aspx?LCDId=33021'

define "Get Questionnaire Uri":
  'Questionnaire/PriorAuthRequired'

define "Questionnaire URLs":
  { 'http://hl7.org/fhir/us/davinci-dtr/Questionnaire/PriorAuthRequired' }

define "Requires Coverage Determination":
  "Coverage" is not null

// ============================================================
// COVERAGE EXTENSION VALUES
// ============================================================

define "Covered": 'covered'

// ============================================================
// FINAL COVERAGE EXTENSION
// ============================================================

// Produces: covered='covered', pa-needed='auth-needed', doc-needed='no-doc'
// PasCoverageEvaluator maps this to A1 (Certified in total)
define "Coverage Info Extension":
  if "Coverage" is not null and "Coverage".id is not null then
    Ext."Build Coverage Info Extension"(
      CRD."Build Coverage Reference"("Coverage"),
      "Covered",
      Today(),
      CRD."Build Assertion ID"("Assertion Prefix", "Coverage"),
      CRD."PA Needed Value"("Prior Authorization Required"),
      { 'no-doc' },
      null as List<String>,
      null as List<String>,
      "Questionnaire URLs",
      null as List<FHIR.CodeableConcept>,
      "Device Coding"
    )
  else null as FHIR.Extension
