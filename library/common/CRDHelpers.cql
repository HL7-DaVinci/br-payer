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

// ============================================================
// COVERAGE EXTENSION HELPERS (null-safe)
// ============================================================

define function "Build Coverage Reference"(coverage Coverage):
  if coverage is not null and coverage.id is not null then
    FHIR.Reference {
      reference: FHIR.string { value: 'Coverage/' + coverage.id.value }
    }
  else null as FHIR.Reference

define function "Build Assertion ID"(prefix String, coverage Coverage):
  if coverage is not null and coverage.id is not null then
    prefix + '-' + ToString(Today()) + '-' + coverage.id.value
  else
    prefix + '-' + ToString(Today()) + '-unknown'

define function "PA Needed Value"(requiresPA Boolean):
  if requiresPA = true then 'auth-needed' else null

define function "Doc Needed Value"(requiresDoc Boolean, docType String):
  if requiresDoc = true then { docType } else null as List<String>

define function "Questionnaire URLs Value"(condition Boolean, urls List<String>):
  if condition = true then urls else null as List<String>

// ============================================================
// CONSTRAINT HELPERS (crd-ci-q1 through crd-ci-q6)
// ============================================================

// crd-ci-q3: info-needed SHALL exist iff covered, pa-needed, or doc-needed = 'conditional'
define function "Info Needed Required"(covered String, paNeeded String, docNeeded List<String>):
  covered = 'conditional'
    or paNeeded = 'conditional'
    or exists(docNeeded D where D = 'conditional')

// crd-ci-q1: questionnaire requires doc-needed to exist
define function "Questionnaire Allowed"(docNeeded List<String>):
  docNeeded is not null and exists(docNeeded)

// crd-ci-q2: if covered='not-covered', pa-needed must not exist
define function "PA Needed Allowed"(covered String):
  covered != 'not-covered'

// crd-ci-q4: if pa-needed in ('satisfied', 'noauth'), doc-purpose cannot be 'withpa'
define function "Filter Valid Doc Purpose"(paNeeded String, purposes List<String>):
  if purposes is null then null as List<String>
  else if paNeeded in {'satisfied', 'noauth'} then
    purposes P where P != 'withpa'
  else purposes

// crd-ci-q6: if info-needed='OTH', reason must exist - provide default reason
define function "Conditional Status Reason"():
  FHIR.CodeableConcept {
    text: FHIR.string { value: 'Additional information required to determine coverage' }
  }

// ============================================================
// CONSTRAINT VALIDATION WRAPPERS
// ============================================================

// crd-ci-q2: returns paNeeded if allowed, else null
define function "Validate PA Needed"(covered String, paNeeded String):
  if "PA Needed Allowed"(covered) then paNeeded else null

// crd-ci-q1: returns questionnaire URLs only if doc-needed exists
define function "Validate Questionnaires"(docNeeded List<String>, urls List<String>):
  if "Questionnaire Allowed"(docNeeded) then urls else null as List<String>

// crd-ci-q3: returns infoNeeded, or {'OTH'} if conditional status requires it
define function "Validate Info Needed"(covered String, paNeeded String, docNeeded List<String>, infoNeeded List<String>):
  if "Info Needed Required"(covered, paNeeded, docNeeded) and (infoNeeded is null or not exists(infoNeeded)) then
    {'OTH'}
  else
    infoNeeded

// crd-ci-q6: returns reasons, or adds default reason if OTH is in info-needed
define function "Validate Reasons For Info Needed"(infoNeeded List<String>, reasons List<FHIR.CodeableConcept>):
  if exists(infoNeeded I where I = 'OTH') and (reasons is null or not exists(reasons)) then
    { "Conditional Status Reason"() }
  else
    reasons

// crd-ci-q5: returns satisfiedPaId only if paNeeded='satisfied'
define function "Validate Satisfied PA ID"(paNeeded String, satisfiedPaId String):
  if paNeeded = 'satisfied' then satisfiedPaId else null
