package org.hl7.davinci.cdshooks.shared;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.*;
import org.hl7.davinci.cdshooks.CdsHooksTestUtils;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CdsServiceBase shared logic.
 *
 * Tests the abstract base class functionality including:
 * - Payor identifier extraction
 * - Code extraction from different resource types
 * - Card consolidation
 * - System action consolidation
 * - Coverage extension building
 * - Primary/secondary hook detection
 */
@ExtendWith(MockitoExtension.class)
class CdsServiceBaseTest {

  @Mock
  private PlanDefinitionFinder planDefinitionFinder;

  @Mock
  private CoverageInfoHandler coverageInfoHandler;

  @Mock
  private CardConverter cardConverter;

  // Concrete implementation for testing abstract class
  private TestCdsService testService;

  @BeforeEach
  void setUp() {
    testService = new TestCdsService();
    testService.planDefinitionFinder = planDefinitionFinder;
    testService.coverageInfoHandler = coverageInfoHandler;
    testService.cardConverter = cardConverter;
  }

  /**
   * Concrete test implementation of CdsServiceBase
   */
  static class TestCdsService extends CdsServiceBase {
    private String hookName = "order-sign";

    @Override
    protected String getHookName() {
      return hookName;
    }

    @Override
    protected void validateRequestInput(CdsServiceRequestJson request) {
      // No-op for testing
    }

    @Override
    protected void validateExtractedResources(ResolvedResources context) {
      // No-op for testing
    }

    @Override
    protected List<Resource> selectContextResources(ResolvedResources context) {
      return context.getOrders();
    }

    public void setHookName(String name) {
      this.hookName = name;
    }
  }

  @Nested
  @DisplayName("Payor Identifier Extraction")
  class PayorIdentifierExtraction {

    @Test
    @DisplayName("Should extract identifiers from Organization referenced by Coverage.payor")
    void testExtractPayorIdentifiers_FromOrgReference() {
      ResolvedResources context = new ResolvedResources();

      Coverage coverage = new Coverage();
      coverage.setId("cov-1");
      coverage.addPayor(new Reference("Organization/org1234"));
      context.setCoverage(coverage);

      Organization org = new Organization();
      org.setId("org1234");
      org.addIdentifier()
          .setSystem(CdsHooksTestUtils.CMS_PAYOR_SYSTEM)
          .setValue(CdsHooksTestUtils.CMS_PAYOR_VALUE);
      context.setOrganizations(List.of(org));

      List<Identifier> payorIds = testService.extractPayorIdentifiers(context);

      assertEquals(1, payorIds.size());
      assertEquals(CdsHooksTestUtils.CMS_PAYOR_SYSTEM, payorIds.get(0).getSystem());
      assertEquals(CdsHooksTestUtils.CMS_PAYOR_VALUE, payorIds.get(0).getValue());
    }

    @Test
    @DisplayName("Should return empty list when no payor reference")
    void testExtractPayorIdentifiers_NoPayor() {
      ResolvedResources context = new ResolvedResources();

      Coverage coverage = new Coverage();
      coverage.setId("cov-1");
      // No payor set
      context.setCoverage(coverage);

      List<Identifier> payorIds = testService.extractPayorIdentifiers(context);

      assertTrue(payorIds.isEmpty());
    }

    @Test
    @DisplayName("Should return empty list when payor org not in context")
    void testExtractPayorIdentifiers_OrgNotInContext() {
      ResolvedResources context = new ResolvedResources();

      Coverage coverage = new Coverage();
      coverage.setId("cov-1");
      coverage.addPayor(new Reference("Organization/unknown-org"));
      context.setCoverage(coverage);
      context.setOrganizations(Collections.emptyList());

      List<Identifier> payorIds = testService.extractPayorIdentifiers(context);

      assertTrue(payorIds.isEmpty());
    }

    @Test
    @DisplayName("Should handle multiple payor organizations")
    void testExtractPayorIdentifiers_MultiplePayors() {
      ResolvedResources context = new ResolvedResources();

      Coverage coverage = new Coverage();
      coverage.setId("cov-1");
      coverage.addPayor(new Reference("Organization/org1"));
      coverage.addPayor(new Reference("Organization/org2"));
      context.setCoverage(coverage);

      Organization org1 = new Organization();
      org1.setId("org1");
      org1.addIdentifier().setSystem("sys1").setValue("val1");

      Organization org2 = new Organization();
      org2.setId("org2");
      org2.addIdentifier().setSystem("sys2").setValue("val2");

      context.setOrganizations(List.of(org1, org2));

      List<Identifier> payorIds = testService.extractPayorIdentifiers(context);

      assertEquals(2, payorIds.size());
    }
  }

