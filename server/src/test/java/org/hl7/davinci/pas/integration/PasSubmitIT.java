package org.hl7.davinci.pas.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import org.hl7.davinci.pas.PasConstants;
import org.hl7.davinci.pas.PasSubmitService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.opencds.cqf.fhir.cr.hapi.config.CrCdsHooksConfig;
import org.opencds.cqf.fhir.cr.hapi.config.RepositoryConfig;
import org.opencds.cqf.fhir.cr.hapi.config.test.TestCdsHooksConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.starter.cdshooks.StarterCdsHooksConfig;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceRegistry;

/**
 * Integration test for PAS $submit operation.
 * Verifies end-to-end submit flow: bundle validation, coverage evaluation,
 * response building, and persistence through the full Spring Boot context.
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
        "spring.datasource.url=jdbc:h2:mem:dbr4-pas-submit-it",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
        "hapi.fhir.enable_repository_validating_interceptor=false",
        "hapi.fhir.fhir_version=r4",
        "hapi.fhir.cr.enabled=true",
        "hapi.fhir.cdshooks.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "vsac.api-key=",
        "dtr.adaptive.next-question-url=http://localhost:8080/fhir/Questionnaire/$next-question"
    }
)
class PasSubmitIT {

  private static final Logger log = LoggerFactory.getLogger(PasSubmitIT.class);
  private static final String REFERRAL_BUNDLE = "examples-pas/Bundle-ReferralAuthorizationBundleExample.json";

  @LocalServerPort
  private int port;

  @Autowired
  private PasSubmitService submitService;

  @Autowired
  private DaoRegistry daoRegistry;

  @Autowired
  private ICdsServiceRegistry cdsServiceRegistry;

  @Autowired
  private org.hl7.davinci.scenarios.pas.PasScenarioService scenarioService;

  @BeforeAll
  void setUpOnce() {
    log.info("Waiting for server initialization (port {})...", port);
    await().atMost(120, TimeUnit.SECONDS)
        .pollInterval(2, TimeUnit.SECONDS)
        .until(() -> {
          try {
            var services = cdsServiceRegistry.getCdsServicesJson();
            return services != null && services.getServices() != null
                && !services.getServices().isEmpty();
          } catch (Exception e) {
            return false;
          }
        });
    log.info("Server initialized, running PAS $submit integration tests");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("$submit returns a PAS Response Bundle with ClaimResponse as first entry")
  void submitReturnsResponseBundleWithClaimResponse() {
    Bundle request = loadBundle(REFERRAL_BUNDLE);

    Bundle response = submitService.submit(request);

    assertNotNull(response);
    assertEquals(Bundle.BundleType.COLLECTION, response.getType());
    assertTrue(response.hasEntry(), "Response bundle should have entries");

    assertInstanceOf(ClaimResponse.class, response.getEntryFirstRep().getResource());
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    assertEquals(ClaimResponse.Use.PREAUTHORIZATION, cr.getUse());
    assertEquals(ClaimResponse.ClaimResponseStatus.ACTIVE, cr.getStatus());
    assertEquals(ClaimResponse.RemittanceOutcome.COMPLETE, cr.getOutcome());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("$submit response echoes item sequences from the request Claim")
  void submitResponseEchoesItemSequences() {
    Bundle request = loadBundle(REFERRAL_BUNDLE);
    Claim requestClaim = (Claim) request.getEntryFirstRep().getResource();

    Bundle response = submitService.submit(request);
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    assertEquals(requestClaim.getItem().size(), cr.getItem().size(),
        "Response should have same number of items as request");

    for (int i = 0; i < cr.getItem().size(); i++) {
      assertEquals(
          requestClaim.getItem().get(i).getSequence(),
          cr.getItem().get(i).getItemSequence(),
          "Item sequence at index " + i + " should match");
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("$submit response items have reviewAction extension on adjudication")
  void submitResponseItemsHaveReviewActionExtension() {
    Bundle request = loadBundle(REFERRAL_BUNDLE);

    Bundle response = submitService.submit(request);
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    for (ClaimResponse.ItemComponent item : cr.getItem()) {
      assertFalse(item.getAdjudication().isEmpty(),
          "Item " + item.getItemSequence() + " should have adjudication");

      Extension reviewAction = item.getAdjudicationFirstRep()
          .getExtensionByUrl(PasConstants.REVIEW_ACTION);
      assertNotNull(reviewAction,
          "Item " + item.getItemSequence() + " adjudication should have reviewAction extension");

      Extension reviewCode = reviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
      assertNotNull(reviewCode,
          "reviewAction should contain a reviewActionCode sub-extension");
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("$submit stores ClaimResponse in the database with a server-assigned ID")
  void submitStoresClaimResponseInDatabase() {
    Bundle request = loadBundle(REFERRAL_BUNDLE);

    Bundle response = submitService.submit(request);
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    String crId = cr.getIdElement().getIdPart();
    assertNotNull(crId, "ClaimResponse should have a server-assigned ID");

    ClaimResponse stored = daoRegistry.getResourceDao(ClaimResponse.class)
        .read(new IdType("ClaimResponse", crId), new SystemRequestDetails());
    assertNotNull(stored, "ClaimResponse should be retrievable from the database");
    assertEquals(ClaimResponse.Use.PREAUTHORIZATION, stored.getUse());
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("$submit response fullUrl is resolvable and matches ClaimResponse ID")
  void submitResponseFullUrlMatchesClaimResponseId() {
    Bundle request = loadBundle(REFERRAL_BUNDLE);

    Bundle response = submitService.submit(request);
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    String fullUrl = response.getEntryFirstRep().getFullUrl();

    assertNotNull(fullUrl, "Response entry should have a fullUrl");
    assertTrue(fullUrl.contains("/ClaimResponse/"),
        "fullUrl should contain /ClaimResponse/ path");
    assertTrue(fullUrl.endsWith(cr.getIdElement().getIdPart()),
        "fullUrl should end with the ClaimResponse ID");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("$submit stores provider resources under payer-assigned ids, never the provider's")
  void submitStoresResourcesUnderPayerAssignedIds() {
    String json = loadBundleJson(REFERRAL_BUNDLE)
        .replace("Patient/SubscriberExample", "Patient/1716")
        .replace("\"id\":\"SubscriberExample\"", "\"id\":\"1716\"")
        .replace("12345678901", "99912345678901")
        .replace("\"resourceType\":\"ServiceRequest\",",
            "\"resourceType\":\"ServiceRequest\",\"encounter\":{\"reference\":\"Encounter/1715\"},");
    Bundle request = (Bundle) FhirContext.forR4Cached().newJsonParser().parseResource(json);

    Bundle response = submitService.submit(request);

    assertInstanceOf(ClaimResponse.class, response.getEntryFirstRep().getResource());
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    var params = new ca.uhn.fhir.jpa.searchparam.SearchParameterMap();
    params.add("identifier",
        new ca.uhn.fhir.rest.param.TokenParam("http://example.org/MIN", "99912345678901"));
    Patient stored = daoRegistry.getResourceDao(Patient.class)
        .searchForResources(params, new SystemRequestDetails())
        .stream().findFirst().orElse(null);
    assertNotNull(stored, "Patient should be stored and findable by identifier");
    assertNotEquals("1716", stored.getIdElement().getIdPart(),
        "Stored Patient must carry a payer-assigned id, not the provider's");
    assertEquals("Patient/" + stored.getIdElement().getIdPart(),
        cr.getPatient().getReference(),
        "ClaimResponse.patient must reference the payer-side Patient");
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  @DisplayName("generated home oxygen therapy scenario pends with a questionnaire documentation request")
  void generatedHomeOxygenTherapyScenarioPendsWithDocumentationRequest() {
    Bundle request = scenarioService
        .findVariantBundle("home-oxygen-therapy", "initial")
        .orElseThrow(() -> new AssertionError("home-oxygen-therapy initial scenario not found"));

    Bundle response = submitService.submit(request);
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    assertEquals(org.hl7.davinci.common.FhirConstants.REVIEW_CODE_A4,
        org.hl7.davinci.pas.PasExtensions.extractReviewActionCode(cr.getItemFirstRep()),
        "home oxygen therapy requires prior auth with documentation, so the item must pend");
    assertTrue(cr.hasCommunicationRequest(),
        "pended response must reference the questionnaire CommunicationRequest");
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  @DisplayName("documentation-required demo scenario always pends with a documentation request")
  void documentationRequiredDemoScenarioAlwaysPends() {
    Bundle request = scenarioService
        .findVariantBundle("documentation-required", "initial")
        .orElseThrow(() -> new AssertionError("documentation-required initial scenario not found"));

    Bundle response = submitService.submit(request);
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    assertEquals(org.hl7.davinci.common.FhirConstants.REVIEW_CODE_A4,
        org.hl7.davinci.pas.PasExtensions.extractReviewActionCode(cr.getItemFirstRep()),
        "the documentation-required demo rule must always pend");
    assertTrue(cr.hasCommunicationRequest(),
        "pended response must reference the questionnaire CommunicationRequest");
  }

  private Bundle loadBundle(String classpathPath) {
    return (Bundle) FhirContext.forR4Cached().newJsonParser()
        .parseResource(loadBundleJson(classpathPath));
  }

  private String loadBundleJson(String classpathPath) {
    InputStream is = getClass().getClassLoader().getResourceAsStream(classpathPath);
    assertNotNull(is, "Test fixture not found: " + classpathPath);
    try (is) {
      return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
