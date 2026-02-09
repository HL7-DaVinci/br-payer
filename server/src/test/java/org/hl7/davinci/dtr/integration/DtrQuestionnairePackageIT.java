package org.hl7.davinci.dtr.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.hl7.davinci.dtr.DtrPackageService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceRegistry;

/**
 * Integration test for the $questionnaire-package operation with the
 * HospitalBedsAndAccessories pilot module.
 *
 * Uses a full embedded FHIR server with resources loaded from library/.
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
        "spring.datasource.url=jdbc:h2:mem:dbr4-dtr-it",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
        "hapi.fhir.enable_repository_validating_interceptor=true",
        "hapi.fhir.fhir_version=r4",
        "hapi.fhir.cr.enabled=true",
        "hapi.fhir.cdshooks.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "vsac.api-key="
    }
)
class DtrQuestionnairePackageIT {

  private static final Logger log = LoggerFactory.getLogger(DtrQuestionnairePackageIT.class);
  private static final String Q_CANONICAL = "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HospitalBedsAndAccessories";

  @LocalServerPort
  private int port;

  @Autowired
  private DaoRegistry daoRegistry;

  @Autowired
  private DtrPackageService dtrPackageService;

  @Autowired
  private ICdsServiceRegistry cdsServiceRegistry;

  private Patient testPatient;
  private Organization testOrganization;
  private Coverage testCoverage;

  @BeforeAll
  void setUpOnce() {
    // Wait for CDS services to be registered (indicates full server initialization
    // including DataInitializer, CQL compilation, and CR module readiness)
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
    log.info("CDS services registered. Setting up DTR integration test data...");

    // Create Patient with demographics for pre-population testing
    testPatient = new Patient();
    testPatient.setId("dtr-test-patient");
    testPatient.addName().setFamily("Smith").addGiven("John");
    testPatient.setBirthDateElement(new DateType("1960-03-15"));
    testPatient = (Patient) daoRegistry.getResourceDao(Patient.class)
        .update(testPatient, new SystemRequestDetails()).getResource();

    // Create Organization with payor identifier matching PlanDefinition useContext
    testOrganization = new Organization();
    testOrganization.setId("dtr-test-org");
    testOrganization.setName("CMS Test Payor");
    testOrganization.addIdentifier()
        .setSystem("urn:oid:2.16.840.1.113883.6.300")
        .setValue("00001");
    testOrganization.addIdentifier()
        .setSystem("http://hl7.org/fhir/sid/us-npi")
        .setValue("1234567893");
    testOrganization = (Organization) daoRegistry.getResourceDao(Organization.class)
        .update(testOrganization, new SystemRequestDetails()).getResource();

    // Create Coverage linking patient and organization
    testCoverage = new Coverage();
    testCoverage.setId("dtr-test-coverage");
    testCoverage.setStatus(Coverage.CoverageStatus.ACTIVE);
    testCoverage.setBeneficiary(new Reference("Patient/" + testPatient.getIdElement().getIdPart()));
    testCoverage.addPayor(new Reference("Organization/" + testOrganization.getIdElement().getIdPart()));
    testCoverage = (Coverage) daoRegistry.getResourceDao(Coverage.class)
        .update(testCoverage, new SystemRequestDetails()).getResource();

    log.info("Test data setup complete. Patient={}, Org={}, Coverage={}",
        testPatient.getIdElement().getIdPart(),
        testOrganization.getIdElement().getIdPart(),
        testCoverage.getIdElement().getIdPart());
  }

  // ============================================================
  // QUESTIONNAIRE: Direct Questionnaire Canonical URL Resolution
  // ============================================================

  @Nested
  @DisplayName("Questionnaire Parameter Resolution")
  class QuestionnaireResolutionTests {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Returns package bundle with Questionnaire, Libraries, and QR")
    void questionnaire_returnsCompletePackage() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(Q_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null);

      assertNotNull(result, "Result should not be null");

      // Should have packagebundle parameter
      Parameters.ParametersParameterComponent bundleParam = result.getParameter().stream()
          .filter(p -> "packagebundle".equals(p.getName()))
          .findFirst().orElse(null);
      assertNotNull(bundleParam, "Should have packagebundle parameter");
      assertInstanceOf(Bundle.class, bundleParam.getResource());

      Bundle packageBundle = (Bundle) bundleParam.getResource();
      assertTrue(packageBundle.getEntry().size() >= 3,
          "Bundle should have at least Questionnaire + Libraries + QR");

      // First entry is Questionnaire (dtrb-1)
      Resource firstResource = packageBundle.getEntry().get(0).getResource();
      assertInstanceOf(Questionnaire.class, firstResource);
      Questionnaire q = (Questionnaire) firstResource;
      assertTrue(q.getUrl().contains("HospitalBedsAndAccessories"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Bundle profile is DTR-QPackageBundle")
    void questionnaire_bundleHasCorrectProfile() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(Q_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle);
      assertTrue(bundle.getMeta().hasProfile(
          "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/DTR-QPackageBundle"),
          "Bundle should have DTR-QPackageBundle profile");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Libraries present with ELM content")
    void questionnaire_librariesHaveElm() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(Q_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle);

      List<Library> libraries = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(Library.class::isInstance)
          .map(Library.class::cast)
          .toList();

      assertFalse(libraries.isEmpty(), "Should have at least one library");

      // Check for ELM content on at least one library
      boolean hasElm = libraries.stream()
          .anyMatch(lib -> lib.getContent().stream()
              .anyMatch(c -> "application/elm+json".equals(c.getContentType()) && c.hasData()));
      assertTrue(hasElm, "At least one library should have ELM content");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("QuestionnaireResponse present with required DTR extensions")
    void questionnaire_qrHasExtensions() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(Q_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle);

      QuestionnaireResponse qr = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(QuestionnaireResponse.class::isInstance)
          .map(QuestionnaireResponse.class::cast)
          .findFirst().orElse(null);

      assertNotNull(qr, "Bundle should contain a QuestionnaireResponse");

      // qr-coverage extension
      assertNotNull(qr.getExtensionByUrl(
          "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-coverage"),
          "QR should have qr-coverage extension");

      // intendedUse extension
      assertNotNull(qr.getExtensionByUrl(
          "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/intendedUse"),
          "QR should have intendedUse extension");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("No alternativeExpression validation errors")
    void questionnaire_noAlternativeExpressionErrors() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(Q_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null);

      // Check outcome for alternativeExpression errors
      for (Parameters.ParametersParameterComponent param : result.getParameter()) {
        if ("outcome".equals(param.getName()) && param.getResource() instanceof OperationOutcome oo) {
          for (OperationOutcome.OperationOutcomeIssueComponent issue : oo.getIssue()) {
            assertFalse(
                issue.getDiagnostics() != null && issue.getDiagnostics().contains("alternativeExpression"),
                "Should not have alternativeExpression validation errors: " + issue.getDiagnostics());
          }
        }
      }
    }
  }

  // ============================================================
  // PRE-POPULATION: Patient Demographics via CQL
  // ============================================================

  @Nested
  @DisplayName("Patient Demographics Pre-Population")
  class PatientDemographicsTests {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Patient demographics are pre-populated from repository via CQL")
    void questionnaire_patientDemographicsPrePopulated() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(Q_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Should have package bundle. Warnings: " + extractWarnings(result));

      QuestionnaireResponse qr = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(QuestionnaireResponse.class::isInstance)
          .map(QuestionnaireResponse.class::cast)
          .findFirst().orElse(null);
      assertNotNull(qr, "Bundle should contain a QuestionnaireResponse");

      // Find pre-populated patient info items (assembled linkIds: 1.PBI.1, 1.PBI.2, 1.PBI.3)
      QuestionnaireResponseItemComponent lastName = findItemByLinkId(qr.getItem(), "1.PBI.1");
      QuestionnaireResponseItemComponent firstName = findItemByLinkId(qr.getItem(), "1.PBI.2");
      QuestionnaireResponseItemComponent dob = findItemByLinkId(qr.getItem(), "1.PBI.3");

      assertNotNull(lastName, "Should have Last Name item (1.PBI.1)");
      assertNotNull(firstName, "Should have First Name item (1.PBI.2)");
      assertNotNull(dob, "Should have Date of Birth item (1.PBI.3)");

      // Verify pre-populated values
      assertFalse(lastName.getAnswer().isEmpty(), "Last Name should have an answer");
      assertEquals("Smith",
          ((StringType) lastName.getAnswer().get(0).getValue()).getValue());

      assertFalse(firstName.getAnswer().isEmpty(), "First Name should have an answer");
      assertEquals("John",
          ((StringType) firstName.getAnswer().get(0).getValue()).getValue());

      assertFalse(dob.getAnswer().isEmpty(), "Date of Birth should have an answer");
      assertTrue(dob.getAnswer().get(0).getValue() instanceof DateType);
      assertTrue(((DateType) dob.getAnswer().get(0).getValue()).getValueAsString().startsWith("1960-03-15"));

      // Verify information-origin extension on answers
      for (QuestionnaireResponseItemComponent item : List.of(lastName, firstName, dob)) {
        Extension originExt = item.getAnswer().get(0).getExtensionByUrl(
            "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/information-origin");
        assertNotNull(originExt,
            "Pre-populated answer for " + item.getLinkId() + " should have information-origin extension");
        Extension sourceExt = originExt.getExtensionByUrl("source");
        assertNotNull(sourceExt, "information-origin should have source sub-extension");
        assertEquals("auto-server", ((CodeType) sourceExt.getValue()).getValue());
      }
    }
  }

  // ============================================================
  // ORDER: Order-Based Resolution
  // ============================================================

  @Nested
  @Disabled("Disabled for basic conformance mode: order-based PlanDefinition matching is tested separately.")
  @DisplayName("Order-Based Resolution")
  class OrderResolutionTests {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("DeviceRequest E0250 produces package via PlanDefinition evaluation")
    void order_deviceRequestProducesPackage() {
      DeviceRequest deviceRequest = new DeviceRequest();
      deviceRequest.setId("dtr-test-dr");
      deviceRequest.setStatus(DeviceRequest.DeviceRequestStatus.DRAFT);
      deviceRequest.setIntent(DeviceRequest.RequestIntent.ORDER);
      deviceRequest.setCode(new CodeableConcept().addCoding(
          new Coding()
              .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
              .setCode("E0250")
              .setDisplay("Hospital bed, fixed height, with any type side rails, with mattress")));
      deviceRequest.setSubject(new Reference("Patient/" + testPatient.getIdElement().getIdPart()));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(deviceRequest), List.of(), null);

      assertNotNull(result, "Result should not be null");

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle,
          "Order-based resolution should produce a package bundle for E0250 DeviceRequest. " +
          "Warnings: " + extractWarnings(result));

      // First entry should be the HospitalBeds Questionnaire
      Resource firstResource = bundle.getEntry().get(0).getResource();
      assertInstanceOf(Questionnaire.class, firstResource);
    }
  }

  // ============================================================
  // ERROR CASES
  // ============================================================

  @Nested
  @DisplayName("Error Cases")
  class ErrorCases {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Nonexistent canonical returns empty Parameters with warning")
    void nonexistentCanonical_emptyWithWarning() {
      List<CanonicalType> canonicals = List.of(
          new CanonicalType("http://example.org/fhir/Questionnaire/DoesNotExist"));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null);

      assertNotNull(result);

      // No packagebundle
      boolean hasPackageBundle = result.getParameter().stream()
          .anyMatch(p -> "packagebundle".equals(p.getName()));
      assertFalse(hasPackageBundle, "Should not have packagebundle for nonexistent canonical");

      // Should have outcome with warning
      boolean hasWarning = false;
      for (Parameters.ParametersParameterComponent param : result.getParameter()) {
        if ("outcome".equals(param.getName()) && param.getResource() instanceof OperationOutcome oo) {
          hasWarning = oo.getIssue().stream()
              .anyMatch(i -> i.getDiagnostics() != null && i.getDiagnostics().contains("not found"));
        }
      }
      assertTrue(hasWarning, "Should have warning about nonexistent questionnaire");
    }
  }

  // ============================================================
  // HELPERS
  // ============================================================

  private QuestionnaireResponseItemComponent findItemByLinkId(
      List<QuestionnaireResponseItemComponent> items, String linkId) {
    if (items == null) return null;
    for (QuestionnaireResponseItemComponent item : items) {
      if (linkId.equals(item.getLinkId())) return item;
      QuestionnaireResponseItemComponent found = findItemByLinkId(item.getItem(), linkId);
      if (found != null) return found;
    }
    return null;
  }

  private Bundle extractPackageBundle(Parameters result) {
    if (result == null) return null;
    return result.getParameter().stream()
        .filter(p -> "packagebundle".equals(p.getName()))
        .map(p -> (Bundle) p.getResource())
        .findFirst().orElse(null);
  }

  private String extractWarnings(Parameters result) {
    if (result == null) return "null result";
    StringBuilder sb = new StringBuilder();
    for (Parameters.ParametersParameterComponent param : result.getParameter()) {
      if ("outcome".equals(param.getName()) && param.getResource() instanceof OperationOutcome oo) {
        for (OperationOutcome.OperationOutcomeIssueComponent issue : oo.getIssue()) {
          if (sb.length() > 0) sb.append("; ");
          sb.append(issue.getDiagnostics());
        }
      }
    }
    return sb.length() > 0 ? sb.toString() : "no warnings";
  }
}
