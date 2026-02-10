package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.UriType;
import org.opencds.cqf.fhir.cr.hapi.common.IQuestionnaireProcessorFactory;
import org.opencds.cqf.fhir.cr.questionnaire.QuestionnaireProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

/**
 * Builds QuestionnaireResponse resources with required DTR extensions
 * for inclusion in $questionnaire-package response bundles.
 * Executes server-side CQL pre-population via QuestionnaireProcessor
 * and marks pre-populated answers with information-origin extensions.
 */
@Component
public class DtrResponseBuilder {

  private static final Logger logger = LoggerFactory.getLogger(DtrResponseBuilder.class);

  private static final String QR_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaireresponse";
  private static final String QR_ADAPT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaireresponse-adapt";
  private static final String Q_ADAPT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt";
  private static final String QR_COVERAGE_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-coverage";
  private static final String INTENDED_USE_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/intendedUse";
  private static final String QR_CONTEXT_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context";
  private static final String CRD_COVERAGE_INFO_SYSTEM =
      "http://hl7.org/fhir/us/davinci-crd/CodeSystem/coverage-information-codes";
  private static final String INFO_ORIGIN_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/information-origin";
  private static final String QUESTIONNAIRE_ADAPTIVE_EXT =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive";

  private final IQuestionnaireProcessorFactory questionnaireProcessorFactory;
  private final DaoRegistry daoRegistry;
  private final DtrAdaptiveProperties adaptiveProperties;

  public DtrResponseBuilder(
      IQuestionnaireProcessorFactory questionnaireProcessorFactory,
      DaoRegistry daoRegistry,
      DtrAdaptiveProperties adaptiveProperties) {
    this.questionnaireProcessorFactory = questionnaireProcessorFactory;
    this.daoRegistry = daoRegistry;
    this.adaptiveProperties = adaptiveProperties;
  }

  public record PrepopulationResult(QuestionnaireResponse response, List<String> warnings) {}

  /**
   * A Questionnaire is adaptive if it carries the required questionnaireAdaptive extension
   * (1..1 per dtr-questionnaire-adapt profile) or declares the adaptive profile in meta (unreliable but a fallback).
   */
  public static boolean isAdaptiveQuestionnaire(Questionnaire q) {
    if (q.hasExtension(QUESTIONNAIRE_ADAPTIVE_EXT)) {
      return true;
    }
    return q.getMeta().hasProfile(Q_ADAPT_PROFILE);
  }

  /**
   * Build a QuestionnaireResponse with DTR extensions and server-side CQL pre-population.
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
        addInformationOrigin(qr.getItem());
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

    // Enrich with DTR-required fields and extensions
    enrichWithDtrExtensions(qr, questionnaire, coverage, provenance, allOrders);

    return new PrepopulationResult(qr, warnings);
  }

  /**
   * Build an adaptive QuestionnaireResponse for questionnaires that use the
   * dtr-questionnaire-adapt profile. No CQL pre-population — questions are
   * delivered incrementally via $next-question.
   */
  public PrepopulationResult buildAdaptiveResponse(
      Questionnaire questionnaire,
      Coverage coverage,
      DtrQuestionnaireResolver.ResolvedQuestionnaire provenance,
      List<Resource> allOrders) {

    List<String> warnings = new ArrayList<>();
    QuestionnaireResponse qr = new QuestionnaireResponse();

    // Generate a UUID ID (used as the session key for $next-question)
    String qrId = UUID.randomUUID().toString();
    qr.setId(qrId);

    // Adaptive QR profile
    qr.getMeta().addProfile(QR_ADAPT_PROFILE);
    qr.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS);

    // Version-specific questionnaire canonical
    String canonical = DtrFhirUtil.toVersionSpecific(questionnaire.getUrl(), questionnaire.getVersion());
    String containedQuestionnaireId = "contained-questionnaire";
    qr.setQuestionnaire("#" + containedQuestionnaireId);

    // Subject from coverage beneficiary
    if (coverage.hasBeneficiary()) {
      qr.setSubject(coverage.getBeneficiary().copy());
    }

    // Authored timestamp
    qr.setAuthored(new Date());

    // qr-coverage extension
    Extension coverageExt = new Extension(QR_COVERAGE_EXT);
    coverageExt.setValue(toRelativeTypedReference(coverage));
    qr.addExtension(coverageExt);

