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

import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.fhir.instance.model.api.IBaseBackboneElement;
import org.hl7.fhir.instance.model.api.IBaseDatatype;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.PlanDefinition.PlanDefinitionActionComponent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedArtifact;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.VisionPrescription;
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
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestContextJson;
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

/**
 * Abstract base class for CDS Hook services.
 */
public abstract class CdsServiceBase {

  protected static final String COVERAGE_INFO_EXT_URL = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
  protected static final String CRD_SERVICE_EXTENSION = """
      {
        "davinci-crd.version":["2.2"],
        "davinci-crd.configuration-options":[
          {"code":"coverage-info","type":"boolean","name":"Coverage Information","description":"Return coverage-information system actions and related cards.","default":true},
          {"code":"max-cards","type":"integer","name":"Maximum cards","description":"Maximum number of cards to return.","default":10}
        ]
      }
      """;

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
   * Validates that the raw request context has required fields with correct types.
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
  protected abstract void validateExtractedResources(HookResourceContext context);

  /**
   * Selects which resources from the context should be processed by
   * PlanDefinitions.
   *
   * @return list of resources to process (orders, appointments, etc.)
   */
  protected abstract List<Resource> selectContextResources(HookResourceContext context);

  /**
   * Final method that orchestrates all response processing.
   * Calls operations in order: hook-specific customization, client requested
   * configuration, CRD conformance enforcement.
   *
   * @param response the CDS response to finalize
   * @param request  the original CDS request
   */
  protected final void finalizeResponse(CdsServiceResponseJson response, CdsServiceRequestJson request) {
    if (response == null) {
      return;
    }

    customizeResponseHook(response, request);
    applyClientConfiguration(response, request);
    ensureCrdConformance(response);
  }

  /**
   * Operation for subclasses to customize the response before configuration and
   * conformance processing.
   * Override this method to add custom response manipulation.
   *
   * @param response the CDS response to customize
   * @param request  the original CDS request
   */
  protected void customizeResponseHook(CdsServiceResponseJson response, CdsServiceRequestJson request) {
    // Empty - subclasses override for custom response manipulation
  }

  /**
   * Applies client configuration options from the CRD request extension.
   * Filters cards by topic and limits card count based on configuration.
   *
   * @param response the CDS response to filter
   * @param request  the original CDS request containing configuration
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
        if (action.getResource() instanceof DomainResource domainResource) {
          return domainResource.hasExtension(COVERAGE_INFO_EXT_URL);
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
   * Ensures all system action resources conform to CRD profile requirements.
   * Sets default values for required fields where possible and logs warnings for
   * missing fields.
   *
   * @param response the CDS response containing system actions to validate
   */
  protected void ensureCrdConformance(CdsServiceResponseJson response) {
    if (response.getServiceActions() == null) {
      return;
    }

    Date now = new Date();
    for (CdsServiceResponseSystemActionJson action : response.getServiceActions()) {
      if (action == null || action.getResource() == null) {
        continue;
      }

      IBaseResource resource = action.getResource();

      if (resource instanceof ServiceRequest sr) {
        ensureServiceRequestConformance(sr, now);
      } else if (resource instanceof DeviceRequest dr) {
        ensureDeviceRequestConformance(dr, now);
      } else if (resource instanceof MedicationRequest mr) {
        ensureMedicationRequestConformance(mr, now);
      } else if (resource instanceof NutritionOrder no) {
        ensureNutritionOrderConformance(no, now);
      } else if (resource instanceof CommunicationRequest cr) {
        ensureCommunicationRequestConformance(cr, now);
      } else if (resource instanceof VisionPrescription vp) {
        ensureVisionPrescriptionConformance(vp, now);
      }
    }
  }

  /**
   * Ensures a ServiceRequest conforms to CRD profile requirements.
   * Sets defaults for status, intent, and authoredOn. Logs warnings for other
   * required fields.
   */
  protected void ensureServiceRequestConformance(ServiceRequest sr, Date defaultDate) {
    if (!sr.hasStatus()) {
      sr.setStatus(ServiceRequest.ServiceRequestStatus.DRAFT);
    }
    if (!sr.hasIntent()) {
      sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
    }
    if (!sr.hasAuthoredOn()) {
      sr.setAuthoredOn(defaultDate);
    }
    logMissingRequiredFields(sr, "ServiceRequest", "code", "subject", "requester");
  }

