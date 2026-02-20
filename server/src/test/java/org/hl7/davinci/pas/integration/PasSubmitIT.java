package org.hl7.davinci.pas.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import org.hl7.davinci.pas.PasExtensions;
import org.hl7.davinci.pas.PasSubmitService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
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
          .getExtensionByUrl(PasExtensions.REVIEW_ACTION);
      assertNotNull(reviewAction,
          "Item " + item.getItemSequence() + " adjudication should have reviewAction extension");

      Extension reviewCode = reviewAction.getExtensionByUrl(PasExtensions.REVIEW_ACTION_CODE);
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

  private Bundle loadBundle(String classpathPath) {
    InputStream is = getClass().getClassLoader().getResourceAsStream(classpathPath);
    assertNotNull(is, "Test fixture not found: " + classpathPath);
    return (Bundle) FhirContext.forR4Cached().newJsonParser().parseResource(is);
  }
}
