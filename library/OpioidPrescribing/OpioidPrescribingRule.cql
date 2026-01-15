library OpioidPrescribingRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// RULE-SPECIFIC CONSTANTS
// ============================================================

define "Assertion Prefix": 'opioid'

define "Schedule II Opioid Codes":
  { '197696', '1049502', '1049504', '197446', '197447', '1014599', '1014615', '991147', '991149' }

define "Extended Release Opioid Codes":
  { '1049543', '1049557', '892496', '892516', '197447' }

define "High MME Opioid Codes":
  { '197447', '1049557', '892516', '991149' }

define "Benzodiazepine Codes":
  { '197321', '197322', '197590', '197591', '199281', '199282', '198060' }

// ============================================================
// CONTEXT RESOURCES
// ============================================================

define "Coverage": First([Coverage])
define "Medication Request": First([MedicationRequest])

define "Requested Medication Coding":
  CRD."Get Medication Code"("Medication Request")

// ============================================================
// HISTORICAL DATA
// ============================================================

define "Active Medications":
  [MedicationStatement] M where M.status.value in { 'active', 'intended' }

define "Active Medication Codes":
  "Active Medications" M
    return if M.medication is CodeableConcept
      then CRD."Get First Code"((M.medication as CodeableConcept).coding)
      else null

define "Previous Opioid Prescriptions":
  [MedicationRequest] R
    where R.status.value in { 'completed', 'active' }
      and CRD."Has Code In List"(CRD."Get Medication Code"(R), "Schedule II Opioid Codes")

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

define "Is Opioid Naive": not exists("Previous Opioid Prescriptions")

define "Requested Days Supply":
  Coalesce("Medication Request".dispenseRequest.expectedSupplyDuration.value.value, 30)

define "Exceeds Initial Supply Limit":
  "Is Opioid Naive" and "Requested Days Supply" > 7

// ============================================================
// COVERAGE DETERMINATION LOGIC
// ============================================================

define "Prior Authorization Required":
  "Is Extended Release" or "Is High MME" or ("Is Opioid Naive" and "Is Schedule II Opioid")

define "Documentation Required": "Is Schedule II Opioid"

define "Covered":
  case
    when "Has Active Benzodiazepine" then 'conditional'
    when "Is Extended Release" and "Is Opioid Naive" then 'conditional'
    else 'covered'
  end

// ============================================================
// SAFETY WARNINGS CONTENT
// ============================================================

define "Has Safety Warnings":
  "Is Schedule II Opioid" and (
    "Has Active Benzodiazepine" or ("Is Opioid Naive" and "Is Extended Release")
    or "Exceeds Initial Supply Limit" or "Is High MME"
  )

define "Safety Warnings List":
  Flatten({
    if "Has Active Benzodiazepine" then { 'CRITICAL: Patient has active benzodiazepine - concurrent use increases overdose risk' } else {},
    if "Is Opioid Naive" and "Is Extended Release" then { 'WARNING: Extended-release opioids not recommended for opioid-naive patients' } else {},
    if "Exceeds Initial Supply Limit" then { 'NOTE: CDC recommends ≤7 day supply for initial opioid prescription' } else {},
    if "Is High MME" then { 'CAUTION: High MME formulation - ensure appropriate clinical justification' } else {}
  })

define "Warning Summary":
  if "Has Active Benzodiazepine" then 'CRITICAL: Opioid-Benzodiazepine Interaction'
  else 'Opioid Prescribing Alert'

define "Warning Detail": Combine("Safety Warnings List", '. ')

define "Warning Indicator":
  if "Has Active Benzodiazepine" then 'critical' else 'warning'

// ============================================================
// ALTERNATIVE SUGGESTION CONTENT
// ============================================================

define "Has Non Opioid Alternative":
  "Is Opioid Naive" and "Is Schedule II Opioid" and not "Has Active Benzodiazepine"

define "Alternative Summary":
  'Consider Non-Opioid Alternative'

define "Alternative Detail":
  'Patient is opioid-naive. Consider non-opioid pain management options such as ' +
  'NSAIDs, acetaminophen, or physical therapy before initiating opioid therapy. ' +
  'If opioids are necessary, start with the lowest effective dose of an immediate-release formulation.'

// ============================================================
// COVERAGE INFO CONTENT
// ============================================================

define "Requires Coverage Info": "Coverage" is not null and "Is Schedule II Opioid"

define "Coverage Summary": "Get Summary"

define "Coverage Detail": "Get Detail"

// ============================================================
// CARD CONTENT
// ============================================================

define "Get Summary":
  case
    when "Prior Authorization Required" then 'Prior Authorization Required for Opioid'
    when "Documentation Required" then 'Documentation Required for Opioid'
    else 'Opioid Coverage Information'
  end

define "Get Detail":
  'Prescribing ' + CRD."Codes To Display String"("Requested Medication Coding") + '. ' +
  (if "Prior Authorization Required" then 'Prior authorization is required. ' else '') +
  (if "Documentation Required" then 'Clinical documentation must be submitted. ' else '') +
  'Patient is ' + (if "Is Opioid Naive" then 'opioid-naive' else 'opioid-tolerant') +
  '. Days supply requested: ' + ToString("Requested Days Supply") + '.'

// ============================================================
// EXTENSION VALUES (complex rule - uses full builder)
// ============================================================

define "Missing Info":
  Flatten({
    if "Medication Request".performer is null then { 'performer' } else {},
    if "Medication Request".dispenseRequest is null then { 'timeframe' } else {}
  })

define "Doc Purpose":
  if "Documentation Required" then { 'withpa' } else null as List<String>

define "Info Needed":
  if exists("Missing Info") then "Missing Info" else null as List<String>

define "Questionnaire URLs":
  if "Documentation Required" then
    { 'Questionnaire/OpioidPrescribingJustification', 'Questionnaire/OpioidPDMP' }
  else null as List<String>

define "Coverage Reason":
  Flatten({
    if "Has Active Benzodiazepine" then
      { Ext."Reason From Text"('Concurrent benzodiazepine use - additional review required') } else {},
    if "Prior Authorization Required" then
      { Ext."Reason From Text"('Prior authorization required for Schedule II opioid') } else {},
    if "Is Extended Release" and "Is Opioid Naive" then
      { Ext."Reason From Text"('Step therapy recommended - consider immediate-release formulation first') } else {},
    if "Exceeds Initial Supply Limit" then
      { Ext."Reason From Text"('Initial prescription exceeds CDC 7-day recommendation') } else {}
  })

// ============================================================
// FINAL COVERAGE EXTENSION
// ============================================================

define "Coverage Info Extension":
  if "Coverage" is not null and "Coverage".id is not null then
    Ext."Build Coverage Info Extension"(
      CRD."Build Coverage Reference"("Coverage"),
      "Covered",
      Today(),
      CRD."Build Assertion ID"("Assertion Prefix", "Coverage"),
      CRD."PA Needed Value"("Prior Authorization Required"),
      CRD."Doc Needed Value"("Documentation Required", 'clinical'),
      "Doc Purpose",
      "Info Needed",
      "Questionnaire URLs",
      "Coverage Reason",
      "Requested Medication Coding"
    )
  else null as FHIR.Extension
