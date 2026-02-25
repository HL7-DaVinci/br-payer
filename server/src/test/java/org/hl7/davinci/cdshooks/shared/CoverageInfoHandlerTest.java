package org.hl7.davinci.cdshooks.shared;

import static org.hl7.davinci.common.CrdConstants.COVERAGE_INFO_EXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.davinci.cdshooks.CdsHooksTestUtils;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;

class CoverageInfoHandlerTest {

  private final CoverageInfoHandler handler = new CoverageInfoHandler();

  @Test
  void buildCoverageInfoSystemAction_returnsNullWhenExistingExtensionIsEquivalent() {
    DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient1");
    order.addExtension(coverageInfo("Coverage/cov-1", "conditional", "assert-old", "2026-01-01"));

    Extension incoming = coverageInfo("Coverage/cov-1", "conditional", "assert-new", "2026-01-02");
    CdsServiceResponseSystemActionJson action = handler.buildCoverageInfoSystemAction(order, incoming);

    assertNull(action);
  }

  @Test
  void buildCoverageInfoSystemAction_replacesExistingExtensionForSameCoverage() {
    DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient1");
    order.addExtension(coverageInfo("Coverage/cov-1", "conditional", "assert-old", "2026-01-01"));

    Extension incoming = coverageInfo("Coverage/cov-1", "covered", "assert-new", "2026-01-02");
    CdsServiceResponseSystemActionJson action = handler.buildCoverageInfoSystemAction(order, incoming);

    assertNotNull(action);
    Resource updated = (Resource) action.getResource();
    DeviceRequest updatedOrder = (DeviceRequest) updated;
    List<Extension> coverageInfoExts = updatedOrder.getExtensionsByUrl(COVERAGE_INFO_EXT);
    assertEquals(1, coverageInfoExts.size());
    assertEquals("covered", coverageInfoExts.get(0).getExtensionByUrl("covered").getValue().primitiveValue());
  }

  @Test
  void addDefaultCoverageInfo_skipsResourcesThatAlreadyContainCoverageInfo() {
    ResolvedResources context = new ResolvedResources();
    Coverage coverage = CdsHooksTestUtils.createTestCoverage("cov-1", "org-1");
    context.setCoverage(coverage);

    DeviceRequest alreadyTagged = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient1");
    alreadyTagged.addExtension(coverageInfo("Coverage/cov-1", "conditional", "assert-1", "2026-01-01"));

    DeviceRequest missingCoverageInfo = CdsHooksTestUtils.createTestDeviceRequest("dr-2", "E0424", "patient1");

    CdsServiceResponseJson response = new CdsServiceResponseJson();
    handler.addDefaultCoverageInfo(response, context, List.of(alreadyTagged, missingCoverageInfo));

    assertNotNull(response.getServiceActions());
    assertEquals(1, response.getServiceActions().size());
  }

  @Test
  void hasCoverageInfoSystemAction_detectsCoverageInfoExtension() {
    DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient1");
    order.addExtension(coverageInfo("Coverage/cov-1", "conditional", "assert-1", "2026-01-01"));

    CdsServiceResponseSystemActionJson action = new CdsServiceResponseSystemActionJson();
    action.setType("update");
    action.setResource(order);

    CdsServiceResponseJson response = new CdsServiceResponseJson();
    response.addServiceAction(action);

    assertTrue(handler.hasCoverageInfoSystemAction(response));
  }

  private Extension coverageInfo(String coverageRef, String coveredCode, String assertionId, String date) {
    Extension ext = new Extension(COVERAGE_INFO_EXT);
    ext.addExtension("coverage", new Reference(coverageRef));
    ext.addExtension("covered", new CodeType(coveredCode));
    ext.addExtension("coverage-assertion-id", new StringType(assertionId));
    ext.addExtension("date", new DateType(date));
    return ext;
  }
}
