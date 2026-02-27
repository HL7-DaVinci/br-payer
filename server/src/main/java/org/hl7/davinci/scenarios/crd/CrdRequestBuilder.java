package org.hl7.davinci.scenarios.crd;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.davinci.scenarios.ScenarioResourceUtil;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.hl7.davinci.common.FhirConstants.*;

import ca.uhn.fhir.context.FhirContext;

/**
 * Builds CDS Hooks request JSON from ScenarioMetadata.
 * Produces hook-specific variants (order-sign, order-select, appointment-book,
 * order-dispatch) for each scenario whose PlanDefinition declares matching
 * triggers. Pure-logic builder with no Spring dependencies.
 */
public class CrdRequestBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> ORDER_BASED_HOOKS = Set.of(
      "order-sign", "order-select", "appointment-book", "order-dispatch");
  private static final String FHIR_SERVER = "http://localhost:8080/fhir";
  private static final String PATIENT_ID = "example";

  private CrdRequestBuilder() {}

  /** Build CRD scenarios with CDS Hooks request JSON for each hook variant. */
  public static List<CrdScenario> build(FhirContext ctx, List<ScenarioMetadata> metadataList) {
    List<CrdScenario> result = new ArrayList<>();

    for (ScenarioMetadata meta : metadataList) {
      if (meta.hookTriggers().isEmpty() || meta.focusCodes().isEmpty() || meta.orderType() == null) {
        continue;
      }

      List<CrdHookVariant> variants = new ArrayList<>();
      Coding firstCode = meta.focusCodes().get(0);

      for (String hookName : meta.hookTriggers()) {
        if (!ORDER_BASED_HOOKS.contains(hookName)) {
          continue;
        }

        String resourceType = resolveResourceTypeForHook(meta.orderType(), hookName);
        if (resourceType == null) {
          continue;
        }

        Resource orderResource = buildOrderForHook(firstCode, resourceType, meta.id());
        if (orderResource == null) {
          continue;
        }

        String requestJson = buildRequestJson(ctx, hookName, orderResource);
        String variantId = meta.id() + "-" + hookName;
        String label = formatHookLabel(hookName);

        variants.add(new CrdHookVariant(variantId, hookName, label, requestJson));
      }

      if (!variants.isEmpty()) {
        String description = ScenarioResourceUtil.buildDescription(meta);
        result.add(new CrdScenario(meta.id(), meta.name(), description, variants));
      }
    }

    return result;
  }

  // ===== Resource type mapping =====

  /**
   * Maps (orderType, hookName) to the FHIR resource type for the request.
   * Appointment-typed scenarios become ServiceRequest for non-appointment hooks.
   */
  static String resolveResourceTypeForHook(String orderType, String hookName) {
    if (orderType == null) {
      return null;
    }

    return switch (hookName) {
      case "order-sign", "order-select" ->
          "Appointment".equals(orderType) ? "ServiceRequest" : orderType;
      case "appointment-book" ->
          "Appointment".equals(orderType) ? "Appointment" : null;
      case "order-dispatch" ->
          "Appointment".equals(orderType) ? "ServiceRequest" : orderType;
      default -> null;
    };
  }

  // ===== Order resource construction =====

  private static Resource buildOrderForHook(Coding code, String resourceType, String scenarioId) {
    if ("ServiceRequest".equals(resourceType)) {
      return buildServiceRequest(code, scenarioId);
    }
    return ScenarioResourceUtil.buildOrderResource(code, resourceType, scenarioId);
  }

  private static ServiceRequest buildServiceRequest(Coding code, String scenarioId) {
    Coding codeCopy = code.copy();
    codeCopy.setDisplay(null);
    ServiceRequest sr = new ServiceRequest();
    sr.setId(scenarioId + "-service-request");
    sr.setStatus(ServiceRequest.ServiceRequestStatus.DRAFT);
    sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
    sr.setCode(new CodeableConcept().addCoding(codeCopy));
    sr.setSubject(new Reference("Patient/" + PATIENT_ID));
    sr.addInsurance(new Reference("Coverage/coverage-1"));
    return sr;
  }

  // ===== JSON construction =====

  private static String buildRequestJson(FhirContext ctx, String hookName,
      Resource orderResource) {
    try {
      ObjectNode root = MAPPER.createObjectNode();
      root.put("hookInstance", UUID.randomUUID().toString());
      root.put("fhirServer", FHIR_SERVER);
      root.put("hook", hookName);

      addFhirAuthorization(root);
      addContext(ctx, root, hookName, orderResource);
      addPrefetch(ctx, root, hookName, orderResource);

      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (Exception e) {
      throw new RuntimeException("Failed to build CRD request JSON for hook: " + hookName, e);
    }
  }

  private static void addFhirAuthorization(ObjectNode root) {
    ObjectNode auth = root.putObject("fhirAuthorization");
    auth.put("access_token", "example-token");
    auth.put("token_type", "Bearer");
    auth.put("expires_in", 300);
    auth.put("scope", "patient/Patient.read patient/Coverage.read");
    auth.put("subject", "cds-service4");
  }

  private static void addContext(FhirContext ctx, ObjectNode root, String hookName,
      Resource orderResource) {
    ObjectNode context = root.putObject("context");

    switch (hookName) {
      case "order-sign" -> {
        context.put("userId", "Practitioner/PractitionerExample");
        context.put("patientId", PATIENT_ID);
        context.set("draftOrders", wrapInContextBundle(ctx, orderResource));
      }
      case "order-select" -> {
        context.put("userId", "Practitioner/PractitionerExample");
        context.put("patientId", PATIENT_ID);
        ArrayNode selections = context.putArray("selections");
        selections.add(resourceReference(orderResource));
        context.set("draftOrders", wrapInContextBundle(ctx, orderResource));
      }
      case "appointment-book" -> {
        context.put("userId", "Practitioner/PractitionerExample");
        context.put("patientId", PATIENT_ID);
        context.set("appointments", wrapInContextBundle(ctx, orderResource));
      }
      case "order-dispatch" -> {
        context.put("patientId", PATIENT_ID);
        context.put("performer", "Practitioner/full");
        ArrayNode dispatched = context.putArray("dispatchedOrders");
        dispatched.add(resourceReference(orderResource));
      }
    }
  }

  private static void addPrefetch(FhirContext ctx, ObjectNode root, String hookName,
      Resource orderResource) {
    ObjectNode prefetch = root.putObject("prefetch");

    prefetch.set("patient", toJsonNode(ctx, buildPatient()));
    prefetch.set("coverage", buildCoveragePrefetchBundle(ctx));

    if ("order-dispatch".equals(hookName)) {
      prefetch.set("dispatchedOrders", wrapInPrefetchBundle(ctx, orderResource));
    }
  }

  // ===== Shared resource builders =====

  private static Patient buildPatient() {
    Patient patient = new Patient();
    patient.setId(PATIENT_ID);
    patient.addName().setFamily("Test").addGiven("Patient");
    patient.setGender(Enumerations.AdministrativeGender.MALE);
    patient.setBirthDateElement(new DateType("1960-01-01"));
    return patient;
  }

  private static ObjectNode buildCoveragePrefetchBundle(FhirContext ctx) {
    Coverage coverage = new Coverage();
    coverage.setId("coverage-1");
    coverage.setStatus(Coverage.CoverageStatus.ACTIVE);
    coverage.setSubscriberId("10A3D58WH456");
    coverage.setBeneficiary(new Reference("Patient/" + PATIENT_ID));
    coverage.getRelationship().addCoding()
        .setSystem(SUBSCRIBER_RELATIONSHIP_SYSTEM)
        .setCode("self").setDisplay("Self");
    coverage.addPayor(new Reference("Organization/cms-payer"));

    Organization org = new Organization();
    org.setId("cms-payer");
    org.addIdentifier()
        .setSystem("urn:oid:2.16.840.1.113883.6.300").setValue("00001");
    org.setName("Centers for Medicare and Medicaid Services");

    ObjectNode bundle = MAPPER.createObjectNode();
    bundle.put("resourceType", "Bundle");
    bundle.put("type", "collection");
    ArrayNode entries = bundle.putArray("entry");

    ObjectNode coverageEntry = entries.addObject();
    coverageEntry.put("fullUrl", FHIR_SERVER + "/Coverage/coverage-1");
    coverageEntry.set("resource", toJsonNode(ctx, coverage));

    ObjectNode orgEntry = entries.addObject();
    orgEntry.set("resource", toJsonNode(ctx, org));

    return bundle;
  }

  // ===== JSON helpers =====

  private static ObjectNode wrapInContextBundle(FhirContext ctx, Resource resource) {
    ObjectNode bundle = MAPPER.createObjectNode();
    bundle.put("resourceType", "Bundle");
    bundle.put("type", "collection");
    ArrayNode entries = bundle.putArray("entry");

    ObjectNode entry = entries.addObject();
    entry.put("fullUrl", "urn:uuid:" + UUID.randomUUID().toString());
    entry.set("resource", toJsonNode(ctx, resource));

    return bundle;
  }

  private static ObjectNode wrapInPrefetchBundle(FhirContext ctx, Resource resource) {
    ObjectNode bundle = MAPPER.createObjectNode();
    bundle.put("resourceType", "Bundle");
    bundle.put("type", "collection");
    ArrayNode entries = bundle.putArray("entry");

    ObjectNode entry = entries.addObject();
    entry.set("resource", toJsonNode(ctx, resource));

    return bundle;
  }

  private static JsonNode toJsonNode(FhirContext ctx, Resource resource) {
    try {
      String json = ctx.newJsonParser().encodeResourceToString(resource);
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize FHIR resource", e);
    }
  }

  private static String resourceReference(Resource resource) {
    return resource.fhirType() + "/" + resource.getIdElement().getIdPart();
  }

  static String formatHookLabel(String hookName) {
    String[] parts = hookName.split("-");
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (!sb.isEmpty()) {
        sb.append(' ');
      }
      sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return sb.toString();
  }

  // ===== DTOs =====

  /** A CRD test scenario with its hook variants. */
  public record CrdScenario(String id, String name, String description,
      List<CrdHookVariant> variants) {}

  /** A single CDS Hooks request variant for a specific hook. */
  public record CrdHookVariant(String id, String hookName, String label,
      String requestJson) {}
}
