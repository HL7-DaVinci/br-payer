package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.UrlType;
import org.opencds.cqf.fhir.cr.hapi.common.IQuestionnaireProcessorFactory;
import org.opencds.cqf.fhir.cr.questionnaire.QuestionnaireProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import static org.hl7.davinci.common.CrdConstants.DOC_REASON_SYSTEM;
import static org.hl7.davinci.common.FhirConstants.*;
import static org.hl7.davinci.dtr.DtrConstants.*;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

/**
 * Builds QuestionnaireResponse resources with required DTR extensions
 * for inclusion in $questionnaire-package response bundles.
 * Executes server-side CQL pre-population via QuestionnaireProcessor
 * and marks pre-populated answers with information-origin extensions.
 */
@Component
@EnableConfigurationProperties(DtrAdaptiveProperties.class)
public class DtrResponseBuilder {

  private static final Logger logger = LoggerFactory.getLogger(DtrResponseBuilder.class);

  private static final String DEFAULT_NEXT_QUESTION_URL = "http://localhost:8080/fhir/Questionnaire/$next-question";

  private final IQuestionnaireProcessorFactory questionnaireProcessorFactory;
  private final DaoRegistry daoRegistry;
  private final DtrAdaptiveProperties adaptiveProperties;
  private final AppProperties appProperties;

  public DtrResponseBuilder(
      IQuestionnaireProcessorFactory questionnaireProcessorFactory,
      DaoRegistry daoRegistry,
      DtrAdaptiveProperties adaptiveProperties,
      AppProperties appProperties) {
    this.questionnaireProcessorFactory = questionnaireProcessorFactory;
    this.daoRegistry = daoRegistry;
    this.adaptiveProperties = adaptiveProperties;
    this.appProperties = appProperties;
  }

  public record PrepopulationResult(QuestionnaireResponse response, List<String> warnings) {
  }

  /**
   * A Questionnaire is adaptive if it carries the required questionnaireAdaptive
   * extension
   * (1..1 per dtr-questionnaire-adapt profile) or declares the adaptive profile
   * in meta (unreliable but a fallback).
   */
  public static boolean isAdaptiveQuestionnaire(Questionnaire q) {
    if (q.hasExtension(QUESTIONNAIRE_ADAPTIVE_EXT)) {
      return true;
    }
    return q.getMeta().hasProfile(Q_ADAPT_PROFILE)
        || q.getMeta().hasProfile(Q_ADAPT_SEARCH_PROFILE);
  }

  /**
   * Build a QuestionnaireResponse with DTR extensions and server-side CQL
   * pre-population.
   * Falls back to an empty QR if pre-population fails.
   *
   * @param questionnaire the resolved Questionnaire
   * @param coverage      the Coverage resource
   * @param provenance    resolution provenance metadata for qr-context scoping
   * @param allOrders     all order resources from the request
   * @param libraries     resolved CQL libraries for the questionnaire
   */
  public PrepopulationResult buildResponse(
      Questionnaire questionnaire,
      Coverage coverage,
      DtrQuestionnaireResolver.ResolvedQuestionnaire provenance,
      List<Resource> allOrders,
      List<Library> libraries) {

    List<String> warnings = new ArrayList<>();
    QuestionnaireResponse qr = null;

    // Attempt CQL pre-population
    try {
      qr = executePopulate(questionnaire, coverage, allOrders);
      if (qr != null) {
        extractPopulateWarnings(qr, warnings);
      }
    } catch (Exception e) {
      String warning = "CQL pre-population failed for questionnaire "
          + questionnaire.getUrl() + ": " + e.getMessage();
      logger.warn(warning, e);
      warnings.add(warning);
      qr = null;
    }

    // Fallback to empty QR if populate returned null or failed
    if (qr == null) {
      qr = new QuestionnaireResponse();
    }

    // QuestionnaireResponse.item.text is optional; remove it to avoid false
    // mismatches when source questionnaire text is normalized during assembly.
    clearItemText(qr.getItem());

    // Mark all pre-populated answers with information-origin (auto-server).
    // Called after clearItemText so we only annotate answers from the populate
    // step.
    addInformationOrigin(qr.getItem());

    // Enrich with DTR-required fields and extensions
    enrichWithDtrExtensions(qr, questionnaire, coverage, provenance, allOrders);

    return new PrepopulationResult(qr, warnings);
  }

