package org.hl7.davinci.cdshooks;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
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

import ca.uhn.fhir.context.FhirContext;
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
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.hapi.fhir.cdshooks.api.CdsService;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServicePrefetch;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceIndicatorEnum;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardSourceJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseLinkJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;

public class OrderSignService {

  private static final String COVERAGE_INFO_EXT_URL = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

  private final static Logger logger = LoggerFactory.getLogger(OrderSignService.class);

  private AppProperties appProperties;
  private final DaoRegistry daoRegistry;
  private final IPlanDefinitionProcessorFactory planDefinitionProcessorFactory;

  public OrderSignService(
      AppProperties appProperties,
      DaoRegistry daoRegistry,
      IPlanDefinitionProcessorFactory planDefinitionProcessorFactory) {
    this.appProperties = appProperties;
    this.daoRegistry = daoRegistry;
    this.planDefinitionProcessorFactory = planDefinitionProcessorFactory;
  }

  @CdsService(value = "order-sign-crd", hook = "order-sign", title = "CRD Order Sign Hook", description = "CRD order-sign hook", allowAutoFhirClientPrefetch = true, prefetch = {
      @CdsServicePrefetch(value = "user", query = "{{context.userId}}"),
      @CdsServicePrefetch(value = "patient", query = "Patient/{{context.patientId}}"),
      @CdsServicePrefetch(value = "coverage", query = "Coverage?patient={{context.patientId}}")
  })
  public CdsServiceResponseJson handleRequest(CdsServiceRequestJson request) {
    CdsServiceResponseJson response = new CdsServiceResponseJson();

    // Extract required data from prefetch and context and throw exceptions if
    // missing
    Patient patient = (Patient) request.getPrefetch("patient");
    Bundle draftOrders = (Bundle) request.getContext().get("draftOrders");
    Bundle coverageBundle = (Bundle) request.getPrefetch("coverage");

    if (patient == null) {
      throw new InvalidRequestException("Prefetch patient data is missing");
    }
    if (draftOrders == null) {
      throw new InvalidRequestException("'draftOrders' context data is missing");
    }
    if (coverageBundle == null) {
      throw new InvalidRequestException("Prefetch coverage data is missing");
    }

    // Extract coverage resource -- taking the first one for now
    Coverage coverage = coverageBundle.getEntry().isEmpty() ? null
        : (Coverage) coverageBundle.getEntryFirstRep().getResource();
    if (coverage == null) {
      throw new InvalidRequestException("No Coverage resource found in prefetch data");
    }

    // Extract payor codes from coverage
    List<String> payorCodes = extractPayorCodes(coverage, coverageBundle, request);

    // For each order, find applicable PlanDefinitions
    draftOrders.getEntry().forEach(orderEntry -> {

      Resource order = orderEntry.getResource();

      // Extract relevant codes from the order
      List<Coding> codes = extractOrderCodes(order, true);

      codes.forEach(code -> {

        // Find applicable PlanDefinitions based on order code, payor codes, and hook
        List<PlanDefinition> plans = findPlanDefinitions(code, payorCodes, "order-sign");

        // Execute each PlanDefinition and add resulting response to main response
        plans.forEach(plan -> {
          CdsServiceResponseJson planResponse = executePlanDefinition(plan, patient, coverage, order, request);
          if (planResponse != null) {
            planResponse.getCards().forEach(card -> response.addCard(card));
            if (planResponse.getServiceActions() != null) {
              planResponse.getServiceActions().forEach(action -> response.addServiceAction(action));
            }
          }
        });

      });
    });

    String name = patient.getNameFirstRep().getNameAsSingleString();
    if (name == null || name.isEmpty()) {
      name = "unknown";
    }

    return response;
  }

