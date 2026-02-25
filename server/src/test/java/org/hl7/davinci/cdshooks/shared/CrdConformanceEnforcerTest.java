package org.hl7.davinci.cdshooks.shared;

import static org.hl7.davinci.common.CrdConstants.COVERAGE_INFO_EXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.Test;

import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;

class CrdConformanceEnforcerTest {

  @Test
  void enforce_secondaryHookStripsDocumentationExtensionsFromCoverageInfo() {
    DeviceRequest order = new DeviceRequest();
    order.setId("dr-1");
    order.addExtension(coverageInfoWithDocumentation());

    CdsServiceResponseSystemActionJson action = new CdsServiceResponseSystemActionJson();
    action.setType("update");
    action.setResource(order);

    CdsServiceResponseJson response = new CdsServiceResponseJson();
    response.addServiceAction(action);

    CrdConformanceEnforcer.enforce(response, "order-select");

    Extension coverageInfo = order.getExtensionByUrl(COVERAGE_INFO_EXT);
    assertNotNull(coverageInfo);
    assertTrue(coverageInfo.getExtensionsByUrl("doc-needed").isEmpty());
    assertTrue(coverageInfo.getExtensionsByUrl("doc-purpose").isEmpty());
    assertTrue(coverageInfo.getExtensionsByUrl("questionnaire").isEmpty());
  }

  @Test
  void enforce_primaryHookKeepsDocumentationExtensions() {
    DeviceRequest order = new DeviceRequest();
    order.setId("dr-1");
    order.addExtension(coverageInfoWithDocumentation());

    CdsServiceResponseSystemActionJson action = new CdsServiceResponseSystemActionJson();
    action.setType("update");
    action.setResource(order);

    CdsServiceResponseJson response = new CdsServiceResponseJson();
    response.addServiceAction(action);

    CrdConformanceEnforcer.enforce(response, "order-sign");

    Extension coverageInfo = order.getExtensionByUrl(COVERAGE_INFO_EXT);
    assertNotNull(coverageInfo);
    assertFalse(coverageInfo.getExtensionsByUrl("doc-needed").isEmpty());
    assertFalse(coverageInfo.getExtensionsByUrl("doc-purpose").isEmpty());
    assertFalse(coverageInfo.getExtensionsByUrl("questionnaire").isEmpty());
  }

  @Test
  void ensureResourceConformance_setsDefaultsForServiceRequest() {
    ServiceRequest sr = new ServiceRequest();
    Date defaultDate = new Date(1234L);

    CrdConformanceEnforcer.ensureResourceConformance(sr, defaultDate);

    assertEquals(ServiceRequest.ServiceRequestStatus.DRAFT, sr.getStatus());
    assertEquals(ServiceRequest.ServiceRequestIntent.ORDER, sr.getIntent());
    assertEquals(defaultDate.getTime(), sr.getAuthoredOn().getTime());
  }

  private Extension coverageInfoWithDocumentation() {
    Extension coverageInfo = new Extension(COVERAGE_INFO_EXT);
    coverageInfo.addExtension("covered", new CodeType("conditional"));
    coverageInfo.addExtension("doc-needed", new CodeType("clinical"));
    coverageInfo.addExtension("doc-purpose", new CodeType("info"));
    coverageInfo.addExtension("questionnaire", new CanonicalType("Questionnaire/example"));
    return coverageInfo;
  }
}
