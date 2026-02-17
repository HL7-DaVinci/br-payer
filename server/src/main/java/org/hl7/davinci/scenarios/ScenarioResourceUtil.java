package org.hl7.davinci.scenarios;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Shared utilities for scenario generation used by both CRD and DTR request
 * builders. Contains order resource construction and description generation
 * that are not specific to either builder.
 */
public final class ScenarioResourceUtil {

  private ScenarioResourceUtil() {}

  /** Build a synthetic order resource (DeviceRequest, MedicationRequest, or Appointment). */
  public static Resource buildOrderResource(Coding firstCode, String orderType, String scenarioId) {
    Coding codeCopy = firstCode.copy();

    switch (orderType) {
      case "DeviceRequest" -> {
        DeviceRequest dr = new DeviceRequest();
        dr.setId(scenarioId + "-device-request");
        dr.setStatus(DeviceRequest.DeviceRequestStatus.DRAFT);
        dr.setIntent(DeviceRequest.RequestIntent.ORIGINALORDER);
        dr.setCode(new CodeableConcept().addCoding(codeCopy));
        dr.setSubject(new Reference("Patient/example"));
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
        mr.addInsurance(new Reference("Coverage/coverage-1"));
        return mr;
      }
      case "Appointment" -> {
        Appointment appt = new Appointment();
        appt.setId(scenarioId + "-appointment");
        appt.setStatus(Appointment.AppointmentStatus.PROPOSED);
        appt.addServiceType(new CodeableConcept().addCoding(codeCopy));
        appt.addParticipant()
            .setActor(new Reference("Patient/example"))
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
