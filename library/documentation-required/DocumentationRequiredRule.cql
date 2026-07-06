library DocumentationRequiredRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// RULE-SPECIFIC CONSTANTS
// ============================================================

define "Assertion Prefix": 'documentation-required'

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

// ============================================================
// COVERAGE DETERMINATION LOGIC
// ============================================================

// PA is required and supporting clinical documentation is always needed.
// This combination produces A4 (Pended) at PAS $submit time with a
// questionnaire CommunicationRequest, making it the reliable demonstration
// scenario for the CDex attachments workflow:
//   covered='covered' + pa-needed='auth-needed' + doc-needed='clinical'
define "Prior Authorization Required": true
define "Documentation Required": true

// ============================================================
// CARD CONTENT
// ============================================================

define "Device Code Display":
  if exists("Device Coding") then CRD."Codes To String"("Device Coding") else 'unspecified'

define "Get Summary":
  'Prior Authorization Required with Supporting Documentation'

define "Get Detail":
  'The requested item (' + "Device Code Display" +
  ') requires prior authorization and supporting clinical documentation. Submit a prior authorization request and complete the required questionnaire.'

define "Get Info Link":
  'https://www.cms.gov/medicare-coverage-database'

define "Questionnaire URLs":
  { 'http://example.org/fhir/Questionnaire/DocumentationRequired' }

define "Doc Purpose": { 'withpa' }

// The rule is intentionally gated on Coverage alone, never on the order
// resource type, so it pends for any order and stays a dependable A4 source.
define "Requires Coverage Determination":
  "Coverage" is not null

// ============================================================
// COVERAGE EXTENSION VALUES
// ============================================================

define "Covered": 'covered'

// ============================================================
// FINAL COVERAGE EXTENSION
// ============================================================

// Produces: covered='covered', pa-needed='auth-needed', doc-needed='clinical'
// PasCoverageEvaluator maps this to A4 (Pended) with a documentation request
define "Coverage Info Extension":
  if "Coverage" is not null and "Coverage".id is not null then
    Ext."Build Coverage Info Extension"(
      CRD."Build Coverage Reference"("Coverage"),
      "Covered",
      Today(),
      CRD."Build Assertion ID"("Assertion Prefix", "Coverage"),
      CRD."PA Needed Value"("Prior Authorization Required"),
      CRD."Doc Needed Value"("Documentation Required", 'clinical'),
      "Doc Purpose",
      null as List<String>,
      "Questionnaire URLs",
      null as List<FHIR.CodeableConcept>,
      "Device Coding"
    )
  else null as FHIR.Extension
