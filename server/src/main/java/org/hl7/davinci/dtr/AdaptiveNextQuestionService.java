package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus;
import org.hl7.fhir.r4.model.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

/**
 * Implements the $next-question operation for adaptive questionnaires.
 *
 * The source questionnaire canonical is extracted from the contained Questionnaire's
 * derivedFrom element and resolved from the FHIR repository at call time.
 *
 * Uses a greedy multi-group batch delivery algorithm that walks the source
 * questionnaire's top-level groups and delivers as many as possible per call.
 */
@Service
public class AdaptiveNextQuestionService {

  private static final Logger logger = LoggerFactory.getLogger(AdaptiveNextQuestionService.class);

  private static final String NEXT_QUESTION_OUTPUT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-next-question-output-parameters";

  private static final String OUTPUT_PARAMETER_NAME = "questionnaire-response";

  private final DaoRegistry daoRegistry;
  private final DtrSubQuestionnaireAssembler subQuestionnaireAssembler;
  private final EnableWhenEvaluator enableWhenEvaluator;

  public AdaptiveNextQuestionService(
      DaoRegistry daoRegistry,
      DtrSubQuestionnaireAssembler subQuestionnaireAssembler,
      EnableWhenEvaluator enableWhenEvaluator) {
    this.daoRegistry = daoRegistry;
    this.subQuestionnaireAssembler = subQuestionnaireAssembler;
    this.enableWhenEvaluator = enableWhenEvaluator;
  }

  /**
   * Process a $next-question request.
   *
   * @param qr the incoming QuestionnaireResponse with current answers and contained Questionnaire
   * @return Parameters containing the updated QuestionnaireResponse
   */
  public Parameters processNextQuestion(QuestionnaireResponse qr) {
    validateInput(qr);

    // Extract contained Questionnaire from incoming QR
    Questionnaire containedQ = extractContainedQuestionnaire(qr);

    // Extract source questionnaire canonical from contained Q's derivedFrom
    String sourceCanonical = extractSourceCanonical(containedQ);

    // Resolve source questionnaire from FHIR repository
    Questionnaire sourceQ = DtrFhirUtil.resolveByCanonical(
        daoRegistry, Questionnaire.class, sourceCanonical);
    if (sourceQ == null) {
      throw new InternalErrorException(
          "Source questionnaire not found: " + sourceCanonical);
    }

    // Sub-questionnaire assembly on a copy
    Questionnaire assembled = sourceQ.copy();
    subQuestionnaireAssembler.assemble(assembled);

    // Build delivered linkIds from contained Q's top-level items
    Set<String> deliveredLinkIds = new HashSet<>();
    for (QuestionnaireItemComponent item : containedQ.getItem()) {
      deliveredLinkIds.add(item.getLinkId());
    }

    // Build answer index from QR answers
    Map<String, List<Type>> answerIndex = enableWhenEvaluator.buildAnswerIndex(qr);
    Set<String> answeredLinkIds = answerIndex.keySet();

    // Greedy batch collection
    List<QuestionnaireItemComponent> batchItems = new ArrayList<>();
    for (QuestionnaireItemComponent group : assembled.getItem()) {
      if (deliveredLinkIds.contains(group.getLinkId())) {
        continue; // Already delivered
      }

      if (group.hasEnableWhen()) {
        if (enableWhenEvaluator.canEvaluate(group, answeredLinkIds)) {
          if (enableWhenEvaluator.isEnabled(group, answerIndex)) {
            batchItems.add(group.copy());
          }
          // If disabled, skip this group and continue to next
        } else {
          break; // Can't evaluate yet, depends on undelivered/unanswered questions
        }
      } else {
        batchItems.add(group.copy());
      }
    }

    // Append batch items to contained Questionnaire
    for (QuestionnaireItemComponent batchItem : batchItems) {
      containedQ.addItem(batchItem);
    }

    String qrId = qr.hasIdElement() ? qr.getIdElement().getIdPart() : "(anonymous)";
    logger.info("$next-question: qr={}, delivered {} new groups (linkIds: {})",
        qrId, batchItems.size(),
        batchItems.stream().map(QuestionnaireItemComponent::getLinkId).toList());

    // Check for completion
    if (batchItems.isEmpty()) {
      Set<String> allDelivered = new HashSet<>(deliveredLinkIds);
      boolean allGroupsHandled = assembled.getItem().stream()
          .allMatch(g -> allDelivered.contains(g.getLinkId())
              || (g.hasEnableWhen()
                  && enableWhenEvaluator.canEvaluate(g, answeredLinkIds)
                  && !enableWhenEvaluator.isEnabled(g, answerIndex)));

      if (allGroupsHandled) {
        qr.setStatus(QuestionnaireResponseStatus.COMPLETED);
        logger.info("$next-question: qr={} completed", qrId);
      }
    }

    // Wrap in output Parameters
    return buildOutputParameters(qr);
  }

  private void validateInput(QuestionnaireResponse qr) {
    if (qr == null) {
      throw new InvalidRequestException("QuestionnaireResponse is required");
    }
  }

  private String extractSourceCanonical(Questionnaire containedQ) {
    if (containedQ.hasDerivedFrom()) {
      for (CanonicalType canonical : containedQ.getDerivedFrom()) {
        if (canonical.hasValue()) {
          return canonical.getValue();
        }
      }
    }
    throw new InvalidRequestException(
        "Contained Questionnaire must have a derivedFrom element identifying the source questionnaire");
  }

  private Questionnaire extractContainedQuestionnaire(QuestionnaireResponse qr) {
    return qr.getContained().stream()
        .filter(Questionnaire.class::isInstance)
        .map(Questionnaire.class::cast)
        .findFirst()
        .orElseThrow(() -> new InvalidRequestException(
            "QuestionnaireResponse must contain a Questionnaire resource"));
  }

  private Parameters buildOutputParameters(QuestionnaireResponse qr) {
    Parameters params = new Parameters();
    params.getMeta().addProfile(NEXT_QUESTION_OUTPUT_PROFILE);
    params.addParameter().setName(OUTPUT_PARAMETER_NAME).setResource(qr);
    return params;
  }
}
