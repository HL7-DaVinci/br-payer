package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.davinci.common.BundleResourceUtil;
import org.hl7.davinci.common.CrdConstants;
import org.hl7.davinci.common.PayorIdentifierUtil;
import org.hl7.davinci.common.PlanDefinitionService;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestContextJson;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;

/**
 * Abstract base class for CDS Hook services.
 */
public abstract class CdsServiceBase {

  protected static final String CRD_SERVICE_EXTENSION = """
      {
        "davinci-crd.version":["2.2"],
        "davinci-crd.configuration-options":[
          {"code":"coverage-info","type":"boolean","name":"Coverage Information","description":"Return coverage-information system actions.","default":true},
          {"code":"max-cards","type":"integer","name":"Maximum cards","description":"Maximum number of cards to return.","default":10}
        ]
      }
      """;

  protected final Logger logger = LoggerFactory.getLogger(getClass());

  @Autowired
  protected PlanDefinitionService planDefinitionService;

  @Autowired
  protected PlanDefinitionFinder planDefinitionFinder;

  @Autowired
  protected CoverageInfoHandler coverageInfoHandler;

  @Autowired
  protected CardConverter cardConverter;

  /**
   * Returns the hook name for this service ("order-sign", "appointment-book").
   */
  protected abstract String getHookName();

  /**
   * Validates request context has required fields with correct types.
   * Called early in processRequest, before resource extraction.
   *
   * @param request the CDS service request to validate
   * @throws CdsHooksException.BadRequestException if required context fields are
   *                                               missing or have invalid types
   */
  protected abstract void validateRequestInput(CdsServiceRequestJson request);

  /**
   * Validates that required FHIR resources are present after extraction.
   * Called after resource extraction in processRequest.
   *
   * @param context the extracted resources to validate
   * @throws CdsHooksException.BadRequestException if required resources are
   *                                               missing
   */
  protected abstract void validateExtractedResources(ResolvedResources context);

  /**
   * Selects which resources from the context should be processed by
   * PlanDefinitions.
   *
   * @return list of resources to process (orders, appointments, etc.)
   */
  protected abstract List<Resource> selectContextResources(ResolvedResources context);

  /**
   * Final method that orchestrates all response processing.
   * Calls operations in order: hook-specific customization, client requested
   * configuration, CRD conformance enforcement.
   */
  protected final void finalizeResponse(CdsServiceResponseJson response, CdsServiceRequestJson request) {
    if (response == null) {
      return;
    }

    customizeResponseHook(response, request);
    applyClientConfiguration(response, request);
    CrdConformanceEnforcer.enforce(response, getHookName());
  }

  /**
   * Operation for subclasses to customize the response before configuration and
   * conformance processing.
   */
  protected void customizeResponseHook(CdsServiceResponseJson response, CdsServiceRequestJson request) {
    // Empty - subclasses override for custom response manipulation
  }

  /**
   * Applies client configuration options from the CRD request extension.
   * Filters cards by topic and limits card count based on configuration.
   */
  protected void applyClientConfiguration(CdsServiceResponseJson response, CdsServiceRequestJson request) {
    if (request == null || response == null || request.getExtension() == null) {
      return;
    }

    if (!(request.getExtension() instanceof CrdRequestExtension crdExtension)) {
      return;
    }

    Map<String, Object> config = crdExtension.getConfiguration();
    if (config == null || config.isEmpty()) {
      return;
    }

    List<String> disabledCardTypes = new ArrayList<>();
    for (Map.Entry<String, Object> entry : config.entrySet()) {
      if (entry.getValue() instanceof Boolean enabled && !enabled) {
        disabledCardTypes.add(entry.getKey());
      }
    }

    if (!disabledCardTypes.isEmpty() && response.getCards() != null) {
      response.getCards().removeIf(card -> {
        if (card == null || card.getSource() == null || card.getSource().getTopic() == null) {
          return false;
        }
        String code = card.getSource().getTopic().getCode();
        return code != null && disabledCardTypes.contains(code);
      });
    }

    if (disabledCardTypes.contains("coverage-info") && response.getServiceActions() != null) {
      response.getServiceActions().removeIf(action -> {
        if (action == null || action.getResource() == null) {
          return false;
        }
        if (action.getResource() instanceof org.hl7.fhir.r4.model.DomainResource domainResource) {
          return domainResource.hasExtension(CrdConstants.COVERAGE_INFO_EXT);
        }
        return false;
      });
    }

    Object maxCardsObj = config.get("max-cards");
    if (maxCardsObj instanceof Number maxCards && response.getCards() != null) {
      int limit = maxCards.intValue();
      if (limit >= 0 && response.getCards().size() > limit) {
        response.getCards().subList(limit, response.getCards().size()).clear();
      }
    }
  }

