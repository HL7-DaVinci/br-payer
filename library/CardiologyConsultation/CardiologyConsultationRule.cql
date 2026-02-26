library CardiologyConsultationRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// RULE-SPECIFIC CONSTANTS
// ============================================================

define "Assertion Prefix": 'cardio-consult'

// ============================================================
// CONTEXT RESOURCES
// ============================================================

define "Coverage": First([Coverage])
define "Appointment": First([Appointment])

define "Appointment Coding":
  if "Appointment" is not null and "Appointment".serviceType is not null then
    Flatten("Appointment".serviceType ST return ST.coding)
  else
    {} as List<FHIR.Coding>

// ============================================================
// REFERRAL DETECTION
// ============================================================

define "Has Existing Referral":
  exists([ServiceRequest] SR
    where SR.status.value in {'active', 'completed'}
      and SR.intent.value = 'order'
      and exists(SR.category cat where exists(cat.coding c where c.code.value = 'referral')))

define "Referral Missing": not "Has Existing Referral"

// ============================================================
// COVERAGE DETERMINATION LOGIC
// ============================================================

define "Requires Coverage Determination": "Coverage" is not null
define "Prior Authorization Required": false
define "Documentation Required": "Referral Missing"
define "Covered": 'covered'

// ============================================================
// CARD CONTENT
// ============================================================

define "Service Code Display":
  if exists("Appointment Coding") then CRD."Codes To String"("Appointment Coding") else 'unspecified'

define "Get Summary":
  case
    when "Referral Missing" then 'Referral recommended for coverage review'
    else 'Covered - no additional requirements'
  end

define "Get Detail":
  'Cardiology consultation (' + "Service Code Display" + '): ' +
  case
    when "Referral Missing" then
      'A referral may support coverage review. Patient age: ' +
      Coalesce(ToString(AgeInYears()), 'unknown') + ' years.'
    else
      'No additional documentation required.'
  end

define "Info Summary":
  case
    when "Referral Missing" then 'Add or verify cardiology referral details'
    else 'Confirm consultation reason and supporting history'
  end

define "Info Detail":
  'Cardiology consultation (' + "Service Code Display" + '): ' +
  case
    when "Referral Missing" then
      'Ensure the referring provider, reason for referral, and relevant cardiac history are documented.'
    else
      'Confirm the appointment reason and attach relevant history or recent results to support the consult.'
  end

// ============================================================
// COVERAGE EXTENSION VALUES
// ============================================================

define "Doc Purpose":
  if "Documentation Required" then { 'withorder' } else null as List<String>

define "Questionnaire URLs":
  CRD."Questionnaire URLs Value"("Documentation Required",
    { 'http://example.org/fhir/Questionnaire/CardiologyConsultation' })

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
      CRD."Doc Needed Value"("Documentation Required", 'clinical'),
      "Doc Purpose",
      null as List<String>,
      "Questionnaire URLs",
      null as List<FHIR.CodeableConcept>,
      "Appointment Coding"
    )
  else null as FHIR.Extension
