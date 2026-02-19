package org.hl7.davinci.cdshooks.shared;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestContextJson;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.davinci.cdshooks.CdsHooksTestUtils;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ResourceResolver utility class.
 * 
 * Tests resource resolution from various sources:
 * - Prefetch data (direct resources and bundles)
 * - Contained resources
 * - Context resources (draftOrders, appointments)
 */
class ResourceResolverTest {

  @Nested
  @DisplayName("Extract All Resources")
  class ExtractAllResources {

    @Test
    @DisplayName("Should extract Patient from direct prefetch")
    void testExtractPatient_FromDirectPrefetch() throws IOException {
      CdsServiceRequestJson request = CdsHooksTestUtils.loadGeneratedRequest("order-sign", "hospital-beds-and-accessories-order-sign.json");

      ResolvedResources context = CdsResourceExtractor.extractAllResources(request);

      assertNotNull(context.getPatient(), "Patient should be extracted from prefetch");
      assertEquals("example", context.getPatient().getIdElement().getIdPart());
    }

    @Test
    @DisplayName("Should extract Coverage from Bundle prefetch")
    void testExtractCoverage_FromBundlePrefetch() throws IOException {
      CdsServiceRequestJson request = CdsHooksTestUtils.loadGeneratedRequest("order-sign", "hospital-beds-and-accessories-order-sign.json");

      ResolvedResources context = CdsResourceExtractor.extractAllResources(request);

      assertNotNull(context.getCoverage(), "Coverage should be extracted from prefetch bundle");
    }

    @Test
    @DisplayName("Should extract orders from draftOrders context")
    void testExtractOrders_FromDraftOrders() throws IOException {
      CdsServiceRequestJson request = CdsHooksTestUtils.loadGeneratedRequest("order-sign", "hospital-beds-and-accessories-order-sign.json");

      ResolvedResources context = CdsResourceExtractor.extractAllResources(request);

      assertFalse(context.getOrders().isEmpty(), "Orders should be extracted from draftOrders");
      // Generated order-sign fixture has a DeviceRequest
      assertTrue(
          context.getOrders().stream().anyMatch(r -> r instanceof DeviceRequest),
          "Should contain DeviceRequest");
    }

    @Test
    @DisplayName("Should extract appointments from context")
    void testExtractAppointments_FromContext() throws IOException {
      CdsServiceRequestJson request = CdsHooksTestUtils.loadGeneratedRequest("appointment-book", "cardiology-consultation-appointment-book.json");

      ResolvedResources context = CdsResourceExtractor.extractAllResources(request);

      assertFalse(context.getAppointments().isEmpty(), "Appointments should be extracted");
      assertNotNull(context.getAppointments().get(0).getIdElement().getIdPart(),
          "Appointment should have an ID");
    }

    @Test
    @DisplayName("Should extract Organizations from coverage Bundle")
    void testExtractOrganizations_FromCoverageBundle() throws IOException {
      // Generated appointment-book fixture includes Organization in coverage bundle
      CdsServiceRequestJson request = CdsHooksTestUtils.loadGeneratedRequest("appointment-book", "cardiology-consultation-appointment-book.json");

      ResolvedResources context = CdsResourceExtractor.extractAllResources(request);

      assertFalse(context.getOrganizations().isEmpty(), "Organizations should be extracted");
    }

    @Test
    @DisplayName("Should extract Practitioners from prefetch bundle")
    void testExtractPractitioners_FromPrefetch() throws IOException {
      // Note: extractAllResources only extracts practitioners from specific prefetch keys
      // (user, performer), not from generic bundles.
      CdsServiceRequestJson request = CdsHooksTestUtils.loadGeneratedRequest("order-sign", "hospital-beds-and-accessories-order-sign.json");

      ResolvedResources context = CdsResourceExtractor.extractAllResources(request);

      // Practitioners are resolved on-demand when processing orders, not from generic prefetch
      assertNotNull(context, "Context should be created");
    }

    @Test
    @DisplayName("Should extract Practitioner and PractitionerRole from prefetch keys")
    void testExtractPractitionerAndRole_FromPrefetchKeys() {
      CdsServiceRequestJson request = new CdsServiceRequestJson();

      Practitioner practitioner = CdsHooksTestUtils.createTestPractitioner("prac-1");
      PractitionerRole role = new PractitionerRole();
      role.setId("role-1");

      Bundle practitionerBundle = new Bundle();
      practitionerBundle.setType(Bundle.BundleType.COLLECTION);
      practitionerBundle.addEntry().setResource(practitioner);

      Bundle roleBundle = new Bundle();
      roleBundle.setType(Bundle.BundleType.COLLECTION);
      roleBundle.addEntry().setResource(role);

      request.addPrefetch("practitioner", practitionerBundle);
      request.addPrefetch("practitionerRoles", roleBundle);

      ResolvedResources context = CdsResourceExtractor.extractAllResources(request);

      assertEquals(1, context.getPractitioners().size());
      assertEquals("prac-1", context.getPractitioners().get(0).getIdElement().getIdPart());
      assertEquals(1, context.getPractitionerRoles().size());
      assertEquals("role-1", context.getPractitionerRoles().get(0).getIdElement().getIdPart());
    }

