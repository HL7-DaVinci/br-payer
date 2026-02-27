library HomeOxygenTherapyRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers

// Helper value sets
valueset "Home Oxygen Therapy Codes": 'http://example.org/fhir/ValueSet/home-oxygen-therapy-codes'


// Input parameters from CDS Hook context
parameter Patient Patient
parameter Coverage Coverage  
parameter DeviceRequest DeviceRequest


// Applicability
define "Rule Applies":
  exists (DeviceRequest.code.coding C where C in "Home Oxygen Therapy Codes")

// Coverage determination
define "Prior Auth Required":
  true

define "Documentation Required":
  true

// Card content
define "Get Summary":
  'Prior Authorization Required for Home Oxygen Therapy'

define "Get Detail":
  'Home oxygen therapy (codes ' + 
  Combine(DeviceRequest.code.coding C return C.code, ', ') + 
  ') requires prior authorization. Please complete the required documentation.'

define "Get Info Link":
  'https://www.cms.gov/medicare-coverage-database/details/ncd-details.aspx?NCDId=169'

define "Get Questionnaire Uri":
  'Questionnaire/HomeOxygenTherapy'

// Coverage Information Extension values
define "Coverage Info Covered":
  'covered'

define "Coverage Info PA Required":
  true

define "Coverage Info Doc Purpose":
  if "Documentation Required" then 'clinical'
  else null