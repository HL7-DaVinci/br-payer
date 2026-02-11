library CardiologyConsultationPrepopulation version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include DTRHelpers version '1.0.0' called DTR

codesystem "SNOMED-CT": 'http://snomed.info/sct'

parameter appointment Appointment

context Patient

// ============================================================
// REFERRAL INFORMATION
// ============================================================

define "Active Referrals":
  [ServiceRequest] SR
    where SR.status.value in {'active', 'completed'}
      and SR.intent.value = 'order'
      and exists(SR.category cat where exists(cat.coding c where c.code.value = 'referral'))

define "ReferralStatus":
  if exists("Active Referrals") then 'active' else 'missing'

define "First Active Referral": First("Active Referrals")

define "ExistingReferralDetails":
  if "First Active Referral" is not null then
    'Referral ' + Coalesce("First Active Referral".status.value, '') +
    ': ' + Coalesce(First("First Active Referral".reasonCode).text.value,
      First(First("First Active Referral".reasonCode).coding).display.value, 'No reason specified')
  else null

// ============================================================
// CARDIAC HISTORY
// ============================================================

define "Cardiac Conditions":
  DTR.ActiveOrRecurring(
    [Condition] C
      where exists(C.code.coding cdc where cdc.system.value = 'http://snomed.info/sct')
  )

define "CardiacConditionDisplay":
  if exists("Cardiac Conditions") then
    Combine(
      "Cardiac Conditions" C
        return Coalesce(First(C.code.coding).display.value, First(C.code.coding).code.value),
      '; '
    )
  else null

define "RecentCardiacHistory":
  DTR.ConvertEncounterDetails(DTR.EncounterLookBack([Encounter], 12 months))

// ============================================================
// PATIENT AND APPOINTMENT
// ============================================================

define "PatientAge": AgeInYears()

define "AppointmentServiceType":
  if appointment is not null and appointment.serviceType is not null then
    Coalesce(
      First(Flatten(appointment.serviceType ST return ST.coding)).display.value,
      First(Flatten(appointment.serviceType ST return ST.coding)).code.value
    )
  else null