  /**
   * Main entry point for processing CDS requests.
   *
   * This performs the following:
   * - Validate request context input fields (implemented by subclasses)
   * - Extract all available resources from context and prefetch
   * - Validate required resources are present (implemented by subclasses)
   * - Select resources to process (implemented by subclasses)
   * - For each resource, find and execute applicable PlanDefinitions
   * - Customize response (optionally implemented by subclasses)
   * - Return response
   */
  protected CdsServiceResponseJson processRequest(CdsServiceRequestJson request) {

    // Check that the request hook matches this service
    if (!request.getHook().equals(getHookName())) {
      throw new CdsHooksException.BadRequestException(
          "Mismatched hook in request. Expected: " + getHookName() + ", Actual: " + request.getHook());
    }

    CdsServiceResponseJson response = new CdsServiceResponseJson();

    // Validate raw request context types before extraction
    validateRequestInput(request);

    // Extract all resources upfront
    ResolvedResources context = CdsResourceExtractor.extractAllResources(request);

    // Validate required FHIR resources are present
    validateExtractedResources(context);

    // Per CDS Hooks spec: 412 = Required prefetch data could not be retrieved
    if (context.getCoverage() == null) {
      throw new CdsHooksException.PreconditionFailedException(
          "No Coverage resource is accessible for this patient. A Coverage resource with a valid payer identifier is required.");
    }

    // Per CRD IG: The server SHALL return a 400 error...This includes situations where... multiple Coverages are accessible"
    if (context.getCoverageCount() > 1) {
      throw new CdsHooksException.BadRequestException(
          "Multiple Coverage resources are accessible for this patient. CRD requires a single primary Coverage in the request.");
    }

    // Get payor identifiers for PlanDefinition matching
    List<Identifier> payorIdentifiers = extractPayorIdentifiers(context);

    // Per CRD IG: The server SHALL return a 400 error...This includes situations where... the provided Coverage does not have a payer.identifier at all"
    if (payorIdentifiers.isEmpty()) {
      throw new CdsHooksException.BadRequestException(
          "Coverage resource (" + context.getCoverage().getId()
              + ") lacks valid payer identifier. Coverage.payor must reference an Organization with a valid identifier. Coverage.payor value: "
              + context.getCoverage().getPayor().stream().map(Reference::getReference).toList());
    }

    // Per CRD IG: "if a CRD server receives a call where the primary Coverage...does not
    // have a payer.identifier that identifies a payer that is handled by that CRD server
    // endpoint, the server SHALL return a 400 error"
    if (!planDefinitionService.isPayorHandled(payorIdentifiers)) {
      throw new CdsHooksException.BadRequestException(
          "The payer identifier in Coverage is not handled by this CRD server endpoint.");
    }

    // Select resources to process (varies by hook)
    List<Resource> resourcesToProcess = selectContextResources(context);

    logger.info("Selected resources for processing: {}", resourcesToProcess.stream()
        .map(res -> res.getIdElement().toUnqualifiedVersionless().getValue())
        .toList());

    // Process each selected resource
    for (Resource resource : resourcesToProcess) {
      processContextResource(resource, context, payorIdentifiers, request, response);
    }

    // Consolidate duplicate cards
    if (response.getCards() != null && !response.getCards().isEmpty()) {
      List<CdsServiceResponseCardJson> consolidatedCards = cardConverter.consolidateDuplicateCards(response.getCards());
      response.getCards().clear();
      consolidatedCards.forEach(response::addCard);
    }

    // Consolidate duplicate service actions
    if (response.getServiceActions() != null && !response.getServiceActions().isEmpty()) {
      List<CdsServiceResponseSystemActionJson> consolidated = cardConverter.consolidateDuplicateServiceActions(
          response.getServiceActions());
      response.getServiceActions().clear();
      consolidated.forEach(response::addServiceAction);
    }

    // Per CRD IG: Primary hooks (order-sign, order-dispatch, appointment-book)
    // SHALL return coverage-information system action even if no PlanDefinition
    // matched
    if (CrdConformanceEnforcer.isPrimaryHook(getHookName())
        && !coverageInfoHandler.hasCoverageInfoSystemAction(response)) {
      logger.info("No coverage-information generated by rules. Adding default coverage-info for primary hook.");
      coverageInfoHandler.addDefaultCoverageInfo(response, context, resourcesToProcess);
    }

    finalizeResponse(response, request);

    // System actions are required in the response. Add a null action and then clear
    // to force initialization so it's in the response.
    if (response.getServiceActions() == null) {
      response.addServiceAction(null);
      response.getServiceActions().clear();
    }

    return response;
  }

