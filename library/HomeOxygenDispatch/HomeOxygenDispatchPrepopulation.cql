library HomeOxygenDispatchPrepopulation version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include DTRHelpers version '1.0.0' called DTR

codesystem "ICD-10-CM": 'http://hl7.org/fhir/sid/icd-10-cm'
codesystem "LOINC": 'http://loinc.org'
codesystem "SNOMED-CT": 'http://snomed.info/sct'

// Respiratory conditions qualifying for home oxygen
valueset "RespiratoryConditionsCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.25'

// Device classification value sets
valueset "PortableOxygenDeviceCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.80'
valueset "StationaryOxygenDeviceCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.79'
valueset "LiquidOxygenDeviceCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.77'
valueset "CompressedGasDeviceCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.74'
valueset "ConcentratorDeviceCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.78'

// Observation value sets
valueset "OxygenSaturationCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.106'
valueset "HematocritCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.132'

// Individual LOINC codes for specific oxygen lab tests
code "59408-5": '59408-5' from "LOINC"  // Pulse oximetry
code "2708-6": '2708-6' from "LOINC"    // Arterial O2 saturation (ABG)
code "2703-7": '2703-7' from "LOINC"    // Arterial PO2

parameter device_request DeviceRequest

context Patient

// ============================================================
// DIAGNOSES
// ============================================================

define "PulseOxCodes": { "59408-5", "2708-6" }
define "ArterialPO2Codes": { "2703-7" }

define "RelevantDiagnoses": DTR.CodesFromConditions(DTR.ActiveOrRecurring([Condition: "RespiratoryConditionsCodes"]))

// ============================================================
// OXYGEN SATURATION AND BLOOD GAS
// ============================================================

define "ArterialOxygenSaturation":
  DTR.GetObservationValue(
    DTR.MostRecent(DTR.Verified(DTR.ObservationLookBack([Observation: "PulseOxCodes"], 3 months)))
  )

define "ArterialPartialPressureOfOxygen":
  DTR.GetObservationValue(
    DTR.MostRecent(DTR.Verified(DTR.ObservationLookBack([Observation: "ArterialPO2Codes"], 3 months)))
  )

define "HematocritThatIsGreaterThanThreshold":
  DTR.GetObservationValue(
    DTR.MostRecent(DTR.Verified(DTR.ObservationLookBack([Observation: "HematocritCodes"], 3 months)))
  )

// ============================================================
// MOBILITY
// ============================================================

define "PatientMobile": exists([Condition] C
  where exists(C.code.coding co where co.system.value = 'http://snomed.info/sct'
    and co.code.value in { '160685001', '129006008' }))

// ============================================================
// DEVICE REQUEST DETAILS
// ============================================================

define "DeviceRequestHcpcsCoding": singleton from (
  ((cast device_request.code as CodeableConcept).coding) coding
    where coding.system.value = 'http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets')

define "DeviceRequestDescription": 'HCPCS ' + "DeviceRequestHcpcsCoding".code.value + ' - ' + "DeviceRequestHcpcsCoding".display.value

define "DeviceRequestedIsPortable":
  if "DeviceRequestHcpcsCoding" in "PortableOxygenDeviceCodes" then true
  else if "DeviceRequestHcpcsCoding" in "StationaryOxygenDeviceCodes" then false
  else null

define "DeviceType":
  if "DeviceRequestHcpcsCoding" in "LiquidOxygenDeviceCodes" then 'Liquid Oxygen'
  else if "DeviceRequestHcpcsCoding" in "CompressedGasDeviceCodes" then 'Compressed Gas'
  else if "DeviceRequestHcpcsCoding" in "ConcentratorDeviceCodes" then 'Concentrator'
  else null

// ============================================================
// PRESCRIBED USE
// ============================================================

define "LengthOfNeed":
  if device_request.occurrence is FHIR.Period then
    months between
      (device_request.occurrence as FHIR.Period)."start".value
      and (device_request.occurrence as FHIR.Period)."end".value
  else null

define "FrequencyOfUse": null as String

// ============================================================
// ENCOUNTERS
// ============================================================

define "RecentEncounters": DTR.EncounterLookBack([Encounter], 6 months)
define "EncounterList": DTR.ConvertEncounterDetails("RecentEncounters")
