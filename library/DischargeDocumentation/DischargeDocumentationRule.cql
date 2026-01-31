library DischargeDocumentationRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

define "Assertion Prefix": 'discharge-doc'
define "Coverage": First([Coverage])
define "Encounter": First([Encounter])

define "Encounter Type Coding":
  if "Encounter" is not null and "Encounter".type is not null then
    Flatten("Encounter".type T return T.coding)
  else {} as List<FHIR.Coding>

define "Encounter Class Code":
  if "Encounter" is not null and "Encounter".class is not null then
    "Encounter".class.code.value
  else null

define "Is Inpatient Encounter":
  "Encounter Class Code" in {'IMP', 'ACUTE', 'NONAC'}

define "Discharge Disposition Code":
  if "Encounter" is not null
     and "Encounter".hospitalization is not null
     and "Encounter".hospitalization.dischargeDisposition is not null
     and exists("Encounter".hospitalization.dischargeDisposition.coding) then
    First("Encounter".hospitalization.dischargeDisposition.coding).code.value
  else null

define "Discharge To SNF":
  "Discharge Disposition Code" in {'snf', 'SNF', '03', '04', '05'}

define "Discharge To Home":
  "Discharge Disposition Code" in {'home', 'HOME', '01', '06'}

define "Discharge To Rehab":
  "Discharge Disposition Code" in {'rehab', 'REHAB', '62', '63'}

define "Length Of Stay Days":
  if "Encounter" is not null
     and "Encounter".period is not null
     and "Encounter".period.start is not null then
    days between date from FHIRHelpers.ToDateTime("Encounter".period.start) and Today()
  else 0

define "Extended Stay":
  "Length Of Stay Days" >= 7

define "Requires Documentation Reminder":
  "Coverage" is not null and "Is Inpatient Encounter" and ("Extended Stay" or "Discharge To SNF" or "Discharge To Rehab")

define "Covered": 'covered'

define "Get Summary":
  case
    when "Discharge To SNF" then 'SNF discharge - documentation checklist'
    when "Discharge To Rehab" then 'Rehab discharge - documentation required'
    when "Extended Stay" then 'Extended stay discharge - ensure complete documentation'
    else 'Discharge documentation reminder'
  end

define "Get Detail":
  'Discharge from ' + Coalesce(CRD."Codes To Display String"("Encounter Type Coding"), 'inpatient stay') +
  case
    when "Discharge To SNF" then
      '. Patient discharging to SNF. Ensure discharge summary includes: primary diagnosis, active problem list, ' +
      'current medications with reconciliation, cognitive and functional status, treatment course summary, ' +
      'and follow-up care plan. SNF admission certification may be required.'
    when "Discharge To Rehab" then
      '. Patient discharging to rehabilitation facility. Document rehabilitation goals, functional baseline, ' +
      'therapy recommendations, and expected recovery trajectory. Prior authorization for rehab stay may apply.'
    when "Extended Stay" then
      '. Extended hospitalization (' + ToString("Length Of Stay Days") + ' days). ' +
      'Complete discharge summary with detailed treatment course, response to therapy, ' +
      'discharge condition, and comprehensive follow-up plan.'
    else '. Standard discharge documentation required.'
  end

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
      "Encounter Type Coding"
    )
  else null as FHIR.Extension
