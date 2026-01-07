library OpioidPrescribingRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// OPIOID CODE SETS (RxNorm)
// ============================================================
// These are example RxNorm codes for common opioids

define "Schedule II Opioid Codes":
  {
    '197696',   // Hydrocodone 5 MG / Acetaminophen 325 MG
    '1049502',  // Oxycodone 5 MG Oral Tablet
    '1049504',  // Oxycodone 10 MG Oral Tablet
    '197446',   // Fentanyl 25 MCG/HR Transdermal
    '197447',   // Fentanyl 50 MCG/HR Transdermal
    '1014599',  // Morphine Sulfate 15 MG Oral Tablet
    '1014615',  // Morphine Sulfate 30 MG Oral Tablet
    '991147',   // Methadone 5 MG Oral Tablet
    '991149'    // Methadone 10 MG Oral Tablet
  }

define "Extended Release Opioid Codes":
  {
    '1049543',  // Oxycodone ER 10 MG
    '1049557',  // Oxycodone ER 20 MG
    '892496',   // Morphine ER 15 MG
    '892516',   // Morphine ER 30 MG
    '197447'    // Fentanyl 50 MCG/HR (considered extended release)
  }

define "High MME Opioid Codes":
  {
    '197447',   // Fentanyl 50 MCG/HR
    '1049557',  // Oxycodone ER 20 MG
    '892516',   // Morphine ER 30 MG
    '991149'    // Methadone 10 MG
  }

define "Benzodiazepine Codes":
  {
    '197321',   // Alprazolam 0.5 MG
    '197322',   // Alprazolam 1 MG
    '197590',   // Diazepam 5 MG
    '197591',   // Diazepam 10 MG
    '199281',   // Lorazepam 1 MG
    '199282',   // Lorazepam 2 MG
    '198060'    // Clonazepam 1 MG
  }

// Non-opioid alternatives for step therapy
define "Non Opioid Alternative Codes":
  {
    '197805',   // Ibuprofen 400 MG
    '197806',   // Ibuprofen 600 MG
    '198439',   // Naproxen 500 MG
    '313782',   // Acetaminophen 500 MG
    '729527',   // Tramadol 50 MG (Schedule IV)
    '198405'    // Meloxicam 15 MG
  }

// ============================================================
// ORDER EXTRACTION
// ============================================================

define "Medication Requests":
  [MedicationRequest]

define "Medication Request":
  First("Medication Requests")

define "Requested Medication Coding":
  CRD."Get Medication Code"("Medication Request")

define "Requested Medication Code":
  CRD."Get First Code"("Requested Medication Coding")

// ============================================================
// HISTORICAL DATA (from prefetch)
// ============================================================

define "Active Medications":
  [MedicationStatement] M
    where M.status.value in { 'active', 'intended' }

define "Active Medication Codes":
  "Active Medications" M
    return
      if M.medication is CodeableConcept
      then CRD."Get First Code"((M.medication as CodeableConcept).coding)
      else null

define "Previous Opioid Prescriptions":
  [MedicationRequest] R
    where R.status.value in { 'completed', 'active' }
      and CRD."Has Code In List"(CRD."Get Medication Code"(R), "Schedule II Opioid Codes")

// ============================================================
// COVERAGE INFORMATION
// ============================================================

define "Coverage":
  First([Coverage])

define "Coverage Reference":
  FHIR.Reference {
    reference: FHIR.string { value: 'Coverage/' + "Coverage".id.value }
  }

define "Assertion Date":
  Today()

define "Assertion ID":
  'opioid-' + ToString("Assertion Date") + '-' + "Coverage".id.value

// ============================================================
// CLINICAL DECISION LOGIC
// ============================================================

define "Is Schedule II Opioid":
  CRD."Has Code In List"("Requested Medication Coding", "Schedule II Opioid Codes")

define "Is Extended Release":
  CRD."Has Code In List"("Requested Medication Coding", "Extended Release Opioid Codes")

define "Is High MME":
  CRD."Has Code In List"("Requested Medication Coding", "High MME Opioid Codes")

define "Has Active Benzodiazepine":
  exists("Active Medication Codes" C where C in "Benzodiazepine Codes")

define "Is Opioid Naive":
  not exists("Previous Opioid Prescriptions")

define "Requested Days Supply":
  Coalesce("Medication Request".dispenseRequest.expectedSupplyDuration.value.value, 30)

define "Exceeds Initial Supply Limit":
  "Is Opioid Naive" and "Requested Days Supply" > 7

// ============================================================
// APPLICABILITY CONDITIONS
// ============================================================

define "Has Safety Warnings":
  "Is Schedule II Opioid" and (
    "Has Active Benzodiazepine"
    or ("Is Opioid Naive" and "Is Extended Release")
    or "Exceeds Initial Supply Limit"
    or "Is High MME"
  )

define "Has Non Opioid Alternative":
  "Is Schedule II Opioid" and "Is Opioid Naive"

define "Requires Coverage Info":
  "Is Schedule II Opioid"