  /**
   * Extracts payor identifiers from the coverage in the context.
   */
  protected List<Identifier> extractPayorIdentifiers(ResolvedResources context) {
    Coverage coverage = context.getCoverage();
    if (coverage == null) {
      logger.warn("No Coverage in context");
      return List.of();
    }

    return PayorIdentifierUtil.extractFromCoverageAndOrganizations(coverage, context.getOrganizations());
  }

  /**
   * Processes a single context resource by finding and executing applicable
   * PlanDefinitions.
   */
  protected void processContextResource(Resource contextResource, ResolvedResources resourceContext,
      List<Identifier> payorIdentifiers, CdsServiceRequestJson request, CdsServiceResponseJson response) {

    List<Coding> codes = FhirCodeExtractor.extractCodes(contextResource, true, request);

    logger.info("Processing resource {} with codes: {}", contextResource.getIdElement().toUnqualifiedVersionless(),
        codes.stream()
            .map(code -> code.getSystem() + "|" + code.getCode())
            .toList());

    // Collect all matching PlanDefinitions and deduplicate by ID
    Map<String, PlanDefinition> uniquePlans = new LinkedHashMap<>();
    for (Coding code : codes) {
      List<PlanDefinition> plans = planDefinitionService.findPlanDefinitions(code, payorIdentifiers, getHookName());
      logger.info("Found {} PlanDefinitions for code {}|{}", plans.size(), code.getSystem(), code.getCode());
      for (PlanDefinition plan : plans) {
        String planId = plan.getIdElement().getIdPart();
        if (!uniquePlans.containsKey(planId)) {
          uniquePlans.put(planId, plan);
        }
      }
    }

    logger.info("Found {} unique PlanDefinitions for resource", uniquePlans.size());

    Bundle dataBundle = buildDataBundle(resourceContext, contextResource);

    for (PlanDefinition plan : uniquePlans.values()) {
      CdsServiceResponseJson planResponse = planDefinitionFinder.applyForCdsResponse(
          plan, resourceContext, contextResource, request, getHookName(), dataBundle);
      if (planResponse != null) {
        logger.info("PlanDefinition executed with {} cards and {} service actions",
            planResponse.getCards() != null ? planResponse.getCards().size() : 0,
            planResponse.getServiceActions() != null ? planResponse.getServiceActions().size() : 0);
        planResponse.getCards().forEach(response::addCard);
        if (planResponse.getServiceActions() != null) {
          planResponse.getServiceActions().forEach(response::addServiceAction);
        }
      }
    }
  }

  protected Bundle buildDataBundle(ResolvedResources context, Resource contextResource) {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    Set<String> seenIds = new HashSet<>();

    addResourceToBundle(bundle, seenIds, context.getPatient());
    addResourceToBundle(bundle, seenIds, context.getCoverage());
    addResourceToBundle(bundle, seenIds, context.getEncounter());
    addResourcesToBundle(bundle, seenIds, context.getPractitioners());
    addResourcesToBundle(bundle, seenIds, context.getPractitionerRoles());
    addResourcesToBundle(bundle, seenIds, context.getOrganizations());
    addResourcesToBundle(bundle, seenIds, context.getCareTeams());
    addResourcesToBundle(bundle, seenIds, context.getLocations());
    addResourcesToBundle(bundle, seenIds, context.getAppointments());
    addResourcesToBundle(bundle, seenIds, context.getOrders());
    addResourcesToBundle(bundle, seenIds, context.getMedicationStatements());
    addResourcesToBundle(bundle, seenIds, context.getMedicationHistory());
    addResourcesToBundle(bundle, seenIds, context.getMedicationDispenses());
    addResourcesToBundle(bundle, seenIds, context.getServiceRequests());
    addResourcesToBundle(bundle, seenIds, context.getDeviceHistory());
    addResourcesToBundle(bundle, seenIds, context.getTasks());
    addResourcesToBundle(bundle, seenIds, context.getConditions());
    addResourcesToBundle(bundle, seenIds, context.getProcedures());
    addResourceToBundle(bundle, seenIds, contextResource);

    return bundle;
  }

