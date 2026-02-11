library PhysicalTherapyPrepopulation version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers
include DTRHelpers version '1.0.0' called DTR

codesystem "CPT": 'http://www.ama-assn.org/go/cpt'

code "97110": '97110' from "CPT"
code "97140": '97140' from "CPT"
code "97530": '97530' from "CPT"
code "97112": '97112' from "CPT"
code "97116": '97116' from "CPT"
code "97535": '97535' from "CPT"

parameter appointment Appointment

context Patient

define "PT CPT Codes": { "97110", "97140", "97530", "97112", "97116", "97535" }

define "PlanSessionLimit": 24

// Musculoskeletal conditions from active problem list
define "MusculoskeletalConditions":
  DTR.ActiveOrRecurring([Condition])

define "DiagnosisDisplay":
  if exists("MusculoskeletalConditions") then
    First("MusculoskeletalConditions").code.coding[0].display.value
  else null

// PT procedures completed this calendar year
define function "GetProcedureYear"(proc Procedure):
  case
    when proc.performed is FHIR.dateTime then
      year from FHIRHelpers.ToDateTime(proc.performed as FHIR.dateTime)
    when proc.performed is FHIR.Period then
      year from FHIRHelpers.ToDateTime((proc.performed as FHIR.Period).start)
    else null
  end

define "PTProceduresThisYear":
  [Procedure] P
    where P.status.value = 'completed'
      and P.performed is not null
      and "GetProcedureYear"(P) = year from Today()
      and P.code is not null
      and exists(P.code.coding c
        where c.system.value = 'http://www.ama-assn.org/go/cpt'
          and c.code.value in {'97110', '97140', '97530', '97112', '97116', '97535'})

define "SessionsUsedThisYear": Count("PTProceduresThisYear")

define "SessionsRemaining": "PlanSessionLimit" - "SessionsUsedThisYear"

// Format PT session history for display
define "PTHistoryFormatted":
  "PTProceduresThisYear" P
    return
      Coalesce(First(P.code.coding c where c.system.value = 'http://www.ama-assn.org/go/cpt').display.value, '') +
      ' (CPT ' + Coalesce(First(P.code.coding c where c.system.value = 'http://www.ama-assn.org/go/cpt').code.value, '') + ')' +
      ' - ' + ToString(
        case
          when P.performed is FHIR.dateTime then FHIRHelpers.ToDateTime(P.performed as FHIR.dateTime)
          when P.performed is FHIR.Period then FHIRHelpers.ToDateTime((P.performed as FHIR.Period).start)
          else null
        end
      )

// Service type from the triggering appointment
define "AppointmentServiceType":
  if appointment is not null and appointment.serviceType is not null then
    First(Flatten(appointment.serviceType ST return ST.coding)).display.value
  else null