  /**
   * Build an adaptive QuestionnaireResponse for questionnaires that use the
   * dtr-questionnaire-adapt profile. When initialItems is non-empty, the
   * contained Questionnaire includes those items and the QR is pre-populated
   * with CQL answers. When empty, the QR starts empty and pre-population
   * happens in $next-question when items are actually delivered.
   *
   * @param initialItems items to include in the initial delivery (may be empty)
   */
  public PrepopulationResult buildAdaptiveResponse(
      Questionnaire questionnaire,
      Coverage coverage,
      DtrQuestionnaireResolver.ResolvedQuestionnaire provenance,
      List<Resource> allOrders,
      List<Questionnaire.QuestionnaireItemComponent> initialItems) {

    List<String> warnings = new ArrayList<>();
    QuestionnaireResponse qr = new QuestionnaireResponse();

    qr.setId(UUID.randomUUID().toString());

    // Adaptive QR profile
    qr.getMeta().addProfile(QR_ADAPT_PROFILE);
    qr.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS);

    // Version-specific questionnaire canonical via contained reference
    String containedQuestionnaireId = "contained-questionnaire";
    qr.setQuestionnaire("#" + containedQuestionnaireId);

    if (coverage.hasBeneficiary()) {
      qr.setSubject(coverage.getBeneficiary().copy());
    }

    qr.setAuthored(new Date());

    addCoverageAndIntendedUseExtensions(qr, coverage);

    // qr-context extensions
    addQrContextExtensions(qr, provenance, allOrders);

    // Contained Questionnaire: shell derived from the adaptive source.
    // The contained Questionnaire must have derivedFrom pointing to the source canonical.
    // The url must differ from the source to avoid a self-referential derivedFrom.
    Questionnaire contained = new Questionnaire();
    contained.setId(containedQuestionnaireId);
    contained.setUrl(questionnaire.getUrl() + "-adaptive");
    contained.setStatus(questionnaire.hasStatus()
        ? questionnaire.getStatus()
        : Enumerations.PublicationStatus.ACTIVE);
    if (questionnaire.hasSubjectType()) {
      questionnaire.getSubjectType().forEach(subjectType -> contained.addSubjectType(subjectType.getValue()));
    } else {
      contained.addSubjectType("Patient");
    }
    String sourceCanonical = DtrFhirUtil.toVersionSpecific(questionnaire.getUrl(), questionnaire.getVersion());
    if (sourceCanonical != null && !sourceCanonical.isBlank()) {
      contained.addDerivedFrom(sourceCanonical);
    }

    // questionnaireAdaptive extension pointing to $next-question endpoint
    Extension adaptiveExt = new Extension(QUESTIONNAIRE_ADAPTIVE_EXT);
    String nextQuestionUrl = resolveNextQuestionUrl();
    if (nextQuestionUrl == null || nextQuestionUrl.isBlank()) {
      nextQuestionUrl = DEFAULT_NEXT_QUESTION_URL;
    }
    adaptiveExt.setValue(new UrlType(nextQuestionUrl));
    contained.addExtension(adaptiveExt);

    // When initial items are provided, include them in the contained Q
    // and pre-populate the QR with CQL answers
    if (initialItems != null && !initialItems.isEmpty()) {
      for (Questionnaire.QuestionnaireItemComponent item : initialItems) {
        contained.addItem(item);
      }

      // Pre-populate answers for initial items
      try {
        Questionnaire scopedQ = questionnaire.copy();
        scopedQ.setItem(new ArrayList<>());
        for (Questionnaire.QuestionnaireItemComponent item : initialItems) {
          scopedQ.addItem(item);
        }
        QuestionnaireResponse populated = executePopulate(scopedQ, coverage, allOrders);
        if (populated != null) {
          extractPopulateWarnings(populated, warnings);
          clearItemText(populated.getItem());
          addInformationOrigin(populated.getItem());
          qr.setItem(populated.getItem());
        }
      } catch (Exception e) {
        String warning = "Adaptive pre-population failed for initial items of "
            + questionnaire.getUrl() + ": " + e.getMessage();
        logger.warn(warning, e);
        warnings.add(warning);
      }
    }

