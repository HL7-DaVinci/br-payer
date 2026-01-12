library PhysicalTherapyRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// RULE-SPECIFIC CONSTANTS
// ============================================================

define "Assertion Prefix": 'pt'
define "Annual Session Limit": 20
define "Warning Threshold": 15

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
// SESSION TRACKING
// ============================================================

define function "Get Procedure Year"(proc Procedure):
  case
    when proc.performed is FHIR.dateTime then
      year from FHIRHelpers.ToDateTime(proc.performed as FHIR.dateTime)
    when proc.performed is FHIR.Period then
      year from FHIRHelpers.ToDateTime((proc.performed as FHIR.Period).start)
    else null
  end

define "PT Procedures This Year":
  [Procedure] P
    where P.status.value = 'completed'
      and P.performed is not null
      and "Get Procedure Year"(P) = year from Today()
      and P.code is not null
      and exists(P.code.coding c
        where c.system.value = 'http://www.ama-assn.org/go/cpt'
          and c.code.value in {'97110', '97140', '97530', '97112', '97116', '97535'})

define "Sessions Used": Count("PT Procedures This Year")
define "Sessions Remaining": "Annual Session Limit" - "Sessions Used"
define "Near Session Limit": "Sessions Used" >= "Warning Threshold"
define "Exceeded Session Limit": "Sessions Used" >= "Annual Session Limit"

// ============================================================
// COVERAGE DETERMINATION LOGIC
// ============================================================

define "Prior Authorization Required": "Exceeded Session Limit"
define "Documentation Required": "Near Session Limit" and not "Exceeded Session Limit"

define "Covered":
  if "Exceeded Session Limit" then 'conditional' else 'covered'

// ============================================================
// CARD CONTENT
// ============================================================

define "Service Code Display":
  if exists("Appointment Coding") then CRD."Codes To String"("Appointment Coding") else 'unspecified'

define "Get Summary":
  case
    when "Exceeded Session Limit" then 'Authorization needed - limit exceeded'
    when "Near Session Limit" then 'Covered - nearing session limit'
    else 'Covered - no additional requirements'
  end

define "Get Detail":
  'Physical therapy (' + "Service Code Display" + '): ' +
  ToString("Sessions Used") + '/' + ToString("Annual Session Limit") + ' sessions used this year. ' +
  case
    when "Exceeded Session Limit" then
      'Annual limit has been reached. Prior authorization required for continued treatment.'
    when "Near Session Limit" then
      ToString("Sessions Remaining") + ' sessions remaining before authorization is required.'
    else
      'Coverage confirmed with no additional requirements at this time.'
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
      CRD."Questionnaire URLs Value"("Exceeded Session Limit", { 'Questionnaire/PhysicalTherapyExtension' }),
      null as List<FHIR.CodeableConcept>,
      "Appointment Coding"
    )
  else null as FHIR.Extension
