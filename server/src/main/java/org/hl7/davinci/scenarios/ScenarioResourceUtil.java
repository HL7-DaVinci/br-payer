package org.hl7.davinci.scenarios;

import java.util.Date;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * Shared utilities for scenario generation used by both CRD and DTR request
 * builders. Contains order resource construction and description generation
 * that are not specific to either builder.
 */
public final class ScenarioResourceUtil {

  private ScenarioResourceUtil() {}

  private static final String CRD_PATIENT_PROFILE =
      "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/profile-patient";
  private static final String CRD_PRACTITIONER_PROFILE =
      "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/profile-practitioner";

  /** Build a synthetic order resource (DeviceRequest, MedicationRequest, ServiceRequest, or Appointment). */
  public static Resource buildOrderResource(Coding firstCode, String orderType, String scenarioId) {
    Coding codeCopy = firstCode.copy();
    // Displays from source metadata can drift from terminology package displays.
    codeCopy.setDisplay(null);

    switch (orderType) {
      case "DeviceRequest" -> {
        DeviceRequest dr = new DeviceRequest();
        dr.setId(scenarioId + "-device-request");
        dr.setStatus(DeviceRequest.DeviceRequestStatus.DRAFT);
        dr.setIntent(DeviceRequest.RequestIntent.ORIGINALORDER);
        dr.setCode(new CodeableConcept().addCoding(codeCopy));
        dr.setSubject(new Reference("Patient/example"));
        dr.setAuthoredOn(new Date());
        dr.setRequester(new Reference("Practitioner/example"));
        dr.addInsurance(new Reference("Coverage/coverage-1"));
        return dr;
      }
      case "MedicationRequest" -> {
        MedicationRequest mr = new MedicationRequest();
        mr.setId(scenarioId + "-medication-request");
        mr.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        mr.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
        mr.setMedication(new CodeableConcept().addCoding(codeCopy));
        mr.setSubject(new Reference("Patient/example"));
        mr.setAuthoredOn(new Date());
        mr.setRequester(new Reference("Practitioner/example"));
        mr.addInsurance(new Reference("Coverage/coverage-1"));
        return mr;
      }
      case "ServiceRequest" -> {
        ServiceRequest sr = new ServiceRequest();
        sr.setId(scenarioId + "-service-request");
        sr.setStatus(ServiceRequest.ServiceRequestStatus.DRAFT);
        sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        sr.setCode(new CodeableConcept().addCoding(codeCopy));
        sr.setSubject(new Reference("Patient/example"));
        sr.setAuthoredOn(new Date());
        sr.setRequester(new Reference("Practitioner/example"));
        sr.addInsurance(new Reference("Coverage/coverage-1"));
        return sr;
      }
      case "Appointment" -> {
        Date start = new Date();
        Date end = new Date(start.getTime() + 30L * 60L * 1000L);

        Patient patient = new Patient();
        patient.setId("appointment-patient");
        patient.getMeta().addProfile(CRD_PATIENT_PROFILE);
        patient.addIdentifier()
            .setSystem("http://example.org/mrn")
            .setValue("MRN-" + scenarioId);
        patient.addName().setFamily("Example").addGiven("Pat");
        patient.addTelecom()
            .setSystem(ContactPoint.ContactPointSystem.PHONE)
            .setValue("555-0101");
        patient.setGender(Enumerations.AdministrativeGender.UNKNOWN);
        patient.addCommunication()
            .getLanguage()
            .addCoding(new Coding("urn:ietf:bcp:47", "en", "English"));
        patient.addLink()
            .setOther(new Reference("#appointment-patient"))
            .setType(Patient.LinkType.SEEALSO);

        Practitioner practitioner = new Practitioner();
        practitioner.setId("appointment-practitioner");
        practitioner.getMeta().addProfile(CRD_PRACTITIONER_PROFILE);
        practitioner.addIdentifier()
            .setSystem("http://hl7.org/fhir/sid/us-npi")
            .setValue("1234567893");
        practitioner.addName().setFamily("Provider").addGiven("Primary");
        practitioner.addQualification()
            .getCode()
            .addCoding(new Coding(
                "http://terminology.hl7.org/CodeSystem/v2-0360",
                "MD",
                "Doctor of Medicine"));

        Appointment appt = new Appointment();
        appt.setId(scenarioId + "-appointment");
        appt.setStatus(Appointment.AppointmentStatus.PROPOSED);
        appt.setStart(start);
        appt.setEnd(end);
        appt.addServiceType(new CodeableConcept().addCoding(codeCopy));
        appt.addContained(patient);
        appt.addContained(practitioner);
        appt.addParticipant()
            .setActor(new Reference("#appointment-patient"))
            .setStatus(Appointment.ParticipationStatus.ACCEPTED);
        appt.addParticipant()
            .addType(new CodeableConcept().addCoding(new Coding(
                "http://terminology.hl7.org/CodeSystem/v3-ParticipationType", "PPRF", null)))
            .setActor(new Reference("#appointment-practitioner"))
            .setStatus(Appointment.ParticipationStatus.ACCEPTED);
        return appt;
      }
      default -> {
        return null;
      }
    }
  }

  /** Generate a human-readable description from scenario metadata. */
  static String buildDescription(ScenarioMetadata meta) {
    if (meta.description() != null) {
      return meta.description();
    }

    StringBuilder sb = new StringBuilder();
    if (!meta.focusCodes().isEmpty()) {
      Coding first = meta.focusCodes().get(0);
      if (first.hasDisplay()) {
        sb.append(first.getDisplay());
      }
      if (first.hasCode()) {
        sb.append(" (").append(first.getCode()).append(")");
      }
      sb.append(". ");
    }

    if (meta.isAdaptive()) {
      sb.append("Adaptive questionnaire using $next-question.");
    } else if (meta.orderType() != null) {
      sb.append(meta.orderType()).append("-based questionnaire resolution.");
    }

    return sb.toString().trim();
  }
}
