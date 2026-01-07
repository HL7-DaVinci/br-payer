package org.hl7.davinci.cdshooks.shared;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.fhir.instance.model.api.IBaseBackboneElement;
import org.hl7.fhir.instance.model.api.IBaseDatatype;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedArtifact;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.TriggerDefinition.TriggerType;
import org.opencds.cqf.fhir.cr.hapi.common.IPlanDefinitionProcessorFactory;
import org.opencds.cqf.fhir.cr.plandefinition.PlanDefinitionProcessor;
import org.opencds.cqf.fhir.utility.monad.Eithers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.fhir.rest.param.CompositeAndListParam;
import ca.uhn.fhir.rest.param.CompositeOrListParam;
import ca.uhn.fhir.rest.param.CompositeParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceIndicatorEnum;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardSourceJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCodingJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseLinkJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

/**
 * Abstract base class for CDS Hook services.
 */
public abstract class CdsServiceBase {

  protected static final String COVERAGE_INFO_EXT_URL = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

  protected final Logger logger = LoggerFactory.getLogger(getClass());

  @Autowired
  protected AppProperties appProperties;

  @Autowired
  protected DaoRegistry daoRegistry;

  @Autowired
  protected IPlanDefinitionProcessorFactory planDefinitionProcessorFactory;

  /**
   * Returns the hook name for this service ("order-sign", "appointment-book").
   */
  protected abstract String getHookName();

  /**
   * Validates that required resources are present in the context.
   *
   * @throws InvalidRequestException if required resources are missing
   */
  protected abstract void validateResourceContext(HookResourceContext context);

  /**
   * Selects which resources from the context should be processed by
   * PlanDefinitions.
   *
   * @return list of resources to process (orders, appointments, etc.)
   */
  protected abstract List<Resource> selectContextResources(HookResourceContext context);

  /**
   * Optional hook for subclasses to customize the response before returning.
   * Default implementation does nothing.
   * 
   * @param response the CDS response to customize
   * @param request  the original CDS request
   */
  protected void customizeResponse(CdsServiceResponseJson response, CdsServiceRequestJson request) {
    // Default: no customization
  }