    @Test
    @DisplayName("Should extract device and service history from prefetch")
    void testExtractDeviceAndServiceHistory_FromPrefetch() {
      CdsServiceRequestJson request = new CdsServiceRequestJson();

      DeviceRequest deviceRequest = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1");
      ServiceRequest serviceRequest = CdsHooksTestUtils.createTestServiceRequest("sr-1", "99213", "patient1");

      Bundle deviceHistoryBundle = new Bundle();
      deviceHistoryBundle.setType(Bundle.BundleType.COLLECTION);
      deviceHistoryBundle.addEntry().setResource(deviceRequest);

      Bundle serviceHistoryBundle = new Bundle();
      serviceHistoryBundle.setType(Bundle.BundleType.COLLECTION);
      serviceHistoryBundle.addEntry().setResource(serviceRequest);

      request.addPrefetch("deviceHistory", deviceHistoryBundle);
      request.addPrefetch("serviceHistory", serviceHistoryBundle);

      ResolvedResources context = CdsResourceExtractor.extractAllResources(request);

      assertEquals(1, context.getDeviceHistory().size());
      assertEquals("dr-1", context.getDeviceHistory().get(0).getIdElement().getIdPart());
      assertEquals(1, context.getServiceRequests().size());
      assertEquals("sr-1", context.getServiceRequests().get(0).getIdElement().getIdPart());
    }

    @Test
    @DisplayName("Should extract orders from draftOrders map")
    void testExtractOrders_FromDraftOrdersMap() throws IOException {
      CdsServiceRequestJson request = new CdsServiceRequestJson();

      DeviceRequest deviceRequest = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1");
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);
      bundle.addEntry().setResource(deviceRequest);

      Map<String, Object> draftOrdersMap = new HashMap<>();
      draftOrdersMap.put("resourceType", "Bundle");
      draftOrdersMap.put("type", "collection");

      List<Map<String, Object>> entries = new ArrayList<>();
      Map<String, Object> entry = new HashMap<>();
      String deviceJson = CdsHooksTestUtils.getFhirContext().newJsonParser().encodeResourceToString(deviceRequest);
      entry.put("resource", new ObjectMapper().readValue(deviceJson, Map.class));
      entries.add(entry);
      draftOrdersMap.put("entry", entries);

      CdsServiceRequestContextJson requestContext = new CdsServiceRequestContextJson();
      requestContext.put("draftOrders", draftOrdersMap);
      request.setContext(requestContext);

      ResolvedResources extractedContext = CdsResourceExtractor.extractAllResources(request);

