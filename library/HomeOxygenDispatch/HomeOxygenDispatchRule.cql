library HomeOxygenDispatchRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

define "Assertion Prefix": 'oxygen-dispatch'

// Contracted DME supplier NPIs (simulated payer data)
define "Contracted Supplier NPIs": { '1112223334', '4445556667', '7778889990' }

define "Coverage": First([Coverage])
define "Device Request": First([DeviceRequest])
define "DME Supplier": First([Organization])

define "Device Coding":
  if "Device Request" is not null and "Device Request".code is CodeableConcept then
    ("Device Request".code as CodeableConcept).coding
  else
    {} as List<FHIR.Coding>

define "Order Code":
  if exists("Device Coding") then CRD."Get First Code"("Device Coding") else null

define "Supplier NPI":
  if "DME Supplier" is not null then
    First(
      "DME Supplier".identifier I
      where I.system.value = 'http://hl7.org/fhir/sid/us-npi'
      return I.value.value
    )
  else null

define "Is Contracted Supplier":
  "Supplier NPI" is not null and "Supplier NPI" in "Contracted Supplier NPIs"

define "Is Non Contracted Supplier":
  "Supplier NPI" is not null and not ("Supplier NPI" in "Contracted Supplier NPIs")

define "Supplier Status Unknown":
  "Supplier NPI" is null

define "Prior Authorization Required":
  "Is Non Contracted Supplier"

define "Documentation Required": true

define "Requires Coverage Determination":
  "Coverage" is not null

define "Device Code Display":
  if exists("Device Coding") then CRD."Codes To String"("Device Coding") else 'unspecified'

define "Supplier Name":
  if "DME Supplier" is not null and "DME Supplier".name is not null then
    "DME Supplier".name.value
  else 'Unknown DME Supplier'

define "Get Summary":
  case
    when "Is Contracted Supplier" then 'Documentation Required - Contracted Supplier'
    when "Is Non Contracted Supplier" then 'Prior Auth + Documentation Required - Non-Contracted'
    when "Supplier Status Unknown" then 'Conditional - Supplier verification needed'
    else 'Documentation Required'
  end

define "Get Detail":
  'Home oxygen therapy (' + "Device Code Display" + ') dispatched to ' + "Supplier Name" + '. ' +
  case
    when "Is Contracted Supplier" then
      'Supplier is contracted. Qualifying diagnosis documentation required (arterial blood gas or pulse oximetry).'
    when "Is Non Contracted Supplier" then
      'Selected supplier is not contracted. Prior authorization required in addition to clinical documentation. ' +
      'Consider contracted suppliers for faster processing.'
    when "Supplier Status Unknown" then
      'Unable to verify supplier contract status. Contact payer before proceeding.'
    else 'Standard documentation requirements apply.'
  end

define "Info Summary":
  case
    when "Is Contracted Supplier" then 'Confirm qualifying oxygen documentation'
    when "Is Non Contracted Supplier" then 'Verify supplier network status and documentation'
    when "Supplier Status Unknown" then 'Verify supplier status before dispatch'
    else 'Confirm qualifying oxygen documentation'
  end

define "Info Detail":
  'Home oxygen dispatch (' + "Device Code Display" + ') to ' + "Supplier Name" + '. ' +
  case
    when "Is Contracted Supplier" then
      'Ensure qualifying test results (ABG or pulse oximetry) and face-to-face notes are documented.'
    when "Is Non Contracted Supplier" then
      'Supplier is not in the contracted list. Verify network status with the payer and document qualifying test results.'
    when "Supplier Status Unknown" then
      'Supplier contract status is unknown. Verify NPI and contract status with the payer and document qualifying test results.'
    else 'Ensure qualifying test results (ABG or pulse oximetry) and face-to-face notes are documented.'
  end

define "Covered Status":
  case
    when "Is Contracted Supplier" then 'covered'
    when "Is Non Contracted Supplier" then 'conditional'
    when "Supplier Status Unknown" then 'conditional'
    else 'covered'
  end

define "Info Needed List":
  if "Supplier Status Unknown" then { 'performer' }
  else null as List<String>

define "Reason List":
  if "Is Non Contracted Supplier" then { Ext."Auth Out Network Reason"() }
  else null as List<FHIR.CodeableConcept>

define "Doc Purpose":
  if "Documentation Required" then
    (if "Prior Authorization Required" then { 'withpa' } else { 'withorder' })
  else null as List<String>

define "Questionnaire URLs":
  CRD."Questionnaire URLs Value"("Documentation Required",
    { 'http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HomeOxygenDispatch' })

define "Coverage Info Extension":
  if "Coverage" is not null and "Coverage".id is not null then
    Ext."Build Coverage Info Extension"(
      CRD."Build Coverage Reference"("Coverage"),
      "Covered Status",
      Today(),
      CRD."Build Assertion ID"("Assertion Prefix", "Coverage"),
      CRD."PA Needed Value"("Prior Authorization Required"),
      CRD."Doc Needed Value"("Documentation Required", 'clinical'),
      "Doc Purpose",
      "Info Needed List",
      "Questionnaire URLs",
      "Reason List",
      "Device Coding"
    )
  else null as FHIR.Extension