    // intendedUse extension
    Extension intendedUseExt = new Extension(INTENDED_USE_EXT);
    CodeableConcept intendedUseCC = new CodeableConcept();
    intendedUseCC.addCoding(new Coding()
        .setSystem(CRD_COVERAGE_INFO_SYSTEM)
        .setCode("withorder")
        .setDisplay("Include with order"));
    intendedUseExt.setValue(intendedUseCC);
    qr.addExtension(intendedUseExt);

    // qr-context extensions
    addQrContextExtensions(qr, provenance, allOrders);

    // Contained Questionnaire: empty, derived from the adaptive source
    Questionnaire contained = new Questionnaire();
    contained.setId(containedQuestionnaireId);
    contained.setDerivedFrom(List.of(new CanonicalType(canonical)));
    qr.addContained(contained);

    // questionnaireAdaptive extension pointing to $next-question endpoint
    // must be carried on the contained Questionnaire for adaptive clients
    String nextQuestionUrl = adaptiveProperties.nextQuestionUrl();
    if (nextQuestionUrl != null && !nextQuestionUrl.isBlank()) {
      Extension adaptiveExt = new Extension(QUESTIONNAIRE_ADAPTIVE_EXT);
      adaptiveExt.setValue(new UriType(nextQuestionUrl));
      contained.addExtension(adaptiveExt);
    } else {
      warnings.add("dtr.adaptive.next-question-url is not configured; "
          + "questionnaireAdaptive extension omitted from contained adaptive Questionnaire");
    }

    return new PrepopulationResult(qr, warnings);
  }

  private QuestionnaireResponse executePopulate(
      Questionnaire questionnaire,
      Coverage coverage,
      List<Resource> allOrders) {

    QuestionnaireProcessor processor = questionnaireProcessorFactory.create(new SystemRequestDetails());

    String subjectId = extractPatientId(coverage);
    Bundle dataBundle = buildDataBundle(coverage, allOrders, subjectId);

    // populate() evaluates CQL initialExpression/calculatedExpression on questionnaire items.
    // Order resources in the data bundle are available to CQL retrieve operations (e.g. [DeviceRequest]).
    // CQL parameter declarations (e.g. "parameter device_request DeviceRequest") require
    // launchContext extensions on the Questionnaire — a future enhancement.
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

    // qr-coverage extension
    Extension coverageExt = new Extension(QR_COVERAGE_EXT);
    coverageExt.setValue(toRelativeTypedReference(coverage));
    qr.addExtension(coverageExt);

    // intendedUse extension
    Extension intendedUseExt = new Extension(INTENDED_USE_EXT);
    CodeableConcept intendedUseCC = new CodeableConcept();
    intendedUseCC.addCoding(new Coding()
        .setSystem(CRD_COVERAGE_INFO_SYSTEM)
        .setCode("withorder")
        .setDisplay("Include with order"));
    intendedUseExt.setValue(intendedUseCC);
    qr.addExtension(intendedUseExt);

    // qr-context extensions — provenance-aware scoping
    addQrContextExtensions(qr, provenance, allOrders);
  }

  private String extractPatientId(Coverage coverage) {
    if (coverage.hasBeneficiary() && coverage.getBeneficiary().hasReference()) {
      Reference beneficiary = coverage.getBeneficiary();
      String ref = beneficiary.getReference();
      if (ref == null || ref.isBlank()) {
        return null;
      }

      IIdType beneficiaryRef = beneficiary.getReferenceElement();
      String resourceType = beneficiaryRef.getResourceType();
      String idPart = beneficiaryRef.getIdPart();
      if ("Patient".equals(resourceType) && idPart != null && !idPart.isBlank()) {
        String versionlessRef = beneficiaryRef.toVersionless().getValue();
        if (versionlessRef != null && !versionlessRef.isBlank()) {
          return versionlessRef;
        }
        return "Patient/" + idPart;
      }

      if (ref.startsWith("Patient/")) {
        return new IdType(ref).toVersionless().getValue();
      }
    }
    return null;
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
          // Keep absolute subject IDs to avoid accidentally resolving a same-ID local patient.
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

  /**
   * Extracts OperationOutcome warnings embedded by cqf-fhir-cr in the QR's contained resources.
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

    String idPart = resource.getIdElement().getIdPart();
    if (idPart == null || idPart.isBlank()) {
      return new Reference(resource.getIdElement().toUnqualifiedVersionless());
    }

    return new Reference(resource.fhirType() + "/" + idPart);
  }
}
