package org.hl7.davinci.common;

import static org.hl7.davinci.common.CrdConstants.COVERAGE_INFO_EXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.RequestGroup;
import org.junit.jupiter.api.Test;

class CoverageInfoUtilTest {

  @Test
  void extractCoverageExtension_addsRequiredFieldsAndNormalizesQuestionnaireUrls() {
    RequestGroup requestGroup = new RequestGroup();
    RequestGroup.RequestGroupActionComponent action = requestGroup.addAction();
    Extension coverageInfo = new Extension(COVERAGE_INFO_EXT);
    coverageInfo.addExtension("covered", new CodeType("conditional"));
    coverageInfo.addExtension("questionnaire", new CanonicalType("Questionnaire/test-form"));
    action.addExtension(coverageInfo);

    Coverage coverage = new Coverage();
    coverage.setId("cov-1");

    Extension extracted = CoverageInfoUtil.extractCoverageExtension(
        requestGroup,
        coverage,
        "http://example.org/fhir/");

    assertNotNull(extracted);
    assertNotNull(extracted.getExtensionByUrl("coverage"));
    assertNotNull(extracted.getExtensionByUrl("date"));
    assertNotNull(extracted.getExtensionByUrl("coverage-assertion-id"));
    assertEquals(
        "http://example.org/fhir/Questionnaire/test-form",
        ((CanonicalType) extracted.getExtensionsByUrl("questionnaire").get(0).getValue()).getValue());
  }

  @Test
  void extractCoverageExtension_returnsNullWhenNoCoverageInfoActionsExist() {
    RequestGroup requestGroup = new RequestGroup();
    requestGroup.addAction().setTitle("No coverage info");

    assertNull(CoverageInfoUtil.extractCoverageExtension(requestGroup, new Coverage(), "http://example.org"));
  }

  @Test
  void subExtensionCode_returnsPrimitiveValueForMatchingSubExtension() {
    Extension parent = new Extension(COVERAGE_INFO_EXT);
    parent.addExtension("covered", new CodeType("covered"));

    assertEquals("covered", CoverageInfoUtil.subExtensionCode(parent, "covered"));
    assertNull(CoverageInfoUtil.subExtensionCode(parent, "missing"));
    assertNull(CoverageInfoUtil.subExtensionCode(null, "covered"));
    assertTrue(CoverageInfoUtil.extractCoverageInfoExtensions(null).isEmpty());
  }
}
