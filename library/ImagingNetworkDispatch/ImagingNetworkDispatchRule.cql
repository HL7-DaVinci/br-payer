library ImagingNetworkDispatchRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

define "Assertion Prefix": 'imaging-dispatch'

// In-network imaging facility NPIs (simulated payer data)
define "In Network Facility NPIs": { '5556667778', '9998887776', '1231231234' }

define "Coverage": First([Coverage])
define "Service Request": First([ServiceRequest])
define "Performing Organization": First([Organization])

define "Service Coding":
  if "Service Request" is not null and "Service Request".code is not null then
    "Service Request".code.coding
  else
    {} as List<FHIR.Coding>

define "Order Code":
  if exists("Service Coding") then CRD."Get First Code"("Service Coding") else null

define "Facility NPI":
  if "Performing Organization" is not null then
    First(
      "Performing Organization".identifier I
      where I.system.value = 'http://hl7.org/fhir/sid/us-npi'
      return I.value.value
    )
  else null

define "Is In Network":
  "Facility NPI" is not null and "Facility NPI" in "In Network Facility NPIs"

define "Is Out Of Network":
  "Facility NPI" is not null and not ("Facility NPI" in "In Network Facility NPIs")

define "Network Status Unknown":
  "Facility NPI" is null

define "Prior Authorization Required":
  "Is Out Of Network"

define "Documentation Required": false

define "Requires Coverage Determination":
  "Coverage" is not null

define "Service Code Display":
  if exists("Service Coding") then CRD."Codes To String"("Service Coding") else 'unspecified'

define "Facility Name":
  if "Performing Organization" is not null and "Performing Organization".name is not null then
    "Performing Organization".name.value
  else 'Unknown Imaging Facility'

define "Get Summary":
  case
    when "Is In Network" then 'Covered - In-Network Facility'
    when "Is Out Of Network" then 'Prior Authorization Required - Out-of-Network'
    when "Network Status Unknown" then 'Conditional - Unable to verify network status'
    else 'Covered'
  end

define "Get Detail":
  'Imaging service (' + "Service Code Display" + ') dispatched to ' + "Facility Name" + '. ' +
  case
    when "Is In Network" then 'Facility is in-network. Standard coverage applies.'
    when "Is Out Of Network" then
      'Selected facility is out-of-network. Prior authorization required. Consider in-network alternatives.'
    when "Network Status Unknown" then
      'Unable to verify facility network status. Contact payer for coverage confirmation.'
    else 'Standard coverage applies.'
  end

define "Info Summary":
  case
    when "Is In Network" then 'Confirm in-network imaging facility details'
    when "Is Out Of Network" then 'Document rationale for out-of-network imaging'
    when "Network Status Unknown" then 'Verify imaging facility network status'
    else 'Confirm imaging facility details'
  end

define "Info Detail":
  'Imaging dispatch (' + "Service Code Display" + ') to ' + "Facility Name" + '. ' +
  case
    when "Is In Network" then
      'Verify facility NPI and location details and document the clinical indication.'
    when "Is Out Of Network" then
      'Facility appears out-of-network. Document the clinical rationale and any network exception steps required by the payer.'
    when "Network Status Unknown" then
      'Facility network status is unknown. Verify NPI and network participation with the payer before proceeding.'
    else 'Document the clinical indication and verify facility details.'
  end

define "Covered Status":
  case
    when "Is In Network" then 'covered'
    when "Is Out Of Network" then 'conditional'
    when "Network Status Unknown" then 'conditional'
    else 'covered'
  end

define "Info Needed List":
  if "Network Status Unknown" then { 'performer' }
  else null as List<String>

define "Reason List":
  if "Is Out Of Network" then { Ext."Auth Out Network Reason"() }
  else null as List<FHIR.CodeableConcept>

define "Coverage Info Extension":
  if "Coverage" is not null and "Coverage".id is not null then
    Ext."Build Coverage Info Extension"(
      CRD."Build Coverage Reference"("Coverage"),
      "Covered Status",
      Today(),
      CRD."Build Assertion ID"("Assertion Prefix", "Coverage"),
      CRD."PA Needed Value"("Prior Authorization Required"),
      CRD."Doc Needed Value"("Documentation Required", null),
      null as List<String>,
      "Info Needed List",
      null as List<String>,
      "Reason List",
      "Service Coding"
    )
  else null as FHIR.Extension
