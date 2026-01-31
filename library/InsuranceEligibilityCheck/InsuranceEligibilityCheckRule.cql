library InsuranceEligibilityCheckRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

define "Assertion Prefix": 'eligibility-check'
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

define "Is Emergency Encounter":
  "Encounter Class Code" = 'EMER'

define "Coverage Expiring Soon":
  if "Coverage" is not null and "Coverage".period is not null
     and "Coverage".period.end is not null then
    "Coverage".period.end.value between Today() and Today() + 30 days
  else false

define "Requires Eligibility Notification":
  "Coverage" is not null and ("Is Inpatient Encounter" or "Coverage Expiring Soon" or "Is Emergency Encounter")

define "Covered": 'covered'

define "Get Summary":
  case
    when "Coverage Expiring Soon" then 'Coverage expires within 30 days'
    when "Is Emergency Encounter" then 'Emergency encounter eligibility verified'
    when "Is Inpatient Encounter" then 'Inpatient encounter eligibility verified'
    else 'Eligibility check complete'
  end

define "Get Detail":
  'Encounter type: ' + CRD."Codes To Display String"("Encounter Type Coding") + '. ' +
  case
    when "Coverage Expiring Soon" then
      'Coverage end date approaching. Verify continued eligibility or plan transition.'
    when "Is Emergency Encounter" then
      'Emergency admission verified against active coverage. Follow up with care coordination as appropriate.'
    when "Is Inpatient Encounter" then
      'Inpatient admission verified against active coverage.'
    else 'Standard eligibility verification completed.'
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
