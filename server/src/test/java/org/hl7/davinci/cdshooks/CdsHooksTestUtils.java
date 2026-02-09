package org.hl7.davinci.cdshooks;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestContextJson;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared test utilities for CDS Hooks tests.
 */
public class CdsHooksTestUtils {

  private static final FhirContext fhirContext = FhirContext.forR4Cached();
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final IParser fhirJsonParser = fhirContext.newJsonParser();

  public static final String COVERAGE_INFO_EXT_URL = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
  public static final String CMS_PAYOR_SYSTEM = "urn:oid:2.16.840.1.113883.6.300";
  public static final String CMS_PAYOR_VALUE = "00001";

  // ============================================================
  // FIXTURE LOADING
  // ============================================================

  /**
   * Load a JSON fixture from src/test/resources/cdshooks/
   */
  public static String loadFixture(String filename) throws IOException {
    String path = "cdshooks/" + filename;
    DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
    try (InputStream is = resourceLoader.getResource(path).getInputStream()) {
      return IOUtils.toString(is, StandardCharsets.UTF_8);
    }
  }

  /**
   * Parse a JSON string into a CdsServiceRequestJson object.
   * Uses Jackson ObjectMapper with manual FHIR resource deserialization
   * since CdsServiceRequestJson contains IBaseResource fields.
   */
  public static CdsServiceRequestJson parseRequest(String json) {
    try {
      JsonNode rootNode = objectMapper.readTree(json);

      CdsServiceRequestJson request = new CdsServiceRequestJson();

      // Set basic string fields
      if (rootNode.has("hook")) {
        request.setHook(rootNode.get("hook").asText());
      }
      if (rootNode.has("hookInstance")) {
        request.setHookInstance(rootNode.get("hookInstance").asText());
      }
      if (rootNode.has("fhirServer")) {
        request.setFhirServer(rootNode.get("fhirServer").asText());
      }

      // Parse context
      if (rootNode.has("context")) {
        JsonNode contextNode = rootNode.get("context");
        CdsServiceRequestContextJson context = new CdsServiceRequestContextJson();

        Iterator<Map.Entry<String, JsonNode>> fields = contextNode.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> field = fields.next();
          String key = field.getKey();
          JsonNode value = field.getValue();

          if (value.isObject() && value.has("resourceType")) {
            // This is a FHIR resource - parse it
            IBaseResource resource = fhirJsonParser.parseResource(value.toString());
            context.put(key, resource);
          } else if (value.isTextual()) {
            context.put(key, value.asText());
          } else if (value.isNumber()) {
            context.put(key, value.numberValue());
          } else if (value.isBoolean()) {
            context.put(key, value.asBoolean());
          } else if (value.isArray()) {
            // Convert JSON array to List
            List<Object> list = new ArrayList<>();
            for (JsonNode element : value) {
              if (element.isTextual()) {
                list.add(element.asText());
              } else if (element.isObject() && element.has("resourceType")) {
                list.add(fhirJsonParser.parseResource(element.toString()));
              } else {
                list.add(element.toString());
              }
            }
            context.put(key, list);
          } else {
            // For complex non-resource objects, store as string
            context.put(key, value.toString());
          }
        }
        request.setContext(context);
      }

      // Parse prefetch - each value is a FHIR resource
      if (rootNode.has("prefetch")) {
        JsonNode prefetchNode = rootNode.get("prefetch");
        Iterator<Map.Entry<String, JsonNode>> prefetchFields = prefetchNode.fields();
        while (prefetchFields.hasNext()) {
          Map.Entry<String, JsonNode> field = prefetchFields.next();
          String key = field.getKey();
          JsonNode value = field.getValue();

          if (value.isObject() && value.has("resourceType")) {
            IBaseResource resource = fhirJsonParser.parseResource(value.toString());
            request.addPrefetch(key, resource);
          }
        }
      }

      return request;
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse CDS Hooks request JSON", e);
    }
  }

  /**
   * Load and parse a fixture file into a CdsServiceRequestJson.
   */
  public static CdsServiceRequestJson loadRequest(String filename) throws IOException {
    return parseRequest(loadFixture(filename));
  }

  /**
   * Parse a JSON response string.
   */
  public static JsonNode parseResponseJson(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse JSON response", e);
    }
  }

  // ============================================================
  // TEST RESOURCE BUILDERS
  // ============================================================

  /**
   * Create a test Patient with the given ID.
   */
  public static Patient createTestPatient(String id) {
    Patient patient = new Patient();
    patient.setId(id);
    patient.addName().setFamily("TestPatient").addGiven("Test");
    patient.setGender(Enumerations.AdministrativeGender.MALE);
    patient.setBirthDate(new Date());
    return patient;
  }

  /**
   * Create a test Patient with specific birth date for age-based testing.
   */
  public static Patient createTestPatient(String id, Date birthDate) {
    Patient patient = createTestPatient(id);
    patient.setBirthDate(birthDate);
    return patient;
  }

  /**
   * Create a test Coverage with the given ID and payor organization reference.
   */
  public static Coverage createTestCoverage(String id, String payorOrgId) {
    Coverage coverage = new Coverage();
    coverage.setId(id);
    coverage.setStatus(Coverage.CoverageStatus.ACTIVE);
    coverage.addPayor(new Reference("Organization/" + payorOrgId));
    return coverage;
  }

  /**
   * Create a test Organization with the CMS payor identifier.
   */
  public static Organization createCmsOrganization(String id) {
    Organization org = new Organization();
    org.setId(id);
    org.setName("Centers for Medicare and Medicaid Services");
    org.addIdentifier()
        .setSystem(CMS_PAYOR_SYSTEM)
        .setValue(CMS_PAYOR_VALUE);
    return org;
  }

  /**
   * Create a test Organization with a custom identifier.
   */
  public static Organization createTestOrganization(String id, String identifierSystem, String identifierValue) {
    Organization org = new Organization();
    org.setId(id);
    org.setName("Test Organization");
    org.addIdentifier()
        .setSystem(identifierSystem)
        .setValue(identifierValue);
    return org;
  }

  /**
   * Create a test DeviceRequest with HCPCS code.
   */
  public static DeviceRequest createTestDeviceRequest(String id, String hcpcsCode, String patientId) {
    DeviceRequest request = new DeviceRequest();
    request.setId(id);
    request.setStatus(DeviceRequest.DeviceRequestStatus.DRAFT);
    request.setIntent(DeviceRequest.RequestIntent.ORDER);
    request.setSubject(new Reference("Patient/" + patientId));

    CodeableConcept code = new CodeableConcept();
    code.addCoding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode(hcpcsCode)
        .setDisplay("Test Device");
    request.setCode(code);

    return request;
  }

  /**
   * Create a test MedicationRequest with RxNorm code.
   */
  public static MedicationRequest createTestMedicationRequest(String id, String rxnormCode, String patientId) {
    MedicationRequest request = new MedicationRequest();
    request.setId(id);
    request.setStatus(MedicationRequest.MedicationRequestStatus.DRAFT);
    request.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
    request.setSubject(new Reference("Patient/" + patientId));

    CodeableConcept medication = new CodeableConcept();
    medication.addCoding()
        .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
        .setCode(rxnormCode)
        .setDisplay("Test Medication");
    request.setMedication(medication);

    return request;
  }

  /**
   * Create a test ServiceRequest.
   */
  public static ServiceRequest createTestServiceRequest(String id, String cptCode, String patientId) {
    ServiceRequest request = new ServiceRequest();
    request.setId(id);
    request.setStatus(ServiceRequest.ServiceRequestStatus.DRAFT);
    request.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
    request.setSubject(new Reference("Patient/" + patientId));

    CodeableConcept code = new CodeableConcept();
    code.addCoding()
        .setSystem("http://www.ama-assn.org/go/cpt")
        .setCode(cptCode)
        .setDisplay("Test Service");
    request.setCode(code);

    return request;
  }

  /**
   * Create a test Practitioner with the given ID.
   */
  public static Practitioner createTestPractitioner(String id) {
    Practitioner practitioner = new Practitioner();
    practitioner.setId(id);
    practitioner.addName().setFamily("TestPractitioner").addGiven("Test");
    return practitioner;
  }

  /**
   * Create a test Encounter with class code and type.
   */
  public static Encounter createTestEncounter(String id, String classCode, String patientId) {
    Encounter encounter = new Encounter();
    encounter.setId(id);
    encounter.setStatus(Encounter.EncounterStatus.INPROGRESS);
    encounter.setClass_(new Coding()
        .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
        .setCode(classCode)
        .setDisplay(classCode.equals("IMP") ? "Inpatient encounter" : "Ambulatory"));
    encounter.setSubject(new Reference("Patient/" + patientId));
    return encounter;
  }

  /**
   * Create a test Appointment with service type.
   */
  public static Appointment createTestAppointment(String id, String snomedCode, String patientId) {
    Appointment appointment = new Appointment();
    appointment.setId(id);
    appointment.setStatus(Appointment.AppointmentStatus.PROPOSED);

    CodeableConcept serviceType = new CodeableConcept();
    serviceType.addCoding()
        .setSystem("http://snomed.info/sct")
        .setCode(snomedCode)
        .setDisplay("Test Service Type");
    appointment.addServiceType(serviceType);

    appointment.addParticipant()
        .setActor(new Reference("Patient/" + patientId))
        .setStatus(Appointment.ParticipationStatus.ACCEPTED);

    return appointment;
  }

  /**
   * Create a test SupplyRequest with item code.
   */
  public static SupplyRequest createTestSupplyRequest(String id, String hcpcsCode, String patientId) {
    SupplyRequest request = new SupplyRequest();
    request.setId(id);
    request.setStatus(SupplyRequest.SupplyRequestStatus.ACTIVE);

    CodeableConcept item = new CodeableConcept();
    item.addCoding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode(hcpcsCode)
        .setDisplay("Test Supply Item");
    request.setItem(item);

    request.getRequester().setReference("Patient/" + patientId);

    return request;
  }

  // ============================================================
  // ASSERTION HELPERS
  // ============================================================

  /**
   * Assert that a CDS response contains a coverage-information system action.
   */
  public static void assertHasCoverageInfoSystemAction(CdsServiceResponseJson response) {
    assertNotNull(response.getServiceActions(), "Response should have service actions");
    assertFalse(response.getServiceActions().isEmpty(), "Service actions should not be empty");

    boolean hasCoverageInfo = response.getServiceActions().stream()
        .filter(action -> action != null && action.getResource() != null)
        .anyMatch(action -> {
          if (action.getResource() instanceof DomainResource dr) {
            return dr.hasExtension(COVERAGE_INFO_EXT_URL);
          }
          return false;
        });

    assertTrue(hasCoverageInfo, "Response should contain a coverage-information system action");
  }

  /**
   * Assert that a coverage-information extension has required fields per CRD spec.
   */
  public static void assertCoverageInfoExtensionValid(Extension coverageInfoExt) {
    assertNotNull(coverageInfoExt, "Coverage info extension should not be null");
    assertEquals(COVERAGE_INFO_EXT_URL, coverageInfoExt.getUrl());

    // Required fields per CRD spec
    Extension coverageExt = coverageInfoExt.getExtensionByUrl("coverage");
    assertNotNull(coverageExt, "coverage-information must have 'coverage' extension");
    assertInstanceOf(Reference.class, coverageExt.getValue(), "coverage value must be a Reference");

    Extension coveredExt = coverageInfoExt.getExtensionByUrl("covered");
    assertNotNull(coveredExt, "coverage-information must have 'covered' extension");

    Extension dateExt = coverageInfoExt.getExtensionByUrl("date");
    assertNotNull(dateExt, "coverage-information must have 'date' extension");

    Extension assertionIdExt = coverageInfoExt.getExtensionByUrl("coverage-assertion-id");
    assertNotNull(assertionIdExt, "coverage-information must have 'coverage-assertion-id' extension");
  }

  /**
   * Assert that a card has all required fields per CDS Hooks spec.
   */
  public static void assertCardHasRequiredFields(CdsServiceResponseCardJson card) {
    assertNotNull(card, "Card should not be null");
    assertNotNull(card.getSummary(), "Card must have a summary");
    assertFalse(card.getSummary().isEmpty(), "Card summary must not be empty");
    assertTrue(card.getSummary().length() <= 140, "Card summary should be <= 140 characters");
    assertNotNull(card.getIndicator(), "Card must have an indicator");
    assertNotNull(card.getSource(), "Card must have a source");
    assertNotNull(card.getSource().getLabel(), "Card source must have a label");
  }

  /**
   * Assert that a card has source.topic populated (CRD requirement).
   */
  public static void assertCardHasSourceTopic(CdsServiceResponseCardJson card) {
    assertNotNull(card.getSource(), "Card must have a source");
    assertNotNull(card.getSource().getTopic(), "Card source must have a topic (CRD requirement)");
    assertNotNull(card.getSource().getTopic().getCode(), "Card source topic must have a code");
  }

  /**
   * Extract the coverage-information extension from a system action's resource.
   */
  public static Extension getCoverageInfoExtension(CdsServiceResponseSystemActionJson action) {
    if (action == null || action.getResource() == null) {
      return null;
    }
    if (action.getResource() instanceof DomainResource dr) {
      return dr.getExtensionByUrl(COVERAGE_INFO_EXT_URL);
    }
    return null;
  }

  /**
   * Get the 'covered' code value from a coverage-information extension.
   */
  public static String getCoveredValue(Extension coverageInfoExt) {
    if (coverageInfoExt == null) {
      return null;
    }
    Extension coveredExt = coverageInfoExt.getExtensionByUrl("covered");
    if (coveredExt != null && coveredExt.getValue() != null) {
      return coveredExt.getValue().primitiveValue();
    }
    return null;
  }

  /**
   * Get the 'pa-needed' code value from a coverage-information extension.
   */
  public static String getPaNeededValue(Extension coverageInfoExt) {
    if (coverageInfoExt == null) {
      return null;
    }
    Extension paNeededExt = coverageInfoExt.getExtensionByUrl("pa-needed");
    if (paNeededExt != null && paNeededExt.getValue() != null) {
      return paNeededExt.getValue().primitiveValue();
    }
    return null;
  }

  /**
   * Get the 'doc-needed' code value from a coverage-information extension.
   */
  public static String getDocNeededValue(Extension coverageInfoExt) {
    if (coverageInfoExt == null) {
      return null;
    }
    Extension docNeededExt = coverageInfoExt.getExtensionByUrl("doc-needed");
    if (docNeededExt != null && docNeededExt.getValue() != null) {
      return docNeededExt.getValue().primitiveValue();
    }
    return null;
  }

  // ============================================================
  // REQUEST MODIFICATION HELPERS
  // ============================================================

  /**
   * Modify a request's context to set a different hook name.
   */
  public static void setHookName(CdsServiceRequestJson request, String hookName) {
    // CdsServiceRequestJson uses reflection/gson, so we access via the underlying map
    try {
      var field = request.getClass().getDeclaredField("myHook");
      field.setAccessible(true);
      field.set(request, hookName);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set hook name", e);
    }
  }

  /**
   * Remove prefetch data from a request by setting it to null.
   * Note: CdsServiceRequestJson doesn't expose direct prefetch map manipulation,
   * so this uses reflection to clear specific prefetch keys.
   */
  public static void clearPrefetch(CdsServiceRequestJson request, String prefetchKey) {
    // CdsServiceRequestJson stores prefetch in an internal map accessed by key
    // We cannot directly remove, but we can set to null via addPrefetch
    request.addPrefetch(prefetchKey, null);
  }

  /**
   * Get the FHIR context for parsing resources.
   */
  public static FhirContext getFhirContext() {
    return fhirContext;
  }
}
