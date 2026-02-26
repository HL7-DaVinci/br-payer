package org.hl7.davinci.dtr.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.hl7.davinci.dtr.DtrPackageService;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
        "vsac.api-key=",
        "dtr.adaptive.next-question-url=http://localhost:8080/fhir/Questionnaire/$next-question"
    }
)
class DtrQuestionnairePackageIT {

  private static final Logger log = LoggerFactory.getLogger(DtrQuestionnairePackageIT.class);
  private static final String Q_CANONICAL = "http://example.org/fhir/Questionnaire/HospitalBedsAndAccessories";

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
          testCoverage, List.of(), canonicals, null, null);

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
          testCoverage, List.of(), canonicals, null, null);

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
          testCoverage, List.of(), canonicals, null, null);

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
          testCoverage, List.of(), canonicals, null, null);

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
          testCoverage, List.of(), canonicals, null, null);

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
          testCoverage, List.of(), canonicals, null, null);

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
          testCoverage, List.of(deviceRequest), List.of(), null, null);

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
          testCoverage, List.of(), canonicals, null, null);

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
  // HOME OXYGEN DISPATCH
  // ============================================================

  @Nested
  @DisplayName("Home Oxygen Dispatch")
  class HomeOxygenDispatchTests {

    private static final String HOME_OXYGEN_CANONICAL =
        "http://example.org/fhir/Questionnaire/HomeOxygenDispatch";

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Canonical resolution returns complete package")
    void canonicalResolution_returnsCompletePackage() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(HOME_OXYGEN_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      assertNotNull(result);
      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Should have package bundle. Warnings: " + extractWarnings(result));

      Resource firstResource = bundle.getEntry().get(0).getResource();
      assertInstanceOf(Questionnaire.class, firstResource);
      assertTrue(((Questionnaire) firstResource).getUrl().contains("HomeOxygenDispatch"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("PatientInfo sub-questionnaire items are inlined")
    void bundleContainsSubQuestionnaire_patientInfoInlined() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(HOME_OXYGEN_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle);

      QuestionnaireResponse qr = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(QuestionnaireResponse.class::isInstance)
          .map(QuestionnaireResponse.class::cast)
          .findFirst().orElse(null);
      assertNotNull(qr);

      // After sub-questionnaire assembly, PatientInfo items are inlined under linkId "1"
      assertNotNull(findItemByLinkId(qr.getItem(), "1.PBI.1"),
          "Inlined PatientInfo Last Name item should be present");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Libraries present with ELM content")
    void librariesHaveElm() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(HOME_OXYGEN_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle);

      List<Library> libraries = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(Library.class::isInstance)
          .map(Library.class::cast)
          .toList();

      assertFalse(libraries.isEmpty(), "Should have at least one library");
      boolean hasElm = libraries.stream()
          .anyMatch(lib -> lib.getContent().stream()
              .anyMatch(c -> "application/elm+json".equals(c.getContentType()) && c.hasData()));
      assertTrue(hasElm, "At least one library should have ELM content");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("DeviceRequest E0424 order resolves via PlanDefinition")
    void orderResolution_deviceRequestProducesPackage() {
      DeviceRequest deviceRequest = new DeviceRequest();
      deviceRequest.setId("dtr-test-home-oxygen-dr");
      deviceRequest.setStatus(DeviceRequest.DeviceRequestStatus.DRAFT);
      deviceRequest.setIntent(DeviceRequest.RequestIntent.ORDER);
      deviceRequest.setCode(new CodeableConcept().addCoding(
          new Coding()
              .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
              .setCode("E0424")
              .setDisplay("Stationary compressed gaseous oxygen system, rental")));
      deviceRequest.setSubject(new Reference("Patient/" + testPatient.getIdElement().getIdPart()));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(deviceRequest), List.of(), null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Order-based resolution should produce a package for E0424. "
          + "Warnings: " + extractWarnings(result));
    }
  }

  // ============================================================
  // IMMUNOSUPPRESSIVE DRUGS
  // ============================================================

  @Nested
  @DisplayName("Immunosuppressive Drugs")
  class ImmunosuppressiveDrugsTests {

    private static final String IMMUNO_CANONICAL =
        "http://example.org/fhir/Questionnaire/ImmunosuppressiveDrugs";
    private static final String PROGRESS_NOTE_CANONICAL =
        "http://example.org/fhir/Questionnaire/ImmunosuppressiveDrugsProgressNote";
    private static final String IMMUNO_LIBRARY_URL =
        "http://example.org/fhir/Library/ImmunosuppressiveDrugsPrepopulation";

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Main form canonical produces package")
    void mainFormCanonical_returnsPackage() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(IMMUNO_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Should produce package. Warnings: " + extractWarnings(result));

      Resource firstResource = bundle.getEntry().get(0).getResource();
      assertInstanceOf(Questionnaire.class, firstResource);
      assertTrue(((Questionnaire) firstResource).getUrl().contains("ImmunosuppressiveDrugs"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Progress note canonical produces separate package")
    void progressNoteCanonical_returnsSeparatePackage() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(PROGRESS_NOTE_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Should produce package. Warnings: " + extractWarnings(result));

      Resource firstResource = bundle.getEntry().get(0).getResource();
      assertInstanceOf(Questionnaire.class, firstResource);
      assertTrue(((Questionnaire) firstResource).getUrl().contains("ProgressNote"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Both questionnaires reference same prepopulation Library")
    void sharedLibrary_samePrepopulationLibraryInBothPackages() {
      Parameters mainResult = dtrPackageService.generatePackages(
          testCoverage, List.of(), List.of(new CanonicalType(IMMUNO_CANONICAL)), null, null);
      Bundle mainBundle = extractPackageBundle(mainResult);
      assertNotNull(mainBundle);

      Parameters noteResult = dtrPackageService.generatePackages(
          testCoverage, List.of(), List.of(new CanonicalType(PROGRESS_NOTE_CANONICAL)), null, null);
      Bundle noteBundle = extractPackageBundle(noteResult);
      assertNotNull(noteBundle);

      // Library URLs may include version suffix (e.g. "url|1.0.0") from HAPI canonical resolution
      boolean mainHasLib = mainBundle.getEntry().stream()
          .filter(e -> e.getResource() instanceof Library)
          .map(e -> ((Library) e.getResource()).getUrl())
          .anyMatch(url -> url != null && url.startsWith(IMMUNO_LIBRARY_URL));
      boolean noteHasLib = noteBundle.getEntry().stream()
          .filter(e -> e.getResource() instanceof Library)
          .map(e -> ((Library) e.getResource()).getUrl())
          .anyMatch(url -> url != null && url.startsWith(IMMUNO_LIBRARY_URL));

      assertTrue(mainHasLib,
          "Main form package should contain ImmunosuppressiveDrugsPrepopulation. Warnings: "
              + extractWarnings(mainResult));
      assertTrue(noteHasLib,
          "Progress note package should contain ImmunosuppressiveDrugsPrepopulation. Warnings: "
              + extractWarnings(noteResult));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("MedicationRequest RxNorm 105585 resolves both questionnaires")
    void orderResolution_medicationRequestResolvesBoth() {
      MedicationRequest medRequest = new MedicationRequest();
      medRequest.setId("dtr-test-immuno-mr");
      medRequest.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
      medRequest.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
      medRequest.setMedication(new CodeableConcept().addCoding(
          new Coding()
              .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
              .setCode("105585")
              .setDisplay("Tacrolimus")));
      medRequest.setSubject(new Reference("Patient/" + testPatient.getIdElement().getIdPart()));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(medRequest), List.of(), null, null);

      long bundleCount = result.getParameter().stream()
          .filter(p -> "packagebundle".equals(p.getName()))
          .count();
      assertTrue(bundleCount >= 2,
          "Order should resolve both questionnaires. Warnings: " + extractWarnings(result));
    }
  }

  // ============================================================
  // PHYSICAL THERAPY
  // ============================================================

  @Nested
  @DisplayName("Physical Therapy")
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class PhysicalTherapyTests {

    private static final String PT_CANONICAL =
        "http://example.org/fhir/Questionnaire/PhysicalTherapyExtension";

    @Test
    @Order(1)
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Canonical resolution returns complete package")
    void canonicalResolution_returnsCompletePackage() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(PT_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Should have package bundle. Warnings: " + extractWarnings(result));

      Resource firstResource = bundle.getEntry().get(0).getResource();
      assertInstanceOf(Questionnaire.class, firstResource);
      assertTrue(((Questionnaire) firstResource).getUrl().contains("PhysicalTherapyExtension"));
    }

    @Test
    @Order(2)
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Below session limit: no questionnaire produced")
    void orderResolution_belowLimitNoQuestionnaire() {
      // With zero Procedure resources, Sessions Used = 0 (well below limit of 24),
      // so PhysicalTherapyRule does not include the questionnaire URL
      Appointment appointment = new Appointment();
      appointment.setId("dtr-test-pt-appt");
      appointment.setStatus(Appointment.AppointmentStatus.PROPOSED);
      appointment.addServiceType(new CodeableConcept().addCoding(
          new Coding()
              .setSystem("http://www.ama-assn.org/go/cpt")
              .setCode("97110")
              .setDisplay("Therapeutic exercises")));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(appointment), List.of(), null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNull(bundle,
          "Below session limit should not produce a questionnaire package");
    }

    @Test
    @Order(3)
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Exceeded session limit: questionnaire produced")
    void orderResolution_exceededLimitProducesPackage() {
      // Seed 24 completed PT procedures to meet the plan session limit
      String[] cptCodes = {"97110", "97140", "97530"};
      String[] cptDisplays = {"Therapeutic exercises", "Manual therapy", "Therapeutic activities"};
      for (int i = 0; i < 24; i++) {
        Procedure proc = new Procedure();
        proc.setId("dtr-test-pt-proc-" + i);
        proc.setStatus(Procedure.ProcedureStatus.COMPLETED);
        proc.setCode(new CodeableConcept().addCoding(
            new Coding()
                .setSystem("http://www.ama-assn.org/go/cpt")
                .setCode(cptCodes[i % 3])
                .setDisplay(cptDisplays[i % 3])));
        // Spread dates across Jan-Feb 2026
        int day = (i % 28) + 1;
        String month = (i < 14) ? "01" : "02";
        proc.setPerformed(new DateTimeType("2026-" + month + "-" + String.format("%02d", day)));
        proc.setSubject(new Reference("Patient/" + testPatient.getIdElement().getIdPart()));
        daoRegistry.getResourceDao(Procedure.class)
            .update(proc, new SystemRequestDetails());
      }

      Appointment appointment = new Appointment();
      appointment.setId("dtr-test-pt-appt-exceeded");
      appointment.setStatus(Appointment.AppointmentStatus.PROPOSED);
      appointment.addServiceType(new CodeableConcept().addCoding(
          new Coding()
              .setSystem("http://www.ama-assn.org/go/cpt")
              .setCode("97110")
              .setDisplay("Therapeutic exercises")));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(appointment), List.of(), null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Exceeded session limit should produce a package for 97110. "
          + "Warnings: " + extractWarnings(result));
    }
  }

  // ============================================================
  // CARDIOLOGY CONSULTATION
  // ============================================================

  @Nested
  @DisplayName("Cardiology Consultation")
  class CardiologyConsultationTests {

    private static final String CARDIO_CANONICAL =
        "http://example.org/fhir/Questionnaire/CardiologyConsultation";

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Canonical resolution returns complete package")
    void canonicalResolution_returnsCompletePackage() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(CARDIO_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Should have package bundle. Warnings: " + extractWarnings(result));

      Resource firstResource = bundle.getEntry().get(0).getResource();
      assertInstanceOf(Questionnaire.class, firstResource);
      assertTrue(((Questionnaire) firstResource).getUrl().contains("CardiologyConsultation"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Appointment SNOMED 394579002 resolves questionnaire")
    void orderResolution_appointmentProducesPackage() {
      Appointment appointment = new Appointment();
      appointment.setId("dtr-test-cardio-appt");
      appointment.setStatus(Appointment.AppointmentStatus.PROPOSED);
      appointment.addServiceType(new CodeableConcept().addCoding(
          new Coding()
              .setSystem("http://snomed.info/sct")
              .setCode("394579002")
              .setDisplay("Cardiology")));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(appointment), List.of(), null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Order-based resolution should produce a package for 394579002. "
          + "Warnings: " + extractWarnings(result));
    }
  }

  // ============================================================
  // OPIOID PRESCRIBING
  // ============================================================

  @Nested
  @DisplayName("Opioid Prescribing")
  class OpioidPrescribingTests {

    private static final String JUSTIFICATION_CANONICAL =
        "http://example.org/fhir/Questionnaire/OpioidPrescribingJustification";
    private static final String PDMP_CANONICAL =
        "http://example.org/fhir/Questionnaire/OpioidPDMP";
    private static final String QR_ADAPT_PROFILE =
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaireresponse-adapt";
    private static final String QUESTIONNAIRE_ADAPTIVE_EXT =
        "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive";

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Justification canonical produces adaptive response")
    void justificationCanonical_producesAdaptiveResponse() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(JUSTIFICATION_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Should produce package. Warnings: " + extractWarnings(result));

      QuestionnaireResponse qr = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(QuestionnaireResponse.class::isInstance)
          .map(QuestionnaireResponse.class::cast)
          .findFirst().orElse(null);
      assertNotNull(qr, "Bundle should contain a QuestionnaireResponse");

      assertTrue(qr.getMeta().hasProfile(QR_ADAPT_PROFILE),
          "Adaptive QR should have dtr-questionnaireresponse-adapt profile");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("PDMP canonical produces standard pre-populated response")
    void pdmpCanonical_producesStandardResponse() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(PDMP_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle, "Should produce package. Warnings: " + extractWarnings(result));

      QuestionnaireResponse qr = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(QuestionnaireResponse.class::isInstance)
          .map(QuestionnaireResponse.class::cast)
          .findFirst().orElse(null);
      assertNotNull(qr, "Bundle should contain a QuestionnaireResponse");

      assertFalse(qr.getMeta().hasProfile(QR_ADAPT_PROFILE),
          "Standard QR should not have adaptive profile");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Adaptive response has contained Questionnaire with derivedFrom and adaptive ext")
    void adaptiveContainedQuestionnaire_hasCorrectMetadata() {
      List<CanonicalType> canonicals = List.of(new CanonicalType(JUSTIFICATION_CANONICAL));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(), canonicals, null, null);

      Bundle bundle = extractPackageBundle(result);
      assertNotNull(bundle);

      QuestionnaireResponse qr = bundle.getEntry().stream()
          .map(e -> e.getResource())
          .filter(QuestionnaireResponse.class::isInstance)
          .map(QuestionnaireResponse.class::cast)
          .findFirst().orElse(null);
      assertNotNull(qr);

      // QR should reference a contained Questionnaire
      assertTrue(qr.getQuestionnaire().startsWith("#"),
          "Adaptive QR should reference a contained Questionnaire");

      // Find the contained Questionnaire
      Questionnaire contained = qr.getContained().stream()
          .filter(Questionnaire.class::isInstance)
          .map(Questionnaire.class::cast)
          .findFirst().orElse(null);
      assertNotNull(contained, "QR should contain a Questionnaire resource");

      // Contained Q should have derivedFrom pointing to the source canonical
      assertFalse(contained.getDerivedFrom().isEmpty(),
          "Contained Questionnaire should have derivedFrom");
      assertTrue(contained.getDerivedFrom().get(0).getValue()
              .contains("OpioidPrescribingJustification"),
          "derivedFrom should reference the source adaptive Questionnaire");

      // Contained Q should have questionnaireAdaptive extension
      assertNotNull(contained.getExtensionByUrl(QUESTIONNAIRE_ADAPTIVE_EXT),
          "Contained Questionnaire should have questionnaireAdaptive extension");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("MedicationRequest RxNorm 197696 resolves both questionnaires")
    void orderResolution_medicationRequestResolvesBoth() {
      MedicationRequest medRequest = new MedicationRequest();
      medRequest.setId("dtr-test-opioid-mr");
      medRequest.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
      medRequest.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
      medRequest.setMedication(new CodeableConcept().addCoding(
          new Coding()
              .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
              .setCode("197696")
              .setDisplay("Hydrocodone")));
      medRequest.setSubject(new Reference("Patient/" + testPatient.getIdElement().getIdPart()));

      Parameters result = dtrPackageService.generatePackages(
          testCoverage, List.of(medRequest), List.of(), null, null);

      long bundleCount = result.getParameter().stream()
          .filter(p -> "packagebundle".equals(p.getName()))
          .count();
      assertTrue(bundleCount >= 2,
          "Order should resolve both questionnaires. Warnings: " + extractWarnings(result));

      assertFalse(
          extractWarnings(result).contains("cannot be cast to class org.hl7.fhir.r4.model.Type"),
          "Pre-population should not return resource values for questionnaire item answers. Warnings: "
              + extractWarnings(result));
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
