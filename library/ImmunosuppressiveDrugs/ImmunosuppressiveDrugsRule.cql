library ImmunosuppressiveDrugsRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

// ============================================================
// ORDER EXTRACTION
// ============================================================

define "Medication Requests":
  [MedicationRequest]

define "Medication Request":
  First("Medication Requests")

define "Medication Dispenses":
  [MedicationDispense]

define "Medication Dispense":
  First("Medication Dispenses")

define "Medication Statements":
  [MedicationStatement]

define "Medication Statement":
  First("Medication Statements")

define "Requested Medication Coding":
  Coalesce(
    CRD."Get Medication Code"("Medication Request"),
    if "Medication Dispense".medication is CodeableConcept then
      ("Medication Dispense".medication as CodeableConcept).coding
    else null
  )

define "Statement Medication Coding":
  if "Medication Statement".medication is CodeableConcept then
    ("Medication Statement".medication as CodeableConcept).coding
  else null

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
  'immuno-drugs-' + ToString("Assertion Date") + '-' + "Coverage".id.value

// ============================================================
// COVERAGE DETERMINATION LOGIC
// ============================================================

define "Rule Applies":
  true

define "Prior Authorization Required":
  false

define "Documentation Required":
  true

define "Medication Code":
  CRD."Get First Code"("Requested Medication Coding")

define "Medication Statement Code":
  CRD."Get First Code"("Statement Medication Coding")

define "Alternative Therapy":
  if "Medication Code" in { '105611', '239983' }
  then Code { code: '197388', display: 'Azathioprine 50 MG Oral', system: 'http://www.nlm.nih.gov/research/umls/rxnorm' }
  else null

define "Drug Interaction":
  if "Medication Code" in { '105585' }
    and "Medication Statement Code" in { '105611', '197388', '239983' }
    then true
  else if "Medication Code" in { '105611', '197388', '239983' }
    and "Medication Statement Code" in { '105585' }
    then true
  else false

// ============================================================
// CARD CONTENT
// ============================================================

define "Get Summary":
  if "Documentation Required" then
    'Documentation required for immunosuppressive drugs'
  else
    'No documentation required'

define "Get Detail":
  if "Alternative Therapy" is not null then
    'Alternative therapy available: ' + "Alternative Therapy".display
  else if "Drug Interaction" then
    'Drug interaction detected between requested medication (' +
    CRD."Codes To String"("Requested Medication Coding") +
    ') and current medications (' +
    CRD."Codes To String"("Statement Medication Coding") + ').'
  else
    'Immunosuppressive drug coverage evaluation for ' +
    CRD."Codes To String"("Requested Medication Coding") + '.'

// ============================================================
// COVERAGE EXTENSION VALUES
// ============================================================

define "Covered":
  'covered'

define "PA Needed":
  if "Prior Authorization Required" then 'auth-needed' else null

define "Doc Needed":
  if "Documentation Required" then { 'clinical' } else null

// doc-purpose: withpa | withclaim | withorder | retain-doc | OTH
define "Doc Purpose":
  if "Documentation Required" then { 'withorder' } else null

define "Info Needed":
  null as List<String>

define "Questionnaire URLs":
  { 
    'Questionnaire/ImmunosuppressiveDrugs',
    'Questionnaire/ImmunosuppressiveDrugsProgressNote' 
  }

// reason: CodeableConcept (use text-only for custom reasons)
define "Coverage Reason":
  if "Alternative Therapy" is not null then
    { Ext."Reason From Text"('Alternative therapy recommended: ' + "Alternative Therapy".display) }
  else if "Drug Interaction" then
    { Ext."Reason From Text"('Drug interaction detected - review required') }
  else if "Documentation Required" then
    { Ext."Reason From Text"('Clinical documentation required for immunosuppressive drug coverage') }
  else
    { Ext."Reason From Text"('Standard coverage applies') }

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