  /**
   * Extracts relevant codes from the order resource based on its type.
   * Normalizes code systems to use http:// instead of https:// for consistency if
   * requested
   */
  private List<Coding> extractOrderCodes(Resource order, boolean normalizeSystem) {
    List<Coding> codes = new ArrayList<>();

    if (order instanceof DeviceRequest deviceRequest) {
      if (deviceRequest.hasCodeCodeableConcept()) {
        codes.addAll(deviceRequest.getCodeCodeableConcept().getCoding());
      }
    } else if (order instanceof MedicationRequest medRequest) {
      if (medRequest.hasMedicationCodeableConcept()) {
        codes.addAll(medRequest.getMedicationCodeableConcept().getCoding());
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
   * Returns payor codes from the Coverage resource by resolving payor Organization references
   * and extracting identifier codes for the "urn:oid:2.16.840.1.113883.6.300" system.
   * 
   * Organizations are resolved in this order:
   * 1. Contained resources within the Coverage
   * 2. Resources in the coverage prefetch bundle
   * 3. Resources in other prefetch bundles
   * 4. Server lookup via DaoRegistry
   */
  private List<String> extractPayorCodes(Coverage coverage, Bundle coverageBundle, CdsServiceRequestJson request) {
    List<String> payorCodes = new ArrayList<>();
    String targetSystem = "urn:oid:2.16.840.1.113883.6.300";

    for (Reference payorRef : coverage.getPayor()) {
      Organization org = resolveOrganization(payorRef, coverage, coverageBundle, request);
      if (org != null) {
        // Look for identifier with the target system
        for (Identifier identifier : org.getIdentifier()) {
          if (targetSystem.equals(identifier.getSystem()) && identifier.hasValue()) {
            payorCodes.add(identifier.getValue());
          }
        }
      }
    }

    // If no payor codes found, log warning but continue with empty list
    if (payorCodes.isEmpty()) {
      logger.warn("No payor codes found for system {} in Coverage payors", targetSystem);
    }

    return payorCodes;
  }

  /**
   * Resolves an Organization reference from various sources.
   */
  private Organization resolveOrganization(Reference ref, Coverage coverage, 
      Bundle coverageBundle, CdsServiceRequestJson request) {
    
    if (ref == null || !ref.hasReference()) {
      return null;
    }

    String reference = ref.getReference();

    // 1. Check if it's a contained resource reference (starts with #)
    if (reference.startsWith("#")) {
      String containedId = reference.substring(1);
      for (Resource contained : coverage.getContained()) {
        if (contained instanceof Organization org && containedId.equals(org.getIdElement().getIdPart())) {
          return org;
        }
      }
    }

    // 2. Check the coverage bundle for the Organization
    Organization org = findOrganizationInBundle(coverageBundle, reference);
    if (org != null) {
      return org;
    }

    // 3. Check other prefetch resources
    org = findOrganizationInPrefetch(request, reference);
    if (org != null) {
      return org;
    }

    // 4. Try to resolve from the server
    org = resolveOrganizationFromServer(reference);
    if (org != null) {
      return org;
    }

    logger.warn("Could not resolve Organization reference: {}", reference);
    return null;
  }

  /**
   * Searches a bundle for an Organization matching the reference.
   */
  private Organization findOrganizationInBundle(Bundle bundle, String reference) {
    if (bundle == null || !bundle.hasEntry()) {
      return null;
    }

    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (entry.getResource() instanceof Organization org) {
        // Match by full URL or resource type/id
        String resourceRef = "Organization/" + org.getIdElement().getIdPart();
        if (reference.equals(resourceRef) || reference.endsWith(resourceRef) 
            || (entry.hasFullUrl() && reference.equals(entry.getFullUrl()))) {
          return org;
        }
      }
    }
    return null;
  }

  /**
   * Searches prefetch data for an Organization matching the reference.
   */
  private Organization findOrganizationInPrefetch(CdsServiceRequestJson request, String reference) {
    // Check if there's a direct Organization prefetch
    Object prefetchOrg = request.getPrefetch("organization");
    if (prefetchOrg instanceof Organization org) {
      String resourceRef = "Organization/" + org.getIdElement().getIdPart();
      if (reference.equals(resourceRef) || reference.endsWith(resourceRef)) {
        return org;
      }
    }

    // Check if there's a Bundle in any prefetch that might contain the Organization
    // Iterate through known prefetch keys
    for (String key : List.of("patient", "user", "coverage")) {
      Object prefetch = request.getPrefetch(key);
      if (prefetch instanceof Bundle bundle) {
        Organization org = findOrganizationInBundle(bundle, reference);
        if (org != null) {
          return org;
        }
      }
    }

    return null;
  }

  /**
   * Resolves an Organization from the FHIR server.
   */
  private Organization resolveOrganizationFromServer(String reference) {
    try {
      // Extract the ID from the reference (e.g., "Organization/123" -> "123")
      String orgId = reference;
      if (reference.contains("/")) {
        orgId = reference.substring(reference.lastIndexOf("/") + 1);
      }
      // Remove any URL prefix
      if (orgId.contains("|")) {
        orgId = orgId.substring(0, orgId.indexOf("|"));
      }

      IBaseResource resource = daoRegistry
          .getResourceDao(Organization.class)
          .read(new org.hl7.fhir.r4.model.IdType("Organization", orgId), new SystemRequestDetails());
      
      if (resource instanceof Organization org) {
        return org;
      }
    } catch (Exception e) {
      logger.debug("Could not resolve Organization {} from server: {}", reference, e.getMessage());
    }
    return null;
  }

  /**
   * Finds PlanDefinitions based on the provided code, payor codes, and hook.
   */
  private List<PlanDefinition> findPlanDefinitions(Coding code, List<String> payorCodes, String hook) {

    logger.info("Finding PlanDefinitions for code: {}, payorCodes: {}, hook: {}", code, payorCodes, hook);

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

    // Payor codes search
    CompositeAndListParam<TokenParam, TokenParam> payorCodesParam = new CompositeAndListParam<>(TokenParam.class,
        TokenParam.class);
    CompositeOrListParam<TokenParam, TokenParam> payorOrList = new CompositeOrListParam<>(TokenParam.class,
        TokenParam.class);
    for (String payorCode : payorCodes) {
      payorOrList.addOr(new CompositeParam<>(
          new TokenParam("program"),
          new TokenParam("urn:oid:2.16.840.1.113883.6.300", payorCode)));
    }
    payorCodesParam.addAnd(payorOrList);
    searchParams.add("context-type-value", payorCodesParam);

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

    logger.info("SearchParamMap used: {}", searchParams.toNormalizedQueryString(FhirContext.forR4Cached()));
    logger.info("Found {} PlanDefinitions", plans.size());
    return plans;
  }

  /**
   * Executes the given PlanDefinition and returns a response with cards and
   * system actions.
   */
  private CdsServiceResponseJson executePlanDefinition(PlanDefinition plan, Patient patient,
      Coverage coverage, Resource order, CdsServiceRequestJson request) {

    CdsServiceResponseJson planResponse = new CdsServiceResponseJson();

    // Create processor from factory using SystemRequestDetails
    PlanDefinitionProcessor processor = planDefinitionProcessorFactory.create(new SystemRequestDetails());

    // Build data bundle with prefetch resources
    Bundle dataBundle = new Bundle();
    dataBundle.setType(Bundle.BundleType.COLLECTION);
    dataBundle.addEntry().setResource(patient);
    dataBundle.addEntry().setResource(coverage);
    dataBundle.addEntry().setResource(order);

    // Build CQL parameters to pass the order resource directly
    Parameters cqlParameters = new Parameters();
    if (order instanceof DeviceRequest) {
      cqlParameters.addParameter().setName("device_request").setResource(order);
    }

    // Execute $apply operation using applyR5 to get a Bundle with RequestGroup
    // instead of CarePlan that would be returned by regular (R4) apply
    IBaseResource result = processor.applyR5(
        Eithers.forMiddle3(plan.getIdElement().toUnqualifiedVersionless()),
        List.of(patient.getIdElement().getIdPart()),
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

    // Convert the RequestGroup to cards and extract coverage info
    RequestGroup requestGroup = extractRequestGroup(result);
    List<CdsServiceResponseCardJson> cards = convertToCards(requestGroup, plan);
    cards.forEach(card -> planResponse.addCard(card));

    // Extract coverage-information extension from the RequestGroup action
    CoverageInfo coverageInfo = extractCoverageInfoFromRequestGroup(requestGroup);

    // If we have coverage information, add a system action
    if (coverageInfo != null) {
      CdsServiceResponseSystemActionJson systemAction = buildCoverageInfoSystemAction(order, coverage, coverageInfo);
      if (systemAction != null) {
        planResponse.addServiceAction(systemAction);

        // If documentation is needed, add SMART links for DTR launch
        if (coverageInfo.getDocNeeded() != null && !"no-doc".equals(coverageInfo.getDocNeeded())
            && !coverageInfo.getQuestionnaireUrls().isEmpty()) {
          addDtrLaunchLinks(cards, coverageInfo, order, coverage);
        }
      }
    }

    return planResponse;
  }

  /**
   * Extracts the RequestGroup from the $apply result.
   */
  private RequestGroup extractRequestGroup(IBaseResource resource) {
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
   * Extracts coverage-information values from the RequestGroup action.
   * Questionnaire URLs are extracted from sub-actions with resource references.
   */
  private CoverageInfo extractCoverageInfoFromRequestGroup(RequestGroup requestGroup) {
    if (requestGroup == null || !requestGroup.hasAction()) {
      return null;
    }

    for (RequestGroup.RequestGroupActionComponent action : requestGroup.getAction()) {
      CoverageInfo info = new CoverageInfo();
      info.setAssertionId("CRD-" + UUID.randomUUID().toString());

      // Default coverage values
      info.setCovered("covered");
      info.setPaNeeded("no-auth");

      // Derive doc-needed from the action title
      // The CQL "Get Summary" returns "Documentation required" or "No documentation
      // required"
      String title = action.getTitle();
      if (title != null && title.toLowerCase().contains("documentation required")) {
        info.setDocNeeded("clinical");
      } else {
        info.setDocNeeded("no-doc");
      }

      // Extract questionnaire URLs from sub-actions with resource references
      // Sub-actions only appear if their CQL applicability condition evaluated to true
      if (action.hasAction()) {
        for (RequestGroup.RequestGroupActionComponent subAction : action.getAction()) {
          if (subAction.hasResource() && subAction.getResource().hasReference()) {
            String ref = subAction.getResource().getReference();
            if (isQuestionnaireReference(ref)) {
              info.addQuestionnaireUrl(ref);
            }
          }
        }
      }

      return info;
    }

    return null;
  }

  /**
   * Checks if a reference matches the Questionnaire resource pattern.
   * Matches patterns like:
   * - Questionnaire/some-id
   * - http://example.org/fhir/Questionnaire/some-id
   * - http://example.org/fhir/Questionnaire/some-id|1.0.0
   */
  private boolean isQuestionnaireReference(String ref) {
    if (ref == null || ref.isEmpty()) {
      return false;
    }
    // Match relative reference: Questionnaire/...
    // Or absolute URL containing /Questionnaire/
    return ref.startsWith("Questionnaire/") || ref.contains("/Questionnaire/");
  }

  /**
   * Builds a system action to update the order with the coverage-information
   * extension.
   */
  private CdsServiceResponseSystemActionJson buildCoverageInfoSystemAction(Resource order,
      Coverage coverage, CoverageInfo coverageInfo) {

    // Create the coverage-information extension
    Extension coverageInfoExt = new Extension(COVERAGE_INFO_EXT_URL);

    // Add coverage reference
    coverageInfoExt.addExtension("coverage", new Reference(coverage.getIdElement().toUnqualifiedVersionless()));

    // Add covered status
    if (coverageInfo.getCovered() != null) {
      coverageInfoExt.addExtension("covered", new CodeType(coverageInfo.getCovered()));
    }

    // Add pa-needed
    if (coverageInfo.getPaNeeded() != null) {
      coverageInfoExt.addExtension("pa-needed", new CodeType(coverageInfo.getPaNeeded()));
    }

    // Add doc-needed
    if (coverageInfo.getDocNeeded() != null) {
      coverageInfoExt.addExtension("doc-needed", new CodeType(coverageInfo.getDocNeeded()));
    }

    // Add questionnaires (can have multiple)
    for (String questionnaireUrl : coverageInfo.getQuestionnaireUrls()) {
      coverageInfoExt.addExtension("questionnaire", new CanonicalType(questionnaireUrl));
    }

    // Add date
    coverageInfoExt.addExtension("date", new DateType(LocalDate.now().toString()));

    // Add coverage-assertion-id
    coverageInfoExt.addExtension("coverage-assertion-id", new StringType(coverageInfo.getAssertionId()));

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
  private void addDtrLaunchLinks(List<CdsServiceResponseCardJson> cards, CoverageInfo coverageInfo,
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
   */
  private List<CdsServiceResponseCardJson> convertToCards(RequestGroup requestGroup, PlanDefinition planDef) {
    if (requestGroup == null) {
      throw new InvalidRequestException("No RequestGroup found in PlanDefinition execution result");
    }

    List<CdsServiceResponseCardJson> cards = new ArrayList<>();

    for (RequestGroup.RequestGroupActionComponent action : requestGroup.getAction()) {
      if (action == null) {
        continue;
      }

      CdsServiceResponseCardJson card = new CdsServiceResponseCardJson();
      card.setSummary(action.getTitle());
      card.setDetail(action.getDescription());
      card.setIndicator(CdsServiceIndicatorEnum.INFO);

      CdsServiceResponseCardSourceJson source = new CdsServiceResponseCardSourceJson();
      source.setLabel("Da Vinci CRD");
      source.setUrl(planDef.getUrl());
      card.setSource(source);

      cards.add(card);
    }

    return cards;
  }

}
