library CareManagementEnrollmentRule version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include CRDHelpers version '1.0.0' called CRD
include CoverageExtensions version '1.0.0' called Ext

context Patient

define "Assertion Prefix": 'care-mgmt'

define "Coverage": First([Coverage])
define "Encounter": First([Encounter])
define "Active Conditions": [Condition] C where C.clinicalStatus.coding[0].code.value = 'active'

define "Encounter Type Coding":
  if "Encounter" is not null and "Encounter".type is not null then
    Flatten("Encounter".type T return T.coding)
  else {} as List<FHIR.Coding>

define "Diabetes Prefixes": {'E10', 'E11', 'E13', '250'}
define "Heart Failure Prefixes": {'I50', '428'}
define "COPD Prefixes": {'J44', '496'}
define "CKD Prefixes": {'N18', '585'}

define function "Code Has Prefix"(code String, prefixes List<String>):
  exists(prefixes P where StartsWith(code, P))

define function "Has Condition With Prefix"(prefixes List<String>):
  exists("Active Conditions" C
    where exists(C.code.coding CC
      where "Code Has Prefix"(CC.code.value, prefixes)))

define "Has Diabetes": "Has Condition With Prefix"("Diabetes Prefixes")
define "Has Heart Failure": "Has Condition With Prefix"("Heart Failure Prefixes")
define "Has COPD": "Has Condition With Prefix"("COPD Prefixes")
define "Has CKD": "Has Condition With Prefix"("CKD Prefixes")

define "Has Qualifying Chronic Condition":
  "Has Diabetes" or "Has Heart Failure" or "Has COPD" or "Has CKD"

define "Qualifying Condition Names":
  Combine(
    Flatten({
      if "Has Diabetes" then {'Diabetes'} else {} as List<String>,
      if "Has Heart Failure" then {'Heart Failure'} else {} as List<String>,
      if "Has COPD" then {'COPD'} else {} as List<String>,
      if "Has CKD" then {'Chronic Kidney Disease'} else {} as List<String>
    }),
    ', '
  )

define "Requires Care Management Notification":
  "Coverage" is not null and "Has Qualifying Chronic Condition"

define "Covered": 'covered'

define "Get Summary":
  'Care management program available for ' + "Qualifying Condition Names"

define "Get Detail":
  'Patient has qualifying chronic conditions (' + "Qualifying Condition Names" + ') for care management enrollment. ' +
  'Care management programs provide care coordination, regular health monitoring, medication management support, ' +
  'and resources to help manage chronic conditions effectively. ' +
  'Contact the payer care management team for enrollment information and available services.'

define "Coverage Info Extension":
  if "Coverage" is not null and "Coverage".id is not null then
    Ext."Build Coverage Info Extension"(
      CRD."Build Coverage Reference"("Coverage"),
      "Covered",
      Today(),
      CRD."Build Assertion ID"("Assertion Prefix", "Coverage"),
      null,
      null as List<String>,
      null as List<String>,
      null as List<String>,
      null as List<String>,
      null as List<FHIR.CodeableConcept>,
      "Encounter Type Coding"
    )
  else null as FHIR.Extension
