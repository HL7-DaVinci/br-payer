package org.hl7.davinci.dtr;

import java.util.Date;
import java.util.List;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Builds QuestionnaireResponse resources with required DTR extensions
 * for inclusion in $questionnaire-package response bundles.
 * Uses resolver provenance metadata for qr-context scoping.
 */
@Component
public class DtrResponseBuilder {

  private static final String QR_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaireresponse";
  private static final String QR_COVERAGE_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-coverage";
  private static final String INTENDED_USE_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/intendedUse";
  private static final String QR_CONTEXT_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context";
  private static final String INTENDED_USE_SYSTEM =
      "http://hl7.org/fhir/us/davinci-dtr/CodeSystem/intended-use";

  /**
   * Build a QuestionnaireResponse with required DTR extensions.
   * CQL pre-population is deferred; returns empty QR with extensions only.
   *
   * @param questionnaire the resolved Questionnaire
   * @param coverage      the Coverage resource
   * @param provenance    resolution provenance metadata for qr-context scoping
   * @param allOrders     all order resources from the request
   */
  public QuestionnaireResponse buildResponse(
      Questionnaire questionnaire,
      Coverage coverage,
      DtrQuestionnaireResolver.ResolvedQuestionnaire provenance,
      List<Resource> allOrders) {

    QuestionnaireResponse qr = new QuestionnaireResponse();
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
        .setSystem(INTENDED_USE_SYSTEM)
        .setCode("withorder"));
    intendedUseExt.setValue(intendedUseCC);
    qr.addExtension(intendedUseExt);

    // qr-context extensions — provenance-aware scoping
    addQrContextExtensions(qr, provenance, allOrders);

    return qr;
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
