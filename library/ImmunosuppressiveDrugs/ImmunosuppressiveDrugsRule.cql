library ImmunosuppressiveDrugsRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers

parameter medication_request MedicationRequest
parameter medication_dispense MedicationDispense
parameter medication_statement MedicationStatement

context Patient

define "Rule Applies":
    true

define "Prior Authorization Required":
  false

define "Documentation Required":
  true

define "Info Link":
    'https://www.cms.gov/Outreach-and-Education/Medicare-Learning-Network-MLN/MLNProducts/Downloads/ProviderComplianceTipsforHospitalBedsandAccessories-ICN909476.pdf'

define "Questionnaire Order Url":
    'Questionnaire/ImmunosuppressiveDrugs'

define "Questionnaire Dispense Url":
    'Questionnaire/ImmunosuppressiveDrugs'

define "Questionnaire Progress Note Url":
    'Questionnaire/ImmunosuppressiveDrugsProgressNote'

define "Get Summary":
  if "Documentation Required" then
    'Documentation required for immunosuppressive drugs'
  else
    'No documentation required'

define "Get Detail":
  if "Alternative Therapy" is not null then
    'Alternative therapy available: ' + "Alternative Therapy".display
  else if "Drug Interaction" then
    'Drug interaction detected between requested medication and current medications'
  else
    'Immunosuppressive drug coverage evaluation'

define "Covered":
  'covered'

define "PA Needed":
  if "Prior Authorization Required" then 'auth-needed' else 'no-auth'

define "Doc Needed":
  if "Documentation Required" then 'clinical' else null

define "Coverage Reason":
  if "Alternative Therapy" is not null then
    'Alternative therapy recommended: ' + "Alternative Therapy".display
  else if "Drug Interaction" then
    'Drug interaction detected - review required'
  else if "Documentation Required" then
    'Clinical documentation required for immunosuppressive drug coverage'
  else
    'Standard coverage applies'

define "Questionnaires":
  {
    FHIR.Extension {
      url: FHIR.uri { value: 'questionnaire' },
      value: FHIR.canonical { value: "Questionnaire Order Url" }
    },
    FHIR.Extension {
      url: FHIR.uri { value: 'questionnaire' },
      value: FHIR.canonical { value: "Questionnaire Progress Note Url" }
    }
  }

define "Coverage Info Inner Extensions":
  Flatten({
    {
      FHIR.Extension {
        url: FHIR.uri { value: 'covered' },
        value: FHIR.code { value: "Covered" }
      },
      FHIR.Extension {
        url: FHIR.uri { value: 'pa-needed' },
        value: FHIR.code { value: "PA Needed" }
      }
    },
    if "Doc Needed" is not null then
      {
        FHIR.Extension {
          url: FHIR.uri { value: 'doc-needed' },
          value: FHIR.code { value: "Doc Needed" }
        }
      }
    else {}
    ,
    "Questionnaires",
    {
      FHIR.Extension {
        url: FHIR.uri { value: 'reason' },
        value: FHIR.CodeableConcept { text: FHIR.string { value: "Coverage Reason" } }
      }
    }
  })

define "Coverage Info Extension":
  FHIR.Extension {
    url: FHIR.uri { value: 'http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information' },
    extension: "Coverage Info Inner Extensions"
  }

define "Requested Medication":
  medication_request

define "Dispensed Medication":
  medication_dispense

define "Medication Coding From Parameter":
  Coalesce(medication_request.medication.coding, medication_dispense.medication.coding)

define "Medication Statement Coding From Parameter":
  medication_statement.medication.coding

define "Medication Code":
  First("Medication Coding From Parameter").code.value

define "Medication Statement Code":
  First("Medication Statement Coding From Parameter").code.value
  
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

define "Requested Drug Code":
  "Medication Coding From Parameter"

define "Statement Drug Code":
  "Medication Statement Coding From Parameter"
