package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Type;
import org.opencds.cqf.fhir.cr.hapi.common.IQuestionnaireProcessorFactory;
import org.opencds.cqf.fhir.cr.questionnaire.QuestionnaireProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

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
  private static final String QR_COVERAGE_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-coverage";
  private static final String QR_CONTEXT_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context";
  private static final String INFO_ORIGIN_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/information-origin";

  private final DaoRegistry daoRegistry;
  private final DtrSubQuestionnaireAssembler subQuestionnaireAssembler;
  private final EnableWhenEvaluator enableWhenEvaluator;
  private final IQuestionnaireProcessorFactory questionnaireProcessorFactory;

  public AdaptiveNextQuestionService(
      DaoRegistry daoRegistry,
      DtrSubQuestionnaireAssembler subQuestionnaireAssembler,
      EnableWhenEvaluator enableWhenEvaluator,
      IQuestionnaireProcessorFactory questionnaireProcessorFactory) {
    this.daoRegistry = daoRegistry;
    this.subQuestionnaireAssembler = subQuestionnaireAssembler;
    this.enableWhenEvaluator = enableWhenEvaluator;
    this.questionnaireProcessorFactory = questionnaireProcessorFactory;
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

    // Check for completion: all source groups must be either delivered or disabled
    Set<String> allDelivered = new HashSet<>(deliveredLinkIds);
    for (QuestionnaireItemComponent batchItem : batchItems) {
      allDelivered.add(batchItem.getLinkId());
    }

    // Opportunistic pre-population for delivered groups in adaptive flow.
    // This fills defaults (for example patient demographics) without overriding user-entered answers.
    try {
      prepopulateDeliveredItems(qr, assembled, allDelivered);
    } catch (Exception e) {
      logger.warn("$next-question: pre-population skipped due to error: {}", e.getMessage(), e);
    }

    // Keep existing completion behavior: only evaluate completion on empty delivery rounds.
    if (batchItems.isEmpty()) {
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

  private void prepopulateDeliveredItems(
      QuestionnaireResponse targetQr,
      Questionnaire assembledSource,
      Set<String> deliveredGroupLinkIds) {

    if (deliveredGroupLinkIds.isEmpty()) {
      return;
    }

    String subjectId = extractSubjectId(targetQr);
    if (subjectId == null || subjectId.isBlank()) {
      return;
    }

    Questionnaire deliveredQ = buildDeliveredQuestionnaire(assembledSource, deliveredGroupLinkIds);
    if (!deliveredQ.hasItem()) {
      return;
    }

    QuestionnaireProcessor processor = questionnaireProcessorFactory.create(new SystemRequestDetails());
    Bundle dataBundle = buildDataBundleFromQr(targetQr, subjectId);

    IBaseResource populateResult = processor.populate(
        deliveredQ, subjectId, List.of(), null, dataBundle, null);

    if (populateResult instanceof QuestionnaireResponse populatedQr) {
      mergePrepopulatedAnswers(targetQr, populatedQr);
    }
  }

  private String extractSubjectId(QuestionnaireResponse qr) {
    if (!qr.hasSubject() || !qr.getSubject().hasReference()) {
      return null;
    }

    String reference = qr.getSubject().getReference();
    if (reference == null || reference.isBlank()) {
      return null;
    }

    IdType refId = new IdType(reference);
    if (!"Patient".equals(refId.getResourceType()) || refId.getIdPart() == null) {
      return null;
    }

    String versionless = refId.toVersionless().getValue();
    if (versionless != null && !versionless.isBlank()) {
      return versionless;
    }
    return "Patient/" + refId.getIdPart();
  }

  private Questionnaire buildDeliveredQuestionnaire(
      Questionnaire assembledSource, Set<String> deliveredGroupLinkIds) {
    Questionnaire delivered = assembledSource.copy();
    delivered.setItem(new ArrayList<>());

    for (QuestionnaireItemComponent group : assembledSource.getItem()) {
      if (deliveredGroupLinkIds.contains(group.getLinkId())) {
        delivered.addItem(group.copy());
      }
    }

    return delivered;
  }

  private Bundle buildDataBundleFromQr(QuestionnaireResponse qr, String subjectId) {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    Set<String> seen = new HashSet<>();

    addSubjectPatientContext(subjectId, bundle, seen);

    for (Extension ext : qr.getExtensionsByUrl(QR_COVERAGE_EXT)) {
      addResolvedReferenceResource(ext, qr, bundle, seen);
    }
    for (Extension ext : qr.getExtensionsByUrl(QR_CONTEXT_EXT)) {
      addResolvedReferenceResource(ext, qr, bundle, seen);
    }

    return bundle;
  }

  private void addResolvedReferenceResource(
      Extension ext,
      QuestionnaireResponse qr,
      Bundle bundle,
      Set<String> seen) {

    if (!ext.hasValue() || !(ext.getValue() instanceof Reference ref)) {
      return;
    }

    Resource resolved = resolveReference(ref, qr);
    if (resolved == null) {
      return;
    }

    addResource(bundle, seen, resolved);
  }

  private void addSubjectPatientContext(String subjectId, Bundle bundle, Set<String> seen) {
    if (subjectId == null || subjectId.isBlank()) {
      return;
    }

    IdType patientRef = new IdType(subjectId);
    String patientIdPart = patientRef.getIdPart();
    if (patientIdPart == null || patientIdPart.isBlank()) {
      return;
    }

    String baseUrl = patientRef.getBaseUrl();
    boolean isAbsolute = baseUrl != null && !baseUrl.isBlank();
    if (isAbsolute || !patientExistsInRepository(patientIdPart)) {
      Patient stub = new Patient();
      // Keep absolute subject IDs to avoid resolving to same-ID local patient resources.
      stub.setId(isAbsolute ? patientRef.toVersionless().getValue() : patientIdPart);
      addResource(bundle, seen, stub);
    }
  }

  private boolean patientExistsInRepository(String patientIdPart) {
    try {
      daoRegistry.getResourceDao(Patient.class)
          .read(new IdType("Patient", patientIdPart), new SystemRequestDetails());
      return true;
    } catch (ResourceNotFoundException e) {
      return false;
    } catch (Exception e) {
      logger.debug("$next-question: unable to verify patient {} existence: {}", patientIdPart, e.getMessage());
      return false;
    }
  }

  private void addResource(Bundle bundle, Set<String> seen, Resource resource) {
    String identity = resource.getIdElement().toVersionless().getValue();
    if (identity == null || identity.isBlank()) {
      String idPart = resource.getIdElement().getIdPart();
      identity = idPart == null || idPart.isBlank() ? null : resource.fhirType() + "/" + idPart;
    }

    if (identity == null || identity.isBlank()) {
      bundle.addEntry().setResource(resource);
      return;
    }

    if (seen.add(identity)) {
      bundle.addEntry().setResource(resource);
    }
  }

  private Resource resolveReference(Reference ref, QuestionnaireResponse qr) {
    if (ref.getResource() instanceof Resource inline) {
      return inline;
    }
    if (!ref.hasReference()) {
      return null;
    }

    String reference = ref.getReference();
    if (reference == null || reference.isBlank()) {
      return null;
    }

    if (reference.startsWith("#")) {
      String id = reference.substring(1);
      return qr.getContained().stream()
          .filter(r -> id.equals(r.getIdElement().getIdPart()))
          .findFirst()
          .orElse(null);
    }

    Resource containedMatch = resolveContainedReference(reference, qr);
    if (containedMatch != null) {
      return containedMatch;
    }

    try {
      IdType refId = new IdType(reference);
      String resourceType = refId.getResourceType();
      String idPart = refId.getIdPart();
      if (resourceType == null || idPart == null) {
        return null;
      }
      return (Resource) daoRegistry.getResourceDao(resourceType)
          .read(new IdType(resourceType, idPart), new SystemRequestDetails());
    } catch (Exception e) {
      logger.debug("$next-question: unable to resolve reference {}: {}", reference, e.getMessage());
      return null;
    }
  }

  private Resource resolveContainedReference(String reference, QuestionnaireResponse qr) {
    final IdType refId;
    try {
      refId = new IdType(reference);
    } catch (Exception e) {
      return null;
    }
    String resourceType = refId.getResourceType();
    String idPart = refId.getIdPart();
    if (idPart == null || idPart.isBlank()) {
      return null;
    }

    for (Resource contained : qr.getContained()) {
      String containedIdPart = contained.getIdElement().getIdPart();
      if (containedIdPart == null || containedIdPart.isBlank()) {
        continue;
      }
      if (!idPart.equals(containedIdPart)) {
        continue;
      }
      if (resourceType == null || resourceType.isBlank() || resourceType.equals(contained.fhirType())) {
        return contained;
      }
    }

    return null;
  }

  private void mergePrepopulatedAnswers(
      QuestionnaireResponse targetQr, QuestionnaireResponse populatedQr) {

    mergeItems(targetQr.getItem(), populatedQr.getItem());
  }

  private void mergeItems(
      List<QuestionnaireResponseItemComponent> targetItems,
      List<QuestionnaireResponseItemComponent> populatedItems) {

    for (QuestionnaireResponseItemComponent populated : populatedItems) {
      if (!populated.hasLinkId()) {
        continue;
      }

      QuestionnaireResponseItemComponent targetItem =
          findSiblingByLinkId(targetItems, populated.getLinkId());

      if (targetItem == null && (populated.hasAnswer() || populated.hasItem())) {
        targetItem = new QuestionnaireResponseItemComponent().setLinkId(populated.getLinkId());
        if (populated.hasText()) {
          targetItem.setText(populated.getText());
        }
        targetItems.add(targetItem);
      }

      if (targetItem == null) {
        continue;
      }

      boolean copiedAnswers = false;
      if (populated.hasAnswer() && !targetItem.hasAnswer()) {
        copyAnswers(targetItem, populated.getAnswer());
        copiedAnswers = true;
      }

      if (populated.hasItem()) {
        mergeItems(targetItem.getItem(), populated.getItem());
      }

      if (!copiedAnswers && populated.hasAnswer() && targetItem.hasAnswer()) {
        mergeAnswerItems(targetItem.getAnswer(), populated.getAnswer());
      }
    }
  }

  private QuestionnaireResponseItemComponent findSiblingByLinkId(
      List<QuestionnaireResponseItemComponent> items, String linkId) {
    for (QuestionnaireResponseItemComponent item : items) {
      if (linkId.equals(item.getLinkId())) {
        return item;
      }
    }
    return null;
  }

  private void mergeAnswerItems(
      List<QuestionnaireResponseItemAnswerComponent> targetAnswers,
      List<QuestionnaireResponseItemAnswerComponent> populatedAnswers) {

    int mergeCount = Math.min(targetAnswers.size(), populatedAnswers.size());
    for (int i = 0; i < mergeCount; i++) {
      QuestionnaireResponseItemAnswerComponent populatedAnswer = populatedAnswers.get(i);
      if (populatedAnswer.hasItem()) {
        mergeItems(targetAnswers.get(i).getItem(), populatedAnswer.getItem());
      }
    }
  }

  private void copyAnswers(
      QuestionnaireResponseItemComponent target,
      List<QuestionnaireResponseItemAnswerComponent> sourceAnswers) {

    for (QuestionnaireResponseItemAnswerComponent sourceAnswer : sourceAnswers) {
      QuestionnaireResponseItemAnswerComponent copied = sourceAnswer.copy();
      ensureInformationOrigin(copied);
      target.addAnswer(copied);
    }
  }

  private void ensureInformationOrigin(QuestionnaireResponseItemAnswerComponent answer) {
    if (answer.getExtensionByUrl(INFO_ORIGIN_EXT) != null) {
      return;
    }
    Extension originExt = new Extension(INFO_ORIGIN_EXT);
    originExt.addExtension(new Extension("source", new CodeType("auto-server")));
    answer.addExtension(originExt);
  }

  private Parameters buildOutputParameters(QuestionnaireResponse qr) {
    Parameters params = new Parameters();
    params.getMeta().addProfile(NEXT_QUESTION_OUTPUT_PROFILE);
    params.addParameter().setName(OUTPUT_PARAMETER_NAME).setResource(qr);
    return params;
  }
}
