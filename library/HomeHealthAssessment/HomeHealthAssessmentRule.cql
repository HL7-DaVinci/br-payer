library HomeHealthAssessmentRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

define "Assertion Prefix": 'home-health'

define "Coverage": First([Coverage])
define "Service Request": First([ServiceRequest])

define "Requires Coverage Info": "Coverage" is not null and "Service Request" is not null

define "Prior Authorization Required": true

define "Documentation Required": true

define "Covered": 'conditional'

define "Coverage Summary": 'Prior Authorization Required for Home Health Service'

define "Coverage Detail":
  'Home health services require prior authorization and clinical documentation. ' +
  'Complete the adaptive questionnaire to provide required clinical information.'

define "Doc Purpose": { 'withpa' }
define "Info Needed": null as List<String>

define "Questionnaire URLs":
  { 'http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HomeHealthAssessment' }

define "Coverage Reason":
  { Ext."Reason From Text"('Prior authorization required for home health services') }

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
      null
    )
  else null as FHIR.Extension
