package org.hl7.davinci.scenarios;

import java.util.ArrayList;
import java.util.List;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Builds DTR $questionnaire-package request Parameters from ScenarioMetadata.
 * Produces canonical, order, and combined variants per scenario.
 * Pure FHIR model logic with no Spring dependencies.
 */
public class DtrRequestBuilder {

  private static final String DTR_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-qpackage-input-parameters";
  private static final String DTR_Q_PREFIX =
      "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/";

  private DtrRequestBuilder() {}

  /** Build DTR scenarios with FHIR Parameters for each variant. */
  public static List<DtrScenario> build(List<ScenarioMetadata> metadataList) {
    Coverage sharedCoverage = buildSharedCoverage();
    List<DtrScenario> result = new ArrayList<>();

    for (ScenarioMetadata meta : metadataList) {
      List<DtrVariant> variants = new ArrayList<>();
      boolean hasFocusCodes = !meta.focusCodes().isEmpty();
      boolean hasOrderType = meta.orderType() != null;

      // Canonical variant for each questionnaire URL
      for (String url : meta.questionnaireUrls()) {
        String qKebab = questionnaireIdFromUrl(url);
        variants.add(new DtrVariant(
            qKebab + "-canonical", "Canonical", "canonical",
            buildCanonicalParams(url, sharedCoverage)));
      }

      // Order and combined variants when focus codes and order type are available
      if (hasFocusCodes && hasOrderType) {
        Resource orderResource = buildOrderResource(
            meta.focusCodes().get(0), meta.orderType(), meta.id());

        if (orderResource != null) {
          variants.add(new DtrVariant(
              meta.id() + "-order", "Order", "order",
              buildOrderParams(orderResource, sharedCoverage)));

          for (String url : meta.questionnaireUrls()) {
            String qKebab = questionnaireIdFromUrl(url);
            variants.add(new DtrVariant(
                qKebab + "-combined", "Combined", "combined",
                buildCombinedParams(url, orderResource, sharedCoverage)));
          }
        }
      }

      String description = buildDescription(meta);

      result.add(new DtrScenario(
          meta.id(),
          meta.name(),
          description,
          hasOrderType ? meta.orderType() : "Unknown",
          meta.isAdaptive(),
          variants));
    }

    return result;
  }

  // ===== Coverage construction =====

  public static Coverage buildSharedCoverage() {
    Coverage coverage = new Coverage();
    coverage.setId("coverage-1");
    coverage.getMeta().addProfile(
        "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/profile-coverage");

    Organization payorOrg = new Organization();
    payorOrg.setId("payor-org");
    payorOrg.addIdentifier()
        .setSystem("urn:oid:2.16.840.1.113883.6.300").setValue("00001");
    payorOrg.setActive(true);
    payorOrg.addType().addCoding()
        .setSystem("http://terminology.hl7.org/CodeSystem/organization-type")
        .setCode("pay").setDisplay("Payer");
    payorOrg.setName("Centers for Medicare and Medicaid Services");
    coverage.addContained(payorOrg);

    coverage.setStatus(Coverage.CoverageStatus.ACTIVE);
    coverage.setSubscriberId("10A3D58WH456");
    coverage.setBeneficiary(new Reference("Patient/example"));
    coverage.getRelationship().addCoding()
        .setSystem("http://terminology.hl7.org/CodeSystem/subscriber-relationship")
        .setCode("self").setDisplay("Self");
    coverage.getPeriod().setStartElement(new DateTimeType("2025-01-01"));
    coverage.getPeriod().setEndElement(new DateTimeType("2026-12-31"));
    coverage.addPayor(new Reference("#payor-org"));

    return coverage;
  }

  // ===== Order resource construction =====

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

  // ===== Parameters construction =====

  static Parameters buildCanonicalParams(String canonical, Coverage coverage) {
    Parameters params = new Parameters();
    params.getMeta().addProfile(DTR_PROFILE);
    params.addParameter().setName("coverage").setResource(coverage.copy());
    params.addParameter().setName("questionnaire").setValue(new CanonicalType(canonical));
    return params;
  }

  static Parameters buildOrderParams(Resource order, Coverage coverage) {
    Parameters params = new Parameters();
    params.getMeta().addProfile(DTR_PROFILE);
    params.addParameter().setName("coverage").setResource(coverage.copy());
    params.addParameter().setName("order").setResource(order);
    return params;
  }

  static Parameters buildCombinedParams(String canonical, Resource order, Coverage coverage) {
    Parameters params = new Parameters();
    params.getMeta().addProfile(DTR_PROFILE);
    params.addParameter().setName("coverage").setResource(coverage.copy());
    params.addParameter().setName("order").setResource(order);
    params.addParameter().setName("questionnaire").setValue(new CanonicalType(canonical));
    return params;
  }

  // ===== URL helpers =====

  static String questionnaireIdFromUrl(String url) {
    String name = url.startsWith(DTR_Q_PREFIX)
        ? url.substring(DTR_Q_PREFIX.length())
        : url;
    return LibraryScenarioScanner.toKebabCase(name);
  }

  // ===== Description generation =====

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

  // ===== DTOs =====

  /** A DTR test scenario with its request variants. */
  public record DtrScenario(
      String id,
      String name,
      String description,
      String orderType,
      boolean isAdaptive,
      List<DtrVariant> variants) {
  }

  /** A single request variant (canonical, order, or combined) with its FHIR Parameters. */
  public record DtrVariant(
      String id,
      String label,
      String pathType,
      Parameters parameters) {
  }
}
