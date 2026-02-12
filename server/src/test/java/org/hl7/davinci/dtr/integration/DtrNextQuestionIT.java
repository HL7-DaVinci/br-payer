package org.hl7.davinci.dtr.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.hl7.davinci.dtr.AdaptiveNextQuestionService;
import org.hl7.davinci.dtr.DtrPackageService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.starter.cdshooks.StarterCdsHooksConfig;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceRegistry;

/**
 * Integration test for the $next-question operation using the
 * OpioidPrescribingJustification adaptive questionnaire.
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
        "spring.datasource.url=jdbc:h2:mem:dbr4-nq-it",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
        "hapi.fhir.enable_repository_validating_interceptor=true",
        "hapi.fhir.fhir_version=r4",
        "hapi.fhir.cr.enabled=true",
        "hapi.fhir.cdshooks.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "vsac.api-key=",
        "dtr.adaptive.next-question-url=http://localhost:8080/fhir/Questionnaire/$next-question"
    }
)
class DtrNextQuestionIT {

  private static final Logger log = LoggerFactory.getLogger(DtrNextQuestionIT.class);
  private static final String JUSTIFICATION_CANONICAL =
      "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/OpioidPrescribingJustification";

  @LocalServerPort
  private int port;

  @Autowired private DaoRegistry daoRegistry;
  @Autowired private DtrPackageService dtrPackageService;
  @Autowired private AdaptiveNextQuestionService nextQuestionService;
  @Autowired private ICdsServiceRegistry cdsServiceRegistry;

  private Patient testPatient;
  private Coverage testCoverage;

  @BeforeAll
  void setUpOnce() {
    log.info("Waiting for CDS services to be registered (port {})...", port);
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
    log.info("CDS services registered. Setting up $next-question test data...");

    testPatient = new Patient();
    testPatient.setId("nq-test-patient");
    testPatient.addName().setFamily("Doe").addGiven("Jane");
    testPatient.setBirthDateElement(new DateType("1975-08-20"));
    testPatient = (Patient) daoRegistry.getResourceDao(Patient.class)
        .update(testPatient, new SystemRequestDetails()).getResource();

    Organization testOrg = new Organization();
    testOrg.setId("nq-test-org");
    testOrg.setName("NQ Test Payor");
    testOrg.addIdentifier().setSystem("urn:oid:2.16.840.1.113883.6.300").setValue("00001");
    testOrg.addIdentifier().setSystem("http://hl7.org/fhir/sid/us-npi").setValue("1234567893");
    testOrg = (Organization) daoRegistry.getResourceDao(Organization.class)
        .update(testOrg, new SystemRequestDetails()).getResource();

    testCoverage = new Coverage();
    testCoverage.setId("nq-test-coverage");
    testCoverage.setStatus(Coverage.CoverageStatus.ACTIVE);
    testCoverage.setBeneficiary(new Reference("Patient/" + testPatient.getIdElement().getIdPart()));
    testCoverage.addPayor(new Reference("Organization/" + testOrg.getIdElement().getIdPart()));
    testCoverage = (Coverage) daoRegistry.getResourceDao(Coverage.class)
        .update(testCoverage, new SystemRequestDetails()).getResource();

    log.info("$next-question test data setup complete.");
  }

  // ============================================================
  // FULL ADAPTIVE FLOW
  // ============================================================

  @Nested
  @DisplayName("Full adaptive flow")
  class FullAdaptiveFlow {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("First call delivers groups 1-5, second call evaluates group 6")
    void fullAdaptiveFlow_twoRoundTrips() throws IOException {
      // Step 1: Generate adaptive package (provides the initial QR with derivedFrom)
      Parameters packageResult = dtrPackageService.generatePackages(
          testCoverage, List.of(), List.of(new CanonicalType(JUSTIFICATION_CANONICAL)), null);

      Bundle bundle = extractPackageBundle(packageResult);
      assertNotNull(bundle, "Should produce adaptive package");

      QuestionnaireResponse adaptiveQr = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(QuestionnaireResponse.class::isInstance)
          .map(QuestionnaireResponse.class::cast)
          .findFirst().orElse(null);
      assertNotNull(adaptiveQr, "Bundle should contain adaptive QR");

      // Step 2: First $next-question call using the QR from the package directly
      long startFirst = System.currentTimeMillis();
      Parameters firstResult = nextQuestionService.processNextQuestion(adaptiveQr);
      long firstDuration = System.currentTimeMillis() - startFirst;
      log.info("First $next-question call took {}ms", firstDuration);

      QuestionnaireResponse firstQr = (QuestionnaireResponse) firstResult.getParameter()
          .get(0).getResource();
      Questionnaire firstContained = extractContainedQ(firstQr);

      // Groups 1-5 should be delivered (group 6 has enableWhen on "4.1")
      assertTrue(firstContained.getItem().size() >= 5,
          "First call should deliver at least 5 groups, got " + firstContained.getItem().size());
      assertEquals(QuestionnaireResponseStatus.INPROGRESS, firstQr.getStatus());

      // Step 3: Add answer to 4.1 and call $next-question again
      firstQr.addItem().setLinkId("4.1").addAnswer().setValue(new StringType("Back pain"));

      long startSecond = System.currentTimeMillis();
      Parameters secondResult = nextQuestionService.processNextQuestion(firstQr);
      long secondDuration = System.currentTimeMillis() - startSecond;
      log.info("Second $next-question call took {}ms", secondDuration);

      QuestionnaireResponse secondQr = (QuestionnaireResponse) secondResult.getParameter()
          .get(0).getResource();
      Questionnaire secondContained = extractContainedQ(secondQr);

      // Group 6 should now be delivered
      assertTrue(secondContained.getItem().size() >= 6,
          "Second call should deliver group 6, total " + secondContained.getItem().size());

      // Performance assertions
      assertTrue(firstDuration < 5000, "First call should complete within 5 seconds");
      assertTrue(secondDuration < 1000, "Subsequent call should complete within 1 second");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("enableWhen skips group 6 when 4.1 not answered")
    void enableWhenSkipsGroup_whenQuestionNotAnswered() {
      Parameters packageResult = dtrPackageService.generatePackages(
          testCoverage, List.of(), List.of(new CanonicalType(JUSTIFICATION_CANONICAL)), null);

      Bundle bundle = extractPackageBundle(packageResult);
      QuestionnaireResponse adaptiveQr = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(QuestionnaireResponse.class::isInstance)
          .map(QuestionnaireResponse.class::cast)
          .findFirst().orElse(null);

      // First call delivers groups 1-5
      Parameters firstResult = nextQuestionService.processNextQuestion(adaptiveQr);
      QuestionnaireResponse firstQr = (QuestionnaireResponse) firstResult.getParameter()
          .get(0).getResource();

      // Don't answer 4.1, just provide some other answer
      firstQr.addItem().setLinkId("2.1").addAnswer().setValue(new StringType("Back pain"));

      // Second call: group 6 enableWhen references 4.1 which isn't answered
      Parameters secondResult = nextQuestionService.processNextQuestion(firstQr);
      QuestionnaireResponse secondQr = (QuestionnaireResponse) secondResult.getParameter()
          .get(0).getResource();

      // No new groups delivered since 4.1 isn't answered yet
      Questionnaire secondContained = extractContainedQ(secondQr);
      assertEquals(5, secondContained.getItem().size(),
          "No new groups should be delivered when enableWhen question unanswered");
    }
  }

  // ============================================================
  // ERROR CASES
  // ============================================================

  @Nested
  @DisplayName("Error cases")
  class ErrorCases {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Missing derivedFrom > 400")
    void missingDerivedFrom_returns400() {
      QuestionnaireResponse qr = new QuestionnaireResponse();
      qr.setId("test-qr");
      Questionnaire contained = new Questionnaire();
      contained.setId("contained-q");
      // No derivedFrom set
      qr.addContained(contained);

      assertThrows(InvalidRequestException.class,
          () -> nextQuestionService.processNextQuestion(qr));
    }
  }

  // ============================================================
  // HELPERS
  // ============================================================

  private Bundle extractPackageBundle(Parameters result) {
    if (result == null) return null;
    return result.getParameter().stream()
        .filter(p -> "packagebundle".equals(p.getName()))
        .map(p -> (Bundle) p.getResource())
        .findFirst().orElse(null);
  }

  private Questionnaire extractContainedQ(QuestionnaireResponse qr) {
    return qr.getContained().stream()
        .filter(Questionnaire.class::isInstance)
        .map(Questionnaire.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("QR should contain a Questionnaire"));
  }
}