  protected void addResourceToBundle(Bundle bundle, Set<String> seenIds, Resource resource) {
    BundleResourceUtil.addByUnqualifiedVersionlessIdentity(bundle, seenIds, resource);
  }

  protected void addResourcesToBundle(Bundle bundle, Set<String> seenIds, List<? extends Resource> resources) {
    if (resources == null) {
      return;
    }
    for (Resource resource : resources) {
      addResourceToBundle(bundle, seenIds, resource);
    }
  }

  // ============================================================
  // REQUEST INPUT VALIDATION HELPERS
  // ============================================================

  /**
   * Validates that a context field is a string.
   *
   * @param context  the request context
   * @param hook     the hook name (for error messages)
   * @param key      the context field key
   * @param required whether the field is required
   * @throws CdsHooksException.BadRequestException if validation fails
   */
  protected void requireString(CdsServiceRequestContextJson context, String hook, String key, boolean required) {
    Object value = context.get(key);
    if (value == null) {
      if (required) {
        throwMissingContext(hook, key);
      }
      return;
    }
    if (!(value instanceof String)) {
      throwInvalidType(hook, key, "a string");
    }
  }

  /**
   * Validates that a context field is an array of strings.
   *
   * @param context  the request context
   * @param hook     the hook name (for error messages)
   * @param key      the context field key
   * @param required whether the field is required
   * @throws CdsHooksException.BadRequestException if validation fails
   */
  protected void requireStringList(CdsServiceRequestContextJson context, String hook, String key, boolean required) {
    Object value = context.get(key);
    if (value == null) {
      if (required) {
        throwMissingContext(hook, key);
      }
      return;
    }
    if (!(value instanceof List<?>)) {
      throwInvalidType(hook, key, "an array of strings");
    }
    List<?> list = (List<?>) value;
    for (Object item : list) {
      if (!(item instanceof String)) {
        throwInvalidType(hook, key, "an array of strings");
      }
    }
  }

  /**
   * Validates that a context field is an object (Map or IBaseResource).
   *
   * @param context  the request context
   * @param hook     the hook name (for error messages)
   * @param key      the context field key
   * @param required whether the field is required
   * @throws CdsHooksException.BadRequestException if validation fails
   */
  protected void requireObject(CdsServiceRequestContextJson context, String hook, String key, boolean required) {
    Object value = context.get(key);
    if (value == null) {
      if (required) {
        throwMissingContext(hook, key);
      }
      return;
    }
    if (!isObjectValue(value)) {
      throwInvalidType(hook, key, "an object");
    }
  }

  /**
   * Validates that a context field is an array of objects.
   *
   * @param context  the request context
   * @param hook     the hook name (for error messages)
   * @param key      the context field key
   * @param required whether the field is required
   * @throws CdsHooksException.BadRequestException if validation fails
   */
  protected void requireObjectList(CdsServiceRequestContextJson context, String hook, String key, boolean required) {
    Object value = context.get(key);
    if (value == null) {
      if (required) {
        throwMissingContext(hook, key);
      }
      return;
    }
    if (!(value instanceof List<?>)) {
      throwInvalidType(hook, key, "an array of objects");
    }
    List<?> list = (List<?>) value;
    for (Object item : list) {
      if (!isObjectValue(item)) {
        throwInvalidType(hook, key, "an array of objects");
      }
    }
  }

  private boolean isObjectValue(Object value) {
    return value instanceof Map<?, ?> || value instanceof IBaseResource;
  }

  private void throwMissingContext(String hook, String key) {
    throw new CdsHooksException.BadRequestException(
        "Missing required context field '" + key + "' for hook '" + hook + "'.");
  }

  private void throwInvalidType(String hook, String key, String expected) {
    throw new CdsHooksException.BadRequestException(
        "Context field '" + key + "' must be " + expected + " for hook '" + hook + "'.");
  }

}
