package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.PlanDefinition.PlanDefinitionActionComponent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedArtifact;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.VisionPrescription;
import org.hl7.fhir.r4.model.TriggerDefinition.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceIndicatorEnum;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardSourceJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCodingJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseLinkJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;

import static org.hl7.davinci.common.FhirConstants.CARD_TYPE_SYSTEM;
import static org.hl7.davinci.common.CrdConstants.COVERAGE_INFO_EXT;

/**
 * Converts PlanDefinition $apply results (RequestGroups) into CDS Hooks
 * response cards.
 * Also handles card and service action deduplication.
 */
@Component
public class CardConverter {

  private static final Logger logger = LoggerFactory.getLogger(CardConverter.class);

  /**
   * Converts RequestGroup actions to CDS Hooks response cards.
   * Each card is assigned a UUID and linked to the associated resource.
   * Only includes actions whose trigger matches the current hook.
   */
  public List<CdsServiceResponseCardJson> convertToCards(RequestGroup requestGroup, PlanDefinition planDef,
      Resource resource, ResolvedResources context, String hookName) {
    List<CdsServiceResponseCardJson> cards = new ArrayList<>();

    // CDS Hooks spec: return empty array when no RequestGroup
    if (requestGroup == null) {
      logger.info("No RequestGroup found - returning empty cards array");
      return cards;
    }

    List<RequestGroup.RequestGroupActionComponent> actions = requestGroup.getAction();
    if (actions == null || actions.isEmpty()) {
      logger.info("No actions found - returning empty cards array");
      return cards;
    }

    for (RequestGroup.RequestGroupActionComponent action : actions) {

      // Skip null actions and actions without a title (summary is REQUIRED per CDS
      // Hooks spec)
      if (action == null || !action.hasTitle()) {
        logger.info("Skipping null or untitled action - summary is required for valid cards");
        continue;
      }

      PlanDefinitionActionComponent planAction = null;
      String actionId = action.getId();
      if (actionId != null) {
        planAction = findPlanDefinitionAction(planDef, actionId);
      }

      Coding topicCoding = findCardTypeCoding(action.getCode());
      if (topicCoding == null && planAction != null) {
        topicCoding = findCardTypeCoding(planAction.getCode());
      }

      if (topicCoding == null) {
        if (action.getExtensionByUrl(COVERAGE_INFO_EXT) != null) {
          logger.debug("Skipping action {} - no card type code and has coverage-info", actionId);
        } else {
          logger.debug("Skipping action {} - no card type code", actionId);
        }
        continue;
      }

      if ("coverage-info".equals(topicCoding.getCode())) {
        logger.debug("Skipping action {} - coverage-info is system-action only", actionId);
        continue;
      }

      // Filter by trigger - only include actions whose trigger matches the current
      // hook
      if (actionId != null) {
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
      Extension coverageExt = action.getExtensionByUrl(COVERAGE_INFO_EXT);
      if (coverageExt != null) {
        Extension coveredExt = coverageExt.getExtensionByUrl("covered");
        if (coveredExt != null && "not-covered".equals(coveredExt.getValue().primitiveValue())) {
          card.setIndicator(CdsServiceIndicatorEnum.WARNING);
        }
      }

      CdsServiceResponseCardSourceJson source = new CdsServiceResponseCardSourceJson();
      source.setLabel(resolvePayerLabel(context, planDef));
      source.setUrl(planDef.getUrl());

      String topicSystem = topicCoding.hasSystem() ? topicCoding.getSystem() : CARD_TYPE_SYSTEM;
      source.setTopic(new CdsServiceResponseCodingJson()
          .setSystem(topicSystem)
          .setCode(topicCoding.getCode()));
      card.setSource(source);

      // Map links from action.documentation (RelatedArtifact)
      List<CdsServiceResponseLinkJson> links = new ArrayList<>();
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
      String resourceRef = ResourceResolver.toRelativeReference(resource);
      if (resourceRef != null) {
        CrdCardExtension extension = new CrdCardExtension();
        extension.addAssociatedResource(resourceRef);
        card.setExtension(extension);
      }

      cards.add(card);
    }

    return cards;
  }

  /**
   * Consolidates duplicate cards by merging their associated-resource extensions.
   * Two cards are considered duplicates if they have the same summary, detail,
   * indicator, and source URL.
   */
  public List<CdsServiceResponseCardJson> consolidateDuplicateCards(List<CdsServiceResponseCardJson> cards) {
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
        CdsServiceResponseCardJson existingCard = consolidatedCards.get(cardKey);
        mergeAssociatedResources(existingCard, card);
      } else {
        consolidatedCards.put(cardKey, card);
      }
    }

