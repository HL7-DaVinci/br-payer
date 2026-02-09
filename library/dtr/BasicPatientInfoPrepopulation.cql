library BasicPatientInfoPrepopulation version '1.0.0'
using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1' called FHIRHelpers

context Patient

define "LastName": Patient.name[0].family
define "FirstName": Patient.name[0].given[0]
define "MiddleInitial": Substring(Patient.name[0].given[1], 0, 1)
define "DateOfBirth": Patient.birthDate
define "Gender": Patient.gender
define "HomeAddress": singleton from (Patient.address A where A.use = 'home')
define "Phone": singleton from (Patient.telecom T where T.system = 'phone')
