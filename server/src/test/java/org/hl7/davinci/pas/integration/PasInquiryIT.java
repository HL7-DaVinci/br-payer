package org.hl7.davinci.pas.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.hl7.davinci.pas.PasExtensions;
import org.hl7.davinci.pas.PasInquiryService;
import org.hl7.davinci.pas.PasSubmitService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
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
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.starter.cdshooks.StarterCdsHooksConfig;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceRegistry;

/**
 * Integration test for PAS $inquire operation.
 * Verifies the submit-then-inquire round trip: submits an authorization request,
 * then queries for it via $inquire and validates the stored authorization is returned.
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
        "spring.datasource.url=jdbc:h2:mem:dbr4-pas-inquiry-it",
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
class PasInquiryIT {

  private static final Logger log = LoggerFactory.getLogger(PasInquiryIT.class);
  private static final String REFERRAL_BUNDLE = "examples-pas/Bundle-ReferralAuthorizationBundleExample.json";

  @LocalServerPort
  private int port;

  @Autowired
  private PasSubmitService submitService;

  @Autowired
  private PasInquiryService inquiryService;

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
    log.info("Server initialized, running PAS $inquire integration tests");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("$inquire returns matching authorization after $submit")
  void inquireReturnsMatchingAuthorizationAfterSubmit() {
    Bundle submitBundle = loadBundle(REFERRAL_BUNDLE);
    submitService.submit(submitBundle);

    Bundle inquiryBundle = buildInquiryBundle(submitBundle);
    Parameters result = inquiryService.inquire(inquiryBundle);

    assertNotNull(result, "Inquiry should return a Parameters resource");
    assertTrue(result.hasParameter(), "Parameters should contain at least one responseBundle");

    Parameters.ParametersParameterComponent param = result.getParameter().stream()
        .filter(p -> "responseBundle".equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No responseBundle parameter found"));

    assertInstanceOf(Bundle.class, param.getResource(),
        "responseBundle parameter should contain a Bundle");
    Bundle responseBundle = (Bundle) param.getResource();
    assertEquals(Bundle.BundleType.COLLECTION, responseBundle.getType());

    assertTrue(responseBundle.hasEntry(), "Response bundle should have entries");
    assertInstanceOf(ClaimResponse.class, responseBundle.getEntryFirstRep().getResource(),
        "First entry should be a ClaimResponse");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("$inquire response contains the submitted ClaimResponse with reviewAction")
  void inquireResponseContainsSubmittedClaimResponseWithReviewAction() {
    Bundle submitBundle = loadBundle(REFERRAL_BUNDLE);
    Bundle submitResponse = submitService.submit(submitBundle);
    ClaimResponse submittedCr = (ClaimResponse) submitResponse.getEntryFirstRep().getResource();
    String submittedId = submittedCr.getIdElement().getIdPart();

    Bundle inquiryBundle = buildInquiryBundle(submitBundle);
    Parameters result = inquiryService.inquire(inquiryBundle);

    // Find the specific ClaimResponse we submitted (pre-loaded examples may also match)
    ClaimResponse matchedCr = result.getParameter().stream()
        .filter(p -> "responseBundle".equals(p.getName()))
        .map(p -> (Bundle) p.getResource())
        .map(b -> (ClaimResponse) b.getEntryFirstRep().getResource())
        .filter(cr -> submittedId.equals(cr.getIdElement().getIdPart()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "Submitted ClaimResponse " + submittedId + " not found in inquiry results"));

    assertEquals(ClaimResponse.Use.PREAUTHORIZATION, matchedCr.getUse());
    assertEquals(submittedCr.getItem().size(), matchedCr.getItem().size(),
        "Inquired ClaimResponse should have same number of items as submitted");

    for (ClaimResponse.ItemComponent item : matchedCr.getItem()) {
      assertFalse(item.getAdjudication().isEmpty(),
          "Item " + item.getItemSequence() + " should have adjudication");
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("$inquire with no matching authorization returns empty Parameters")
  void inquireWithNoMatchReturnsEmptyParameters() {
    Bundle submitBundle = loadBundle(REFERRAL_BUNDLE);
    // Build inquiry with a non-matching identifier
    Bundle inquiryBundle = buildInquiryBundle(submitBundle);
    Claim inquiryClaim = (Claim) inquiryBundle.getEntryFirstRep().getResource();
    inquiryClaim.getIdentifier().clear();
    inquiryClaim.addIdentifier()
        .setSystem("http://example.org/NON_MATCHING")
        .setValue("999999");

    Parameters result = inquiryService.inquire(inquiryBundle);

    assertNotNull(result);
    assertTrue(result.getParameter().stream()
        .noneMatch(p -> "responseBundle".equals(p.getName())),
        "Should return no responseBundle for non-matching inquiry");
  }

  /**
   * Builds a PAS inquiry bundle from the submit bundle, reusing the same
   * patient/insurer/provider references and Claim identifiers. Adds the
   * required MB (Member ID) identifier to the Patient for inquiry validation.
   */
  private Bundle buildInquiryBundle(Bundle submitBundle) {
    Claim submitClaim = (Claim) submitBundle.getEntryFirstRep().getResource();

    Claim inquiryClaim = new Claim();
    inquiryClaim.getMeta().addProfile(PasExtensions.PROFILE_PAS_CLAIM_INQUIRY);
    inquiryClaim.setStatus(Claim.ClaimStatus.ACTIVE);
    inquiryClaim.setType(submitClaim.getType().copy());
    inquiryClaim.setUse(Claim.Use.PREAUTHORIZATION);
    inquiryClaim.setPatient(submitClaim.getPatient().copy());
    inquiryClaim.setInsurer(submitClaim.getInsurer().copy());
    inquiryClaim.setProvider(submitClaim.getProvider().copy());
    inquiryClaim.setPriority(submitClaim.getPriority().copy());
    inquiryClaim.setCreated(new Date());

    // Copy insurance references for coverage matching
    for (Claim.InsuranceComponent ins : submitClaim.getInsurance()) {
      inquiryClaim.addInsurance(ins.copy());
    }

    // Copy identifiers for identifier matching
    for (Identifier id : submitClaim.getIdentifier()) {
      inquiryClaim.addIdentifier(id.copy());
    }

    Bundle inquiryBundle = new Bundle();
    inquiryBundle.setType(Bundle.BundleType.COLLECTION);
    inquiryBundle.getMeta().addProfile(PasExtensions.PROFILE_PAS_INQUIRY_REQUEST_BUNDLE);
    inquiryBundle.setIdentifier(new Identifier()
        .setSystem("http://example.org/INQUIRY_BUNDLE_ID")
        .setValue(UUID.randomUUID().toString()));
    inquiryBundle.setTimestamp(new Date());

    inquiryBundle.addEntry()
        .setFullUrl("urn:uuid:inquiry-claim")
        .setResource(inquiryClaim);

    // Copy supporting resources from the submit bundle, adding MB identifier to Patient
    for (Bundle.BundleEntryComponent entry : submitBundle.getEntry()) {
      Resource resource = entry.getResource();
      if (resource instanceof Patient patient) {
        Patient inquiryPatient = patient.copy();
        addMemberIdentifier(inquiryPatient);
        inquiryBundle.addEntry()
            .setFullUrl(entry.getFullUrl())
            .setResource(inquiryPatient);
      } else if (resource instanceof Organization || resource instanceof Coverage) {
        inquiryBundle.addEntry()
            .setFullUrl(entry.getFullUrl())
            .setResource(resource);
      }
    }

    return inquiryBundle;
  }

  /**
   * Adds the MB (Member Number) identifier type required by PAS inquiry validation.
   */
  private void addMemberIdentifier(Patient patient) {
    boolean hasMb = patient.getIdentifier().stream()
        .anyMatch(id -> id.hasType() && id.getType().getCoding().stream()
            .anyMatch(c -> "http://terminology.hl7.org/CodeSystem/v2-0203".equals(c.getSystem())
                && "MB".equals(c.getCode())));
    if (!hasMb) {
      Identifier mbId = patient.addIdentifier();
      mbId.setSystem("http://example.org/MEMBER_ID");
      mbId.setValue("MB-12345678901");
      mbId.setType(new CodeableConcept().addCoding(
          new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "MB", "Member Number")));
    }
  }

  private Bundle loadBundle(String classpathPath) {
    InputStream is = getClass().getClassLoader().getResourceAsStream(classpathPath);
    assertNotNull(is, "Test fixture not found: " + classpathPath);
    return (Bundle) FhirContext.forR4Cached().newJsonParser().parseResource(is);
  }
}