define "Prior Authorization Required":
  "Is Extended Release"
  or "Is High MME"
  or ("Is Opioid Naive" and "Is Schedule II Opioid")

define "Documentation Required":
  "Is Schedule II Opioid"

// ============================================================
// SAFETY WARNINGS CONTENT
// ============================================================

define "Safety Warnings List":
  Flatten({
    if "Has Active Benzodiazepine" then
      { 'CRITICAL: Patient has active benzodiazepine - concurrent use increases overdose risk' }
    else {},

    if "Is Opioid Naive" and "Is Extended Release" then
      { 'WARNING: Extended-release opioids not recommended for opioid-naive patients' }
    else {},

    if "Exceeds Initial Supply Limit" then
      { 'NOTE: CDC recommends ≤7 day supply for initial opioid prescription' }
    else {},

    if "Is High MME" then
      { 'CAUTION: High MME formulation - ensure appropriate clinical justification' }
    else {}
  })

define "Warning Summary":
  if "Has Active Benzodiazepine" then
    'CRITICAL: Opioid-Benzodiazepine Interaction'
  else
    'Opioid Prescribing Alert'

define "Warning Detail":
  Combine("Safety Warnings List", '. ')

define "Warning Indicator":
  if "Has Active Benzodiazepine" then 'critical'
  else 'warning'

// ============================================================
// ALTERNATIVE SUGGESTION CONTENT
// ============================================================

define "Alternative Summary":
  'Non-Opioid Alternative Available'

define "Alternative Detail":
  'Patient is opioid-naive. Consider non-opioid alternatives such as NSAIDs (ibuprofen, naproxen) or acetaminophen before initiating opioid therapy per CDC guidelines.'

// ============================================================
// COVERAGE INFO CONTENT
// ============================================================

define "Missing Info":
  Flatten({
    // Per CRD spec info-needed values: performer, location, timeframe, contract-window, detail-code, OTH
    if "Medication Request".performer is null then { 'performer' } else {},
    if "Medication Request".dispenseRequest is null then { 'timeframe' } else {}
  })

define "Coverage Summary":
  case
    when "Prior Authorization Required" then
      'Prior Authorization Required for Opioid'
    when "Documentation Required" then
      'Documentation Required for Opioid'
    else
      'Opioid Coverage Information'
  end

define "Coverage Detail":
  'Prescribing ' + CRD."Codes To Display String"("Requested Medication Coding") +
  '. ' +
  (if "Prior Authorization Required" then 'Prior authorization is required. ' else '') +
  (if "Documentation Required" then 'Clinical documentation must be submitted. ' else '') +
  (if exists("Missing Info") then
    'Missing information: ' + Combine("Missing Info", ', ') + '. '
  else '') +
  'Patient is ' + (if "Is Opioid Naive" then 'opioid-naive' else 'opioid-tolerant') +
  '. Days supply requested: ' + ToString("Requested Days Supply") + '.'

// ============================================================
// COVERAGE DETERMINATION
// ============================================================

define "Covered":
  if "Has Active Benzodiazepine" then
    'conditional'  // Conditional coverage due to safety concern
  else if "Is Extended Release" and "Is Opioid Naive" then
    'conditional'  // Step therapy may apply
  else
    'covered'

define "PA Needed":
  if "Prior Authorization Required" then 'auth-needed' else 'no-auth'

define "Doc Needed":
  if "Documentation Required" then
    { 'clinical' }  // Clinical documentation required
  else
    null

define "Doc Purpose":
  if "Documentation Required" then
    { 'withpa' }  // Documentation needed with prior auth
  else
    null

define "Info Needed":
  if exists("Missing Info") then
    "Missing Info"
  else
    null

define "Questionnaire URLs":
  if "Documentation Required" then
    {
      'Questionnaire/OpioidPrescribingJustification',
      'Questionnaire/OpioidPDMP'
    }
  else
    null

// ============================================================
// COVERAGE REASONS
// ============================================================

define "Coverage Reason":
  Flatten({
    if "Has Active Benzodiazepine" then
      { Ext."Reason From Text"('Concurrent benzodiazepine use - additional review required') }
    else {},

    if "Prior Authorization Required" then
      { Ext."Reason From Text"('Prior authorization required for Schedule II opioid') }
    else {},

    if "Is Extended Release" and "Is Opioid Naive" then
      { Ext."Reason From Text"('Step therapy recommended - consider immediate-release formulation first') }
    else {},

    if "Exceeds Initial Supply Limit" then
      { Ext."Reason From Text"('Initial prescription exceeds CDC 7-day recommendation') }
    else {}
  })

// ============================================================
// FINAL EXTENSION (using shared builder)
// ============================================================

define "Coverage Info Extension":
  Ext."Build Coverage Info Extension"(
    "Coverage Reference",
    "Covered",
    "Assertion Date",
    "Assertion ID",
    "PA Needed",
    "Doc Needed",
    "Doc Purpose",
    "Info Needed",
    "Questionnaire URLs",
    "Coverage Reason",
    "Requested Medication Coding"
  )
