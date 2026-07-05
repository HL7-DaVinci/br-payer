package org.hl7.davinci.cdex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.davinci.pas.PasConstants;
import org.hl7.davinci.pas.PasPendedResolutionService;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.UriParam;

class SubmitAttachmentServiceTest {

  private DaoRegistry daoRegistry;
  private PasPendedResolutionService resolutionService;
  private IFhirResourceDao<ClaimResponse> crDao;
  private IFhirResourceDao<DocumentReference> docRefDao;
  private IFhirResourceDao<CommunicationRequest> commReqDao;
  private IFhirResourceDao<Questionnaire> questionnaireDao;
  private IFhirResourceDao<QuestionnaireResponse> qrDao;
  private SubmitAttachmentService service;

  private final Map<String, CommunicationRequest> crById = new HashMap<>();
  private final Map<String, Questionnaire> questionnaireByUrl = new HashMap<>();

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    daoRegistry = mock(DaoRegistry.class);
    resolutionService = mock(PasPendedResolutionService.class);
    crById.clear();
    questionnaireByUrl.clear();

    crDao = mock(IFhirResourceDao.class);
    when(crDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of());
    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(crDao);

    docRefDao = mock(IFhirResourceDao.class);
    when(docRefDao.create(any(), any(RequestDetails.class))).thenReturn(new DaoMethodOutcome());
    when(daoRegistry.getResourceDao("DocumentReference")).thenReturn((IFhirResourceDao) docRefDao);

    commReqDao = mock(IFhirResourceDao.class);
    when(commReqDao.read(any(IdType.class), any(RequestDetails.class)))
        .thenAnswer(inv -> crById.get(((IdType) inv.getArgument(0)).getIdPart()));
    when(commReqDao.update(any(), any(RequestDetails.class))).thenReturn(new DaoMethodOutcome());
    when(daoRegistry.getResourceDao(CommunicationRequest.class)).thenReturn(commReqDao);

    questionnaireDao = mock(IFhirResourceDao.class);
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenAnswer(inv -> {
          String url = urlOf(inv.getArgument(0));
          Questionnaire q = questionnaireByUrl.get(url);
          return q == null ? List.of() : List.of(q);
        });
    when(daoRegistry.getResourceDao(Questionnaire.class)).thenReturn(questionnaireDao);

    qrDao = mock(IFhirResourceDao.class);
    when(qrDao.create(any(), any(RequestDetails.class))).thenReturn(new DaoMethodOutcome());
    when(daoRegistry.getResourceDao("QuestionnaireResponse")).thenReturn((IFhirResourceDao) qrDao);

