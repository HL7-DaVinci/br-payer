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

define "Is Ambulatory Encounter":
  "Encounter Class Code" = 'AMB'

define "Coverage Expiring Soon":
  if "Coverage" is not null and "Coverage".period is not null
     and "Coverage".period.end is not null then
    "Coverage".period.end.value between Today() and Today() + 30 days
  else false

define "Requires Eligibility Notification":
  "Coverage" is not null and ("Is Inpatient Encounter" or "Coverage Expiring Soon" or "Is Emergency Encounter" or "Is Ambulatory Encounter")

define "Covered": 'covered'

define "Get Summary":
  case
    when "Coverage Expiring Soon" then 'Coverage expires within 30 days'
    when "Is Emergency Encounter" then 'Emergency encounter eligibility verified'
    when "Is Inpatient Encounter" then 'Inpatient encounter eligibility verified'
    when "Is Ambulatory Encounter" then 'Outpatient encounter eligibility verified'
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
    when "Is Ambulatory Encounter" then
      'Outpatient visit verified against active coverage.'
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

// High-risk encounter definitions
define "High Risk Diagnosis Codes": { '22298006', '230690007', '263225007', '84114007' }  // MI, stroke, hip fracture, heart failure

define "Encounter Reason Codes":
  if "Encounter" is not null and "Encounter".reasonCode is not null then
    Flatten("Encounter".reasonCode RC return RC.coding)
  else {} as List<FHIR.Coding>

define "Is High Risk Encounter":
  "Is Inpatient Encounter" and
  exists("Encounter Reason Codes" C where C.code.value in "High Risk Diagnosis Codes")

define "Has High Risk Alert":
  "Is High Risk Encounter"

define "High Risk Summary":
  'High-acuity admission detected'

define "High Risk Detail":
  'Patient admitted for ' + CRD."Codes To Display String"("Encounter Reason Codes") + '. ' +
  'Consider case management referral for care coordination and discharge planning.'

// Care coordination card (for all inpatient encounters)
define "Show Care Coordination":
  "Is Inpatient Encounter" or "Is Emergency Encounter"

define "Care Coordination Summary":
  'Payer care coordination resources available'

define "Care Coordination Detail":
  'CMS provides care coordination support for beneficiaries with complex conditions. ' +
  'Access resources for discharge planning and post-acute care coordination.'

// Coverage expiring alert
define "Coverage Expiration Date":
  if "Coverage" is not null and "Coverage".period is not null and "Coverage".period.end is not null then
    ToString("Coverage".period.end.value)
  else null

define "Expiring Coverage Summary":
  'Coverage expires ' + Coalesce("Coverage Expiration Date", 'soon')

define "Expiring Coverage Detail":
  'Patient coverage end date is approaching. Verify continued eligibility or assist patient with coverage transition options.'
