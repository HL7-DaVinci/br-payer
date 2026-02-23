package org.hl7.davinci.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.StringType;

/**
 * Shared helpers for working with CRD coverage-information extensions.
 */
public final class CoverageInfoUtil {

  private CoverageInfoUtil() {
  }

  /**
   * Extracts all coverage-information extensions from RequestGroup actions.
   */
  public static List<Extension> extractCoverageInfoExtensions(RequestGroup requestGroup) {
    List<Extension> coverageInfoExts = new ArrayList<>();
    if (requestGroup == null || !requestGroup.hasAction()) {
      return coverageInfoExts;
    }

    for (RequestGroup.RequestGroupActionComponent action : requestGroup.getAction()) {
      if (action == null) {
        continue;
      }
      Extension ext = action.getExtensionByUrl(CrdConstants.COVERAGE_INFO_EXT);
      if (ext != null) {
        coverageInfoExts.add(ext);
      }
    }
    return coverageInfoExts;
  }

  /**
   * Extracts the first coverage-information extension and normalizes it with required fields.
   */
  public static Extension extractCoverageExtension(RequestGroup requestGroup, Coverage coverage, String fhirBase) {
    List<Extension> coverageInfoExts = extractCoverageInfoExtensions(requestGroup);
    if (coverageInfoExts.isEmpty()) {
      return null;
    }

    Extension result = coverageInfoExts.get(0).copy();

    if (!result.hasExtension("coverage") && coverage != null) {
      result.addExtension("coverage", new Reference(coverage.getIdElement().toUnqualifiedVersionless()));
    }
    if (!result.hasExtension("date")) {
      result.addExtension("date", new DateType(LocalDate.now().toString()));
    }
    if (!result.hasExtension("coverage-assertion-id")) {
      result.addExtension("coverage-assertion-id", new StringType("CRD-" + UUID.randomUUID()));
    }

    normalizeQuestionnaireCanonicals(result, normalizeBase(fhirBase));
    return result;
  }

  /**
   * Reads a code value from a named sub-extension.
   */
  public static String subExtensionCode(Extension parent, String subUrl) {
    if (parent == null || subUrl == null || subUrl.isBlank()) {
      return null;
    }
    Extension sub = parent.getExtensionByUrl(subUrl);
    if (sub == null || sub.getValue() == null) {
      return null;
    }
    String primitive = sub.getValue().primitiveValue();
    return primitive == null || primitive.isBlank() ? null : primitive;
  }

  private static String normalizeBase(String fhirBase) {
    if (fhirBase == null || fhirBase.isBlank()) {
      return null;
    }
    return fhirBase.endsWith("/") ? fhirBase.substring(0, fhirBase.length() - 1) : fhirBase;
  }

  private static void normalizeQuestionnaireCanonicals(Extension coverageInfoExt, String fhirBase) {
    if (fhirBase == null) {
      return;
    }

    for (Extension qExt : coverageInfoExt.getExtensionsByUrl("questionnaire")) {
      if (!(qExt.getValue() instanceof CanonicalType canonicalType)) {
        continue;
      }
      String val = canonicalType.getValue();
      if (val == null || val.isBlank() || val.startsWith("#")
          || val.startsWith("http://") || val.startsWith("https://")) {
        continue;
      }
      if (val.startsWith("Questionnaire/")) {
        canonicalType.setValue(fhirBase + "/" + val);
      } else {
        canonicalType.setValue(fhirBase + "/Questionnaire/" + val);
      }
    }
  }
}

