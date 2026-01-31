library CareTransitionFollowupRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

define "Assertion Prefix": 'care-transition'
define "Coverage": First([Coverage])
define "Encounter": First([Encounter])
define "Active Conditions": [Condition] C where C.clinicalStatus.coding[0].code.value = 'active'

define "Encounter Type Coding":
  if "Encounter" is not null and "Encounter".type is not null then
    Flatten("Encounter".type T return T.coding)
  else {} as List<FHIR.Coding>

define "Encounter Class Code":
  if "Encounter" is not null and "Encounter".class is not null then
    "Encounter".class.code.value
  else null

define "Is Inpatient Encounter":
  "Encounter Class Code" in {'IMP', 'ACUTE', 'NONAC', 'EMER'}

define "Discharge Disposition Code":
  if "Encounter" is not null
     and "Encounter".hospitalization is not null
     and "Encounter".hospitalization.dischargeDisposition is not null
     and exists("Encounter".hospitalization.dischargeDisposition.coding) then
    First("Encounter".hospitalization.dischargeDisposition.coding).code.value
  else null

define "Discharge Disposition Display":
  if "Encounter" is not null
     and "Encounter".hospitalization is not null
     and "Encounter".hospitalization.dischargeDisposition is not null
     and exists("Encounter".hospitalization.dischargeDisposition.coding) then
    Coalesce(
      First("Encounter".hospitalization.dischargeDisposition.coding).display.value,
      "Encounter".hospitalization.dischargeDisposition.text.value,
      "Discharge Disposition Code"
    )
  else 'unspecified'

define "Discharge To Home":
  "Discharge Disposition Code" in {'home', 'HOME', '01', '06', '08'}

define "Discharge To SNF":
  "Discharge Disposition Code" in {'snf', 'SNF', '03', '04', '05'}

define "Discharge To Rehab":
  "Discharge Disposition Code" in {'rehab', 'REHAB', '62', '63'}

define "Discharge To Home Health":
  "Discharge Disposition Code" in {'hosp', 'HH', '86', '70'}

define "Heart Failure Codes": {'I50', '428'}
define "AMI Codes": {'I21', '410'}
define "Pneumonia Codes": {'J18', 'J13', 'J14', 'J15', 'J16', '486'}

define function "Has Condition With Prefix"(prefixes List<String>):
  exists("Active Conditions" C
    where exists(C.code.coding CC
      where exists(prefixes P where CC.code.value starts with P)))

define "Has Heart Failure": "Has Condition With Prefix"("Heart Failure Codes")
define "Has AMI": "Has Condition With Prefix"("AMI Codes")
define "Has Pneumonia": "Has Condition With Prefix"("Pneumonia Codes")

define "Is High Risk Readmission":
  "Has Heart Failure" or "Has AMI" or "Has Pneumonia"

define "Requires Care Transition Guidance":
  "Coverage" is not null
    and "Is Inpatient Encounter"
    and ("Discharge To Home" or "Discharge To SNF" or "Discharge To Rehab" or "Discharge To Home Health")

define "Covered": 'covered'

define "Transition Type":
  case
    when "Discharge To SNF" then 'skilled nursing facility'
    when "Discharge To Rehab" then 'rehabilitation'
    when "Discharge To Home Health" then 'home with home health services'
    when "Discharge To Home" then 'home'
    else 'other setting'
  end

define "Get Summary":
  'Care transition resources for discharge to ' + "Transition Type"

define "Get Detail":
  'Patient discharging to ' + "Transition Type" + ' (' + "Discharge Disposition Display" + '). ' +
  case
    when "Is High Risk Readmission" then
      'HIGH RISK FOR READMISSION: Patient has condition associated with elevated readmission risk. ' +
      'Payer care transition program available. Services include: medication reconciliation support, ' +
      'follow-up appointment coordination, 24/7 nurse line access, and care management enrollment. '
    else
      'Payer care transition support available including: follow-up care coordination, ' +
      'medication assistance programs, and transportation services if needed. '
  end +
  'Contact care transition team at 1-800-EXAMPLE for enrollment or visit member portal for resources.'

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