    logger.info("Consolidated {} cards into {} unique cards", cards.size(), consolidatedCards.size());
    return new ArrayList<>(consolidatedCards.values());
  }

  /**
   * Consolidates duplicate service actions by resource ID and coverage reference.
   */
  public List<CdsServiceResponseSystemActionJson> consolidateDuplicateServiceActions(
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

  // --- Private helpers ---

  PlanDefinitionActionComponent findPlanDefinitionAction(PlanDefinition planDef, String actionId) {
    if (planDef == null || !planDef.hasAction() || actionId == null) {
      return null;
    }
    return planDef.getAction().stream()
        .filter(a -> actionId.equals(a.getId()))
        .findFirst()
        .orElse(null);
  }

  private Coding findCardTypeCoding(List<CodeableConcept> codes) {
    if (codes == null || codes.isEmpty()) {
      return null;
    }

    for (CodeableConcept concept : codes) {
      if (concept == null || !concept.hasCoding()) {
        continue;
      }
      for (Coding coding : concept.getCoding()) {
        if (coding == null || !coding.hasCode()) {
          continue;
        }
        if (coding.hasSystem() && CARD_TYPE_SYSTEM.equals(coding.getSystem())) {
          return coding;
        }
      }
    }

    return null;
  }

  private String resolvePayerLabel(ResolvedResources context, PlanDefinition planDef) {
    if (context != null && context.getCoverage() != null) {
      Coverage coverage = context.getCoverage();
      for (Organization org : ResourceResolver.findPayorOrganizations(coverage, context.getOrganizations())) {
        if (org.hasName()) {
          return org.getName();
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
      display = FhirCodeExtractor.codeableConceptDisplay(serviceRequest.getCode());
    } else if (resource instanceof DeviceRequest deviceRequest) {
      display = FhirCodeExtractor.codeableConceptDisplay(deviceRequest.getCodeCodeableConcept());
    } else if (resource instanceof MedicationRequest medicationRequest) {
      display = FhirCodeExtractor.codeableConceptDisplay(medicationRequest.getMedicationCodeableConcept());
    } else if (resource instanceof CommunicationRequest communicationRequest) {
      if (communicationRequest.hasCategory()) {
        display = FhirCodeExtractor.codeableConceptDisplay(communicationRequest.getCategoryFirstRep());
      }
      if ((display == null || display.isBlank()) && communicationRequest.hasReasonCode()) {
        display = FhirCodeExtractor.codeableConceptDisplay(communicationRequest.getReasonCodeFirstRep());
      }
    } else if (resource instanceof NutritionOrder nutritionOrder) {
      if (nutritionOrder.hasOralDiet() && nutritionOrder.getOralDiet().hasType()) {
        display = FhirCodeExtractor.codeableConceptDisplay(nutritionOrder.getOralDiet().getTypeFirstRep());
      }
      if ((display == null || display.isBlank()) && nutritionOrder.hasSupplement()) {
        for (NutritionOrder.NutritionOrderSupplementComponent supplement : nutritionOrder.getSupplement()) {
          if (supplement.hasType()) {
            display = FhirCodeExtractor.codeableConceptDisplay(supplement.getType());
            break;
          }
        }
      }
      if ((display == null || display.isBlank()) && nutritionOrder.hasEnteralFormula()
          && nutritionOrder.getEnteralFormula().hasBaseFormulaType()) {
        display = FhirCodeExtractor.codeableConceptDisplay(nutritionOrder.getEnteralFormula().getBaseFormulaType());
      }
    } else if (resource instanceof VisionPrescription visionPrescription) {
      if (visionPrescription.hasLensSpecification()) {
        for (VisionPrescription.VisionPrescriptionLensSpecificationComponent spec : visionPrescription
            .getLensSpecification()) {
          if (spec.hasProduct()) {
            display = FhirCodeExtractor.codeableConceptDisplay(spec.getProduct());
            break;
          }
        }
      }
    } else if (resource instanceof Appointment appointment) {
      if (appointment.hasServiceType()) {
        display = FhirCodeExtractor.codeableConceptDisplay(appointment.getServiceTypeFirstRep());
      }
      if ((display == null || display.isBlank()) && appointment.hasReasonCode()) {
        display = FhirCodeExtractor.codeableConceptDisplay(appointment.getReasonCodeFirstRep());
      }
    }

    if (display != null && !display.isBlank()) {
      return display;
    }

    String relRef = ResourceResolver.toRelativeReference(resource);
    if (relRef != null) {
      return relRef;
    }

    return resource.fhirType();
  }

  private String generateCardKey(CdsServiceResponseCardJson card) {
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
    key.append("|");

    if (card.getSource() != null && card.getSource().getTopic() != null) {
      if (card.getSource().getTopic().getSystem() != null) {
        key.append(card.getSource().getTopic().getSystem());
      }
      key.append("|");
      if (card.getSource().getTopic().getCode() != null) {
        key.append(card.getSource().getTopic().getCode());
      }
    }
    key.append("|");

    if (card.getExtension() != null) {
      key.append(card.getExtension().getClass().getName());
    }

    return key.toString();
  }

  private void mergeAssociatedResources(CdsServiceResponseCardJson targetCard,
      CdsServiceResponseCardJson sourceCard) {
    if (targetCard.getExtension() != null && !(targetCard.getExtension() instanceof CrdCardExtension)) {
      return;
    }

    CrdCardExtension targetExtension = (CrdCardExtension) targetCard.getExtension();
    if (targetExtension == null) {
      targetExtension = new CrdCardExtension();
      targetCard.setExtension(targetExtension);
    }

    if (sourceCard.getExtension() != null && !(sourceCard.getExtension() instanceof CrdCardExtension)) {
      return;
    }

    CrdCardExtension sourceExtension = (CrdCardExtension) sourceCard.getExtension();
    if (sourceExtension == null || sourceExtension.getAssociatedResources().isEmpty()) {
      return;
    }

    List<String> targetResources = targetExtension.getAssociatedResources();
    List<String> sourceResources = sourceExtension.getAssociatedResources();

    for (String resource : sourceResources) {
      if (!targetResources.contains(resource)) {
        targetResources.add(resource);
      }
    }

    logger.info("Merged {} associated resources into card", sourceResources.size());
  }

  private String generateServiceActionKey(CdsServiceResponseSystemActionJson action) {
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
      Extension coverageInfoExt = dr.getExtensionByUrl(COVERAGE_INFO_EXT);
      if (coverageInfoExt != null) {
        Extension coverageRefExt = coverageInfoExt.getExtensionByUrl("coverage");
        if (coverageRefExt != null && coverageRefExt.getValue() instanceof Reference ref) {
          key.append(ref.getReference());
        }
      }
    }

    return key.toString();
  }
}
