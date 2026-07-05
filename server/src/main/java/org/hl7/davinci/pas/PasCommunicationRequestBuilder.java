package org.hl7.davinci.pas;

import java.util.UUID;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

public final class PasCommunicationRequestBuilder {

  private PasCommunicationRequestBuilder() {
  }

  /**
   * Questionnaire request: payload.content is the LOINC {@code 102089-0} marker; the questionnaire
   * itself is named by the item trace number on the matching ClaimResponse.item. The identifier
   * reuses that same trace number so the request satisfies profile-communicationrequest's
   * IdentifierUnlessVO invariant and correlates to the pended item.
   */
  public static CommunicationRequest buildQuestionnaireRequest(int lineNumber, String patientRef,
      String trn) {
    CommunicationRequest request = base(lineNumber, patientRef, trn);
    request.addPayload().setContent(new StringType(PasConstants.LOINC_QUESTIONNAIRE_REQUEST));
    return request;
  }

  /** Builds an attachment-code additional-information request (one LOINC/PWK code per request). */
  public static CommunicationRequest buildAttachmentCodeRequest(int lineNumber, String code,
      String patientRef) {
    CommunicationRequest request = base(lineNumber, patientRef, UUID.randomUUID().toString());
    request.addPayload().setContent(new StringType(code));
    return request;
  }

  /** Shared CommunicationRequest slices; the caller adds the single payload (payload is 0..1). */
  private static CommunicationRequest base(int lineNumber, String patientRef, String identifierValue) {
    CommunicationRequest request = new CommunicationRequest();
    request.setId("cr-" + UUID.randomUUID().toString().substring(0, 8));
    request.setMeta(new Meta().addProfile(PasConstants.PROFILE_PAS_COMMUNICATION_REQUEST));
    request.setStatus(CommunicationRequest.CommunicationRequestStatus.ACTIVE);
    request.addIdentifier(new Identifier()
        .setSystem(PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM).setValue(identifierValue));
    request.addExtension(PasConstants.EXT_SERVICE_LINE_NUMBER, new PositiveIntType(lineNumber));
    request.addCategory(new CodeableConcept().addCoding(new Coding(
        PasConstants.X12_REQUEST_CATEGORY_SYSTEM, "15", "Justification for Admissions")));
    if (patientRef != null) {
      request.setSubject(new Reference(patientRef));
    }
    return request;
  }
}
