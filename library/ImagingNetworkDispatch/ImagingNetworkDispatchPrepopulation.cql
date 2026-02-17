library ImagingNetworkDispatchPrepopulation version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include DTRHelpers version '1.0.0' called DTR

codesystem "CPT": 'http://www.ama-assn.org/go/cpt'
codesystem "SNOMED-CT": 'http://snomed.info/sct'
codesystem "LOINC": 'http://loinc.org'

parameter service_request ServiceRequest

context Patient

// ============================================================
// PATIENT DEMOGRAPHICS
// ============================================================

define "PatientName":
  if Patient.name is not null and exists(Patient.name) then
    Coalesce(First(Patient.name).family.value, '') + ', ' +
    Coalesce(First(First(Patient.name).given).value, '')
  else null

// ============================================================
// REFERRING PROVIDER
// ============================================================

define "ReferringPractitioner":
  if service_request is not null and service_request.requester is not null then
    First([Practitioner] P
      where P.id = Last(Split(service_request.requester.reference.value, '/')))
  else null

define "ReferringProviderName":
  if "ReferringPractitioner" is not null and exists("ReferringPractitioner".name) then
    Coalesce(First("ReferringPractitioner".name).family.value, '') + ', ' +
    Coalesce(First(First("ReferringPractitioner".name).given).value, '')
  else null

define "ReferringProviderNPI":
  if "ReferringPractitioner" is not null then
    First(
      "ReferringPractitioner".identifier I
        where I.system.value = 'http://hl7.org/fhir/sid/us-npi'
        return I.value.value
    )
  else null

// ============================================================
// CLINICAL INDICATION
// ============================================================

define "ClinicalIndication":
  if service_request is not null and service_request.reasonCode is not null then
    Combine(
      service_request.reasonCode RC
        return Coalesce(First(RC.coding).display.value, First(RC.coding).code.value, RC.text.value),
      '; '
    )
  else null

define "ActiveConditions":
  DTR.ActiveOrRecurring([Condition])

define "RelevantDiagnoses":
  Combine(
    "ActiveConditions" C
      return Coalesce(First(C.code.coding).display.value, First(C.code.coding).code.value),
    '; '
  )

// ============================================================
// IMAGING DETAILS
// ============================================================

define "ServiceRequestCoding":
  if service_request is not null and service_request.code is not null then
    service_request.code.coding
  else null

define "ImagingType":
  if exists("ServiceRequestCoding") then
    First("ServiceRequestCoding").display.value
  else null

define "ImagingCPTCode":
  if exists("ServiceRequestCoding") then
    First("ServiceRequestCoding" C where C.system.value = 'http://www.ama-assn.org/go/cpt').code.value
  else null

define "BodySite":
  if service_request is not null and service_request.bodySite is not null then
    Combine(
      service_request.bodySite BS
        return Coalesce(First(BS.coding).display.value, BS.text.value),
      '; '
    )
  else null

// ============================================================
// FACILITY INFORMATION
// ============================================================

define "PerformingOrganization": First([Organization])

define "FacilityName":
  if "PerformingOrganization" is not null and "PerformingOrganization".name is not null then
    "PerformingOrganization".name.value
  else null

define "FacilityNPI":
  if "PerformingOrganization" is not null then
    First(
      "PerformingOrganization".identifier I
        where I.system.value = 'http://hl7.org/fhir/sid/us-npi'
        return I.value.value
    )
  else null

define "FacilityAddress":
  if "PerformingOrganization" is not null and exists("PerformingOrganization".address) then
    Coalesce(
      Combine(First("PerformingOrganization".address).line.value, ', ') + ', ' +
      Coalesce(First("PerformingOrganization".address).city.value, '') + ', ' +
      Coalesce(First("PerformingOrganization".address).state.value, '') + ' ' +
      Coalesce(First("PerformingOrganization".address).postalCode.value, ''),
      ''
    )
  else null

// ============================================================
// PRIOR IMAGING HISTORY
// ============================================================

define "PriorImagingProcedures":
  [Procedure] P
    where P.status.value = 'completed'
      and P.performed is not null
      and P.code is not null
      and exists(P.code.coding C
        where C.system.value = 'http://www.ama-assn.org/go/cpt'
          and C.code.value in { '70553', '72148', '70450', '71260', '74177', '72193' })

define "PriorImagingLast12Months":
  "PriorImagingProcedures" P
    where (
      case
        when P.performed is FHIR.dateTime then
          FHIRHelpers.ToDateTime(P.performed as FHIR.dateTime) after (Now() - 12 months)
        when P.performed is FHIR.Period then
          FHIRHelpers.ToDateTime((P.performed as FHIR.Period).start) after (Now() - 12 months)
        else false
      end
    )

define "PriorImagingCount": Count("PriorImagingLast12Months")

define "HasPriorImaging": exists("PriorImagingLast12Months")
