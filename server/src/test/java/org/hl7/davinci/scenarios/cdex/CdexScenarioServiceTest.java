package org.hl7.davinci.scenarios.cdex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.hl7.davinci.pas.PasCommunicationRequestBuilder;
import org.hl7.davinci.pas.PasConstants;
import org.hl7.davinci.pas.PasExtensions;
import org.hl7.davinci.scenarios.cdex.CdexScenarioService.DocumentationRequestDto;
import org.hl7.davinci.scenarios.cdex.CdexScenarioService.PendedClaimDto;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.RequestDetails;

class CdexScenarioServiceTest {

  private DaoRegistry daoRegistry;
  private IFhirResourceDao<ClaimResponse> crDao;
  private IFhirResourceDao<CommunicationRequest> commReqDao;
  private IFhirResourceDao<Questionnaire> questionnaireDao;
  private IFhirResourceDao<Patient> patientDao;
  private CdexScenarioService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    daoRegistry = mock(DaoRegistry.class);
    crDao = mock(IFhirResourceDao.class);
    commReqDao = mock(IFhirResourceDao.class);
    questionnaireDao = mock(IFhirResourceDao.class);
    patientDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(crDao);
    when(daoRegistry.getResourceDao(CommunicationRequest.class)).thenReturn(commReqDao);
    when(daoRegistry.getResourceDao(Questionnaire.class)).thenReturn(questionnaireDao);
    when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
    service = new CdexScenarioService(daoRegistry);
  }

  private ClaimResponse pendedClaimResponse() {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("cr-pended-1");
    cr.getMeta().addTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE, null);
    cr.addIdentifier().setSystem("http://example.org/PATIENT_EVENT_TRACE_NUMBER").setValue("ACN-1");
    cr.setPatient(new Reference("Patient/p1"));
    ClaimResponse.ItemComponent item = cr.addItem().setItemSequence(1);
    item.addAdjudication()
        .setCategory(new CodeableConcept().addCoding(
            new Coding("http://terminology.hl7.org/CodeSystem/adjudication", "submitted", null)))
        .addExtension(PasExtensions.buildReviewActionExtension("A4", "Pending", null));
    cr.addCommunicationRequest(new Reference("CommunicationRequest/comm-1"));
    return cr;
  }

  @Test
  void decodesQuestionnaireDocumentationRequest() {
    when(crDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(pendedClaimResponse()));

    CommunicationRequest commReq =
        PasCommunicationRequestBuilder.buildQuestionnaireRequest(1, "Patient/p1", "q-hot-1",
            "http://example.org/Questionnaire/HomeOxygenTherapy");
    commReq.setId("comm-1");
    when(commReqDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(commReq);

    Questionnaire questionnaire = new Questionnaire();
    questionnaire.setId("q-hot-1");
    questionnaire.setUrl("http://example.org/Questionnaire/HomeOxygenTherapy");
    questionnaire.setTitle("Home Oxygen Therapy");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(questionnaire));

    Patient patient = new Patient();
    patient.setId("p1");
    patient.addName().setFamily("Demo").addGiven("Cdex");
    patient.addIdentifier(new Identifier()
        .setType(new CodeableConcept().addCoding(
            new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "MB", null)))
        .setValue("M-1"));
    when(patientDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(patient);

    List<PendedClaimDto> claims = service.getPendedClaims();

    assertEquals(1, claims.size());
    PendedClaimDto claim = claims.get(0);
    assertEquals("cr-pended-1", claim.claimResponseId());
    assertEquals("ACN-1", claim.trackingIdValue());
    assertEquals("M-1", claim.memberId());
    assertEquals("Cdex Demo", claim.patientDisplay());
    assertEquals(1, claim.items().size());
    assertEquals("A4", claim.items().get(0).reviewActionCode());

    assertEquals(1, claim.documentationRequests().size());
    DocumentationRequestDto request = claim.documentationRequests().get(0);
    assertEquals("questionnaire", request.type());
    assertEquals("q-hot-1", request.trn());
    assertEquals("http://example.org/Questionnaire/HomeOxygenTherapy", request.questionnaireCanonical());
    assertEquals("Home Oxygen Therapy", request.questionnaireName());
    assertEquals(1, request.lineNumber());
    assertEquals("active", request.status());
  }

  @Test
  void decodesAttachmentCodeRequestAndSkipsClaimsWithoutRequests() {
    ClaimResponse withRequests = pendedClaimResponse();
    ClaimResponse withoutRequests = new ClaimResponse();
    withoutRequests.setId("cr-timer-only");
    withoutRequests.getMeta().addTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE, null);
    when(crDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(withRequests, withoutRequests));

    CommunicationRequest commReq =
        PasCommunicationRequestBuilder.buildAttachmentCodeRequest(2, "18748-4", "Patient/p1");
    commReq.setId("comm-1");
    when(commReqDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(commReq);
    when(patientDao.read(any(IdType.class), any(RequestDetails.class)))
        .thenThrow(new RuntimeException("no patient"));

    List<PendedClaimDto> claims = service.getPendedClaims();

    assertEquals(1, claims.size());
    DocumentationRequestDto request = claims.get(0).documentationRequests().get(0);
    assertEquals("attachment-code", request.type());
    assertEquals("18748-4", request.code());
    assertNull(request.questionnaireCanonical());
    assertEquals(2, request.lineNumber());
    assertNull(claims.get(0).memberId());
  }

  @Test
  void buildsSubmitAttachmentParametersForOpenRequests() {
    ClaimResponse pended = pendedClaimResponse();
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(pended);

    CommunicationRequest commReq =
        PasCommunicationRequestBuilder.buildQuestionnaireRequest(1, "Patient/p1", "q-hot-1",
            "http://example.org/Questionnaire/HomeOxygenTherapy");
    commReq.setId("comm-1");
    when(commReqDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(commReq);

    Questionnaire questionnaire = new Questionnaire();
    questionnaire.setId("q-hot-1");
    questionnaire.setUrl("http://example.org/Questionnaire/HomeOxygenTherapy");
    questionnaire.addItem().setLinkId("1")
        .setType(org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType.STRING);
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(questionnaire));
    when(patientDao.read(any(IdType.class), any(RequestDetails.class)))
        .thenThrow(new RuntimeException("no patient"));

    org.hl7.fhir.r4.model.Parameters parameters =
        service.buildSubmitAttachment("cr-pended-1", java.util.Set.of(), true).orElseThrow();

    org.hl7.fhir.r4.model.Identifier trackingId =
        (org.hl7.fhir.r4.model.Identifier) paramValue(parameters, "TrackingId");
    assertEquals("ACN-1", trackingId.getValue());
    assertEquals("preauthorization", paramValue(parameters, "AttachTo").primitiveValue());
    assertNotNull(paramValue(parameters, "MemberId"));
    assertNotNull(paramValue(parameters, "OrganizationId"));
    assertEquals("true", paramValue(parameters, "Final").primitiveValue());

    var attachments = parameters.getParameter().stream()
        .filter(p -> "Attachment".equals(p.getName())).toList();
    assertEquals(1, attachments.size());
    var contentPart = attachments.get(0).getPart().stream()
        .filter(p -> "Content".equals(p.getName())).findFirst().orElseThrow();
    assertInstanceOf(org.hl7.fhir.r4.model.QuestionnaireResponse.class, contentPart.getResource());
    var lineItemPart = attachments.get(0).getPart().stream()
        .filter(p -> "LineItem".equals(p.getName())).findFirst().orElseThrow();
    assertEquals("1", lineItemPart.getValue().primitiveValue());
  }

  // Looks a parameter up from the list; Parameters.getParameter(String) changed
  // return type across HAPI versions, so tests avoid it.
  private static org.hl7.fhir.r4.model.Type paramValue(
      org.hl7.fhir.r4.model.Parameters parameters, String name) {
    return parameters.getParameter().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  @Test
  void buildSubmitAttachmentReturnsEmptyWhenClaimNotPended() {
    ClaimResponse notPended = new ClaimResponse();
    notPended.setId("cr-decided");
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(notPended);

    assertTrue(service.buildSubmitAttachment("cr-decided", java.util.Set.of(), true).isEmpty());
  }
}