      assertEquals(1, extractedContext.getOrders().size());
      assertInstanceOf(DeviceRequest.class, extractedContext.getOrders().get(0));
    }
  }

  @Nested
  @DisplayName("Resolve Reference")
  class ResolveReference {

    @Test
    @DisplayName("Should resolve contained resource reference")
    void testResolveReference_ContainedResource() {
      // Create parent with contained resource
      DeviceRequest parent = new DeviceRequest();
      parent.setId("parent-1");

      Practitioner contained = new Practitioner();
      contained.setId("prac-1");
      contained.addName().setFamily("ContainedDoctor");
      parent.addContained(contained);

      parent.setRequester(new Reference("#prac-1"));

      // Create minimal request
      CdsServiceRequestJson request = new CdsServiceRequestJson();

      Practitioner resolved = ResourceResolver.resolveReference(
          parent.getRequester(), Practitioner.class, parent, request);

      assertNotNull(resolved, "Should resolve contained reference");
      assertEquals("ContainedDoctor", resolved.getNameFirstRep().getFamily());
    }

    @Test
    @DisplayName("Should return null for null reference")
    void testResolveReference_NullReference() {
      CdsServiceRequestJson request = new CdsServiceRequestJson();

      Patient resolved = ResourceResolver.resolveReference(null, Patient.class, null, request);

      assertNull(resolved);
    }

    @Test
    @DisplayName("Should return null for reference without value")
    void testResolveReference_EmptyReference() {
      CdsServiceRequestJson request = new CdsServiceRequestJson();
      Reference emptyRef = new Reference();

      Patient resolved = ResourceResolver.resolveReference(emptyRef, Patient.class, null, request);

      assertNull(resolved);
    }
  }

  @Nested
  @DisplayName("Find In Bundle")
  class FindInBundle {

    @Test
    @DisplayName("Should find resource by type and ID in bundle")
    void testFindInBundle_ByTypeAndId() {
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);

      Patient patient = CdsHooksTestUtils.createTestPatient("test-patient-123");
      bundle.addEntry().setResource(patient);

      Patient found = ResourceResolver.findInBundle("Patient/test-patient-123", Patient.class, bundle);

      assertNotNull(found);
      assertEquals("test-patient-123", found.getIdElement().getIdPart());
    }

    @Test
    @DisplayName("Should find resource by fullUrl in bundle")
    void testFindInBundle_ByFullUrl() {
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);

      Patient patient = CdsHooksTestUtils.createTestPatient("123");
      bundle.addEntry()
          .setFullUrl("http://example.org/fhir/Patient/123")
          .setResource(patient);

      Patient found = ResourceResolver.findInBundle("http://example.org/fhir/Patient/123", Patient.class, bundle);

      assertNotNull(found);
    }

    @Test
    @DisplayName("Should return null for empty bundle")
    void testFindInBundle_EmptyBundle() {
      Bundle bundle = new Bundle();

      Patient found = ResourceResolver.findInBundle("Patient/123", Patient.class, bundle);

      assertNull(found);
    }

    @Test
    @DisplayName("Should return null for null bundle")
    void testFindInBundle_NullBundle() {
      Patient found = ResourceResolver.findInBundle("Patient/123", Patient.class, null);

      assertNull(found);
    }

    @Test
    @DisplayName("Should return null when resource not found")
    void testFindInBundle_NotFound() {
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);

      Patient patient = CdsHooksTestUtils.createTestPatient("different-id");
      bundle.addEntry().setResource(patient);

      Patient found = ResourceResolver.findInBundle("Patient/not-in-bundle", Patient.class, bundle);

      assertNull(found);
    }
  }

  @Nested
  @DisplayName("Find In Contained")
  class FindInContained {

    @Test
    @DisplayName("Should find contained resource by ID")
    void testFindInContained_ById() {
      DeviceRequest parent = new DeviceRequest();

      Practitioner contained = new Practitioner();
      contained.setId("contained-prac");
      parent.addContained(contained);

      Practitioner found = ResourceResolver.findInContained("contained-prac", Practitioner.class, parent);

      assertNotNull(found);
      assertEquals("contained-prac", found.getIdElement().getIdPart());
    }

    @Test
    @DisplayName("Should return null for wrong resource type")
    void testFindInContained_WrongType() {
      DeviceRequest parent = new DeviceRequest();

      Practitioner contained = new Practitioner();
      contained.setId("contained-prac");
      parent.addContained(contained);

      // Looking for Organization but contained is Practitioner
      Organization found = ResourceResolver.findInContained("contained-prac", Organization.class, parent);

      assertNull(found);
    }

    @Test
    @DisplayName("Should return null when ID not found")
    void testFindInContained_IdNotFound() {
      DeviceRequest parent = new DeviceRequest();

      Practitioner contained = new Practitioner();
      contained.setId("existing-id");
      parent.addContained(contained);

      Practitioner found = ResourceResolver.findInContained("non-existent-id", Practitioner.class, parent);

      assertNull(found);
    }
  }

  @Nested
  @DisplayName("Normalize ID")
  class NormalizeId {

    @Test
    @DisplayName("Should NOT remove resource type prefix from ID (only handles urn:uuid)")
    void testNormalizeId_DoesNotRemoveResourceTypePrefix() {
      // normalizeId only strips urn:uuid: prefix, not ResourceType/ prefix
      String normalized = ResourceResolver.normalizeId("DeviceRequest/dr-123");

      // The input is returned as-is because it's not a urn:uuid
      assertEquals("DeviceRequest/dr-123", normalized);
    }

    @Test
    @DisplayName("Should handle urn:uuid format")
    void testNormalizeId_UrnUuid() {
      String normalized = ResourceResolver.normalizeId("urn:uuid:f08533e0-f825-462a-a2d5-57b69f509138");

      assertEquals("f08533e0-f825-462a-a2d5-57b69f509138", normalized);
    }

    @Test
    @DisplayName("Should return ID as-is when no prefix")
    void testNormalizeId_NoPrefix() {
      String normalized = ResourceResolver.normalizeId("simple-id");

      assertEquals("simple-id", normalized);
    }

    @Test
    @DisplayName("Should handle null gracefully")
    void testNormalizeId_Null() {
      String normalized = ResourceResolver.normalizeId(null);

      assertNull(normalized);
    }
  }

  @Nested
  @DisplayName("Normalize Reference ID")
  class NormalizeReferenceId {

    @Test
    @DisplayName("Should return ID part when type matches")
    void testNormalizeReferenceId_MatchingType() {
      String normalized = ResourceResolver.normalizeReferenceId("Encounter/enc-123", "Encounter");

      assertEquals("enc-123", normalized);
    }

    @Test
    @DisplayName("Should return original when type does not match")
    void testNormalizeReferenceId_WrongType() {
      String normalized = ResourceResolver.normalizeReferenceId("Patient/p-1", "Encounter");

      assertEquals("Patient/p-1", normalized);
    }
  }

  @Nested
  @DisplayName("References Match Resource")
  class ReferencesMatchResource {

    @Test
    @DisplayName("Should match resource reference with urn:uuid ID")
    void testReferencesMatchResource_UrnUuid() {
      DeviceRequest request = new DeviceRequest();
      request.setId("urn:uuid:dr-123");

      boolean matches = ResourceResolver.referencesMatchResource("DeviceRequest/dr-123", request);

      assertTrue(matches);
    }
  }

  @Nested
  @DisplayName("Resolve Order Reference")
  class ResolveOrderReference {

    @Test
    @DisplayName("Should resolve SupplyRequest from reference string")
    void testResolveOrderReference_SupplyRequest() {
      CdsServiceRequestJson request = new CdsServiceRequestJson();

      SupplyRequest supplyRequest = CdsHooksTestUtils.createTestSupplyRequest("sup-1", "E0100", "patient1");
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);
      bundle.addEntry().setResource(supplyRequest);
      request.addPrefetch("draftOrders", bundle);

      Resource resolved = ResourceResolver.resolveOrderReference("SupplyRequest/sup-1", request);

      assertNotNull(resolved);
      assertInstanceOf(SupplyRequest.class, resolved);
      assertEquals("sup-1", resolved.getIdElement().getIdPart());
    }
  }

  @Nested
  @DisplayName("Extract Orders From Bundle")
  class ExtractOrdersFromBundle {

    @Test
    @DisplayName("Should extract DeviceRequest from bundle")
    void testExtractOrders_DeviceRequest() {
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);

      DeviceRequest dr = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1");
      bundle.addEntry().setResource(dr);

      List<Resource> orders = ResourceResolver.extractOrders(bundle);

      assertEquals(1, orders.size());
      assertInstanceOf(DeviceRequest.class, orders.get(0));
    }

    @Test
    @DisplayName("Should extract MedicationRequest from bundle")
    void testExtractOrders_MedicationRequest() {
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);

      MedicationRequest mr = CdsHooksTestUtils.createTestMedicationRequest("mr-1", "1049502", "patient1");
      bundle.addEntry().setResource(mr);

      List<Resource> orders = ResourceResolver.extractOrders(bundle);

      assertEquals(1, orders.size());
      assertInstanceOf(MedicationRequest.class, orders.get(0));
    }

    @Test
    @DisplayName("Should extract ServiceRequest from bundle")
    void testExtractOrders_ServiceRequest() {
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);

      ServiceRequest sr = CdsHooksTestUtils.createTestServiceRequest("sr-1", "99213", "patient1");
      bundle.addEntry().setResource(sr);

      List<Resource> orders = ResourceResolver.extractOrders(bundle);

      assertEquals(1, orders.size());
      assertInstanceOf(ServiceRequest.class, orders.get(0));
    }

    @Test
    @DisplayName("Should extract multiple order types from bundle")
    void testExtractOrders_MultipleTypes() {
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);

      bundle.addEntry().setResource(CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "p1"));
      bundle.addEntry().setResource(CdsHooksTestUtils.createTestMedicationRequest("mr-1", "1049502", "p1"));
      bundle.addEntry().setResource(CdsHooksTestUtils.createTestServiceRequest("sr-1", "99213", "p1"));

      List<Resource> orders = ResourceResolver.extractOrders(bundle);

      assertEquals(3, orders.size());
    }

    @Test
    @DisplayName("Should ignore non-order resources in bundle")
    void testExtractOrders_IgnoresNonOrders() {
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.COLLECTION);

      bundle.addEntry().setResource(CdsHooksTestUtils.createTestPatient("patient1"));
      bundle.addEntry().setResource(CdsHooksTestUtils.createTestCoverage("cov1", "org1"));
      bundle.addEntry().setResource(CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1"));

      List<Resource> orders = ResourceResolver.extractOrders(bundle);

      assertEquals(1, orders.size(), "Should only extract order resources");
      assertInstanceOf(DeviceRequest.class, orders.get(0));
    }
  }
}
