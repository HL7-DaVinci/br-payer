package org.hl7.davinci.cdshooks.shared;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Reference;
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
    CdsServiceResponseJson response = new CdsServiceResponseJson();

    // Extract all resources upfront
    HookResourceContext context = ResourceResolver.extractAllResources(request);

    // Validate required resources are present
    validateResourceContext(context);

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

    customizeResponse(response, request);

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
      if (r == null) return;
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
    addResource.accept(context.getTask());
    addResource.accept(order);

    // Build CQL parameters to pass the order resource directly
    Parameters cqlParameters = new Parameters();
    if (order instanceof DeviceRequest) {
      cqlParameters.addParameter().setName("device_request").setResource(order);
    } else if (order instanceof MedicationRequest) {
      cqlParameters.addParameter().setName("medication_request").setResource(order);
    } else if (order instanceof ServiceRequest) {
      cqlParameters.addParameter().setName("service_request").setResource(order);
    }

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
        true, // useServerData
        dataBundle, // data
        (List<? extends IBaseBackboneElement>) null, // prefetchData
        (IBaseResource) null, // dataRepository
        (IBaseResource) null, // contentRepository
        (IBaseResource) null // terminologyRepository
    );

    RequestGroup requestGroup = extractRequestGroup(result);
    List<CdsServiceResponseCardJson> cards = convertToCards(requestGroup, plan, order);
    cards.forEach(card -> planResponse.addCard(card));

    CoverageInfo coverageInfo = extractCoverageInfoFromRequestGroup(requestGroup, order, context.getCoverage(),
        request);

    if (coverageInfo != null && context.getCoverage() != null) {
      CdsServiceResponseSystemActionJson systemAction = buildCoverageInfoSystemAction(order, coverageInfo);
      if (systemAction != null) {
        planResponse.addServiceAction(systemAction);

        if (!coverageInfo.getDocNeeded().isEmpty()
            && !coverageInfo.getQuestionnaireUrls().isEmpty()) {
          addDtrLaunchLinks(cards, coverageInfo, order, context.getCoverage());
        }
      }
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
   * Extracts coverage extension information from the RequestGroup action.
   */
  protected CoverageInfo extractCoverageInfoFromRequestGroup(RequestGroup requestGroup, Resource order,
      Coverage coverage, CdsServiceRequestJson request) {
    if (requestGroup == null || !requestGroup.hasAction()) {
      return null;
    }

    for (RequestGroup.RequestGroupActionComponent action : requestGroup.getAction()) {
      CoverageInfo info = new CoverageInfo();
      info.setAssertionId("CRD-" + UUID.randomUUID().toString());
      info.setDate(LocalDate.now());

      // Set coverage reference (required)
      if (coverage != null) {
        info.setCoverage(new Reference(coverage.getIdElement().toUnqualifiedVersionless()));
      }

      Extension coverageExt = action.getExtensionByUrl(COVERAGE_INFO_EXT_URL);
      logger.info("Coverage info extension found: " + coverageExt);
      if (coverageExt != null) {
        info.setBaseExtension(coverageExt);
        // Extract values from the extension for DTR and other logic
        // Handle 1..1 covered
        Extension coveredExt = coverageExt.getExtensionByUrl("covered");
        if (coveredExt != null && coveredExt.hasValue()) {
          info.setCovered(coveredExt.getValue().primitiveValue());
        }
        // Handle 0..* doc-needed
        for (Extension ext : coverageExt.getExtensionsByUrl("doc-needed")) {
          info.addDocNeeded(ext.getValue().primitiveValue());
        }
        // Handle 0..* questionnaire
        for (Extension ext : coverageExt.getExtensionsByUrl("questionnaire")) {
          if (ext.getValue() instanceof CanonicalType canonical) {
            info.addQuestionnaireUrl(canonical.getValue());
          }
        }
      }

      return info;
    }

    return null;
  }

  /**
   * Builds a system action to update the order with the coverage-information
   * extension.
   */
  protected CdsServiceResponseSystemActionJson buildCoverageInfoSystemAction(Resource order,
      CoverageInfo coverageInfo) {

    // Use the base extension from CQL if available, otherwise create a new one
    Extension coverageInfoExt = coverageInfo.getBaseExtension() != null 
        ? coverageInfo.getBaseExtension().copy() 
        : new Extension(COVERAGE_INFO_EXT_URL);

    // Add/Update coverage reference (required)
    if (coverageInfo.getCoverage() != null) {
      if (coverageInfoExt.hasExtension("coverage")) {
        coverageInfoExt.getExtensionByUrl("coverage").setValue(coverageInfo.getCoverage());
      } else {
        coverageInfoExt.addExtension("coverage", coverageInfo.getCoverage());
      }
    } else {
      logger.warn("Missing coverage reference for coverage-information extension");
    }

    // Add/Update covered status (required)
    if (coverageInfo.getCovered() != null) {
      if (coverageInfoExt.hasExtension("covered")) {
        coverageInfoExt.getExtensionByUrl("covered").setValue(new CodeType(coverageInfo.getCovered()));
      } else {
        coverageInfoExt.addExtension("covered", new CodeType(coverageInfo.getCovered()));
      }
    } else if (!coverageInfoExt.hasExtension("covered")) {
      coverageInfoExt.addExtension("covered", new CodeType("covered"));
    }

    // Add/Update date (required)
    if (coverageInfo.getDate() != null) {
      if (coverageInfoExt.hasExtension("date")) {
        coverageInfoExt.getExtensionByUrl("date").setValue(new DateType(coverageInfo.getDate().toString()));
      } else {
        coverageInfoExt.addExtension("date", new DateType(coverageInfo.getDate().toString()));
      }
    }

    // Add/Update coverage-assertion-id (required)
    if (coverageInfoExt.hasExtension("coverage-assertion-id")) {
      coverageInfoExt.getExtensionByUrl("coverage-assertion-id").setValue(new StringType(coverageInfo.getAssertionId()));
    } else {
      coverageInfoExt.addExtension("coverage-assertion-id", new StringType(coverageInfo.getAssertionId()));
    }

    // Clone the order and add the extension
    Resource updatedOrder = order.copy();

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
   * Adds SMART links for DTR launch to the first card for each questionnaire.
   */
  protected void addDtrLaunchLinks(List<CdsServiceResponseCardJson> cards, CoverageInfo coverageInfo,
      Resource order, Coverage coverage) {

    if (cards.isEmpty())
      return;

    CdsServiceResponseCardJson card = cards.get(0);
    List<CdsServiceResponseLinkJson> links = new ArrayList<>();

    for (String questionnaireUrl : coverageInfo.getQuestionnaireUrls()) {
      // Build SMART link for DTR launch
      CdsServiceResponseLinkJson dtrLink = new CdsServiceResponseLinkJson();

      // Use questionnaire name as label if available
      String label = "Complete Documentation (DTR)";
      if (questionnaireUrl.contains("/")) {
        String questionnaireName = questionnaireUrl.substring(questionnaireUrl.lastIndexOf("/") + 1);
        label = "Complete " + questionnaireName + " (DTR)";
      }
      dtrLink.setLabel(label);
      dtrLink.setType("smart");
      // DTR launch URL - this would be configured per installation
      dtrLink.setUrl(appProperties.getDtrLaunchUrl());

      // Build app context with questionnaire and order references
      String appContext = String.format(
          "questionnaire=%s&order=%s/%s&coverage=%s/%s&coverage-assertion-id=%s",
          URLEncoder.encode(questionnaireUrl, StandardCharsets.UTF_8),
          order.fhirType(), order.getIdElement().getIdPart(),
          coverage.fhirType(), coverage.getIdElement().getIdPart(),
          coverageInfo.getAssertionId());
      dtrLink.setAppContext(appContext);

      links.add(dtrLink);
    }

    card.setLinks(links);
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
    if (requestGroup == null) {
      throw new InvalidRequestException("No RequestGroup found in PlanDefinition execution result");
    }

    List<CdsServiceResponseCardJson> cards = new ArrayList<>();

    for (RequestGroup.RequestGroupActionComponent action : requestGroup.getAction()) {
      if (action == null) {
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
      source.setTopic(new CdsServiceResponseCodingJson()
          .setSystem("http://hl7.org/fhir/us/davinci-crd/CodeSystem/plan-definition-topics")
          .setCode("crd"));
      card.setSource(source);

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

}
