library ImmunosuppressiveDrugsPrepopulation version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include DTRHelpers version '1.0.0' called DTR

codesystem "LOINC": 'http://loinc.org'

// Transplant-related conditions (e.g., history of organ transplant)
valueset "TransplantConditionCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.81'

// Transplant procedure codes
valueset "TransplantProcedureCodes": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.90'

// Immunosuppressive medication value sets
valueset "ImmunosuppressiveDrugsCodes1": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.192'
valueset "ImmunosuppressiveDrugsCodes2": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.193'

// Lab codes
code "Creatinine": '2160-0' from "LOINC"
code "ALT": '1742-6' from "LOINC"

parameter medication_request MedicationRequest

context Patient

// ============================================================
// TRANSPLANT / INDICATION
// ============================================================

define "QualifyingTransplantConditions":
  DTR.ActiveOrRecurring([Condition: "TransplantConditionCodes"])

define "QualifyingTransplant":
  exists("QualifyingTransplantConditions")

define "TransplantCondition": First("QualifyingTransplantConditions")

define "TransplantDate":
  Coalesce(
    (TransplantCondition.onset as FHIR.dateTime).value,
    TransplantCondition.recordedDate.value
  )

define "TransplantProcedures": [Procedure: "TransplantProcedureCodes"]

define "TransplantProcedureDisplay":
  First(
    "TransplantProcedures" P
      return First(P.code.coding).display.value
  )

// ============================================================
// MEDICATION DETAILS
// ============================================================

define "MedicationCoding": DTR.MedicationRequestCoding(medication_request)

define "MedicationDescription":
  if "MedicationCoding" is not null then
    First("MedicationCoding").display.value
  else null

define "CurrentDosage":
  if medication_request is not null and exists(medication_request.dosageInstruction) then
    First(medication_request.dosageInstruction).text.value
  else null

define "MedicationRoute":
  if medication_request is not null and exists(medication_request.dosageInstruction) then
    First(First(medication_request.dosageInstruction).route.coding).display.value
  else null

define "MedicationFrequency":
  if medication_request is not null and exists(medication_request.dosageInstruction) then
    if First(medication_request.dosageInstruction).timing is not null
      and First(medication_request.dosageInstruction).timing.repeat is not null then
        ToString(First(medication_request.dosageInstruction).timing.repeat.frequency.value)
          + ' per '
          + ToString(First(medication_request.dosageInstruction).timing.repeat.period.value)
          + ' '
          + First(medication_request.dosageInstruction).timing.repeat.periodUnit.value
      else null
  else null

define "MedicationQuantity":
  if medication_request is not null and medication_request.dispenseRequest is not null
    and medication_request.dispenseRequest.quantity is not null then
    ToString(medication_request.dispenseRequest.quantity.value.value) + ' ' +
      Coalesce(medication_request.dispenseRequest.quantity.unit.value, '')
  else null

define "RefillsAuthorized":
  if medication_request is not null and medication_request.dispenseRequest is not null then
    medication_request.dispenseRequest.numberOfRepeatsAllowed.value
  else null

// ============================================================
// CURRENT MEDICATIONS
// ============================================================

define "CurrentMedications":
  DTR.ActiveMedicationStatements(
    [MedicationStatement: "ImmunosuppressiveDrugsCodes1"]
    union [MedicationStatement: "ImmunosuppressiveDrugsCodes2"]
  )

define "CurrentMedicationsList":
  "CurrentMedications" M
    return First((M.medication as FHIR.CodeableConcept).coding).display.value

// ============================================================
// BASELINE LABS
// ============================================================

define "CreatinineLabs": DTR.Verified(DTR.ObservationLookBack([Observation: "Creatinine"], 6 months))
define "BaselineCreatinineObs": DTR.MostRecent("CreatinineLabs")
define "BaselineCreatinine": DTR.ObservationValueAsString("BaselineCreatinineObs")

define "LiverFunctionLabs": DTR.Verified(DTR.ObservationLookBack([Observation: "ALT"], 6 months))
define "BaselineLiverFunctionObs": DTR.MostRecent("LiverFunctionLabs")
define "BaselineLiverFunction": DTR.ObservationValueAsString("BaselineLiverFunctionObs")

define "LabTestDate":
  Coalesce(
    ("BaselineCreatinineObs".effective as FHIR.dateTime).value,
    ("BaselineLiverFunctionObs".effective as FHIR.dateTime).value
  )

// ============================================================
// THERAPY DATES
// ============================================================

define "TherapyStartDate":
  Coalesce(
    medication_request.authoredOn.value,
    First(
      "CurrentMedications" M
        sort by Coalesce(
          (effective as FHIR.dateTime).value,
          (effective as FHIR.Period)."start".value
        )
    ).effective as FHIR.dateTime
  )

// ============================================================
// ENCOUNTERS
// ============================================================

define "RecentEncounters": DTR.EncounterLookBack([Encounter], 6 months)
define "EncounterList": DTR.ConvertEncounterDetails("RecentEncounters")
