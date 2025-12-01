library HospitalBedsAndAccessoriesRule version '1.0.0'
using FHIR version '4.0.0'
include FHIRHelpers version '4.0.0' called FHIRHelpers

context Patient

// Retrieve DeviceRequest from the data bundle
define "Device Requests":
  [DeviceRequest]

define "Device Request":
  First("Device Requests")

define "Device Request Code":
  "Device Request".code as CodeableConcept

define "Debug Has Coding":
  if "Device Request Code" is not null then exists("Device Request Code".coding) else false

define "Device Code Display":
  if "Device Request Code" is not null and exists("Device Request Code".coding) then
    Combine("Device Request Code".coding C return C.code, ', ')
  else
    'unknown'

define "Age":
  AgeInYears()

define "Rule Applies":
  true

define "Prior Authorization Required":
  false

define "Documentation Required":
  "Age" <= 70

define "Info Link":
    'https://www.cms.gov/Outreach-and-Education/Medicare-Learning-Network-MLN/MLNProducts/Downloads/ProviderComplianceTipsforHospitalBedsandAccessories-ICN909476.pdf'

define "Get Summary":
  if "Documentation Required" then
    'Documentation required'
  else
    'No documentation required'

define "Get Detail":
  'Hospital beds and accessories (code ' + "Device Code Display" + 
  ') require documentation for patients age 70 or younger. ' +
  if "Documentation Required" then
      'The patient is ' + ToString("Age") + ' years old.'
  else
      'The patient is ' + ToString("Age") + ' years old, so no documentation is required.'
      
// Whether the service is covered: 'covered' | 'not-covered' | 'conditional'
define "Coverage Covered":
  'covered'

// Whether prior authorization is needed: 'auth-needed' | 'no-auth' | 'satisfied'
define "PA Needed":
  if "Prior Authorization Required" then 'auth-needed' else 'no-auth'

// Whether additional documentation is needed:
// 'clinical' | 'admin' | 'patient' | 'conditional' | 'no-doc'
define "Doc Needed":
  if "Documentation Required" then 'clinical' else 'no-doc'

// Questionnaire URL for DTR launch (if documentation is needed)
define "Questionnaire Order Url":
  if "Documentation Required" then
    'Questionnaire/HospitalBedsAndAccessories'
  else
    ''    

define "Questionnaire Face to Face Url":
  if "Documentation Required" then
    'Questionnaire/HospitalBedsAndAccessoriesFaceToFace'
  else
    ''

// Reason for the coverage determination
define "Coverage Reason":
  if "Documentation Required" then
    'Patient age requires additional documentation for hospital bed coverage'
  else
    'No additional documentation required based on patient age'

// Link to additional information
define "Info Needed":
  "Documentation Required"
