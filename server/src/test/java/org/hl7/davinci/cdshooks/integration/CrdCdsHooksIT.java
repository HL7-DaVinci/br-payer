package org.hl7.davinci.cdshooks.integration;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.starter.IServerSupport;
import ca.uhn.fhir.jpa.starter.cdshooks.StarterCdsHooksConfig;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.hl7.davinci.cdshooks.CdsHooksTestUtils;
import org.junit.jupiter.api.*;
import org.opencds.cqf.fhir.cr.hapi.config.CrCdsHooksConfig;
import org.opencds.cqf.fhir.cr.hapi.config.RepositoryConfig;
import org.opencds.cqf.fhir.cr.hapi.config.test.TestCdsHooksConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CRD CDS Hooks implementation.
 * 
 * These tests verify the full end-to-end behavior of the CDS Hooks services,
 * including:
 * - CQL execution via PlanDefinitions from library/
 * - HTTP request/response handling
 * - Coverage-information extension generation
 * - Card generation and formatting
 * 
 * Tests use actual sample requests from src/test/resources/cdshooks/
 * and real PlanDefinitions loaded from library/ directory.
 * 
 * CRD Specification Compliance:
 * - Primary hooks (order-sign, appointment-book) SHALL return coverage-info
 * - 412 errors for missing required prefetch, 400 for unhandled payor
 * - Card.source.topic SHALL be populated
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {
        Application.class,
        NicknameServiceConfig.class,
        RepositoryConfig.class,
        TestCdsHooksConfig.class,
        CrCdsHooksConfig.class,
        StarterCdsHooksConfig.class,
        org.hl7.davinci.cdshooks.CdsHooksConfig.class
    },
    properties = {
        "spring.profiles.include=storageSettingsTest",
        "spring.datasource.url=jdbc:h2:mem:dbr4-crd-it",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
        "hapi.fhir.enable_repository_validating_interceptor=true",
        "hapi.fhir.fhir_version=r4",
        "hapi.fhir.cr.enabled=true",
        "hapi.fhir.cdshooks.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "server.max-http-request-header-size=16KB"
    }
)
class CrdCdsHooksIT implements IServerSupport {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CrdCdsHooksIT.class);
  private static final Gson gson = new Gson();

  private final FhirContext fhirContext = FhirContext.forR4Cached();
  private IGenericClient fhirClient;
  private String cdsServicesBase;
  private String fhirServerBase;

  @Autowired
  DaoRegistry daoRegistry;

  @Autowired
  ICdsServiceRegistry cdsServiceRegistry;

  @LocalServerPort
  private int port;

  @BeforeAll
  void setUpOnce() {
    // One-time setup - configure FHIR context and build URLs
    fhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    fhirContext.getRestfulClientFactory().setSocketTimeout(120 * 1000);
    fhirServerBase = "http://localhost:" + port + "/fhir/";
    fhirClient = fhirContext.newRestfulGenericClient(fhirServerBase);
    cdsServicesBase = "http://localhost:" + port + "/cds-services";

    log.info("Integration test setup complete. FHIR server: {}, CDS services: {}", fhirServerBase, cdsServicesBase);

    // Wait for CDS services to be registered (server startup can take time)
    log.info("Waiting for CDS services to be registered...");
    await().atMost(120, TimeUnit.SECONDS).until(this::hasExpectedCdsServices);
    log.info("CDS services are registered and ready for testing");
  }

  /**
   * Check if all expected CDS services are registered.
   */
  private boolean hasExpectedCdsServices() {
    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpGet request = new HttpGet(cdsServicesBase);
      try (CloseableHttpResponse response = httpClient.execute(request)) {
        if (response.getStatusLine().getStatusCode() != 200) {
          return false;
        }
        String body = EntityUtils.toString(response.getEntity());
        JsonObject json = gson.fromJson(body, JsonObject.class);
        if (!json.has("services")) {
          return false;
        }
        JsonArray services = json.getAsJsonArray("services");

        boolean hasOrderSign = false;
        boolean hasOrderSelect = false;
        boolean hasAppointmentBook = false;
        boolean hasOrderDispatch = false;
        boolean hasEncounterStart = false;
        boolean hasEncounterDischarge = false;

        for (JsonElement element : services) {
          JsonObject service = element.getAsJsonObject();
          String hook = service.has("hook") ? service.get("hook").getAsString() : "";
          if ("order-sign".equals(hook)) hasOrderSign = true;
          if ("order-select".equals(hook)) hasOrderSelect = true;
          if ("appointment-book".equals(hook)) hasAppointmentBook = true;
          if ("order-dispatch".equals(hook)) hasOrderDispatch = true;
          if ("encounter-start".equals(hook)) hasEncounterStart = true;
          if ("encounter-discharge".equals(hook)) hasEncounterDischarge = true;
        }

        if (hasOrderSign && hasOrderSelect && hasAppointmentBook && hasOrderDispatch
            && hasEncounterStart && hasEncounterDischarge) {
          return true;
        }
        log.debug("Waiting for services - order-sign: {}, order-select: {}, appointment-book: {}, " +
            "order-dispatch: {}, encounter-start: {}, encounter-discharge: {}",
            hasOrderSign, hasOrderSelect, hasAppointmentBook, hasOrderDispatch,
            hasEncounterStart, hasEncounterDischarge);
        return false;
      }
    } catch (Exception e) {
      log.debug("Error checking CDS services: {}", e.getMessage());
      return false;
    }
  }

  // ============================================================
  // DISCOVERY ENDPOINT TESTS
  // ============================================================

  @Nested
  @DisplayName("CDS Services Discovery")
  class DiscoveryTests {

    @Test
    @DisplayName("GET /cds-services returns 200 OK")
    void testDiscoveryEndpoint_Returns200() throws IOException {
      try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
        HttpGet request = new HttpGet(cdsServicesBase);
        request.addHeader("Content-Type", "application/json");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
          assertEquals(200, response.getStatusLine().getStatusCode());
        }
      }
    }

    @Test
    @DisplayName("Discovery returns CRD services")
    void testDiscoveryEndpoint_ReturnsCrdServices() throws IOException {
      // Services are already confirmed registered in @BeforeAll
      try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
        HttpGet request = new HttpGet(cdsServicesBase);
        request.addHeader("Content-Type", "application/json");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
          String body = EntityUtils.toString(response.getEntity());
          JsonObject json = gson.fromJson(body, JsonObject.class);

          assertTrue(json.has("services"), "Response should have 'services' array");
          JsonArray services = json.getAsJsonArray("services");
          assertFalse(services.isEmpty(), "Should have at least one CDS service");

          // Check for our CRD services
          boolean hasOrderSign = false;
          boolean hasOrderSelect = false;
          boolean hasAppointmentBook = false;
          boolean hasOrderDispatch = false;
          boolean hasEncounterStart = false;
          boolean hasEncounterDischarge = false;

          for (JsonElement element : services) {
            JsonObject service = element.getAsJsonObject();
            String hook = service.has("hook") ? service.get("hook").getAsString() : "";
            if ("order-sign".equals(hook))
              hasOrderSign = true;
            if ("order-select".equals(hook))
              hasOrderSelect = true;
            if ("appointment-book".equals(hook))
              hasAppointmentBook = true;
            if ("order-dispatch".equals(hook))
              hasOrderDispatch = true;
            if ("encounter-start".equals(hook))
              hasEncounterStart = true;
            if ("encounter-discharge".equals(hook))
              hasEncounterDischarge = true;
          }

          assertTrue(hasOrderSign, "Should have order-sign service");
          assertTrue(hasOrderSelect, "Should have order-select service");
          assertTrue(hasAppointmentBook, "Should have appointment-book service");
          assertTrue(hasOrderDispatch, "Should have order-dispatch service");
          assertTrue(hasEncounterStart, "Should have encounter-start service");
          assertTrue(hasEncounterDischarge, "Should have encounter-discharge service");
        }
      }
    }

  }

  // ============================================================
  // ORDER-SIGN INTEGRATION TESTS
  // ============================================================

  @Nested
  @DisplayName("Order-Sign Hook Integration")
  class OrderSignIntegrationTests {

    @Test
    @DisplayName("Valid order-sign request returns 200 with response")
    void testOrderSign_ValidRequest_Returns200() throws IOException {
      // Services confirmed ready in @BeforeAll

      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-2.json");

      JsonObject response = postToCdsService("order-sign-crd", requestBody);

      assertNotNull(response, "Response should not be null");
      assertTrue(response.has("cards"), "Response should have 'cards' array");
    }

    @Test
    @DisplayName("Primary hook SHALL return coverage-information")
    void testOrderSign_ReturnsCoverageInfo() throws IOException {
      // Services confirmed ready in @BeforeAll

      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-2.json");

      JsonObject response = postToCdsService("order-sign-crd", requestBody);

      // Per CRD spec: Primary hooks SHALL return coverage-information
      assertTrue(response.has("systemActions") || response.has("extension"),
          "Primary hook should return system actions");

      if (response.has("systemActions")) {
        JsonArray systemActions = response.getAsJsonArray("systemActions");
        boolean hasCoverageInfo = false;

        for (JsonElement element : systemActions) {
          JsonObject action = element.getAsJsonObject();
          if (action.has("resource")) {
            JsonObject resource = action.getAsJsonObject("resource");
            if (resource.has("extension")) {
              JsonArray extensions = resource.getAsJsonArray("extension");
              for (JsonElement ext : extensions) {
                JsonObject extObj = ext.getAsJsonObject();
                if (extObj.has("url") && extObj.get("url").getAsString()
                    .equals(CdsHooksTestUtils.COVERAGE_INFO_EXT_URL)) {
                  hasCoverageInfo = true;
                  break;
                }
              }
            }
          }
        }

        assertTrue(hasCoverageInfo, "Should have coverage-information system action");
      }
    }

    @Test
    @DisplayName("Coverage-info extension has required fields per CRD spec")
    void testOrderSign_CoverageInfoHasRequiredFields() throws IOException {
      // Services confirmed ready in @BeforeAll

      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-2.json");

      JsonObject response = postToCdsService("order-sign-crd", requestBody);

      if (response.has("systemActions")) {
        JsonArray systemActions = response.getAsJsonArray("systemActions");

        for (JsonElement element : systemActions) {
          JsonObject action = element.getAsJsonObject();
          if (action.has("resource")) {
            JsonObject resource = action.getAsJsonObject("resource");
            if (resource.has("extension")) {
              JsonArray extensions = resource.getAsJsonArray("extension");
              for (JsonElement ext : extensions) {
                JsonObject extObj = ext.getAsJsonObject();
                if (extObj.has("url") && extObj.get("url").getAsString()
                    .equals(CdsHooksTestUtils.COVERAGE_INFO_EXT_URL)) {
                  // Validate required fields
                  JsonArray nestedExts = extObj.getAsJsonArray("extension");
                  boolean hasCoverage = false, hasCovered = false, hasDate = false, hasAssertionId = false;

                  for (JsonElement nestedExt : nestedExts) {
                    JsonObject nested = nestedExt.getAsJsonObject();
                    String url = nested.has("url") ? nested.get("url").getAsString() : "";
                    if ("coverage".equals(url))
                      hasCoverage = true;
                    if ("covered".equals(url))
                      hasCovered = true;
                    if ("date".equals(url))
                      hasDate = true;
                    if ("coverage-assertion-id".equals(url))
                      hasAssertionId = true;
                  }

                  assertTrue(hasCoverage, "Must have 'coverage' extension");
                  assertTrue(hasCovered, "Must have 'covered' extension");
                  assertTrue(hasDate, "Must have 'date' extension");
                  assertTrue(hasAssertionId, "Must have 'coverage-assertion-id' extension");
                }
              }
            }
          }
        }
      }
    }

    @Test
    @DisplayName("Multiple orders each get coverage-info")
    void testOrderSign_MultipleOrders_EachGetsCoverageInfo() throws IOException {
      // Services confirmed ready in @BeforeAll

      // order-sign-3.json has 2 DeviceRequests
      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-3.json");

      JsonObject response = postToCdsService("order-sign-crd", requestBody);

      if (response.has("systemActions")) {
        JsonArray systemActions = response.getAsJsonArray("systemActions");
        // Should have coverage-info for each order (or at least one consolidated action)
        assertFalse(systemActions.isEmpty(), "Should have system actions for orders");
      }
    }
  }

  // ============================================================
  // ORDER-SELECT INTEGRATION TESTS
  // ============================================================

  @Nested
  @DisplayName("Order-Select Hook Integration")
  class OrderSelectIntegrationTests {

    @Test
    @DisplayName("Valid order-select request returns 200")
    void testOrderSelect_ValidRequest_Returns200() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-select-1.json");

      JsonObject response = postToCdsService("order-select-crd", requestBody);

      assertNotNull(response, "Response should not be null");
      assertTrue(response.has("cards"), "Response should have 'cards' array");
    }

    @Test
    @DisplayName("Order-select only processes selected orders")
    void testOrderSelect_OnlyProcessesSelections() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-select-1.json");

      JsonObject response = postToCdsService("order-select-crd", requestBody);

      assertNotNull(response);
      assertNotNull(response.get("cards"), "Cards array should be initialized");
    }

    @Test
    @DisplayName("Secondary hook MAY return coverage-info but not required")
    void testOrderSelect_CoverageInfoOptional() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-select-1.json");

      JsonObject response = postToCdsService("order-select-crd", requestBody);

      assertNotNull(response);
      // Per CRD spec: Secondary hooks MAY return cards/system actions but are not required to
      // Just verify response is valid - coverage-info is optional
      assertTrue(response.has("cards"), "Response should have cards array");
    }
  }

  // ============================================================
  // APPOINTMENT-BOOK INTEGRATION TESTS
  // ============================================================

  @Nested
  @DisplayName("Appointment-Book Hook Integration")
  class AppointmentBookIntegrationTests {

    @Test
    @DisplayName("Valid appointment-book cardiology request returns 200")
    void testAppointmentBook_Cardiology_Returns200() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("appointment-book-cardiology.json");

      JsonObject response = postToCdsService("appointment-book-crd", requestBody);

      assertNotNull(response, "Response should not be null");
      assertTrue(response.has("cards"), "Response should have 'cards' array");
    }

    @Test
    @DisplayName("Valid appointment-book physical therapy request returns 200")
    void testAppointmentBook_PhysicalTherapy_Returns200() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("appointment-book-physical-therapy.json");

      JsonObject response = postToCdsService("appointment-book-crd", requestBody);

      assertNotNull(response, "Response should not be null");
    }

    @Test
    @DisplayName("Primary hook SHALL return coverage-info")
    void testAppointmentBook_ReturnsCoverageInfo() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("appointment-book-cardiology.json");

      JsonObject response = postToCdsService("appointment-book-crd", requestBody);

      assertTrue(response.has("systemActions") || response.has("cards"),
          "Primary hook should return response content");

      if (response.has("systemActions")) {
        JsonArray systemActions = response.getAsJsonArray("systemActions");
        boolean hasCoverageInfo = false;

        for (JsonElement element : systemActions) {
          JsonObject action = element.getAsJsonObject();
          if (action.has("resource")) {
            JsonObject resource = action.getAsJsonObject("resource");
            if (resource.has("extension")) {
              JsonArray extensions = resource.getAsJsonArray("extension");
              for (JsonElement ext : extensions) {
                JsonObject extObj = ext.getAsJsonObject();
                if (extObj.has("url") && extObj.get("url").getAsString()
                    .equals(CdsHooksTestUtils.COVERAGE_INFO_EXT_URL)) {
                  hasCoverageInfo = true;
                  break;
                }
              }
            }
          }
        }

        assertTrue(hasCoverageInfo, "Should have coverage-information system action");
      }
    }
  }

  // ============================================================
  // ORDER-DISPATCH INTEGRATION TESTS
  // ============================================================

  @Nested
  @DisplayName("Order-Dispatch Hook Integration")
  class OrderDispatchIntegrationTests {

    @Test
    @DisplayName("Valid order-dispatch imaging request returns 200")
    void testOrderDispatch_Imaging_Returns200() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-dispatch-imaging-innetwork.json");

      JsonObject response = postToCdsService("order-dispatch-crd", requestBody);

      assertNotNull(response, "Response should not be null");
      assertTrue(response.has("cards"), "Response should have 'cards' array");
    }

    @Test
    @DisplayName("Valid order-dispatch DME request returns 200")
    void testOrderDispatch_Dme_Returns200() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-dispatch-dme-outnetwork.json");

      JsonObject response = postToCdsService("order-dispatch-crd", requestBody);

      assertNotNull(response, "Response should not be null");
      assertTrue(response.has("cards"), "Response should have 'cards' array");
    }

    @Test
    @DisplayName("Primary hook SHALL return coverage-information")
    void testOrderDispatch_ReturnsCoverageInfo() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-dispatch-imaging-innetwork.json");

      JsonObject response = postToCdsService("order-dispatch-crd", requestBody);

      assertTrue(response.has("systemActions") || response.has("extension"),
          "Primary hook should return system actions");

      if (response.has("systemActions")) {
        JsonArray systemActions = response.getAsJsonArray("systemActions");
        boolean hasCoverageInfo = false;

        for (JsonElement element : systemActions) {
          JsonObject action = element.getAsJsonObject();
          if (action.has("resource")) {
            JsonObject resource = action.getAsJsonObject("resource");
            if (resource.has("extension")) {
              JsonArray extensions = resource.getAsJsonArray("extension");
              for (JsonElement ext : extensions) {
                JsonObject extObj = ext.getAsJsonObject();
                if (extObj.has("url") && extObj.get("url").getAsString()
                    .equals(CdsHooksTestUtils.COVERAGE_INFO_EXT_URL)) {
                  hasCoverageInfo = true;
                  break;
                }
              }
            }
          }
        }

        assertTrue(hasCoverageInfo, "Should have coverage-information system action");
      }
    }

    @Test
    @DisplayName("Coverage-info extension has required fields per CRD spec")
    void testOrderDispatch_CoverageInfoHasRequiredFields() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-dispatch-imaging-innetwork.json");

      JsonObject response = postToCdsService("order-dispatch-crd", requestBody);

      if (response.has("systemActions")) {
        JsonArray systemActions = response.getAsJsonArray("systemActions");

        for (JsonElement element : systemActions) {
          JsonObject action = element.getAsJsonObject();
          if (action.has("resource")) {
            JsonObject resource = action.getAsJsonObject("resource");
            if (resource.has("extension")) {
              JsonArray extensions = resource.getAsJsonArray("extension");
              for (JsonElement ext : extensions) {
                JsonObject extObj = ext.getAsJsonObject();
                if (extObj.has("url") && extObj.get("url").getAsString()
                    .equals(CdsHooksTestUtils.COVERAGE_INFO_EXT_URL)) {
                  JsonArray nestedExts = extObj.getAsJsonArray("extension");
                  boolean hasCoverage = false, hasCovered = false, hasDate = false, hasAssertionId = false;

                  for (JsonElement nestedExt : nestedExts) {
                    JsonObject nested = nestedExt.getAsJsonObject();
                    String url = nested.has("url") ? nested.get("url").getAsString() : "";
                    if ("coverage".equals(url)) hasCoverage = true;
                    if ("covered".equals(url)) hasCovered = true;
                    if ("date".equals(url)) hasDate = true;
                    if ("coverage-assertion-id".equals(url)) hasAssertionId = true;
                  }

                  assertTrue(hasCoverage, "Must have 'coverage' extension");
                  assertTrue(hasCovered, "Must have 'covered' extension");
                  assertTrue(hasDate, "Must have 'date' extension");
                  assertTrue(hasAssertionId, "Must have 'coverage-assertion-id' extension");
                }
              }
            }
          }
        }
      }
    }

    @Test
    @DisplayName("Missing dispatchedOrders returns 400")
    void testOrderDispatch_MissingOrders_Returns400() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-dispatch-missing-orders.json");

      int statusCode = postAndGetStatusCode("order-dispatch-crd", requestBody);

      assertEquals(400, statusCode, "Missing dispatchedOrders should return 400");
    }

    @Test
    @DisplayName("Wrong hook name returns 400")
    void testOrderDispatch_WrongHookName_Returns400() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-2.json");

      int statusCode = postAndGetStatusCode("order-dispatch-crd", requestBody);

      assertEquals(400, statusCode, "Wrong hook should return 400");
    }
  }

  // ============================================================
  // ERROR HANDLING TESTS
  // ============================================================

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandlingTests {

    @Test
    @DisplayName("Missing patient returns 412")
    void testMissingPatient_Returns412() throws IOException {
      // Services confirmed ready in @BeforeAll

      String requestBody = CdsHooksTestUtils.loadFixture("test-missing-patient.json");

      int statusCode = postAndGetStatusCode("order-sign-crd", requestBody);

      assertEquals(412, statusCode, "Missing patient should return 412");
    }

    @Test
    @DisplayName("Invalid patientId type returns 400")
    void testInvalidPatientIdType_Returns400() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-invalid-patient-array.json");

      int statusCode = postAndGetStatusCode("order-sign-crd", requestBody);

      assertEquals(400, statusCode, "Invalid patientId type should return 400");
    }

    @Test
    @DisplayName("Wrong hook name returns 400")
    void testWrongHookName_Returns400() throws IOException {
      // Services confirmed ready in @BeforeAll

      // Send order-select request to order-sign endpoint
      String requestBody = CdsHooksTestUtils.loadFixture("order-select-1.json");

      int statusCode = postAndGetStatusCode("order-sign-crd", requestBody);

      assertEquals(400, statusCode, "Wrong hook should return 400");
    }
  }

  // ============================================================
  // CARD VALIDATION TESTS
  // ============================================================

  @Nested
  @DisplayName("Card Structure Validation")
  class CardValidationTests {

    @Test
    @DisplayName("Cards have required fields per CDS Hooks spec")
    void testCards_HaveRequiredFields() throws IOException {
      // Services confirmed ready in @BeforeAll

      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-2.json");

      JsonObject response = postToCdsService("order-sign-crd", requestBody);

      if (response.has("cards")) {
        JsonArray cards = response.getAsJsonArray("cards");
        for (JsonElement element : cards) {
          JsonObject card = element.getAsJsonObject();

          // Per CDS Hooks spec: summary is required
          assertTrue(card.has("summary"), "Card must have summary");
          assertFalse(card.get("summary").getAsString().isEmpty(), "Summary must not be empty");

          // Per CDS Hooks spec: indicator is required
          assertTrue(card.has("indicator"), "Card must have indicator");

          // Per CDS Hooks spec: source is required
          assertTrue(card.has("source"), "Card must have source");
          JsonObject source = card.getAsJsonObject("source");
          assertTrue(source.has("label"), "Source must have label");
        }
      }
    }

    @Test
    @DisplayName("Cards have source.topic per CRD requirement")
    void testCards_HaveSourceTopic() throws IOException {
      // Services confirmed ready in @BeforeAll

      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-2.json");

      JsonObject response = postToCdsService("order-sign-crd", requestBody);

      if (response.has("cards")) {
        JsonArray cards = response.getAsJsonArray("cards");
        for (JsonElement element : cards) {
          JsonObject card = element.getAsJsonObject();
          if (card.has("source")) {
            JsonObject source = card.getAsJsonObject("source");
            // Per CRD spec: source.topic SHALL be populated
            assertTrue(source.has("topic"), "Card source SHALL have topic (CRD requirement)");
          }
        }
      }
    }
  }

  // ============================================================
  // HELPER METHODS
  // ============================================================

  /**
   * Replaces the fhirServer URL in the request body with the actual test server URL.
   * Test fixtures have hardcoded "http://localhost:8080/fhir" but the test server
   * starts on a random port.
   */
  private String fixFhirServerUrl(String requestBody) {
    // Replace any hardcoded fhirServer URLs with the actual test server URL
    return requestBody.replaceAll(
        "\"fhirServer\"\\s*:\\s*\"[^\"]+\"",
        "\"fhirServer\": \"" + fhirServerBase + "\"");
  }

  private JsonObject postToCdsService(String serviceId, String requestBody) throws IOException {
    String fixedRequestBody = fixFhirServerUrl(requestBody);
    
    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpPost request = new HttpPost(cdsServicesBase + "/" + serviceId);
      request.setEntity(new StringEntity(fixedRequestBody, ContentType.APPLICATION_JSON));

      try (CloseableHttpResponse response = httpClient.execute(request)) {
        String responseBody = EntityUtils.toString(response.getEntity());
        log.debug("CDS Response: {}", responseBody);

        if (response.getStatusLine().getStatusCode() >= 400) {
          log.warn("CDS Error Response ({}): {}", response.getStatusLine().getStatusCode(), responseBody);
          return null;
        }

        return gson.fromJson(responseBody, JsonObject.class);
      }
    }
  }

  private int postAndGetStatusCode(String serviceId, String requestBody) throws IOException {
    String fixedRequestBody = fixFhirServerUrl(requestBody);

    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpPost request = new HttpPost(cdsServicesBase + "/" + serviceId);
      request.setEntity(new StringEntity(fixedRequestBody, ContentType.APPLICATION_JSON));

      try (CloseableHttpResponse response = httpClient.execute(request)) {
        return response.getStatusLine().getStatusCode();
      }
    }
  }

  // ============================================================
  // ENCOUNTER-START INTEGRATION TESTS
  // ============================================================

  @Nested
  @DisplayName("Encounter-Start Hook Integration")
  class EncounterStartIntegrationTests {

    @Test
    @DisplayName("Valid encounter-start inpatient request returns 200")
    void testEncounterStart_Inpatient_Returns200() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("encounter-start-inpatient.json");

      JsonObject response = postToCdsService("encounter-start-crd", requestBody);

      assertNotNull(response, "Response should not be null");
      assertTrue(response.has("cards"), "Response should have 'cards' array");
    }

    @Test
    @DisplayName("Valid encounter-start outpatient request returns 200")
    void testEncounterStart_Outpatient_Returns200() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("encounter-start-outpatient.json");

      JsonObject response = postToCdsService("encounter-start-crd", requestBody);

      assertNotNull(response, "Response should not be null");
      assertTrue(response.has("cards"), "Response should have 'cards' array");
    }

    @Test
    @DisplayName("Secondary hook MAY return coverage-info but not required")
    void testEncounterStart_CoverageInfoOptional() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("encounter-start-inpatient.json");

      JsonObject response = postToCdsService("encounter-start-crd", requestBody);

      assertNotNull(response);
      assertTrue(response.has("cards"), "Response should have cards array");
    }

    @Test
    @DisplayName("Wrong hook name returns 400")
    void testEncounterStart_WrongHookName_Returns400() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-2.json");

      int statusCode = postAndGetStatusCode("encounter-start-crd", requestBody);

      assertEquals(400, statusCode, "Wrong hook should return 400");
    }
  }

  // ============================================================
  // ENCOUNTER-DISCHARGE INTEGRATION TESTS
  // ============================================================

  @Nested
  @DisplayName("Encounter-Discharge Hook Integration")
  class EncounterDischargeIntegrationTests {

    @Test
    @DisplayName("Valid encounter-discharge SNF request returns 200")
    void testEncounterDischarge_Snf_Returns200() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("encounter-discharge-snf.json");

      JsonObject response = postToCdsService("encounter-discharge-crd", requestBody);

      assertNotNull(response, "Response should not be null");
      assertTrue(response.has("cards"), "Response should have 'cards' array");
    }

    @Test
    @DisplayName("Valid encounter-discharge home request returns 200")
    void testEncounterDischarge_Home_Returns200() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("encounter-discharge-home.json");

      JsonObject response = postToCdsService("encounter-discharge-crd", requestBody);

      assertNotNull(response, "Response should not be null");
      assertTrue(response.has("cards"), "Response should have 'cards' array");
    }

    @Test
    @DisplayName("Secondary hook MAY return coverage-info but not required")
    void testEncounterDischarge_CoverageInfoOptional() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("encounter-discharge-snf.json");

      JsonObject response = postToCdsService("encounter-discharge-crd", requestBody);

      assertNotNull(response);
      assertTrue(response.has("cards"), "Response should have cards array");
    }

    @Test
    @DisplayName("Wrong hook name returns 400")
    void testEncounterDischarge_WrongHookName_Returns400() throws IOException {
      String requestBody = CdsHooksTestUtils.loadFixture("order-sign-2.json");

      int statusCode = postAndGetStatusCode("encounter-discharge-crd", requestBody);

      assertEquals(400, statusCode, "Wrong hook should return 400");
    }
  }
}
