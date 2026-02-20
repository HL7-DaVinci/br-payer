library ExcludedServicesRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// RULE-SPECIFIC CONSTANTS
// ============================================================

define "Assertion Prefix": 'excluded'

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

// Service is explicitly not covered by the plan.
// This produces A2 (Not Certified) at PAS $submit time.
// When covered='not-covered', pa-needed and doc-needed do not affect the outcome.

// ============================================================
// CARD CONTENT
// ============================================================

define "Device Code Display":
  if exists("Device Coding") then CRD."Codes To String"("Device Coding") else 'unspecified'

define "Get Summary":
  'Service Not Covered'

define "Get Detail":
  'Experimental gene therapy procedures (' + "Device Code Display" +
  ') are excluded from coverage under this plan as investigational.'

define "Get Info Link":
  'https://www.cms.gov/medicare-coverage-database/overview-and-quick-search.aspx'

define "Get Questionnaire Uri":
  'Questionnaire/ExcludedServicesNotice'

define "Requires Coverage Determination":
  "Coverage" is not null

// ============================================================
// COVERAGE EXTENSION VALUES
// ============================================================

define "Covered": 'not-covered'

// ============================================================
// FINAL COVERAGE EXTENSION
// ============================================================

// Produces: covered='not-covered'
// PasCoverageEvaluator maps this to A2 (Not Certified)
// Per crd-ci-q2: when covered='not-covered', pa-needed must not exist
define "Coverage Info Extension":
  if "Coverage" is not null and "Coverage".id is not null then
    Ext."Build Coverage Info Extension"(
      CRD."Build Coverage Reference"("Coverage"),
      "Covered",
      Today(),
      CRD."Build Assertion ID"("Assertion Prefix", "Coverage"),
      null,
      null as List<String>,
      null as List<String>,
      null as List<String>,
      null as List<String>,
      null as List<FHIR.CodeableConcept>,
      "Device Coding"
    )
  else null as FHIR.Extension
