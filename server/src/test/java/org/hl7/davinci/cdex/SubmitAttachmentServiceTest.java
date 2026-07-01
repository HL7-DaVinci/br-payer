package org.hl7.davinci.cdex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.hl7.davinci.pas.PasPendedResolutionService;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.RequestDetails;

class SubmitAttachmentServiceTest {

  private DaoRegistry daoRegistry;
  private PasPendedResolutionService resolutionService;
  private IFhirResourceDao<ClaimResponse> crDao;
  private IFhirResourceDao<DocumentReference> docRefDao;
  private SubmitAttachmentService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    daoRegistry = mock(DaoRegistry.class);
    resolutionService = mock(PasPendedResolutionService.class);

    crDao = mock(IFhirResourceDao.class);
    when(crDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of());
    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(crDao);

    docRefDao = mock(IFhirResourceDao.class);
    when(docRefDao.create(any(), any(RequestDetails.class))).thenReturn(new DaoMethodOutcome());
    when(daoRegistry.getResourceDao("DocumentReference")).thenReturn((IFhirResourceDao) docRefDao);

    service = new SubmitAttachmentService(daoRegistry, resolutionService, FhirContext.forR4());
  }

  private static ParametersParameterComponent attachmentWith(Resource content) {
    ParametersParameterComponent attachment = new ParametersParameterComponent();
    attachment.setName(CdexConstants.PARAM_ATTACHMENT);
    attachment.addPart().setName(CdexConstants.PARAM_ATTACHMENT_CONTENT).setResource(content);
    return attachment;
  }

  private OperationOutcome submitValidPreauth() {
    return service.submit(
        new Identifier().setSystem("http://example.org/acn").setValue("ACN-1"),
        null,
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
        new Identifier(), null, new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsInvalidAttachTo() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), null, new CodeType("invalid"),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsMissingMemberId() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), null, new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, new Identifier().setValue("org"), null, new Identifier(),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsMissingOrganizationAndProvider() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), null, new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, null, null, new Identifier().setValue("M123"),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsClaimAttachmentWithoutServiceDate() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), null, new CodeType(CdexConstants.CLAIM_USE_CLAIM),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void acceptsClaimAttachmentWithServiceDate() {
    assertDoesNotThrow(() -> service.submit(
        new Identifier().setValue("ACN-1"), null, new CodeType(CdexConstants.CLAIM_USE_CLAIM),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        new DateTimeType("2026-06-16"), List.of(attachmentWith(new DocumentReference())), null));
  }

  @Test
  void rejectsAttachmentWithoutContent() {
    ParametersParameterComponent empty = new ParametersParameterComponent();
    empty.setName(CdexConstants.PARAM_ATTACHMENT);
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), null, new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(empty), null));
  }

  @Test
  void rejectsEmptyAttachments() {
    assertThrows(IllegalArgumentException.class, () -> service.submit(
        new Identifier().setValue("ACN-1"), null, new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION),
        null, new Identifier().setValue("org"), null, new Identifier().setValue("M123"),
        null, List.of(), null));
  }
}
