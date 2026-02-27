package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Date;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.Test;

class PasExtensionsTest {

  @Test
  void buildReviewActionExtension_certifiedInTotal() {
    Extension ext = PasExtensions.buildReviewActionExtension(
        REVIEW_CODE_A1, "Certified in total", "AUTH001");

    assertEquals(PasConstants.REVIEW_ACTION, ext.getUrl());

    Extension codeExt = ext.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    assertNotNull(codeExt);
    CodeableConcept cc = (CodeableConcept) codeExt.getValue();
    assertEquals("A1", cc.getCodingFirstRep().getCode());
    assertEquals(X12_REVIEW_CODE_SYSTEM, cc.getCodingFirstRep().getSystem());

    Extension numExt = ext.getExtensionByUrl("number");
    assertNotNull(numExt);
    assertEquals("AUTH001", ((StringType) numExt.getValue()).getValue());
  }

  @Test
  void buildReviewActionExtension_pendedNoAuthNumber() {
    Extension ext = PasExtensions.buildReviewActionExtension(
        REVIEW_CODE_A4, "Pending", null);

    assertNull(ext.getExtensionByUrl("number"));
  }

  @Test
  void buildPreAuthPeriodExtension_withDates() {
    Extension ext = PasExtensions.buildPreAuthPeriodExtension(new Date(), new Date());
    assertEquals(PasConstants.ITEM_PREAUTH_PERIOD, ext.getUrl());
    assertInstanceOf(Period.class, ext.getValue());
  }

  // ===== Item Trace Number =====

  @Test
  void buildItemTraceNumberExtension_wrapsIdentifier() {
    Identifier traceId = new Identifier()
        .setSystem("http://example.org/trace")
        .setValue("TRACE001");
    Extension ext = PasExtensions.buildItemTraceNumberExtension(traceId);

    assertEquals(PasConstants.ITEM_TRACE_NUMBER, ext.getUrl());
    assertInstanceOf(Identifier.class, ext.getValue());
    Identifier result = (Identifier) ext.getValue();
    assertEquals("TRACE001", result.getValue());
    assertEquals("http://example.org/trace", result.getSystem());
  }

  @Test
  void extractItemTraceNumbers_returnsTraceIdentifiers() {
    Claim.ItemComponent item = new Claim.ItemComponent();
    Identifier trace1 = new Identifier().setSystem("http://example.org/trace").setValue("T1");
    Identifier trace2 = new Identifier().setSystem("http://example.org/trace").setValue("T2");
    item.addExtension(PasConstants.ITEM_TRACE_NUMBER, trace1);
    item.addExtension(PasConstants.ITEM_TRACE_NUMBER, trace2);

    List<Identifier> result = PasExtensions.extractItemTraceNumbers(item);
    assertEquals(2, result.size());
    assertEquals("T1", result.get(0).getValue());
    assertEquals("T2", result.get(1).getValue());
  }

  @Test
  void extractItemTraceNumbers_emptyWhenNonePresent() {
    Claim.ItemComponent item = new Claim.ItemComponent();
    assertTrue(PasExtensions.extractItemTraceNumbers(item).isEmpty());
  }

  // ===== PreAuth Issue Date =====

  @Test
  void buildPreAuthIssueDateExtension_wrapsDate() {
    Date now = new Date();
    Extension ext = PasExtensions.buildPreAuthIssueDateExtension(now);

    assertEquals(PasConstants.ITEM_PREAUTH_ISSUE_DATE, ext.getUrl());
    assertInstanceOf(DateType.class, ext.getValue());
  }

  // ===== Requested Service Date =====

  @Test
  void buildRequestedServiceDateExtension_wrapsPeriod() {
    Period period = new Period().setStart(new Date());
    Extension ext = PasExtensions.buildRequestedServiceDateExtension(period);

    assertEquals(PasConstants.ITEM_REQUESTED_SERVICE_DATE, ext.getUrl());
    assertInstanceOf(Period.class, ext.getValue());
  }

  @Test
  void buildRequestedServiceDateExtension_wrapsDateType() {
    DateType dateType = new DateType(new Date());
    Extension ext = PasExtensions.buildRequestedServiceDateExtension(dateType);

    assertEquals(PasConstants.ITEM_REQUESTED_SERVICE_DATE, ext.getUrl());
    assertInstanceOf(DateType.class, ext.getValue());
  }

  // ===== Serviced Value Extraction =====

  @Test
  void extractServicedValue_returnsPeriod() {
    Claim.ItemComponent item = new Claim.ItemComponent();
    Period period = new Period().setStart(new Date());
    item.setServiced(period);

    Type result = PasExtensions.extractServicedValue(item);
    assertInstanceOf(Period.class, result);
  }

  @Test
  void extractServicedValue_returnsNullWhenAbsent() {
    Claim.ItemComponent item = new Claim.ItemComponent();
    assertNull(PasExtensions.extractServicedValue(item));
  }

  // ===== Transmission Identifiers =====

  @Test
  void buildTransmissionIdentifiersExtension_buildsComplexExtension() {
    Extension ext = PasExtensions.buildTransmissionIdentifiersExtension(
        "SENDER-001", "RECEIVER-001");

    assertEquals(PasConstants.TRANSMISSION_IDENTIFIERS, ext.getUrl());
    assertNull(ext.getValue(), "Complex extension should not have a root value");

    Extension senderExt = ext.getExtensionByUrl("applicationSenderCode");
    assertNotNull(senderExt);
    assertEquals("SENDER-001", ((StringType) senderExt.getValue()).getValue());

    Extension receiverExt = ext.getExtensionByUrl("applicationReceiverCode");
    assertNotNull(receiverExt);
    assertEquals("RECEIVER-001", ((StringType) receiverExt.getValue()).getValue());
  }

}