    service = new SubmitAttachmentService(daoRegistry, resolutionService, FhirContext.forR4());
  }

  private static String urlOf(SearchParameterMap map) {
    List<List<IQueryParameterType>> params = map.get("url");
    if (params == null || params.isEmpty() || params.get(0).isEmpty()) {
      return null;
    }
    return ((UriParam) params.get(0).get(0)).getValue();
  }

  private ClaimResponse pendedClaimResponse(CommunicationRequest... requests) {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("ClaimResponse/cr-1");
    for (CommunicationRequest request : requests) {
      String idPart = request.getIdElement().getIdPart();
      crById.put(idPart, request);
      cr.addCommunicationRequest(new Reference("CommunicationRequest/" + idPart));
    }
    when(crDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(cr));
    return cr;
  }

  private CommunicationRequest documentationRequest(String id, int lineNumber, String trn) {
    CommunicationRequest request = new CommunicationRequest();
    request.setId("CommunicationRequest/" + id);
    request.setStatus(CommunicationRequestStatus.ACTIVE);
    request.addIdentifier(new Identifier()
        .setSystem(PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM).setValue(trn));
    request.addExtension(PasConstants.EXT_SERVICE_LINE_NUMBER, new PositiveIntType(lineNumber));
    return request;
  }

  private Questionnaire questionnaire(String logicalId, String url) {
    Questionnaire q = new Questionnaire();
    q.setId("Questionnaire/" + logicalId);
    q.setUrl(url);
    questionnaireByUrl.put(url, q);
    return q;
  }

  private static QuestionnaireResponse questionnaireResponse(String canonical) {
    QuestionnaireResponse qr = new QuestionnaireResponse();
    qr.setQuestionnaire(canonical);
    return qr;
  }

  private OperationOutcome submit(Resource content, List<Integer> lineItems, Boolean isFinal) {
    ParametersParameterComponent attachment = new ParametersParameterComponent();
    attachment.setName(CdexConstants.PARAM_ATTACHMENT);
    attachment.addPart().setName(CdexConstants.PARAM_ATTACHMENT_CONTENT).setResource(content);
    if (lineItems != null) {
      for (Integer line : lineItems) {
        attachment.addPart().setName(CdexConstants.PARAM_ATTACHMENT_LINE_ITEM)
            .setValue(new StringType(String.valueOf(line)));
      }
    }
    return service.submit(
        new Identifier().setSystem("http://example.org/acn").setValue("ACN-1"),
        new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION), null,
        new Identifier().setValue("1407071236"), null,
        new Identifier().setValue("M123"), null,
        List.of(attachment), isFinal == null ? null : new BooleanType(isFinal));
  }

  private static ParametersParameterComponent attachmentWith(Resource content) {
    ParametersParameterComponent attachment = new ParametersParameterComponent();
    attachment.setName(CdexConstants.PARAM_ATTACHMENT);
    attachment.addPart().setName(CdexConstants.PARAM_ATTACHMENT_CONTENT).setResource(content);
    return attachment;
  }

  @Test
  void finalFalseDoesNotResolveThePend() {
    CommunicationRequest crA = documentationRequest("cr-a", 1, "qA");
    CommunicationRequest crB = documentationRequest("cr-b", 2, "qB");
    pendedClaimResponse(crA, crB);
    questionnaire("qA", "http://example.org/Questionnaire/A");

    submit(questionnaireResponse("http://example.org/Questionnaire/A"), null, false);

    verify(resolutionService, never()).resolveNow(anyString());
    assertEquals(CommunicationRequestStatus.COMPLETED, crA.getStatus());
    assertEquals(CommunicationRequestStatus.ACTIVE, crB.getStatus());
  }

  @Test
  void secondSubmissionWithFinalTrueResolvesThePend() {
    CommunicationRequest crA = documentationRequest("cr-a", 1, "qA");
    CommunicationRequest crB = documentationRequest("cr-b", 2, "qB");
    pendedClaimResponse(crA, crB);
    questionnaire("qA", "http://example.org/Questionnaire/A");
    questionnaire("qB", "http://example.org/Questionnaire/B");

    submit(questionnaireResponse("http://example.org/Questionnaire/A"), null, false);
    verify(resolutionService, never()).resolveNow(anyString());

    submit(questionnaireResponse("http://example.org/Questionnaire/B"), null, true);

    assertEquals(CommunicationRequestStatus.COMPLETED, crB.getStatus());
    verify(resolutionService).resolveNow("cr-1");
  }

  @Test
  void lineItemScopedAttachmentCompletesOnlyThatLinesRequest() {
    CommunicationRequest crA = documentationRequest("cr-a", 1, "qA");
    CommunicationRequest crB = documentationRequest("cr-b", 2, "qB");
    pendedClaimResponse(crA, crB);

    submit(new DocumentReference(), List.of(2), false);

    assertEquals(CommunicationRequestStatus.ACTIVE, crA.getStatus());
    assertEquals(CommunicationRequestStatus.COMPLETED, crB.getStatus());
    verify(resolutionService, never()).resolveNow(anyString());
  }

  @SuppressWarnings("unchecked")
  @Test
  void storedQuestionnaireResponseCarriesTheTrackingIdIdentifier() {
    CommunicationRequest crA = documentationRequest("cr-a", 1, "qA");
    pendedClaimResponse(crA);
    questionnaire("qA", "http://example.org/Questionnaire/A");

    submit(questionnaireResponse("http://example.org/Questionnaire/A"), null, false);

    ArgumentCaptor<QuestionnaireResponse> captor = ArgumentCaptor.forClass(QuestionnaireResponse.class);
    verify(qrDao).create(captor.capture(), any(RequestDetails.class));
    QuestionnaireResponse stored = captor.getValue();
    assertEquals("ACN-1", stored.getIdentifier().getValue());
    assertEquals("http://example.org/acn", stored.getIdentifier().getSystem());
  }

  @Test
  void mismatchedQuestionnaireCanonicalProducesInformationalIssue() {
    CommunicationRequest crA = documentationRequest("cr-a", 1, "qA");
    pendedClaimResponse(crA);
    questionnaire("qA", "http://example.org/Questionnaire/A");
    questionnaire("qZ", "http://example.org/Questionnaire/Z");

    OperationOutcome outcome = submit(
        questionnaireResponse("http://example.org/Questionnaire/Z"), null, false);

    boolean hasInformationalMismatch = outcome.getIssue().stream().anyMatch(issue ->
        issue.getSeverity() == OperationOutcome.IssueSeverity.INFORMATION
            && issue.getDiagnostics() != null
            && issue.getDiagnostics().toLowerCase().contains("does not match"));
    assertTrue(hasInformationalMismatch, "expected an informational canonical-mismatch issue");
    verify(qrDao).create(any(QuestionnaireResponse.class), any(RequestDetails.class));
    assertEquals(CommunicationRequestStatus.ACTIVE, crA.getStatus());
  }

  private OperationOutcome submitValidPreauth() {
    return service.submit(
        new Identifier().setSystem("http://example.org/acn").setValue("ACN-1"),
        new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null,
        new Identifier().setValue("1407071236"),
        null,
        new Identifier().setValue("M123"),
        null,
        List.of(attachmentWith(new DocumentReference())),
        null);
  }

  @Test
  void storesAttachmentAndReturnsInformationalOutcome() {
    OperationOutcome outcome = submitValidPreauth();

    verify(docRefDao).create(any(DocumentReference.class), any(RequestDetails.class));
    assertEquals(OperationOutcome.IssueSeverity.INFORMATION,
        outcome.getIssueFirstRep().getSeverity());
  }

  @Test
  void resolvesMatchingPendedClaimResponse() {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("ClaimResponse/cr-1");
    when(crDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(cr));

    submitValidPreauth();

    verify(resolutionService).resolveNow("cr-1");
  }

  @Test
  void reportsAssociationWhenAuthorizationResolved() {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("ClaimResponse/cr-1");
    when(crDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(cr));
    when(resolutionService.resolveNow("cr-1")).thenReturn(true);

    OperationOutcome outcome = submitValidPreauth();

    assertTrue(outcome.getIssueFirstRep().getDiagnostics().toLowerCase().contains("associated"));
  }

  @Test
  void recordsIdempotentlyWhenAuthorizationAlreadyDecided() {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("ClaimResponse/cr-1");
    when(crDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(cr));
    when(resolutionService.resolveNow("cr-1")).thenReturn(false);

    OperationOutcome outcome = assertDoesNotThrow(this::submitValidPreauth);

    verify(docRefDao).create(any(DocumentReference.class), any(RequestDetails.class));
    assertEquals(OperationOutcome.IssueSeverity.INFORMATION,
        outcome.getIssueFirstRep().getSeverity());
    assertTrue(outcome.getIssueFirstRep().getDiagnostics().toLowerCase()
        .contains("already been decided"));
  }

  @Test
  void heldForFutureWhenNoMatchingClaimResponse() {
    OperationOutcome outcome = submitValidPreauth();

    verify(resolutionService, never()).resolveNow(anyString());
    assertTrue(outcome.getIssueFirstRep().getDiagnostics().toLowerCase().contains("held for future"));
  }

  @Test
  void rejectsMissingTrackingId() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier(), new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsInvalidAttachTo() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), new CodeType("invalid"),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsMissingMemberId() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, new Identifier().setValue("org"), null, new Identifier(),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsMissingOrganizationAndProvider() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, null, null, new Identifier().setValue("M123"),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsClaimAttachmentWithoutServiceDate() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), new CodeType(CdexConstants.CLAIM_USE_CLAIM),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void acceptsClaimAttachmentWithServiceDate() {
    assertDoesNotThrow(() -> service.submit(
        new Identifier().setValue("ACN-1"), new CodeType(CdexConstants.CLAIM_USE_CLAIM),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        new DateTimeType("2026-06-16"), List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsAttachmentWithoutContent() {
    ParametersParameterComponent empty = new ParametersParameterComponent();
    empty.setName(CdexConstants.PARAM_ATTACHMENT);
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(empty), null));
  }

  @Test
  void rejectsEmptyAttachments() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(), null));
  }
}
