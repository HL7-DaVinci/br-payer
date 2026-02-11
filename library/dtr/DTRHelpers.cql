library DTRHelpers version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers

context Patient


define function PeriodToInterval(period FHIR.Period):
  if period is null then
    null
  else
    Interval[period."start".value, period."end".value]

define function Verified(ObsList List<Observation>):
  ObsList O where O.status.value in {'final', 'corrected', 'amended'}

define function WithUnit(ObsList List<Observation>, Unit String):
  ObsList O
    where (O.value as FHIR.Quantity).unit.value = Unit or (O.value as FHIR.Quantity).code.value = Unit

define function ObservationLookBack(ObsList List<Observation>, LookBack System.Quantity):
  ObsList O
    let LookBackInterval: Interval[Now() - LookBack, Now()]
    where (O.effective as FHIR.dateTime).value in LookBackInterval
      or (O.effective as FHIR.instant).value in LookBackInterval
      or PeriodToInterval(O.effective as FHIR.Period) overlaps LookBackInterval
      or O.issued.value in LookBackInterval

define function HighestObservation(ObsList List<Observation>):
  Max(ObsList O return FHIRHelpers.ToQuantity(O.value as FHIR.Quantity))

// ============================================================
// CONDITIONS
// ============================================================

define function CodesFromConditions(CondList List<Condition>):
  distinct(flatten(
    CondList C
      let DiagnosesCodings:
          (C.code.coding) CODING where CODING.system.value in {
            'http://hl7.org/fhir/sid/icd-10',
            'http://hl7.org/fhir/sid/icd-10-cm',
            'http://snomed.info/sct'
          }
          return FHIRHelpers.ToCode(CODING)
      return DiagnosesCodings
  ))

define function FirstDateConditionRecorded(ConditionList List<FHIR.Condition>):
  First(ConditionList C sort by FHIRHelpers."ToDateTime"(recordedDate))

// ============================================================
// OBSERVATIONS
// ============================================================

define function LowestObservation(ObsList List<Observation>):
  Min(ObsList O return FHIRHelpers.ToQuantity(O.value as Quantity))

define function GetObservationValue(Obs Observation):
  NullSafeToQuantityWithoutUnit(cast Obs.value as Quantity)

define function NullSafeToQuantityWithoutUnit(Qty FHIR.Quantity):
  if Qty is not null then Qty.value.value
  else null

// ============================================================
// ENCOUNTERS
// ============================================================

define function EncounterLookBack(EncounterList List<Encounter>, LookBack System.Quantity):
  EncounterList E
    let LookBackInterval: Interval[Now() - LookBack, Now()]
    where (cast E.period.start as dateTime).value in LookBackInterval
      or PeriodToInterval(cast E.period as Period) overlaps LookBackInterval

define function ConvertEncounterDetails(EncounterList List<Encounter>):
  distinct(
    flatten(
      EncounterList E
        let EncounterCodings:
          (E.type.coding) CODING
          return Tuple {
            code: CODING.code.value,
            system: CODING.system.value,
            display: CODING.display.value,
            periodStart: E.period.start.value,
            encounterReason: E.reasonCode[0].coding.display.value,
            type: 'encounter'
          }
        return EncounterCodings
    )
  )

// ============================================================
// PROCEDURES
// ============================================================

define function ProcedureCoding(ProcedureList List<FHIR.Procedure>):
  distinct(
    flatten(
      ProcedureList P
        let DiagnosesCodings:
          (P.code.coding) CODING
          return Tuple {
            code: CODING.code.value,
            system: CODING.system.value,
            display: CODING.display.value
          }
        return DiagnosesCodings
    )
  )

define function CodesFromProcedures(ProcedureList List<FHIR.Procedure>):
  distinct(flatten(
    ProcedureList P
      let DiagnosesCodings:
          (P.code.coding) CODING where CODING.system.value in {
            'http://hl7.org/fhir/sid/icd-10',
            'http://hl7.org/fhir/sid/icd-10-cm',
            'http://snomed.info/sct'
          }
          return FHIRHelpers.ToCode(CODING)
      return DiagnosesCodings
  ))

// ============================================================
// OBSERVATION SORTING
// ============================================================

define function MostRecent(ObsList List<Observation>):
  Last(ObsList O sort by Coalesce(
    (effective as FHIR.dateTime).value,
    (effective as FHIR.instant).value,
    (effective as FHIR.Period)."end".value,
    issued.value
  ))

define function ObservationValueAsString(Obs Observation):
  if Obs is null then null
  else if Obs.value is FHIR.Quantity then
    ToString((Obs.value as FHIR.Quantity).value.value) + ' ' + Coalesce((Obs.value as FHIR.Quantity).unit.value, '')
  else if Obs.value is FHIR.string then
    (Obs.value as FHIR.string).value
  else if Obs.value is FHIR.CodeableConcept then
    First((Obs.value as FHIR.CodeableConcept).coding).display.value
  else null

// ============================================================
// CONDITION FILTERING
// ============================================================

define function ActiveOrRecurring(CondList List<Condition>):
  CondList C
    where C.clinicalStatus is not null
      and exists(C.clinicalStatus.coding cs
        where cs.code.value in { 'active', 'recurrence', 'relapse' })

define function ConditionLookBack(CondList List<Condition>, LookBack System.Quantity):
  CondList C
    let LookBackInterval: Interval[Now() - LookBack, Now()]
    where (C.onset as FHIR.dateTime).value in LookBackInterval
      or PeriodToInterval(C.onset as FHIR.Period) overlaps LookBackInterval
      or (C.recordedDate).value in LookBackInterval

// ============================================================
// MEDICATION HELPERS
// ============================================================

define function MedicationStatementLookBack(MedList List<MedicationStatement>, LookBack System.Quantity):
  MedList M
    let LookBackInterval: Interval[Now() - LookBack, Now()]
    where (M.effective as FHIR.dateTime).value in LookBackInterval
      or PeriodToInterval(M.effective as FHIR.Period) overlaps LookBackInterval

define function ActiveMedicationStatements(MedList List<MedicationStatement>):
  MedList M where M.status.value in { 'active', 'intended' }

define function MedicationRequestCoding(MedReq MedicationRequest):
  if MedReq is not null and MedReq.medication is FHIR.CodeableConcept then
    (MedReq.medication as FHIR.CodeableConcept).coding
  else null
