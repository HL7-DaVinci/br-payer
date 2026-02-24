package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

class PasExtensionsTest {

  @Test
  void buildReviewActionExtension_certifiedInTotal() {
    Extension ext = PasExtensions.buildReviewActionExtension(
        PasConstants.REVIEW_CODE_A1, "Certified in total", "AUTH001");

    assertEquals(PasConstants.REVIEW_ACTION, ext.getUrl());

    Extension codeExt = ext.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    assertNotNull(codeExt);
    CodeableConcept cc = (CodeableConcept) codeExt.getValue();
    assertEquals("A1", cc.getCodingFirstRep().getCode());
    assertEquals(PasConstants.X12_REVIEW_CODE_SYSTEM, cc.getCodingFirstRep().getSystem());

    Extension numExt = ext.getExtensionByUrl("number");
    assertNotNull(numExt);
    assertEquals("AUTH001", ((StringType) numExt.getValue()).getValue());
  }

  @Test
  void buildReviewActionExtension_pendedNoAuthNumber() {
    Extension ext = PasExtensions.buildReviewActionExtension(
        PasConstants.REVIEW_CODE_A4, "Pending", null);

    assertNull(ext.getExtensionByUrl("number"));
  }

  @Test
  void buildPreAuthPeriodExtension_withDates() {
    Extension ext = PasExtensions.buildPreAuthPeriodExtension(new java.util.Date(), new java.util.Date());
    assertEquals(PasConstants.ITEM_PREAUTH_PERIOD, ext.getUrl());
    assertInstanceOf(Period.class, ext.getValue());
  }
}