  @Nested
  @DisplayName("Code Extraction")
  class CodeExtraction {

    @Test
    @DisplayName("Should extract codes from DeviceRequest.codeCodeableConcept")
    void testExtractCodes_DeviceRequest() {
      DeviceRequest request = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false, null);

      assertEquals(1, codes.size());
      assertEquals("E0250", codes.get(0).getCode());
      assertEquals("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", codes.get(0).getSystem());
    }

    @Test
    @DisplayName("Should extract codes from MedicationRequest.medicationCodeableConcept")
    void testExtractCodes_MedicationRequest() {
      MedicationRequest request = CdsHooksTestUtils.createTestMedicationRequest("mr-1", "1049502", "patient1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false, null);

      assertEquals(1, codes.size());
      assertEquals("1049502", codes.get(0).getCode());
      assertEquals("http://www.nlm.nih.gov/research/umls/rxnorm", codes.get(0).getSystem());
    }

    @Test
    @DisplayName("Should extract codes from ServiceRequest.code")
    void testExtractCodes_ServiceRequest() {
      ServiceRequest request = CdsHooksTestUtils.createTestServiceRequest("sr-1", "99213", "patient1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false, null);

      assertEquals(1, codes.size());
      assertEquals("99213", codes.get(0).getCode());
    }

    @Test
    @DisplayName("Should extract serviceType codes from Appointment")
    void testExtractCodes_Appointment_ServiceType() {
      Appointment appointment = CdsHooksTestUtils.createTestAppointment("appt-1", "394579002", "patient1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(appointment, false, null);

      assertTrue(codes.stream().anyMatch(c -> "394579002".equals(c.getCode())));
    }

    @Test
    @DisplayName("Should normalize https:// to http:// in code systems")
    void testExtractCodes_NormalizesHttpsToHttp() {
      DeviceRequest request = new DeviceRequest();
      request.setId("dr-1");
      CodeableConcept code = new CodeableConcept();
      code.addCoding()
          .setSystem("https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
          .setCode("E0250");
      request.setCode(code);

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, true, null);

      assertEquals("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", codes.get(0).getSystem());
    }
  }

  @Nested
  @DisplayName("Primary/Secondary Hook Detection")
  class HookCategoryDetection {

    @Test
    @DisplayName("order-sign is a primary hook")
    void testIsPrimaryHook_OrderSign() {
      assertTrue(CrdConformanceEnforcer.isPrimaryHook("order-sign"));
    }

    @Test
    @DisplayName("order-dispatch is a primary hook")
    void testIsPrimaryHook_OrderDispatch() {
      assertTrue(CrdConformanceEnforcer.isPrimaryHook("order-dispatch"));
    }

    @Test
    @DisplayName("appointment-book is a primary hook")
    void testIsPrimaryHook_AppointmentBook() {
      assertTrue(CrdConformanceEnforcer.isPrimaryHook("appointment-book"));
    }

    @Test
    @DisplayName("order-select is NOT a primary hook")
    void testIsPrimaryHook_OrderSelect_False() {
      assertFalse(CrdConformanceEnforcer.isPrimaryHook("order-select"));
    }

    @Test
    @DisplayName("encounter-start is NOT a primary hook")
    void testIsPrimaryHook_EncounterStart_False() {
      assertFalse(CrdConformanceEnforcer.isPrimaryHook("encounter-start"));
    }
  }

  @Nested
  @DisplayName("Card Consolidation")
  class CardConsolidation {

    // CardConverter is now a separate class — these tests use the real instance
    private final CardConverter realCardConverter = new CardConverter();

    @Test
    @DisplayName("Should merge duplicate cards with same summary/detail/indicator")
    void testConsolidateDuplicateCards_SameSummaryDetailIndicator() {
      CdsServiceResponseCardJson card1 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);
      CdsServiceResponseCardJson card2 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);

      List<CdsServiceResponseCardJson> cards = List.of(card1, card2);
      List<CdsServiceResponseCardJson> consolidated = realCardConverter.consolidateDuplicateCards(cards);

      assertEquals(1, consolidated.size(), "Duplicate cards should be merged");
    }

    @Test
    @DisplayName("Should NOT merge cards with different summaries")
    void testConsolidateDuplicateCards_DifferentSummaries() {
      CdsServiceResponseCardJson card1 = createTestCard("Summary 1", "Detail", CdsServiceIndicatorEnum.INFO);
      CdsServiceResponseCardJson card2 = createTestCard("Summary 2", "Detail", CdsServiceIndicatorEnum.INFO);

      List<CdsServiceResponseCardJson> cards = List.of(card1, card2);
      List<CdsServiceResponseCardJson> consolidated = realCardConverter.consolidateDuplicateCards(cards);

      assertEquals(2, consolidated.size(), "Cards with different summaries should not be merged");
    }

    @Test
    @DisplayName("Should NOT merge cards with different indicators")
    void testConsolidateDuplicateCards_DifferentIndicators() {
      CdsServiceResponseCardJson card1 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);
      CdsServiceResponseCardJson card2 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.WARNING);

