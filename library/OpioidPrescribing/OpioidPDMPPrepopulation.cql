library OpioidPDMPPrepopulation version '1.0.0'
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
// PATIENT IDENTIFIERS (for PDMP query)
// ============================================================

define "PatientName":
  Coalesce(
    First(Patient.name).family.value + ', ' + First(First(Patient.name).given).value,
    'Unknown'
  )

define "PatientDOB":
  ToString(Patient.birthDate.value)

define "PatientIdentifiers":
  "PatientName" + ' (DOB: ' + Coalesce("PatientDOB", 'Unknown') + ')'

// ============================================================
// OPIOID PRESCRIPTION HISTORY
// ============================================================

define "Schedule II Opioid Codes":
  { '197696', '1049502', '1049504', '197446', '197447', '1014599', '1014615', '991147', '991149' }

define "PrescribedOpioidHistory":
  distinct(
    [MedicationRequest] R
      where R.status.value in { 'completed', 'active' }
        and exists(
          (DTR.MedicationRequestCoding(R)) C
            where C.code.value in "Schedule II Opioid Codes"
        )
      return Coalesce(
        First(DTR.MedicationRequestCoding(R)).display.value,
        First(DTR.MedicationRequestCoding(R)).code.value
      )
  )