  /**
   * Ensures a DeviceRequest conforms to CRD profile requirements.
   * Sets defaults for status, intent, and authoredOn. Logs warnings for other
   * required fields.
   */
  protected void ensureDeviceRequestConformance(DeviceRequest dr, Date defaultDate) {
    if (!dr.hasStatus()) {
      dr.setStatus(DeviceRequest.DeviceRequestStatus.DRAFT);
    }
    if (!dr.hasIntent()) {
      dr.setIntent(DeviceRequest.RequestIntent.ORDER);
    }
    if (!dr.hasAuthoredOn()) {
      dr.setAuthoredOn(defaultDate);
    }
    logMissingRequiredFields(dr, "DeviceRequest", "code", "subject", "requester");
  }

  /**
   * Ensures a MedicationRequest conforms to CRD profile requirements.
   * Sets defaults for status, intent, and authoredOn. Logs warnings for other
   * required fields.
   */
  protected void ensureMedicationRequestConformance(MedicationRequest mr, Date defaultDate) {
    if (!mr.hasStatus()) {
      mr.setStatus(MedicationRequest.MedicationRequestStatus.DRAFT);
    }
    if (!mr.hasIntent()) {
      mr.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
    }
    if (!mr.hasAuthoredOn()) {
      mr.setAuthoredOn(defaultDate);
    }
    logMissingRequiredFields(mr, "MedicationRequest", "medication", "subject", "requester");
  }

  /**
   * Ensures a NutritionOrder conforms to CRD profile requirements.
   * Sets defaults for status and dateTime. Logs warnings for other required
   * fields.
   */
  protected void ensureNutritionOrderConformance(NutritionOrder no, Date defaultDate) {
    if (!no.hasStatus()) {
      no.setStatus(NutritionOrder.NutritionOrderStatus.DRAFT);
    }
    if (!no.hasIntent()) {
      no.setIntent(NutritionOrder.NutritiionOrderIntent.ORDER);
    }
    if (!no.hasDateTime()) {
      no.setDateTime(defaultDate);
    }
    logMissingRequiredFields(no, "NutritionOrder", "patient", "orderer");
  }

  /**
   * Ensures a CommunicationRequest conforms to CRD profile requirements.
   * Sets defaults for status. Logs warnings for other required fields.
   */
  protected void ensureCommunicationRequestConformance(CommunicationRequest cr, Date defaultDate) {
    if (!cr.hasStatus()) {
      cr.setStatus(CommunicationRequest.CommunicationRequestStatus.DRAFT);
    }
    if (!cr.hasAuthoredOn()) {
      cr.setAuthoredOn(defaultDate);
    }
    logMissingRequiredFields(cr, "CommunicationRequest", "subject", "requester");
  }

  /**
   * Ensures a VisionPrescription conforms to CRD profile requirements.
   * Sets defaults for status and dateWritten. Logs warnings for other required
   * fields.
   */
  protected void ensureVisionPrescriptionConformance(VisionPrescription vp, Date defaultDate) {
    if (!vp.hasStatus()) {
      vp.setStatus(VisionPrescription.VisionStatus.DRAFT);
    }
    if (!vp.hasDateWritten()) {
      vp.setDateWritten(defaultDate);
    }
    logMissingRequiredFields(vp, "VisionPrescription", "patient", "prescriber", "lensSpecification");
  }

