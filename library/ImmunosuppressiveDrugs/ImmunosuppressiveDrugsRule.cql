library ImmunosuppressiveDrugsRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// RULE-SPECIFIC CONSTANTS
// ============================================================

define "Assertion Prefix": 'immuno-drugs'

// ============================================================
// CONTEXT RESOURCES
// ============================================================

define "Coverage": First([Coverage])
define "Medication Request": First([MedicationRequest])
define "Medication Dispense": First([MedicationDispense])
define "Medication Statement": First([MedicationStatement])

define "Requested Medication Coding":
  Coalesce(
    CRD."Get Medication Code"("Medication Request"),
    if "Medication Dispense" is not null and "Medication Dispense".medication is CodeableConcept then
      ("Medication Dispense".medication as CodeableConcept).coding
    else null
  )

define "Statement Medication Coding":
  if "Medication Statement" is not null and "Medication Statement".medication is CodeableConcept then
    ("Medication Statement".medication as CodeableConcept).coding
  else null

define "Medication Code":
  if "Requested Medication Coding" is not null then CRD."Get First Code"("Requested Medication Coding") else null

define "Medication Statement Code":
  if "Statement Medication Coding" is not null then CRD."Get First Code"("Statement Medication Coding") else null

define "Has Indication Evidence":
  exists([Condition]) or exists([Procedure])
    or ("Medication Request" is not null and (exists("Medication Request".reasonCode)
      or exists("Medication Request".reasonReference)))

// ============================================================
// COVERAGE DETERMINATION LOGIC
// ============================================================

define "Rule Applies":
  "Coverage" is not null and "Medication Code" is not null

define "Prior Authorization Required": false
define "Documentation Required": true
define "Covered": 'covered'

define "Alternative Therapy":
  if "Medication Code" in { '105611', '239983' }
  then Code { code: '197388', display: 'Azathioprine 50 MG Oral', system: 'http://www.nlm.nih.gov/research/umls/rxnorm' }
  else null

define "Drug Interaction":
  ("Medication Code" in { '105585' } and "Medication Statement Code" in { '105611', '197388', '239983' })
  or ("Medication Code" in { '105611', '197388', '239983' } and "Medication Statement Code" in { '105585' })

// ============================================================
// CARD CONTENT
// ============================================================

define "Medication Code Display":
  if "Requested Medication Coding" is not null then CRD."Codes To String"("Requested Medication Coding") else 'unspecified'

define "Get Summary":
  if "Documentation Required" then 'Documentation required for immunosuppressive drugs'
  else 'No documentation required'

define "Get Detail":
  case
    when "Alternative Therapy" is not null then
      'Alternative therapy may be appropriate: ' + "Alternative Therapy".display
    when "Drug Interaction" then
      'Potential interaction detected between requested medication (' + "Medication Code Display" +
      ') and current medications (' + Coalesce(CRD."Codes To String"("Statement Medication Coding"), 'unknown') + ').'
    when not "Has Indication Evidence" then
      'Indication evidence not found for ' + "Medication Code Display" + '. Add diagnosis or transplant context.'
    else
      'Immunosuppressive drug coverage evaluation for ' + "Medication Code Display" + '.'
  end

define "Info Summary":
  case
    when "Alternative Therapy" is not null then 'Document alternative therapy rationale'
    when "Drug Interaction" then 'Review interacting medications and monitoring plan'
    when not "Has Indication Evidence" then 'Add immunosuppressive indication evidence'
    else 'Prepare immunosuppressive therapy documentation'
  end

define "Info Detail":
  case
    when "Alternative Therapy" is not null then
      'Document why ' + "Alternative Therapy".display + ' is not appropriate or has been tried.'
    when "Drug Interaction" then
      'Review interacting medications and document monitoring or mitigation plan.'
    when not "Has Indication Evidence" then
      'Document diagnosis or transplant indication and prior therapy history for ' + "Medication Code Display" + '.'
    else
      'Document diagnosis, prior therapy history, and monitoring plan for ' + "Medication Code Display" + '.'
  end

// ============================================================
// EXTENSION VALUES (complex rule - uses reasons)
// ============================================================

define "Doc Purpose":
  if "Documentation Required" then { 'withorder' } else null as List<String>

define "Questionnaire URLs":
  { 'Questionnaire/ImmunosuppressiveDrugs', 'Questionnaire/ImmunosuppressiveDrugsProgressNote' }

define "Coverage Reason":
  case
    when "Alternative Therapy" is not null then
      { Ext."Reason From Text"('Alternative therapy recommended: ' + "Alternative Therapy".display) }
    when "Drug Interaction" then
      { Ext."Reason From Text"('Drug interaction detected - review required') }
    when not "Has Indication Evidence" then
      { Ext."Reason From Text"('Indication evidence not found for immunosuppressive therapy') }
    when "Documentation Required" then
      { Ext."Reason From Text"('Clinical documentation required for immunosuppressive drug coverage') }
    else
      null as List<FHIR.CodeableConcept>
  end

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
      null as List<String>,
      "Questionnaire URLs",
      "Coverage Reason",
      "Requested Medication Coding"
    )
  else null as FHIR.Extension