      List<CdsServiceResponseCardJson> cards = List.of(card1, card2);
      List<CdsServiceResponseCardJson> consolidated = realCardConverter.consolidateDuplicateCards(cards);

      assertEquals(2, consolidated.size(), "Cards with different indicators should not be merged");
    }

    @Test
    @DisplayName("Should merge associated-resource extensions from duplicate cards")
    void testConsolidateDuplicateCards_MergesAssociatedResources() {
      CdsServiceResponseCardJson card1 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);
      CrdCardExtension ext1 = new CrdCardExtension();
      ext1.addAssociatedResource("DeviceRequest/dr-1");
      card1.setExtension(ext1);

      CdsServiceResponseCardJson card2 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);
      CrdCardExtension ext2 = new CrdCardExtension();
      ext2.addAssociatedResource("DeviceRequest/dr-2");
      card2.setExtension(ext2);

      List<CdsServiceResponseCardJson> cards = List.of(card1, card2);
      List<CdsServiceResponseCardJson> consolidated = realCardConverter.consolidateDuplicateCards(cards);

      assertEquals(1, consolidated.size());
      CrdCardExtension mergedExt = (CrdCardExtension) consolidated.get(0).getExtension();
      assertNotNull(mergedExt);
      assertEquals(2, mergedExt.getAssociatedResources().size());
      assertTrue(mergedExt.getAssociatedResources().contains("DeviceRequest/dr-1"));
      assertTrue(mergedExt.getAssociatedResources().contains("DeviceRequest/dr-2"));
    }

    @Test
    @DisplayName("Should handle empty cards list")
    void testConsolidateDuplicateCards_EmptyList() {
      List<CdsServiceResponseCardJson> consolidated = realCardConverter.consolidateDuplicateCards(Collections.emptyList());
      assertTrue(consolidated.isEmpty());
    }

    @Test
    @DisplayName("Should handle single card (no consolidation needed)")
    void testConsolidateDuplicateCards_SingleCard() {
      CdsServiceResponseCardJson card = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);
      List<CdsServiceResponseCardJson> consolidated = realCardConverter.consolidateDuplicateCards(List.of(card));

      assertEquals(1, consolidated.size());
    }

    @Test
    @DisplayName("Should not merge cards with different source topics")
    void testConsolidateDuplicateCards_DifferentTopics() {
      CdsServiceResponseCardJson card1 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);
      card1.getSource().setTopic(new CdsServiceResponseCodingJson()
          .setSystem(CrdConstants.CARD_TYPE_SYSTEM)
          .setCode("coverage-info"));

      CdsServiceResponseCardJson card2 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);
      card2.getSource().setTopic(new CdsServiceResponseCodingJson()
          .setSystem(CrdConstants.CARD_TYPE_SYSTEM)
          .setCode("insurance"));

      List<CdsServiceResponseCardJson> consolidated = realCardConverter.consolidateDuplicateCards(List.of(card1, card2));

      assertEquals(2, consolidated.size(), "Cards with different topics should not be merged");
    }

    @Test
    @DisplayName("Should not merge cards when only one has a CrdCardExtension")
    void testConsolidateDuplicateCards_OnlyOneCrdExtension() {
      CdsServiceResponseCardJson card1 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);
      CrdCardExtension ext = new CrdCardExtension();
      ext.addAssociatedResource("DeviceRequest/dr-1");
      card1.setExtension(ext);

      CdsServiceResponseCardJson card2 = createTestCard("Summary", "Detail", CdsServiceIndicatorEnum.INFO);

      List<CdsServiceResponseCardJson> consolidated = realCardConverter.consolidateDuplicateCards(List.of(card1, card2));

      assertEquals(2, consolidated.size(), "Cards without matching extension types should not be merged");
    }
  }

  @Nested
  @DisplayName("System Action Consolidation")
  class SystemActionConsolidation {

    private final CardConverter realCardConverter = new CardConverter();

    @Test
    @DisplayName("Should deduplicate system actions for same resource ID")
    void testConsolidateDuplicateServiceActions_SameResourceId() {
      DeviceRequest dr = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1");

      CdsServiceResponseSystemActionJson action1 = new CdsServiceResponseSystemActionJson();
      action1.setType("update");
      action1.setResource(dr);

      CdsServiceResponseSystemActionJson action2 = new CdsServiceResponseSystemActionJson();
      action2.setType("update");
      action2.setResource(dr.copy()); // Same ID

      List<CdsServiceResponseSystemActionJson> actions = List.of(action1, action2);
      List<CdsServiceResponseSystemActionJson> consolidated = realCardConverter.consolidateDuplicateServiceActions(actions);

      assertEquals(1, consolidated.size(), "Duplicate system actions should be merged");
    }

    @Test
    @DisplayName("Should NOT deduplicate system actions for different resource IDs")
    void testConsolidateDuplicateServiceActions_DifferentResourceIds() {
      DeviceRequest dr1 = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1");
      DeviceRequest dr2 = CdsHooksTestUtils.createTestDeviceRequest("dr-2", "E0251", "patient1");

      CdsServiceResponseSystemActionJson action1 = new CdsServiceResponseSystemActionJson();
      action1.setType("update");
      action1.setResource(dr1);

      CdsServiceResponseSystemActionJson action2 = new CdsServiceResponseSystemActionJson();
      action2.setType("update");
      action2.setResource(dr2);

      List<CdsServiceResponseSystemActionJson> actions = List.of(action1, action2);
      List<CdsServiceResponseSystemActionJson> consolidated = realCardConverter.consolidateDuplicateServiceActions(actions);

      assertEquals(2, consolidated.size(), "Actions for different resources should not be merged");
    }
  }

  @Nested
  @DisplayName("Default Coverage Extension")
  class DefaultCoverageExtension {

    // CoverageInfoHandler needs AppProperties, so use a real instance for buildDefaultCoverageExtension
    // which doesn't use AppProperties
    private final CoverageInfoHandler realCoverageInfoHandler = new CoverageInfoHandler();

    @Test
    @DisplayName("Should build extension with all required fields")
    void testBuildDefaultCoverageExtension_HasRequiredFields() {
      ResolvedResources context = new ResolvedResources();
      Coverage coverage = CdsHooksTestUtils.createTestCoverage("cov-1", "org1234");
      context.setCoverage(coverage);

      Extension ext = realCoverageInfoHandler.buildDefaultCoverageExtension(context);

      assertNotNull(ext);
      assertEquals(CdsHooksTestUtils.COVERAGE_INFO_EXT_URL, ext.getUrl());

      // Required fields per CRD spec
      assertNotNull(ext.getExtensionByUrl("coverage"), "Must have coverage reference");
      assertNotNull(ext.getExtensionByUrl("covered"), "Must have covered code");
      assertNotNull(ext.getExtensionByUrl("date"), "Must have date");
      assertNotNull(ext.getExtensionByUrl("coverage-assertion-id"), "Must have assertion ID");
    }

    @Test
    @DisplayName("Default covered value should be 'conditional' when no rules match")
    void testBuildDefaultCoverageExtension_ConditionalCovered() {
      ResolvedResources context = new ResolvedResources();
      Coverage coverage = CdsHooksTestUtils.createTestCoverage("cov-1", "org1234");
      context.setCoverage(coverage);

      Extension ext = realCoverageInfoHandler.buildDefaultCoverageExtension(context);

      Extension coveredExt = ext.getExtensionByUrl("covered");
      assertNotNull(coveredExt);
      assertEquals("conditional", coveredExt.getValue().primitiveValue());
    }

    @Test
    @DisplayName("Should return null when coverage is null")
    void testBuildDefaultCoverageExtension_NullCoverage() {
      ResolvedResources context = new ResolvedResources();
      context.setCoverage(null);

      Extension ext = realCoverageInfoHandler.buildDefaultCoverageExtension(context);

      assertNull(ext);
    }
  }

  @Nested
  @DisplayName("Data Bundle Assembly")
  class DataBundleAssembly {

    @Test
    @DisplayName("Should include device history, service requests, and medication dispenses")
    void testBuildDataBundle_IncludesHistoricalResources() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("patient1"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("cov-1", "org1"));

      DeviceRequest deviceRequest = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1");
      ServiceRequest serviceRequest = CdsHooksTestUtils.createTestServiceRequest("sr-1", "99213", "patient1");
      MedicationDispense medicationDispense = new MedicationDispense();
      medicationDispense.setId("md-1");

      context.addDeviceHistory(deviceRequest);
      context.addServiceRequest(serviceRequest);
      context.addMedicationDispense(medicationDispense);

      Bundle bundle = testService.buildDataBundle(context, deviceRequest);

      List<Resource> resources = bundle.getEntry().stream()
          .map(Bundle.BundleEntryComponent::getResource)
          .toList();

      assertTrue(resources.stream().anyMatch(r -> r instanceof DeviceRequest
          && "dr-1".equals(r.getIdElement().getIdPart())));
      assertTrue(resources.stream().anyMatch(r -> r instanceof ServiceRequest
          && "sr-1".equals(r.getIdElement().getIdPart())));
      assertTrue(resources.stream().anyMatch(r -> r instanceof MedicationDispense
          && "md-1".equals(r.getIdElement().getIdPart())));
    }
  }

  @Nested
  @DisplayName("Coverage Info System Action")
  class CoverageInfoSystemAction {

    @Test
    @DisplayName("Should return false for non-DomainResource actions")
    void testHasCoverageInfoSystemAction_NonDomainResource() {
      CdsServiceResponseJson response = new CdsServiceResponseJson();
      CdsServiceResponseSystemActionJson action = new CdsServiceResponseSystemActionJson();
      action.setType("update");
      action.setResource(new org.hl7.fhir.r4.model.Parameters());
      response.addServiceAction(action);

      CoverageInfoHandler handler = new CoverageInfoHandler();

      assertFalse(handler.hasCoverageInfoSystemAction(response));
    }
  }

  @Nested
  @DisplayName("Payor Handled Check")
  class PayorHandledCheck {

    @Test
    @DisplayName("Should return true when PlanDefinition exists for payor")
    void testIsPayorHandled_True() {
      Identifier payorId = new Identifier();
      payorId.setSystem(CdsHooksTestUtils.CMS_PAYOR_SYSTEM);
      payorId.setValue(CdsHooksTestUtils.CMS_PAYOR_VALUE);

      when(planDefinitionFinder.isPayorHandled(any())).thenReturn(true);

      boolean handled = planDefinitionFinder.isPayorHandled(List.of(payorId));

      assertTrue(handled);
    }

    @Test
    @DisplayName("Should return false when no PlanDefinition exists for payor")
    void testIsPayorHandled_False() {
      Identifier payorId = new Identifier();
      payorId.setSystem("http://unknown-system");
      payorId.setValue("unknown-value");

      when(planDefinitionFinder.isPayorHandled(any())).thenReturn(false);

      boolean handled = planDefinitionFinder.isPayorHandled(List.of(payorId));

      assertFalse(handled);
    }
  }

  // Helper methods

  private CdsServiceResponseCardJson createTestCard(String summary, String detail, CdsServiceIndicatorEnum indicator) {
    CdsServiceResponseCardJson card = new CdsServiceResponseCardJson();
    card.setSummary(summary);
    card.setDetail(detail);
    card.setIndicator(indicator);

    CdsServiceResponseCardSourceJson source = new CdsServiceResponseCardSourceJson();
    source.setLabel("Test Source");
    source.setUrl("http://example.org/plandefinition/test");
    card.setSource(source);

    return card;
  }
}
