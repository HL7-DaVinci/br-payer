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

define "Requires Referral": not "Has Existing Referral"

// ============================================================
// COVERAGE DETERMINATION LOGIC
// ============================================================

define "Prior Authorization Required": false
define "Documentation Required": "Requires Referral"
define "Covered": 'covered'

// ============================================================
// CARD CONTENT
// ============================================================

define "Service Code Display":
  if exists("Appointment Coding") then CRD."Codes To String"("Appointment Coding") else 'unspecified'

define "Get Summary":
  case
    when "Requires Referral" then 'Referral recommended'
    else 'Covered - no additional requirements'
  end

define "Get Detail":
  'Cardiology consultation (' + "Service Code Display" + '): ' +
  case
    when "Requires Referral" then
      'A specialist referral may be needed for full coverage. Patient age: ' +
      Coalesce(ToString(AgeInYears()), 'unknown') + ' years.'
    else
      'No additional documentation required.'
  end

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
      null as List<String>,
      null as List<String>,
      null as List<String>,
      null as List<FHIR.CodeableConcept>,
      "Appointment Coding"
    )
  else null as FHIR.Extension
