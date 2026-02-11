library OpioidPrescribingJustificationPrepopulation version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include DTRHelpers version '1.0.0' called DTR

codesystem "RxNorm": 'http://www.nlm.nih.gov/research/umls/rxnorm'

parameter medication_request MedicationRequest

context Patient

// ============================================================
// MEDICATION CODING
// ============================================================

define "MedicationCoding": DTR.MedicationRequestCoding(medication_request)

define "MedicationDescription":
  if "MedicationCoding" is not null then
    Coalesce(First("MedicationCoding").display.value, First("MedicationCoding").code.value)
  else null

// ============================================================
// OPIOID HISTORY
// ============================================================

define "Schedule II Opioid Codes":
  { '197696', '1049502', '1049504', '197446', '197447', '1014599', '1014615', '991147', '991149' }

define "Extended Release Opioid Codes":
  { '1049543', '1049557', '892496', '892516', '197447' }

define "Benzodiazepine Codes":
  { '197321', '197322', '197590', '197591', '199281', '199282', '198060' }

define "Previous Opioid Prescriptions":
  [MedicationRequest] R
    where R.status.value in { 'completed', 'active' }
      and exists(
        (DTR.MedicationRequestCoding(R)) C
          where C.code.value in "Schedule II Opioid Codes"
      )

define "IsOpioidNaive": not exists("Previous Opioid Prescriptions")

// ============================================================
// INTERACTION FLAGS
// ============================================================

define "Active Medications": DTR.ActiveMedicationStatements([MedicationStatement])

define "Active Medication Codes":
  "Active Medications" M
    return if M.medication is CodeableConcept
      then First((M.medication as CodeableConcept).coding).code.value
      else null

define "HasActiveBenzodiazepine":
  exists("Active Medication Codes" C where C in "Benzodiazepine Codes")

define "IsExtendedRelease":
  if "MedicationCoding" is not null then
    exists("MedicationCoding" C where C.code.value in "Extended Release Opioid Codes")
  else false

// ============================================================
// PAIN AND TREATMENT HISTORY
// ============================================================

define "Pain Conditions":
  DTR.ActiveOrRecurring(
    [Condition] C
      where exists(C.code.coding cd
        where cd.system.value = 'http://snomed.info/sct'
          or cd.system.value = 'http://hl7.org/fhir/sid/icd-10-cm')
  )

define "PainDiagnosis":
  Combine(
    "Pain Conditions" C
      return Coalesce(First(C.code.coding).display.value, First(C.code.coding).code.value),
    '; '
  )

define "PriorNonOpioidTreatments":
  [MedicationRequest] R
    where R.status.value in { 'completed', 'active', 'stopped' }
      and not exists(
        (DTR.MedicationRequestCoding(R)) C
          where C.code.value in "Schedule II Opioid Codes"
      )

define "CurrentMedications":
  "Active Medications" M
    return if M.medication is CodeableConcept
      then Coalesce(
        First((M.medication as CodeableConcept).coding).display.value,
        First((M.medication as CodeableConcept).coding).code.value
      )
      else null

// ============================================================
// PRESCRIPTION DETAILS
// ============================================================

define "RequestedDaysSupply":
  Coalesce(medication_request.dispenseRequest.expectedSupplyDuration.value.value, 30)