  /**
   * Logs warnings for required fields that are missing and cannot be auto-filled.
   */
  private void logMissingRequiredFields(Resource resource, String resourceType, String... fields) {
    String resourceId = resource.hasIdElement() ? resource.getIdElement().toUnqualifiedVersionless().getValue()
        : "unknown";

    for (String field : fields) {
      boolean missing = switch (field) {
        case "code" -> {
          if (resource instanceof ServiceRequest sr)
            yield !sr.hasCode();
          if (resource instanceof DeviceRequest dr)
            yield !dr.hasCode();
          yield false;
        }
        case "medication" -> resource instanceof MedicationRequest mr && !mr.hasMedication();
        case "subject" -> {
          if (resource instanceof ServiceRequest sr)
            yield !sr.hasSubject();
          if (resource instanceof DeviceRequest dr)
            yield !dr.hasSubject();
          if (resource instanceof MedicationRequest mr)
            yield !mr.hasSubject();
          yield false;
        }
        case "patient" -> {
          if (resource instanceof NutritionOrder no)
            yield !no.hasPatient();
          if (resource instanceof CommunicationRequest cr)
            yield !cr.hasSubject();
          if (resource instanceof VisionPrescription vp)
            yield !vp.hasPatient();
          yield false;
        }
        case "requester" -> {
          if (resource instanceof ServiceRequest sr)
            yield !sr.hasRequester();
          if (resource instanceof DeviceRequest dr)
            yield !dr.hasRequester();
          if (resource instanceof MedicationRequest mr)
            yield !mr.hasRequester();
          if (resource instanceof CommunicationRequest cr)
            yield !cr.hasRequester();
          yield false;
        }
        case "orderer" -> resource instanceof NutritionOrder no && !no.hasOrderer();
        case "prescriber" -> resource instanceof VisionPrescription vp && !vp.hasPrescriber();
        case "lensSpecification" -> resource instanceof VisionPrescription vp && !vp.hasLensSpecification();
        default -> false;
      };

      if (missing) {
        logger.warn("CRD conformance: {} {} is missing required field '{}'", resourceType, resourceId, field);
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
    HookResourceContext context = ResourceResolver.extractAllResources(request);

    // Validate required FHIR resources are present
    validateExtractedResources(context);

    // Per CRD spec: Coverage is required for all hooks - return 400 if not
    // accessible
    if (context.getCoverage() == null) {
      throw new CdsHooksException.BadRequestException(
          "No Coverage resource is accessible for this patient. A Coverage resource with a valid payer identifier is required.");
    }

    if (context.getCoverageCount() > 1) {
      throw new CdsHooksException.BadRequestException(
          "Multiple Coverage resources are accessible for this patient. CRD requires a single primary Coverage in the request.");
    }

    // Get payor identifiers for PlanDefinition matching
    List<Identifier> payorIdentifiers = extractPayorIdentifiers(context);

    // Per CRD spec: Coverage must have a valid payer identifier
    if (payorIdentifiers.isEmpty()) {
      throw new CdsHooksException.BadRequestException(
          "Coverage resource (" + context.getCoverage().getId()
              + ") lacks valid payer identifier. Coverage.payor must reference an Organization with a valid identifier. Coverage.payor value: "
              + context.getCoverage().getPayor().stream().map(Reference::getReference).toList());
    }

    // Per CRD spec: Payer must be handled by this server endpoint
    if (!isPayorHandled(payorIdentifiers)) {
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
      List<CdsServiceResponseCardJson> consolidatedCards = consolidateDuplicateCards(response.getCards());
      response.getCards().clear();
      consolidatedCards.forEach(response::addCard);
    }

    // Consolidate duplicate service actions
    if (response.getServiceActions() != null && !response.getServiceActions().isEmpty()) {
      List<CdsServiceResponseSystemActionJson> consolidated = consolidateDuplicateServiceActions(
          response.getServiceActions());
      response.getServiceActions().clear();
      consolidated.forEach(response::addServiceAction);
    }

    // Per CRD IG: Primary hooks (order-sign, order-dispatch, appointment-book)
    // SHALL return coverage-information system action even if no PlanDefinition
    // matched
    if (isPrimaryHook() && !hasCoverageInfoSystemAction(response)) {
      logger.info("No coverage-information generated by rules. Adding default coverage-info for primary hook.");
      addDefaultCoverageInfo(response, context, resourcesToProcess);
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
  protected List<Identifier> extractPayorIdentifiers(HookResourceContext context) {
    List<Identifier> payorIdentifiers = new ArrayList<>();

    Coverage coverage = context.getCoverage();
    if (coverage == null) {
      logger.warn("No Coverage in context");
      return payorIdentifiers;
    }

    for (Reference payorRef : coverage.getPayor()) {
      String payorRefStr = payorRef.getReference();
      if (payorRefStr == null) {
        continue;
      }
      // Organizations should already be resolved in context
      for (Organization org : context.getOrganizations()) {
        if (!org.hasIdElement()) {
          continue;
        }
        String idPart = org.getIdElement().getIdPart();
        if (idPart == null) {
          continue;
        }
        String orgRef = "Organization/" + idPart;
        if (ResourceResolver.referencesMatch(payorRefStr, orgRef)) {
          for (Identifier identifier : org.getIdentifier()) {
            if (identifier.hasSystem() && identifier.hasValue()) {
              payorIdentifiers.add(identifier);
            }
          }
        }
      }
    }

    return payorIdentifiers;
  }

  /**
   * Processes a single context resource by finding and executing applicable
   * PlanDefinitions.
   */
  protected void processContextResource(Resource contextResource, HookResourceContext resourceContext,
      List<Identifier> payorIdentifiers, CdsServiceRequestJson request, CdsServiceResponseJson response) {

    List<Coding> codes = extractCodes(contextResource, true, request);

    logger.info("Processing resource {} with codes: {}", contextResource.getIdElement().toUnqualifiedVersionless(),
        codes.stream()
            .map(code -> code.getSystem() + "|" + code.getCode())
            .toList());

    // Collect all matching PlanDefinitions and deduplicate by ID to avoid
    // executing the same plan multiple times when multiple codes match
    Map<String, PlanDefinition> uniquePlans = new LinkedHashMap<>();
    for (Coding code : codes) {
      List<PlanDefinition> plans = findPlanDefinitions(code, payorIdentifiers, getHookName());
      logger.info("Found {} PlanDefinitions for code {}|{}", plans.size(), code.getSystem(), code.getCode());
      for (PlanDefinition plan : plans) {
        String planId = plan.getIdElement().getIdPart();
        if (!uniquePlans.containsKey(planId)) {
          uniquePlans.put(planId, plan);
        }
      }
    }

    logger.info("Found {} unique PlanDefinitions for resource", uniquePlans.size());

    for (PlanDefinition plan : uniquePlans.values()) {
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

  /**
   * Extracts codes from context resources (orders, appointments, etc.).
   * Normalizes code systems to http:// for consistent matching.
   */
  protected List<Coding> extractCodes(Resource resource, boolean normalizeSystem, CdsServiceRequestJson request) {
    List<Coding> codes = new ArrayList<>();

    if (resource instanceof DeviceRequest deviceRequest) {
      if (deviceRequest.hasCodeCodeableConcept()) {
        codes.addAll(deviceRequest.getCodeCodeableConcept().getCoding());
      }
    } else if (resource instanceof CommunicationRequest communicationRequest) {
      if (communicationRequest.hasCategory()) {
        communicationRequest.getCategory().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (communicationRequest.hasReasonCode()) {
        communicationRequest.getReasonCode().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (communicationRequest.hasPayload()) {
        communicationRequest.getPayload().forEach(payload -> {
          if (payload.hasContent() && payload.getContent() instanceof CodeableConcept content) {
            codes.addAll(content.getCoding());
          }
        });
      }
    } else if (resource instanceof MedicationRequest medRequest) {
      if (medRequest.hasMedicationCodeableConcept()) {
        codes.addAll(medRequest.getMedicationCodeableConcept().getCoding());
      } else if (medRequest.hasMedicationReference()) {
        Medication medication = ResourceResolver.resolveReference(medRequest.getMedicationReference(), Medication.class,
            medRequest, request);
        if (medication != null && medication.hasCode()) {
          codes.addAll(medication.getCode().getCoding());
        }
      }
    } else if (resource instanceof NutritionOrder nutritionOrder) {
      if (nutritionOrder.hasOralDiet() && nutritionOrder.getOralDiet().hasType()) {
        nutritionOrder.getOralDiet().getType().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (nutritionOrder.hasSupplement()) {
        nutritionOrder.getSupplement().forEach(supplement -> {
          if (supplement.hasType()) {
            codes.addAll(supplement.getType().getCoding());
          }
        });
      }
      if (nutritionOrder.hasEnteralFormula()) {
        if (nutritionOrder.getEnteralFormula().hasBaseFormulaType()) {
          codes.addAll(nutritionOrder.getEnteralFormula().getBaseFormulaType().getCoding());
        }
        if (nutritionOrder.getEnteralFormula().hasAdditiveType()) {
          codes.addAll(nutritionOrder.getEnteralFormula().getAdditiveType().getCoding());
        }
      }
    } else if (resource instanceof ServiceRequest serviceRequest) {
      if (serviceRequest.hasCode()) {
        codes.addAll(serviceRequest.getCode().getCoding());
      }
    } else if (resource instanceof VisionPrescription visionPrescription) {
      if (visionPrescription.hasLensSpecification()) {
        visionPrescription.getLensSpecification().forEach(spec -> {
          if (spec.hasProduct()) {
            codes.addAll(spec.getProduct().getCoding());
          }
        });
      }
    } else if (resource instanceof Appointment appointment) {
      if (appointment.hasServiceType()) {
        appointment.getServiceType().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (appointment.hasReasonCode()) {
        appointment.getReasonCode().forEach(cc -> codes.addAll(cc.getCoding()));
      }
    } else if (resource instanceof Encounter encounter) {
      if (encounter.hasClass_()) {
        codes.add(encounter.getClass_());
      }
      if (encounter.hasType()) {
        encounter.getType().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (encounter.hasServiceType()) {
        codes.addAll(encounter.getServiceType().getCoding());
      }
      if (encounter.hasReasonCode()) {
        encounter.getReasonCode().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (encounter.hasHospitalization() && encounter.getHospitalization().hasDischargeDisposition()) {
        codes.addAll(encounter.getHospitalization().getDischargeDisposition().getCoding());
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

    // logger.info("Search parameter map: {}",
    // searchParams.toNormalizedQueryString(FhirContext.forR4Cached()));

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

    logger.info("Found {} PlanDefinitions for codes", plans.size());
    return plans;
  }

  /**
   * Checks if any PlanDefinition exists for the given payor identifiers.
   */
  protected boolean isPayorHandled(List<Identifier> payorIdentifiers) {
    SearchParameterMap searchParams = new SearchParameterMap();
    searchParams.setCount(1);

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

    IBundleProvider result = daoRegistry
        .getResourceDao(PlanDefinition.class)
        .search(searchParams, new SystemRequestDetails());

    return !result.isEmpty();
  }

  /**
   * Executes a PlanDefinition and returns response with cards and system actions.
   */
  protected CdsServiceResponseJson executePlanDefinition(PlanDefinition plan, HookResourceContext context,
      Resource contextResource, CdsServiceRequestJson request) {

    CdsServiceResponseJson planResponse = new CdsServiceResponseJson();

    PlanDefinitionProcessor processor = planDefinitionProcessorFactory.create(new SystemRequestDetails());

    Bundle dataBundle = buildDataBundle(context, contextResource);

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
    List<CdsServiceResponseCardJson> cards = convertToCards(requestGroup, plan, contextResource, context,
        getHookName());
    cards.forEach(card -> planResponse.addCard(card));

    Extension coverageInfoExt = extractCoverageExtension(requestGroup, context.getCoverage());

    if (coverageInfoExt != null) {
      CdsServiceResponseSystemActionJson systemAction = buildCoverageInfoSystemAction(contextResource, coverageInfoExt);
      if (systemAction != null) {
        planResponse.addServiceAction(systemAction);
      }
    }

    return planResponse;
  }

  protected Bundle buildDataBundle(HookResourceContext context, Resource contextResource) {
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
    addResourcesToBundle(bundle, seenIds, context.getTasks());
    addResourcesToBundle(bundle, seenIds, context.getConditions());
    addResourcesToBundle(bundle, seenIds, context.getProcedures());
    addResourceToBundle(bundle, seenIds, contextResource);

    return bundle;
  }

  protected void addResourceToBundle(Bundle bundle, Set<String> seenIds, Resource resource) {
    if (resource == null) {
      return;
    }
    String id = resource.hasIdElement() ? resource.getIdElement().toUnqualifiedVersionless().getValue() : null;
    if (id == null || seenIds.add(id)) {
      bundle.addEntry().setResource(resource);
    }
  }

  protected void addResourcesToBundle(Bundle bundle, Set<String> seenIds, List<? extends Resource> resources) {
    if (resources == null) {
      return;
    }
    for (Resource resource : resources) {
      addResourceToBundle(bundle, seenIds, resource);
    }
  }

  /**
   * Extracts the RequestGroup from the $apply result.
   */
  protected RequestGroup extractRequestGroup(IBaseResource resource) {

    // R4 $apply returns a CarePlan with contained RequestGroup
    if (resource instanceof CarePlan carePlan) {
      if (!carePlan.hasActivity() || !carePlan.hasContained()) {
        return null;
      }
      return carePlan.getActivity().stream()
          .filter(CarePlan.CarePlanActivityComponent::hasReference)
          .map(activity -> activity.getReference().getReference())
          .filter(ref -> ref != null && ref.startsWith("#"))
          .map(ref -> {
            String id = ref.substring(1);
            return carePlan.getContained().stream()
                .filter(r -> r.hasIdElement() && id.equals(r.getIdElement().getIdPart()))
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
      Parameters.ParametersParameterComponent returnParam = params.getParameter("return");
      if (returnParam == null) {
        return null;
      }
      Resource returnResource = (Resource) returnParam.getResource();
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
   * Removes stale CRD artifacts from a resource for a specific coverage.
   * Only removes coverage-information extensions that reference the same
   * coverage, preserving extensions for other coverages (e.g., secondary
   * insurance). Also removes CRD-generated notes that would otherwise accumulate.
   *
   * @param resource    the resource to clean up
   * @param coverageRef the coverage reference to match (only extensions for this
   *                    coverage are removed)
   */
  protected void cleanupForResponse(Resource resource, String coverageRef) {
    if (resource instanceof DeviceRequest dr) {
      dr.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (resource instanceof MedicationRequest mr) {
      mr.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (resource instanceof ServiceRequest sr) {
      sr.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (resource instanceof Appointment appt) {
      appt.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (resource instanceof Encounter enc) {
      enc.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
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
    return ResourceResolver.referencesMatch(coverageRef, extCoverageRef);
  }

  /**
   * Builds a system action to update the resource with the coverage-information
   * extension. Removes any existing coverage-information extensions from the
   * resource before adding the new one.
   *
   * @param resource        the original resource
   * @param coverageInfoExt the coverage-information extension from CQL (with
   *                        server fields added)
   * @return the system action for the CDS response
   */
  protected CdsServiceResponseSystemActionJson buildCoverageInfoSystemAction(Resource resource,
      Extension coverageInfoExt) {

    if (resource == null || coverageInfoExt == null) {
      return null;
    }

    Extension existingCoverage = findMatchingCoverageExtension(resource, coverageInfoExt);
    if (existingCoverage != null) {
      Extension normalizedExisting = normalizeCoverageExtension(existingCoverage);
      Extension normalizedNew = normalizeCoverageExtension(coverageInfoExt);
      if (normalizedExisting.equalsDeep(normalizedNew)) {
        return null;
      }
    }

    // Clone the resource and clean up stale CRD artifacts before adding new ones
    Resource updatedResource = resource.copy();

    // Extract coverage reference from the new extension to match against old ones
    String coverageRef = null;
    Extension coverageExtInNew = coverageInfoExt.getExtensionByUrl("coverage");
    if (coverageExtInNew != null && coverageExtInNew.getValue() instanceof Reference ref) {
      coverageRef = ref.getReference();
    }
    cleanupForResponse(updatedResource, coverageRef);

    // Add the new coverage-information extension
    if (updatedResource instanceof DeviceRequest dr) {
      dr.addExtension(coverageInfoExt);
    } else if (updatedResource instanceof MedicationRequest mr) {
      mr.addExtension(coverageInfoExt);
    } else if (updatedResource instanceof ServiceRequest sr) {
      sr.addExtension(coverageInfoExt);
    } else if (updatedResource instanceof Appointment appt) {
      appt.addExtension(coverageInfoExt);
    } else if (updatedResource instanceof Encounter enc) {
      enc.addExtension(coverageInfoExt);
    }

    // Build the system action
    CdsServiceResponseSystemActionJson systemAction = new CdsServiceResponseSystemActionJson();
    systemAction.setType("update");
    systemAction.setDescription("Add coverage information to " + resource.fhirType());
    systemAction.setResource(updatedResource);

    return systemAction;
  }

  private Extension findMatchingCoverageExtension(Resource resource, Extension coverageInfoExt) {
    if (!(resource instanceof DomainResource domainResource)) {
      return null;
    }

    String coverageRef = null;
    Extension coverageExt = coverageInfoExt.getExtensionByUrl("coverage");
    if (coverageExt != null && coverageExt.getValue() instanceof Reference ref) {
      coverageRef = ref.getReference();
    }

    for (Extension ext : domainResource.getExtension()) {
      if (!COVERAGE_INFO_EXT_URL.equals(ext.getUrl())) {
        continue;
      }
      if (coverageRef == null || isSameCoverageExtension(ext, coverageRef)) {
        return ext;
      }
    }

    return null;
  }

  private Extension normalizeCoverageExtension(Extension extension) {
    Extension normalized = extension.copy();
    normalized.getExtension().removeIf(ext -> "coverage-assertion-id".equals(ext.getUrl())
        || "date".equals(ext.getUrl()));
    return normalized;
  }

  /**
   * Converts RequestGroup actions to CDS Hooks response cards.
   * Each card is assigned a UUID and linked to the associated resource.
   * Only includes actions whose trigger matches the current hook.
   *
   * @param requestGroup the RequestGroup from PlanDefinition $apply
   * @param planDef      the PlanDefinition that was executed
   * @param resource     the resource this card is associated with
   * @param context      the hook resource context for payer labeling
   * @param hookName     the current hook name for trigger filtering
   */
  protected List<CdsServiceResponseCardJson> convertToCards(RequestGroup requestGroup, PlanDefinition planDef,
      Resource resource, HookResourceContext context, String hookName) {
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

      if (action.getExtensionByUrl(COVERAGE_INFO_EXT_URL) != null) {
        logger.debug("Skipping action {} - coverage information uses system actions", action.getId());
        continue;
      }

      // Filter by trigger - only include actions whose trigger matches the current
      // hook
      String actionId = action.getId();
      if (actionId != null) {
        PlanDefinition.PlanDefinitionActionComponent planAction = findPlanDefinitionAction(planDef, actionId);
        if (planAction != null && planAction.hasTrigger()) {
          boolean hasMatchingTrigger = planAction.getTrigger().stream()
              .anyMatch(t -> t.hasType() && t.getType() == TriggerType.NAMEDEVENT
                  && hookName.equals(t.getName()));
          if (!hasMatchingTrigger) {
            logger.debug("Skipping action {} - trigger doesn't match hook {}", actionId, hookName);
            continue;
          }
        }
      }

      CdsServiceResponseCardJson card = new CdsServiceResponseCardJson();

      // Set UUID for card tracking
      card.setUuid(UUID.randomUUID().toString());

      card.setSummary(action.getTitle());

      String detail = action.getDescription();
      if (context != null && resource != null && context.getOrders().size() > 1) {
        String resourceDisplay = describeOrder(resource);
        if (resourceDisplay != null && !resourceDisplay.isBlank()) {
          String qualifier = "Applies to: " + resourceDisplay;
          if (detail == null || detail.isBlank()) {
            detail = qualifier;
          } else {
            detail = detail + "\n\n" + qualifier;
          }
        }
      }
      card.setDetail(detail);

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
      source.setLabel(resolvePayerLabel(context, planDef));
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

      // Add associated-resource extension linking card to the resource
      if (resource != null && resource.hasIdElement()) {
        CrdCardExtension extension = new CrdCardExtension();
        String idPart = ResourceResolver.normalizeId(resource.getIdElement().getIdPart());
        String resourceRef = resource.fhirType() + "/" + idPart;
        extension.addAssociatedResource(resourceRef);
        card.setExtension(extension);
      }

      cards.add(card);
    }

    return cards;
  }

  /**
   * Finds a PlanDefinition action by its ID.
   */
  protected PlanDefinitionActionComponent findPlanDefinitionAction(PlanDefinition planDef,
      String actionId) {
    if (planDef == null || !planDef.hasAction() || actionId == null) {
      return null;
    }
    return planDef.getAction().stream()
        .filter(a -> actionId.equals(a.getId()))
        .findFirst()
        .orElse(null);
  }

  private String resolvePayerLabel(HookResourceContext context, PlanDefinition planDef) {
    if (context != null && context.getCoverage() != null) {
      Coverage coverage = context.getCoverage();
      for (Reference payorRef : coverage.getPayor()) {
        if (!payorRef.hasReference()) {
          continue;
        }
        for (Organization org : context.getOrganizations()) {
          if (!org.hasIdElement()) {
            continue;
          }
          String orgRef = "Organization/" + org.getIdElement().getIdPart();
          if (ResourceResolver.referencesMatch(payorRef.getReference(), orgRef)) {
            if (org.hasName()) {
              return org.getName();
            }
          }
        }
      }
    }

    if (planDef != null && planDef.hasPublisher()) {
      return planDef.getPublisher();
    }

    return "Da Vinci CRD";
  }

  private String describeOrder(Resource resource) {
    if (resource == null) {
      return null;
    }

    String display = null;
    if (resource instanceof ServiceRequest serviceRequest) {
      display = codeableConceptDisplay(serviceRequest.getCode());
    } else if (resource instanceof DeviceRequest deviceRequest) {
      display = codeableConceptDisplay(deviceRequest.getCodeCodeableConcept());
    } else if (resource instanceof MedicationRequest medicationRequest) {
      display = codeableConceptDisplay(medicationRequest.getMedicationCodeableConcept());
    } else if (resource instanceof CommunicationRequest communicationRequest) {
      if (communicationRequest.hasCategory()) {
        display = codeableConceptDisplay(communicationRequest.getCategoryFirstRep());
      }
      if ((display == null || display.isBlank()) && communicationRequest.hasReasonCode()) {
        display = codeableConceptDisplay(communicationRequest.getReasonCodeFirstRep());
      }
    } else if (resource instanceof NutritionOrder nutritionOrder) {
      if (nutritionOrder.hasOralDiet() && nutritionOrder.getOralDiet().hasType()) {
        display = codeableConceptDisplay(nutritionOrder.getOralDiet().getTypeFirstRep());
      }
      if ((display == null || display.isBlank()) && nutritionOrder.hasSupplement()) {
        for (NutritionOrder.NutritionOrderSupplementComponent supplement : nutritionOrder.getSupplement()) {
          if (supplement.hasType()) {
            display = codeableConceptDisplay(supplement.getType());
            break;
          }
        }
      }
      if ((display == null || display.isBlank()) && nutritionOrder.hasEnteralFormula()
          && nutritionOrder.getEnteralFormula().hasBaseFormulaType()) {
        display = codeableConceptDisplay(nutritionOrder.getEnteralFormula().getBaseFormulaType());
      }
    } else if (resource instanceof VisionPrescription visionPrescription) {
      if (visionPrescription.hasLensSpecification()) {
        for (VisionPrescription.VisionPrescriptionLensSpecificationComponent spec : visionPrescription
            .getLensSpecification()) {
          if (spec.hasProduct()) {
            display = codeableConceptDisplay(spec.getProduct());
            break;
          }
        }
      }
    } else if (resource instanceof Appointment appointment) {
      if (appointment.hasServiceType()) {
        display = codeableConceptDisplay(appointment.getServiceTypeFirstRep());
      }
      if ((display == null || display.isBlank()) && appointment.hasReasonCode()) {
        display = codeableConceptDisplay(appointment.getReasonCodeFirstRep());
      }
    }

    if (display != null && !display.isBlank()) {
      return display;
    }

    if (resource.hasIdElement()) {
      String idPart = ResourceResolver.normalizeId(resource.getIdElement().getIdPart());
      if (idPart != null && !idPart.isBlank()) {
        return resource.fhirType() + "/" + idPart;
      }
    }

    return resource.fhirType();
  }

  private String codeableConceptDisplay(CodeableConcept codeableConcept) {
    if (codeableConcept == null) {
      return null;
    }
    if (codeableConcept.hasText()) {
      return codeableConcept.getText();
    }
    if (codeableConcept.hasCoding()) {
      Coding coding = codeableConcept.getCodingFirstRep();
      if (coding.hasDisplay()) {
        return coding.getDisplay();
      }
      if (coding.hasCode()) {
        return coding.getCode();
      }
    }
    return null;
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
   * Consolidates duplicate service actions by resource ID and coverage reference.
   * Two service actions are considered duplicates if they update the same
   * resource with the same coverage extension.
   */
  protected List<CdsServiceResponseSystemActionJson> consolidateDuplicateServiceActions(
      List<CdsServiceResponseSystemActionJson> actions) {
    if (actions == null || actions.isEmpty()) {
      return new ArrayList<>();
    }

    if (actions.size() == 1) {
      return new ArrayList<>(actions);
    }

    Map<String, CdsServiceResponseSystemActionJson> consolidated = new LinkedHashMap<>();

    for (CdsServiceResponseSystemActionJson action : actions) {
      if (action == null || action.getResource() == null) {
        continue;
      }

      String actionKey = generateServiceActionKey(action);
      if (!consolidated.containsKey(actionKey)) {
        consolidated.put(actionKey, action);
      }
    }

    logger.info("Consolidated {} service actions into {} unique actions", actions.size(), consolidated.size());
    return new ArrayList<>(consolidated.values());
  }

  /**
   * Generates a unique key for a service action based on resource ID and coverage
   * reference.
   */
  protected String generateServiceActionKey(CdsServiceResponseSystemActionJson action) {
    StringBuilder key = new StringBuilder();

    Resource resource = (Resource) action.getResource();
    if (resource != null) {
      key.append(resource.fhirType());
      key.append("/");
      if (resource.getIdElement() != null) {
        key.append(resource.getIdElement().getIdPart());
      }
    }
    key.append("|");

    // Extract coverage reference from the coverage-information extension
    if (resource instanceof DomainResource dr) {
      Extension coverageInfoExt = dr.getExtensionByUrl(COVERAGE_INFO_EXT_URL);
      if (coverageInfoExt != null) {
        Extension coverageRefExt = coverageInfoExt.getExtensionByUrl("coverage");
        if (coverageRefExt != null && coverageRefExt.getValue() instanceof Reference ref) {
          key.append(ref.getReference());
        }
      }
    }

    return key.toString();
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
      List<Resource> resources) {
    for (Resource resource : resources) {
      if (resource instanceof DomainResource domainResource
          && domainResource.hasExtension(COVERAGE_INFO_EXT_URL)) {
        continue;
      }
      Extension coverageInfoExt = buildDefaultCoverageExtension(context);
      if (coverageInfoExt == null) {
        continue;
      }
      CdsServiceResponseSystemActionJson systemAction = buildCoverageInfoSystemAction(resource, coverageInfoExt);
      if (systemAction != null) {
        response.addServiceAction(systemAction);
      }
    }
  }

  /**
   * Builds a default coverage-information extension based on available context.
   */
  protected Extension buildDefaultCoverageExtension(HookResourceContext context) {
    Extension coverageInfoExt = new Extension(COVERAGE_INFO_EXT_URL);
    Coverage coverage = context.getCoverage();

    if (coverage == null || !coverage.hasIdElement()) {
      logger.warn("Cannot build default coverage extension: coverage or coverage ID is missing");
      return null;
    }

    // Coverage reference
    Extension coverageRefExt = new Extension("coverage");
    String idPart = coverage.getIdElement().getIdPart();
    Reference coverageRef = new Reference("Coverage/" + (idPart != null ? idPart : "unknown"));
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