  /**
   * Main entry point for processing CDS requests.
   *
   * This performs the following:
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

    // Extract all resources upfront
    HookResourceContext context = ResourceResolver.extractAllResources(request);

    // Validate required resources are present
    validateResourceContext(context);

    // For primary hooks, Coverage is required to generate coverage-information
    if (isPrimaryHook() && context.getCoverage() == null) {
      throw new CdsHooksException.BadRequestException(
          "No Coverage resource is accessible for this patient. A Coverage resource with a valid payer identifier is required to process primary hook requests (order-sign, order-dispatch, appointment-book).");
    }

    // For supporting hooks with no Coverage, return empty response
    if (!isPrimaryHook() && context.getCoverage() == null) {
      logger.info("Supporting hook called without Coverage - returning empty response");
      return new CdsServiceResponseJson();
    }

    // Get payor identifiers for PlanDefinition matching
    List<Identifier> payorIdentifiers = extractPayorIdentifiers(context);

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
      List<CdsServiceResponseCardJson> consolidatedCards = consolidateDuplicateCards(response.getCards());
      response.getCards().clear();
      consolidatedCards.forEach(response::addCard);
    }

    customizeResponse(response, request);

    // Per CRD IG: Primary hooks (order-sign, order-dispatch, appointment-book)
    // SHALL return
    // coverage-information system action even if no PlanDefinition matched
    if (isPrimaryHook() && !hasCoverageInfoSystemAction(response)) {
      logger.info("No coverage-information generated by rules. Adding default coverage-info for primary hook.");
      addDefaultCoverageInfo(response, context, resourcesToProcess);
    }

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
  protected List<Identifier> extractPayorIdentifiers(HookResourceContext context) {
    List<Identifier> payorIdentifiers = new ArrayList<>();

    Coverage coverage = context.getCoverage();
    if (coverage == null) {
      logger.warn("No Coverage in context");
      return payorIdentifiers;
    }

    for (Reference payorRef : coverage.getPayor()) {
      // Organizations should already be resolved in context
      for (Organization org : context.getOrganizations()) {
        String orgRef = "Organization/" + org.getIdElement().getIdPart();
        if (payorRef.getReference().equals(orgRef) || payorRef.getReference().endsWith(orgRef)) {
          for (Identifier identifier : org.getIdentifier()) {
            if (identifier.hasSystem() && identifier.hasValue()) {
              payorIdentifiers.add(identifier);
            }
          }
        }
      }
    }

    if (payorIdentifiers.isEmpty()) {
      logger.warn("No payor identifiers found in Coverage payors");
    }

    return payorIdentifiers;
  }

  /**
   * Processes a single context resource by finding and executing applicable
   * PlanDefinitions.
   */
  protected void processContextResource(Resource contextResource, HookResourceContext resourceContext,
      List<Identifier> payorIdentifiers, CdsServiceRequestJson request, CdsServiceResponseJson response) {

    List<Coding> codes = extractOrderCodes(contextResource, true, request);

    logger.info("Processing resource {} with codes: {}", contextResource.getIdElement().toUnqualifiedVersionless(),
        codes.stream()
            .map(code -> code.getSystem() + "|" + code.getCode())
            .toList());

    for (Coding code : codes) {
      List<PlanDefinition> plans = findPlanDefinitions(code, payorIdentifiers, getHookName());

      for (PlanDefinition plan : plans) {
        CdsServiceResponseJson planResponse = executePlanDefinition(plan, resourceContext, contextResource, request);
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
  }

  /**
   * Extracts codes from order resources.
   * Normalizes code systems to http:// for consistent matching.
   */
  protected List<Coding> extractOrderCodes(Resource order, boolean normalizeSystem, CdsServiceRequestJson request) {
    List<Coding> codes = new ArrayList<>();

    if (order instanceof DeviceRequest deviceRequest) {
      if (deviceRequest.hasCodeCodeableConcept()) {
        codes.addAll(deviceRequest.getCodeCodeableConcept().getCoding());
      }
    } else if (order instanceof MedicationRequest medRequest) {
      if (medRequest.hasMedicationCodeableConcept()) {
        codes.addAll(medRequest.getMedicationCodeableConcept().getCoding());
      } else if (medRequest.hasMedicationReference()) {
        Medication medication = ResourceResolver.resolveReference(medRequest.getMedicationReference(), Medication.class,
            medRequest, request);
        if (medication != null && medication.hasCode()) {
          codes.addAll(medication.getCode().getCoding());
        }
      }
    } else if (order instanceof ServiceRequest serviceRequest) {
      if (serviceRequest.hasCode()) {
        codes.addAll(serviceRequest.getCode().getCoding());
      }
    }

    // Normalize code systems (https:// -> http://) for consistent matching
    if (normalizeSystem) {
      codes.forEach(coding -> {
        if (coding.hasSystem() && coding.getSystem().startsWith("https://")) {
          coding.setSystem(coding.getSystem().replaceFirst("https://", "http://"));
        }
      });
    }

    return codes;
  }

  /**
   * Finds PlanDefinitions based on the provided code, payor identifiers, and
   * hook.
   */
  protected List<PlanDefinition> findPlanDefinitions(Coding code, List<Identifier> payorIdentifiers, String hook) {

    logger.info("Finding PlanDefinitions for code: {}|{}, payorIdentifiers: {}, hook: {}", code.getSystem(),
        code.getCode(), payorIdentifiers.stream().map(i -> i.getSystem() + "|" + i.getValue()).toList(), hook);

    List<PlanDefinition> plans = new ArrayList<>();

    SearchParameterMap searchParams = new SearchParameterMap();

    // Order code search
    CompositeAndListParam<TokenParam, TokenParam> orderCodeParam = new CompositeAndListParam<>(TokenParam.class,
        TokenParam.class);
    orderCodeParam.addAnd(new CompositeOrListParam<>(TokenParam.class, TokenParam.class)
        .addOr(new CompositeParam<>(
            new TokenParam("focus"),
            new TokenParam(code.getSystem(), code.getCode()))));
    searchParams.add("context-type-value", orderCodeParam);

    // Payor identifiers search
    CompositeAndListParam<TokenParam, TokenParam> payorIdentifiersParam = new CompositeAndListParam<>(TokenParam.class,
        TokenParam.class);
    CompositeOrListParam<TokenParam, TokenParam> payorOrList = new CompositeOrListParam<>(TokenParam.class,
        TokenParam.class);
    for (Identifier payorId : payorIdentifiers) {
      payorOrList.addOr(new CompositeParam<>(
          new TokenParam("program"),
          new TokenParam(payorId.getSystem(), payorId.getValue())));
    }
    payorIdentifiersParam.addAnd(payorOrList);
    searchParams.add("context-type-value", payorIdentifiersParam);

    IBundleProvider planDefBundle = daoRegistry
        .getResourceDao(PlanDefinition.class)
        .search(searchParams, new SystemRequestDetails());

    // Extract PlanDefinitions from the bundle
    planDefBundle.getResources(0, planDefBundle.size()).forEach(resource -> {
      if (resource instanceof PlanDefinition planDef) {

        // Check for the correct hook trigger
        if (planDef.hasAction()) {
          for (PlanDefinition.PlanDefinitionActionComponent action : planDef.getAction()) {
            if (action.hasTrigger()) {
              boolean hasMatchingTrigger = action.getTrigger().stream()
                  .anyMatch(trigger -> trigger.hasType() && trigger.getType() == TriggerType.NAMEDEVENT
                      && trigger.getName().equals(hook));
              if (hasMatchingTrigger) {
                plans.add(planDef);
                break;
              }
            }
          }
        }

      }
    });

    logger.info("SearchParamMap used: {}", searchParams.toNormalizedQueryString());
    logger.info("Found {} PlanDefinitions", plans.size());
    return plans;
  }

  /**
   * Executes a PlanDefinition and returns response with cards and system actions.
   */
  protected CdsServiceResponseJson executePlanDefinition(PlanDefinition plan, HookResourceContext context,
      Resource order, CdsServiceRequestJson request) {

    CdsServiceResponseJson planResponse = new CdsServiceResponseJson();

    PlanDefinitionProcessor processor = planDefinitionProcessorFactory.create(new SystemRequestDetails());

    // Build data bundle with all available resources, ensuring no duplicates by ID
    Bundle dataBundle = new Bundle();
    dataBundle.setType(Bundle.BundleType.COLLECTION);
    Set<String> resourceIds = new HashSet<>();

    Consumer<Resource> addResource = r -> {
      if (r == null)
        return;
      String id = r.hasIdElement() ? r.getIdElement().toUnqualifiedVersionless().getValue() : null;
      if (id == null || resourceIds.add(id)) {
        dataBundle.addEntry().setResource(r);
      }
    };

    addResource.accept(context.getPatient());
    addResource.accept(context.getCoverage());
    addResource.accept(context.getEncounter());
    if (context.getPractitioners() != null) {
      context.getPractitioners().forEach(addResource);
    }
    if (context.getPractitionerRoles() != null) {
      context.getPractitionerRoles().forEach(addResource);
    }
    if (context.getOrganizations() != null) {
      context.getOrganizations().forEach(addResource);
    }
    if (context.getAppointments() != null) {
      context.getAppointments().forEach(addResource);
    }
    if (context.getOrders() != null) {
      context.getOrders().forEach(addResource);
    }
    if (context.getMedicationStatements() != null) {
      context.getMedicationStatements().forEach(addResource);
    }
    if (context.getMedicationHistory() != null) {
      context.getMedicationHistory().forEach(addResource);
    }
    addResource.accept(context.getTask());
    addResource.accept(order);

    // Build common parameters to pass to CQL
    Parameters cqlParameters = new Parameters();
    cqlParameters.addParameter("Hook", new StringType(getHookName()));

    IBaseResource result = processor.applyR5(
        Eithers.forMiddle3(plan.getIdElement().toUnqualifiedVersionless()),
        List.of(context.getPatient().getIdElement().getIdPart()),
        (String) null, // encounter
        (String) null, // practitioner
        (String) null, // organization
        (IBaseDatatype) null, // userType
        (IBaseDatatype) null, // userLanguage
        (IBaseDatatype) null, // userTaskContext
        (IBaseDatatype) null, // setting
        (IBaseDatatype) null, // settingContext
        cqlParameters, // parameters
        false, // useServerData - disabled; use prefetch for historical data
        dataBundle, // data
        (List<? extends IBaseBackboneElement>) null, // prefetchData
        (IBaseResource) null, // dataRepository
        (IBaseResource) null, // contentRepository
        (IBaseResource) null // terminologyRepository
    );

    RequestGroup requestGroup = extractRequestGroup(result);
    List<CdsServiceResponseCardJson> cards = convertToCards(requestGroup, plan, order);
    cards.forEach(card -> planResponse.addCard(card));

    Extension coverageInfoExt = extractCoverageExtension(requestGroup, context.getCoverage());

    if (coverageInfoExt != null) {
      CdsServiceResponseSystemActionJson systemAction = buildCoverageInfoSystemAction(order, coverageInfoExt);
      planResponse.addServiceAction(systemAction);
    }

    return planResponse;
  }

  /**
   * Extracts the RequestGroup from the $apply result.
   */
  protected RequestGroup extractRequestGroup(IBaseResource resource) {

    // R4 $apply returns a CarePlan with contained RequestGroup
    if (resource instanceof CarePlan carePlan) {
      return carePlan.getActivity().stream()
          .filter(CarePlan.CarePlanActivityComponent::hasReference)
          .map(activity -> activity.getReference().getReference())
          .filter(ref -> ref != null && ref.startsWith("#"))
          .map(ref -> {
            String id = ref.substring(1);
            return carePlan.getContained().stream()
                .filter(r -> id.equals(r.getIdElement().getIdPart()))
                .findFirst()
                .orElse(null);
          })
          .filter(RequestGroup.class::isInstance)
          .map(RequestGroup.class::cast)
          .findFirst()
          .orElse(null);
    }

    // R5 $apply returns a Parameters resource with RequestGroup in the "return"
    // parameter
    if (resource instanceof Parameters params) {
      Resource returnResource = params.getParameter("return").getResource();
      if (returnResource instanceof Bundle bundle) {
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
          if (entry.getResource() instanceof RequestGroup rg) {
            return rg;
          }
        }
      } else if (returnResource instanceof RequestGroup rg) {
        return rg;
      }
    }

    return null;
  }

  /**
   * Extracts the coverage-information extension from the RequestGroup action.
   * Adds server-required fields (coverage, date, coverage-assertion-id) if not
   * present.
   *
   * @return the complete extension ready to add to the order, or null if not
   *         found
   */
  protected Extension extractCoverageExtension(RequestGroup requestGroup, Coverage coverage) {
    if (requestGroup == null) {
      return null;
    }

    // Get actions safely - the list may contain null entries when conditions
    // evaluate to false
    List<RequestGroup.RequestGroupActionComponent> actions = requestGroup.getAction();
    if (actions == null || actions.isEmpty()) {
      return null;
    }

    for (RequestGroup.RequestGroupActionComponent action : actions) {
      // Skip null actions (can happen when PlanDefinition action conditions are
      // false)
      if (action == null) {
        continue;
      }
      Extension coverageExt = action.getExtensionByUrl(COVERAGE_INFO_EXT_URL);
      if (coverageExt == null) {
        continue;
      }

      logger.info("Coverage info extension found from CQL");

      String fhirBase = appProperties.getServer_address();
      if (fhirBase != null && fhirBase.endsWith("/")) {
        fhirBase = fhirBase.substring(0, fhirBase.length() - 1);
      }

      // Copy the extension to avoid modifying the original
      Extension result = coverageExt.copy();

      // Add coverage reference if not present (required)
      if (!result.hasExtension("coverage") && coverage != null) {
        result.addExtension("coverage", new Reference(coverage.getIdElement().toUnqualifiedVersionless()));
      }

      // Add date if not present (required)
      if (!result.hasExtension("date")) {
        result.addExtension("date", new DateType(LocalDate.now().toString()));
      }

      // Add coverage-assertion-id if not present (required)
      if (!result.hasExtension("coverage-assertion-id")) {
        result.addExtension("coverage-assertion-id", new StringType("CRD-" + UUID.randomUUID().toString()));
      }

      // Questionnaire values are canonical and should be full URLs if not a fragment
      // or an already defined URL
      List<Extension> questionnaireExt = result.getExtensionsByUrl("questionnaire");
      for (Extension qExt : questionnaireExt) {
        if (qExt.getValue() instanceof CanonicalType strVal) {
          String val = strVal.getValue();
          if (!val.startsWith("#") && !val.startsWith("http://") && !val.startsWith("https://")) {
            String fullUrl = fhirBase;
            if (val.startsWith("Questionnaire/")) {
              fullUrl += "/" + val;
            } else {
              fullUrl += "/Questionnaire/" + val;
            }
            strVal.setValue(fullUrl);
            logger.info("Normalized questionnaire value to full URL: {}", fullUrl);
          }
        }
      }

      return result;
    }

    return null;
  }

  /**
   * Removes stale CRD artifacts from an order resource for a specific coverage.
   * Only removes coverage-information extensions that reference the same
   * coverage, preserving extensions for other coverages (e.g., secondary
   * insurance). Also removes CRD-generated notes that would otherwise accumulate.
   *
   * @param order       the order resource to clean up
   * @param coverageRef the coverage reference to match (only extensions for this
   *                    coverage are removed)
   */
  protected void cleanupOrderForResponse(Resource order, String coverageRef) {
    if (order instanceof DeviceRequest dr) {
      dr.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (order instanceof MedicationRequest mr) {
      mr.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (order instanceof ServiceRequest sr) {
      sr.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    }
  }

  /**
   * Checks if an extension is a coverage-information extension for the specified
   * coverage.
   */
  private boolean isSameCoverageExtension(Extension ext, String coverageRef) {
    if (!COVERAGE_INFO_EXT_URL.equals(ext.getUrl())) {
      return false;
    }
    Extension coverageExt = ext.getExtensionByUrl("coverage");
    if (coverageExt == null || !(coverageExt.getValue() instanceof Reference ref)) {
      return false;
    }
    String extCoverageRef = ref.getReference();
    // Match if references are equal or if one ends with the other
    return coverageRef != null && extCoverageRef != null &&
        (coverageRef.equals(extCoverageRef) ||
            coverageRef.endsWith(extCoverageRef) ||
            extCoverageRef.endsWith(coverageRef));
  }

  /**
   * Builds a system action to update the order with the coverage-information
   * extension. Removes any existing coverage-information extensions from the
   * order before adding the new one.
   *
   * @param order           the original order resource
   * @param coverageInfoExt the coverage-information extension from CQL (with
   *                        server fields added)
   * @return the system action for the CDS response
   */
  protected CdsServiceResponseSystemActionJson buildCoverageInfoSystemAction(Resource order,
      Extension coverageInfoExt) {

    // Clone the order and clean up stale CRD artifacts before adding new ones
    Resource updatedOrder = order.copy();

    // Extract coverage reference from the new extension to match against old ones
    String coverageRef = null;
    Extension coverageExtInNew = coverageInfoExt.getExtensionByUrl("coverage");
    if (coverageExtInNew != null && coverageExtInNew.getValue() instanceof Reference ref) {
      coverageRef = ref.getReference();
    }
    cleanupOrderForResponse(updatedOrder, coverageRef);

    // Add the new coverage-information extension
    if (updatedOrder instanceof DeviceRequest dr) {
      dr.addExtension(coverageInfoExt);
    } else if (updatedOrder instanceof MedicationRequest mr) {
      mr.addExtension(coverageInfoExt);
    } else if (updatedOrder instanceof ServiceRequest sr) {
      sr.addExtension(coverageInfoExt);
    }

    // Build the system action
    CdsServiceResponseSystemActionJson systemAction = new CdsServiceResponseSystemActionJson();
    systemAction.setType("update");
    systemAction.setDescription("Add coverage information to " + order.fhirType());
    systemAction.setResource(updatedOrder);

    return systemAction;
  }

  /**
   * Converts RequestGroup actions to CDS Hooks response cards.
   * Each card is assigned a UUID and linked to the associated order resource.
   *
   * @param requestGroup the RequestGroup from PlanDefinition $apply
   * @param planDef      the PlanDefinition that was executed
   * @param order        the order resource this card is associated with
   */
  protected List<CdsServiceResponseCardJson> convertToCards(RequestGroup requestGroup, PlanDefinition planDef,
      Resource order) {
    List<CdsServiceResponseCardJson> cards = new ArrayList<>();

    // Return empty cards array when no RequestGroup
    // CDS Hooks spec: "If your CDS Service has no decision support for the user,
    // your service should return a 200 HTTP response with an empty array of cards"
    // See: https://cds-hooks.org/specification/current/#http-response
    if (requestGroup == null) {
      logger.info("No RequestGroup found - returning empty cards array");
      return cards;
    }

    // Get actions safely - the list may contain null entries when conditions
    // evaluate to false
    List<RequestGroup.RequestGroupActionComponent> actions = requestGroup.getAction();
    if (actions == null || actions.isEmpty()) {
      logger.info("No actions found - returning empty cards array");
      return cards;
    }

    for (RequestGroup.RequestGroupActionComponent action : actions) {

      // Skip null actions and actions without a title (summary is REQUIRED per CDS
      // Hooks spec)
      // See: https://cds-hooks.org/specification/current/#card-attributes
      if (action == null || !action.hasTitle()) {
        logger.info("Skipping null or untitled action - summary is required for valid cards");
        continue;
      }

      CdsServiceResponseCardJson card = new CdsServiceResponseCardJson();

      // Set UUID for card tracking
      card.setUuid(UUID.randomUUID().toString());

      card.setSummary(action.getTitle());
      card.setDetail(action.getDescription());

      // Set indicator based on coverage status
      card.setIndicator(CdsServiceIndicatorEnum.INFO);
      Extension coverageExt = action.getExtensionByUrl(COVERAGE_INFO_EXT_URL);
      if (coverageExt != null) {
        Extension coveredExt = coverageExt.getExtensionByUrl("covered");
        if (coveredExt != null && "not-covered".equals(coveredExt.getValue().primitiveValue())) {
          card.setIndicator(CdsServiceIndicatorEnum.WARNING);
        }
      }

      CdsServiceResponseCardSourceJson source = new CdsServiceResponseCardSourceJson();
      source.setLabel(planDef.hasPublisher() ? planDef.getPublisher() : "Da Vinci CRD");
      source.setUrl(planDef.getUrl());

      // Get source.topic from action extension, default to "coverage-info"
      String topicCode = "coverage-info";
      Extension cardTypeExt = action
          .getExtensionByUrl("http://hl7.org/fhir/us/davinci-crd/StructureDefinition/cardType");
      if (cardTypeExt != null && cardTypeExt.hasValue()) {
        topicCode = cardTypeExt.getValue().primitiveValue();
      }
      source.setTopic(new CdsServiceResponseCodingJson()
          .setSystem("http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp")
          .setCode(topicCode));
      card.setSource(source);

      // Map links to CDS Hooks card links
      // Sources: action.documentation (RelatedArtifact)
      List<CdsServiceResponseLinkJson> links = new ArrayList<>();

      // Extract from action.documentation
      if (action.hasDocumentation()) {
        for (RelatedArtifact doc : action.getDocumentation()) {
          if (doc.hasUrl()) {
            CdsServiceResponseLinkJson link = new CdsServiceResponseLinkJson();
            link.setLabel(doc.hasDisplay() ? doc.getDisplay() : doc.getUrl());
            link.setUrl(doc.getUrl());
            Extension linkTypeExt = doc
                .getExtensionByUrl("http://hl7.org/fhir/us/davinci-crd/StructureDefinition/linkType");
            if (linkTypeExt != null && "smart".equals(linkTypeExt.getValue().primitiveValue())) {
              link.setType("smart");
            } else {
              link.setType("absolute");
            }
            links.add(link);
          }
        }
      }

      if (!links.isEmpty()) {
        card.setLinks(links);
      }

      // Add associated-resource extension linking card to the order
      if (order != null && order.hasIdElement()) {
        CrdCardExtension extension = new CrdCardExtension();
        String resourceRef = order.fhirType() + "/" + order.getIdElement().getIdPart();
        extension.addAssociatedResource(resourceRef);
        card.setExtension(extension);
      }

      cards.add(card);
    }

    return cards;
  }

  /**
   * Consolidates duplicate cards by merging their associated-resource extensions.
   *
   * Two cards are considered duplicates if they have:
   * - Same summary
   * - Same detail
   * - Same indicator
   * - Same source URL
   *
   * @param cards List of cards to consolidate
   * @return Consolidated list of cards
   */
  protected List<CdsServiceResponseCardJson> consolidateDuplicateCards(List<CdsServiceResponseCardJson> cards) {
    if (cards == null || cards.isEmpty()) {
      return new ArrayList<>();
    }

    if (cards.size() == 1) {
      return new ArrayList<>(cards);
    }

    Map<String, CdsServiceResponseCardJson> consolidatedCards = new LinkedHashMap<>();

    for (CdsServiceResponseCardJson card : cards) {
      String cardKey = generateCardKey(card);

      if (consolidatedCards.containsKey(cardKey)) {
        // Card already exists - merge associated resources
        CdsServiceResponseCardJson existingCard = consolidatedCards.get(cardKey);
        mergeAssociatedResources(existingCard, card);
      } else {
        // New card - add to map
        consolidatedCards.put(cardKey, card);
      }
    }

    logger.info("Consolidated {} cards into {} unique cards", cards.size(), consolidatedCards.size());
    return new ArrayList<>(consolidatedCards.values());
  }

  /**
   * Generates a unique key for a card based on its content.
   * Cards with the same key are considered duplicates.
   */
  protected String generateCardKey(CdsServiceResponseCardJson card) {
    StringBuilder key = new StringBuilder();

    if (card.getSummary() != null) {
      key.append(card.getSummary());
    }
    key.append("|");

    if (card.getDetail() != null) {
      key.append(card.getDetail());
    }
    key.append("|");

    if (card.getIndicator() != null) {
      key.append(card.getIndicator());
    }
    key.append("|");

    if (card.getSource() != null && card.getSource().getUrl() != null) {
      key.append(card.getSource().getUrl());
    }

    return key.toString();
  }

  /**
   * Merges associated-resource extensions from source card into target card.
   */
  protected void mergeAssociatedResources(CdsServiceResponseCardJson targetCard,
      CdsServiceResponseCardJson sourceCard) {
    // Get or create target extension
    CrdCardExtension targetExtension = (CrdCardExtension) targetCard.getExtension();
    if (targetExtension == null) {
      targetExtension = new CrdCardExtension();
      targetCard.setExtension(targetExtension);
    }

    // Get source extension
    CrdCardExtension sourceExtension = (CrdCardExtension) sourceCard.getExtension();
    if (sourceExtension == null || sourceExtension.getAssociatedResources().isEmpty()) {
      return;
    }

    // Get lists
    List<String> targetResources = targetExtension.getAssociatedResources();
    List<String> sourceResources = sourceExtension.getAssociatedResources();

    // Merge resources (avoid duplicates)
    for (String resource : sourceResources) {
      if (!targetResources.contains(resource)) {
        targetResources.add(resource);
      }
    }

    logger.info("Merged {} associated resources into card", sourceResources.size());
  }

  /**
   * Checks if this is a primary hook that requires mandatory
   * coverage-information.
   * Per CRD IG: order-sign, order-dispatch, and appointment-book are primary
   * hooks.
   */
  protected boolean isPrimaryHook() {
    String hookName = getHookName();
    return "order-sign".equals(hookName) ||
        "order-dispatch".equals(hookName) ||
        "appointment-book".equals(hookName);
  }

  /**
   * Checks if the response already contains a coverage-information system action.
   */
  protected boolean hasCoverageInfoSystemAction(CdsServiceResponseJson response) {
    if (response.getServiceActions() == null) {
      return false;
    }
    return response.getServiceActions().stream()
        .anyMatch(action -> {
          if (action == null || action.getResource() == null) {
            return false;
          }
          DomainResource resource = (DomainResource) action.getResource();
          return resource.hasExtension(COVERAGE_INFO_EXT_URL);
        });
  }

  /**
   * Adds default coverage-information system actions when no rules matched.
   * Per CRD IG: Returns "not-covered" with reason "no-active-coverage" if
   * coverage is missing, or "conditional"
   */
  protected void addDefaultCoverageInfo(CdsServiceResponseJson response, HookResourceContext context,
      List<Resource> orders) {
    for (Resource order : orders) {
      Extension coverageInfoExt = buildDefaultCoverageExtension(context);
      CdsServiceResponseSystemActionJson systemAction = buildCoverageInfoSystemAction(order, coverageInfoExt);
      response.addServiceAction(systemAction);
    }
  }

  /**
   * Builds a default coverage-information extension based on available context.
   */
  protected Extension buildDefaultCoverageExtension(HookResourceContext context) {
    Extension coverageInfoExt = new Extension(COVERAGE_INFO_EXT_URL);
    Coverage coverage = context.getCoverage();

    // Coverage reference (always present after validation)
    Extension coverageRefExt = new Extension("coverage");
    Reference coverageRef = new Reference("Coverage/" + coverage.getIdElement().getIdPart());
    coverageRefExt.setValue(coverageRef);
    coverageInfoExt.addExtension(coverageRefExt);

    // Coverage resource exists but no rules matched = conditional
    Extension coveredExt = new Extension("covered");
    coveredExt.setValue(new CodeType("conditional"));
    coverageInfoExt.addExtension(coveredExt);

    // Date
    Extension dateExt = new Extension("date");
    dateExt.setValue(new DateType(new Date()));
    coverageInfoExt.addExtension(dateExt);

    // Coverage assertion ID
    Extension assertionIdExt = new Extension("coverage-assertion-id");
    String assertionId = "default-" + System.currentTimeMillis();
    assertionIdExt.setValue(new StringType(assertionId));
    coverageInfoExt.addExtension(assertionIdExt);

    return coverageInfoExt;
  }

}
