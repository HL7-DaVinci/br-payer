library CRDHelpers version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers

// Hook parameter should generally not be used.
// It is put here for future edge cases where CQL truly needs to know the hook.
parameter Hook String

// ============================================================
// HOOK DETECTION HELPERS
// ============================================================

define "Is Order Select":
  Hook = 'order-select'

define "Is Order Sign":
  Hook = 'order-sign'

define "Is Appointment Book":
  Hook = 'appointment-book'

define "Is Encounter Start":
  Hook = 'encounter-start'

define "Is Encounter Discharge":
  Hook = 'encounter-discharge'

// ============================================================
// PATIENT DEMOGRAPHICS
// ============================================================

define function "Is Pediatric"(age Integer):
  age < 18

define function "Is Geriatric"(age Integer):
  age >= 65

define function "Is Adult"(age Integer):
  age >= 18

// ============================================================
// CODE EXTRACTION HELPERS
// ============================================================

define function "Get First Code"(codings List<FHIR.Coding>):
  First(codings).code.value

define function "Get Code Display"(codings List<FHIR.Coding>):
  Coalesce(First(codings).display.value, First(codings).code.value)

define function "Get Code System"(codings List<FHIR.Coding>):
  First(codings).system.value

define function "Has Code In List"(codings List<FHIR.Coding>, codeList List<String>):
  exists(codings C where C.code.value in codeList)

define function "Codes To String"(codings List<FHIR.Coding>):
  Combine(codings C return C.code.value, ', ')

define function "Codes To Display String"(codings List<FHIR.Coding>):
  Combine(codings C return Coalesce(C.display.value, C.code.value), ', ')

// ============================================================
// ORDER RESOURCE HELPERS
// ============================================================

define function "Get Medication Code"(medRequest MedicationRequest):
  if medRequest.medication is CodeableConcept then
    (medRequest.medication as CodeableConcept).coding
  else
    null

define function "Get Device Code"(deviceRequest DeviceRequest):
  if deviceRequest.code is CodeableConcept then
    (deviceRequest.code as CodeableConcept).coding
  else
    null

define function "Get Service Code"(serviceRequest ServiceRequest):
  serviceRequest.code.coding

// ============================================================
// COVERAGE STATUS HELPERS
// ============================================================

define function "Coverage Status Text"(covered String, paNeeded String, docNeeded String):
  case
    when covered = 'not-covered' then 'Not covered by plan'
    when paNeeded = 'auth-needed' then 'Prior authorization required'
    when docNeeded is not null then 'Documentation required'
    else 'Covered'
  end

// ============================================================
// INFO-NEEDED DETECTION
// ============================================================

define function "Missing Performer Info"(order Resource):
  case
    when order is DeviceRequest then (order as DeviceRequest).performer is null
    when order is ServiceRequest then not exists((order as ServiceRequest).performer)
    when order is MedicationRequest then (order as MedicationRequest).performer is null
    else false
  end

define function "Missing Location Info"(encounter Encounter):
  encounter is null or not exists(encounter.location)

define function "Determine Info Needed"(order Resource, encounter Encounter):
  case
    when "Missing Performer Info"(order) then 'performer'
    when "Missing Location Info"(encounter) then 'location'
    else null
  end

// ============================================================
// CARD INDICATOR HELPERS
// ============================================================

define function "Determine Indicator"(covered String, paNeeded String, hasCriticalInteraction Boolean):
  case
    when hasCriticalInteraction then 'critical'
    when covered = 'not-covered' then 'warning'
    when paNeeded = 'auth-needed' then 'warning'
    else 'info'
  end