    qr.addContained(contained);

    return new PrepopulationResult(qr, warnings);
  }

  /**
   * Resolves the $next-question endpoint URL. Uses the explicit configuration if
   * set,
   * otherwise derives it from hapi.fhir.server_address.
   */
  String resolveNextQuestionUrl() {
    String explicit = adaptiveProperties.nextQuestionUrl();
    if (explicit != null && !explicit.isBlank()) {
      return explicit;
    }
    String serverAddress = FhirUtil.normalizeServerBase(appProperties.getServer_address());
    if (serverAddress != null && !serverAddress.isBlank()) {
      return serverAddress + "/Questionnaire/$next-question";
    }
    return null;
  }

  private QuestionnaireResponse executePopulate(
      Questionnaire questionnaire,
      Coverage coverage,
      List<Resource> allOrders) {

    QuestionnaireProcessor processor = questionnaireProcessorFactory.create(new SystemRequestDetails());

    String subjectId = extractPatientId(coverage);
    Bundle dataBundle = buildDataBundle(coverage, allOrders, subjectId);

    // populate() evaluates CQL initialExpression/calculatedExpression on
    // questionnaire items.
    // Order resources in the data bundle are available to CQL retrieve operations
    // (e.g. [DeviceRequest]).
    // CQL parameter declarations (e.g. "parameter device_request DeviceRequest")
    // require launchContext extensions on the Questionnaire which is not yet implemented.
    var result = processor.populate(questionnaire, subjectId, List.of(), null, dataBundle, null);

    if (result instanceof QuestionnaireResponse populated) {
      return populated;
    }

    return null;
  }

  /**
   * Enriches a QuestionnaireResponse with DTR-required metadata.
   * Applied to both pre-populated and empty QRs.
   */
  private void enrichWithDtrExtensions(
      QuestionnaireResponse qr,
      Questionnaire questionnaire,
      Coverage coverage,
      DtrQuestionnaireResolver.ResolvedQuestionnaire provenance,
      List<Resource> allOrders) {

    qr.getMeta().addProfile(QR_PROFILE);
    qr.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS);

    // Version-specific questionnaire canonical
    String canonical = DtrFhirUtil.toVersionSpecific(questionnaire.getUrl(), questionnaire.getVersion());
    qr.setQuestionnaire(canonical);

    // Subject from coverage beneficiary
    if (coverage.hasBeneficiary()) {
      qr.setSubject(coverage.getBeneficiary().copy());
    }

    // Authored timestamp
    qr.setAuthored(new Date());

    addCoverageAndIntendedUseExtensions(qr, coverage);

    // qr-context extensions -- provenance-aware scoping
    addQrContextExtensions(qr, provenance, allOrders);
  }

  private void addCoverageAndIntendedUseExtensions(QuestionnaireResponse qr, Coverage coverage) {
    Extension coverageExt = new Extension(QR_COVERAGE_EXT);
    coverageExt.setValue(toRelativeTypedReference(coverage));
    qr.addExtension(coverageExt);

    Extension intendedUseExt = new Extension(INTENDED_USE_EXT);
    CodeableConcept intendedUseCC = new CodeableConcept();
    intendedUseCC.addCoding(new Coding()
        .setSystem(DOC_REASON_SYSTEM)
        .setCode(INTENDED_USE_WITH_ORDER)
        .setDisplay("Include with order"));
    intendedUseExt.setValue(intendedUseCC);
    qr.addExtension(intendedUseExt);
  }

  private String extractPatientId(Coverage coverage) {
    if (coverage == null || !coverage.hasBeneficiary()) {
      return null;
    }
    return ResourceResolver.toVersionlessTypedReference(coverage.getBeneficiary(), "Patient");
  }

  private Bundle buildDataBundle(Coverage coverage, List<Resource> allOrders, String patientId) {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);

    // Patient context is established via the subjectId parameter to populate().
    // For relative local references, add a stub only if the patient does not exist
    // in the repository. For absolute references, always include a stub to avoid
    // resolving to a same-id local patient on this server.
    if (patientId != null) {
      IIdType patientRef = new Reference(patientId).getReferenceElement();
      String patientIdPart = patientRef.getIdPart();
      if (patientIdPart != null && !patientIdPart.isBlank()) {
        String baseUrl = patientRef.getBaseUrl();
        boolean isAbsolute = baseUrl != null && !baseUrl.isBlank();
        if (isAbsolute || !patientExistsInRepository(patientIdPart)) {
          Patient stub = new Patient();
          // Keep absolute subject IDs to avoid accidentally resolving a same-ID local
          // patient.
          stub.setId(isAbsolute ? patientRef.toVersionless().getValue() : patientIdPart);
          bundle.addEntry().setResource(stub);
        }
      }
    }

    bundle.addEntry().setResource(coverage);

    if (allOrders != null) {
      for (Resource order : allOrders) {
        bundle.addEntry().setResource(order);
      }
    }

    return bundle;
  }

  private boolean patientExistsInRepository(String patientIdPart) {
    try {
      daoRegistry.getResourceDao(Patient.class)
          .read(new IdType("Patient", patientIdPart), new SystemRequestDetails());
      return true;
    } catch (ResourceNotFoundException e) {
      return false;
    }
  }

  /**
   * Recursively adds information-origin extension to all QR answers.
   */
  private void addInformationOrigin(List<QuestionnaireResponseItemComponent> items) {
    if (items == null) {
      return;
    }
    for (QuestionnaireResponseItemComponent item : items) {
      for (QuestionnaireResponseItemAnswerComponent answer : item.getAnswer()) {
        Extension originExt = new Extension(INFO_ORIGIN_EXT);
        originExt.addExtension(new Extension("source", new CodeType("auto-server")));
        answer.addExtension(originExt);
        addInformationOrigin(answer.getItem());
      }
      addInformationOrigin(item.getItem());
    }
  }

  private void clearItemText(List<QuestionnaireResponseItemComponent> items) {
    if (items == null) {
      return;
    }
    for (QuestionnaireResponseItemComponent item : items) {
      item.setText(null);
      if (item.hasAnswer()) {
        for (QuestionnaireResponseItemAnswerComponent answer : item.getAnswer()) {
          clearItemText(answer.getItem());
        }
      }
      clearItemText(item.getItem());
    }
  }

  /**
   * Extracts OperationOutcome warnings embedded by cqf-fhir-cr in the QR's
   * contained resources.
   */
  private void extractPopulateWarnings(QuestionnaireResponse qr, List<String> warnings) {
    for (Resource contained : qr.getContained()) {
      if (contained instanceof OperationOutcome outcome) {
        for (OperationOutcome.OperationOutcomeIssueComponent issue : outcome.getIssue()) {
          if (issue.hasDiagnostics()) {
            warnings.add("Pre-population: " + issue.getDiagnostics());
          }
        }
      }
    }
  }

  private void addQrContextExtensions(QuestionnaireResponse qr,
      DtrQuestionnaireResolver.ResolvedQuestionnaire provenance,
      List<Resource> allOrders) {

    if (allOrders == null || allOrders.isEmpty()) {
      return;
    }

    switch (provenance.path()) {
      case QUESTIONNAIRE, BOTH -> {
        // Questionnaire parameter or BOTH: all orders get qr-context
        for (Resource order : allOrders) {
          addQrContext(qr, order);
        }
      }
      case ORDER -> {
        // Order-based: only source orders get qr-context
        for (Resource order : allOrders) {
          String orderId = order.getIdElement().toUnqualifiedVersionless().getValue();
          if (provenance.sourceOrderIds().contains(orderId)) {
            addQrContext(qr, order);
          }
        }
      }
    }
  }

  private void addQrContext(QuestionnaireResponse qr, Resource order) {
    Extension contextExt = new Extension(QR_CONTEXT_EXT);
    contextExt.setValue(toRelativeTypedReference(order));
    qr.addExtension(contextExt);
  }

  private Reference toRelativeTypedReference(Resource resource) {
    if (resource == null || !resource.hasIdElement()) {
      return new Reference();
    }

    String relativeRef = ResourceResolver.toRelativeReference(resource);
    if (relativeRef != null) {
      return new Reference(relativeRef);
    }

    return new Reference(resource.getIdElement().toUnqualifiedVersionless());
  }
}
