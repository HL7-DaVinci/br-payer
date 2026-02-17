package org.hl7.davinci.scenarios;

import java.util.ArrayList;
import java.util.List;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateTimeType;
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
      boolean hasMultipleQuestionnaires = meta.questionnaireUrls().size() > 1;

      // Canonical variant for each questionnaire URL
      for (String url : meta.questionnaireUrls()) {
        String qKebab = questionnaireIdFromUrl(url);
        variants.add(new DtrVariant(
            qKebab + "-canonical",
            buildQuestionnaireVariantLabel("Canonical", url, hasMultipleQuestionnaires),
            "canonical",
            buildCanonicalParams(url, sharedCoverage)));
      }

      // Order and combined variants when focus codes and order type are available
      if (hasFocusCodes && hasOrderType) {
        Resource orderResource = ScenarioResourceUtil.buildOrderResource(
            meta.focusCodes().get(0), meta.orderType(), meta.id());

        if (orderResource != null) {
          variants.add(new DtrVariant(
              meta.id() + "-order", "Order", "order",
              buildOrderParams(orderResource, sharedCoverage)));

          for (String url : meta.questionnaireUrls()) {
            String qKebab = questionnaireIdFromUrl(url);
            variants.add(new DtrVariant(
                qKebab + "-combined",
                buildQuestionnaireVariantLabel("Combined", url, hasMultipleQuestionnaires),
                "combined",
                buildCombinedParams(url, orderResource, sharedCoverage)));
          }
        }
      }

      String description = ScenarioResourceUtil.buildDescription(meta);

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

  static String buildQuestionnaireVariantLabel(
      String baseLabel, String questionnaireUrl, boolean includeQuestionnaireName) {
    if (!includeQuestionnaireName) {
      return baseLabel;
    }
    return baseLabel + " (" + questionnaireNameFromUrl(questionnaireUrl) + ")";
  }

  static String questionnaireNameFromUrl(String url) {
    if (url == null || url.isBlank()) {
      return "Questionnaire";
    }

    String canonical = url;
    int versionDelimiter = canonical.indexOf('|');
    if (versionDelimiter >= 0) {
      canonical = canonical.substring(0, versionDelimiter);
    }

    int lastSlash = canonical.lastIndexOf('/');
    if (lastSlash >= 0 && lastSlash + 1 < canonical.length()) {
      return canonical.substring(lastSlash + 1);
    }

    return canonical;
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
