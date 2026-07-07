package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

class PasCommunicationRequestBuilderTest {

  // PAS profile-communicationrequest constrains payload.content[x] to a string from the required
  // valid-hl7-attachment-requests binding; a questionnaire request is the LOINC 102089-0 marker,
  // never the questionnaire URL or a contentReference.
  @Test
  void buildQuestionnaireRequest_emitsLoincMarkerAsString() {
    CommunicationRequest request =
        PasCommunicationRequestBuilder.buildQuestionnaireRequest(1, "Patient/pat-1", "trn-1",
            "http://example.org/Questionnaire/HomeOxygenTherapy");

    assertEquals(1, request.getPayload().size(),
        "payload is 0..1: one request per CommunicationRequest");
    CommunicationRequest.CommunicationRequestPayloadComponent payload = request.getPayloadFirstRep();
    assertFalse(payload.getContent() instanceof Reference,
        "payload.content must not be a Reference; PAS 2.2.1 content[x] is string-only");
    assertTrue(payload.getContent() instanceof StringType);
    assertEquals("102089-0", ((StringType) payload.getContent()).getValue());
    assertEquals(1, ((PositiveIntType) request
        .getExtensionByUrl(PasConstants.EXT_SERVICE_LINE_NUMBER).getValue()).getValue().intValue());
  }

  // profile-communicationrequest invariant IdentifierUnlessVO requires an identifier on every
  // CommunicationRequest; the questionnaire request reuses the TRN minted for the item.
  @Test
  void buildQuestionnaireRequest_carriesIdentifierWithTrn() {
    CommunicationRequest request =
        PasCommunicationRequestBuilder.buildQuestionnaireRequest(1, "Patient/pat-1", "ctx-1",
            "http://example.org/Questionnaire/HomeOxygenTherapy");

    assertFalse(request.getIdentifier().isEmpty(),
        "IdentifierUnlessVO invariant requires an identifier");
    assertEquals(PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM,
        request.getIdentifierFirstRep().getSystem());
    assertEquals("ctx-1", request.getIdentifierFirstRep().getValue());
  }

  @Test
  void buildAttachmentCodeRequest_emitsTheCode() {
    CommunicationRequest request =
        PasCommunicationRequestBuilder.buildAttachmentCodeRequest(2, "18748-4", "Patient/pat-1");

    assertEquals(1, request.getPayload().size());
    assertEquals("18748-4", ((StringType) request.getPayloadFirstRep().getContent()).getValue());
  }

  @Test
  void buildAttachmentCodeRequest_carriesFreshUuidIdentifier() {
    CommunicationRequest request =
        PasCommunicationRequestBuilder.buildAttachmentCodeRequest(2, "18748-4", "Patient/pat-1");

    assertFalse(request.getIdentifier().isEmpty(),
        "IdentifierUnlessVO invariant requires an identifier");
    assertEquals(PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM,
        request.getIdentifierFirstRep().getSystem());
    assertFalse(request.getIdentifierFirstRep().getValue().isBlank());
  }
}
