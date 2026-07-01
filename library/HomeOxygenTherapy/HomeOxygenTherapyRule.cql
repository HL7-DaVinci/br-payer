library HomeOxygenTherapyRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// CONTEXT RESOURCES
// ============================================================

define "Assertion Prefix": 'home-oxygen-therapy'

define "Coverage": First([Coverage])
define "Device Request": First([DeviceRequest])

define "Device Coding":
  if "Device Request" is not null and "Device Request".code is CodeableConcept then
    ("Device Request".code as CodeableConcept).coding
  else
    {} as List<FHIR.Coding>

// ============================================================
// APPLICABILITY
// The PlanDefinition already matches the order code (E0424) via useContext,
// so the rule fires whenever the order is present.
// ============================================================

define "Rule Applies":
  exists("Device Coding")

// ============================================================
// COVERAGE DETERMINATION LOGIC
// Home oxygen therapy is covered but requires prior authorization and
// supporting clinical documentation.
// ============================================================

define "Prior Authorization Required": true
define "Documentation Required": true

// ============================================================
// CARD CONTENT
// ============================================================

define "Device Code Display":
  if exists("Device Coding") then CRD."Codes To String"("Device Coding") else 'unspecified'

define "Get Summary":
  'Prior Authorization Required for Home Oxygen Therapy'

define "Get Detail":
  'Home oxygen therapy (' + "Device Code Display" +
  ') requires prior authorization. Submit a prior authorization request and complete the required documentation.'

define "Get Info Link":
  'https://www.cms.gov/medicare-coverage-database/details/ncd-details.aspx?NCDId=169'

// ============================================================
// COVERAGE EXTENSION VALUES
// ============================================================

define "Covered": 'covered'

define "Doc Purpose":
  if "Documentation Required" then
    (if "Prior Authorization Required" then { 'withpa' } else { 'withorder' })
  else null as List<String>

define "Questionnaire URLs":
  CRD."Questionnaire URLs Value"("Documentation Required",
    { 'http://example.org/fhir/Questionnaire/home-o2-std-questionnaire' })

// ============================================================
// FINAL COVERAGE EXTENSION
// Produces covered='covered', pa-needed='auth-needed', doc-needed='clinical',
// doc-purpose='withpa'. CRD order-sign surfaces "Prior Authorization Required";
// the provider then submits a PAS request which pends (A4) and launches the
// home oxygen DTR questionnaire.
// ============================================================

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
