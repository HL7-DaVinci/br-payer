package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Date;
import java.util.List;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

class PasPendedResolutionServiceTest {

  private DaoRegistry daoRegistry;
  private PasResponseBuilder responseBuilder;
  private PasSubscriptionNotificationService notificationService;
  private PasProperties pasProperties;
  private TaskScheduler taskScheduler;
  private PasPendedResolutionService service;
  private IFhirResourceDao<ClaimResponse> crDao;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    daoRegistry = mock(DaoRegistry.class);
    responseBuilder = mock(PasResponseBuilder.class);
    notificationService = mock(PasSubscriptionNotificationService.class);
    pasProperties = new PasProperties(15, "AUTH-", 100);
    taskScheduler = mock(TaskScheduler.class);

    crDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(crDao);

    service = new PasPendedResolutionService(daoRegistry, responseBuilder,
        notificationService, pasProperties, taskScheduler);
  }

  @Test
  void scheduleResolution_schedulesTaskWithCorrectDelay() {
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

    service.scheduleResolution("cr-1");

    ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(taskScheduler).schedule(any(Runnable.class), instantCaptor.capture());

    Instant scheduledAt = instantCaptor.getValue();
    Instant expectedMin = Instant.now().plusSeconds(14);
    Instant expectedMax = Instant.now().plusSeconds(16);
    assertTrue(scheduledAt.isAfter(expectedMin) && scheduledAt.isBefore(expectedMax),
        "Scheduled time should be ~15 seconds from now");
  }

  @Test
  void scheduleResolution_replacesExistingSchedule() {
    ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);
    doReturn(firstFuture).doReturn(secondFuture)
        .when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

    service.scheduleResolution("cr-1");
    service.scheduleResolution("cr-1");

    verify(firstFuture).cancel(false);
    verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  void cancelResolution_cancelsFuture() {
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

    service.scheduleResolution("cr-1");
    service.cancelResolution("cr-1");

    verify(future).cancel(false);
  }

  @Test
  void cancelResolution_noopWhenNothingScheduled() {
    service.cancelResolution("nonexistent");
    verifyNoInteractions(taskScheduler);
  }

  @Test
  void executeResolution_resolvesCrAndRemovesTag() {
    ClaimResponse pended = buildPendedClaimResponse("cr-1");
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(pended);

    service.executeResolution("cr-1");

    verify(responseBuilder).resolvePendedItems(eq(pended), eq("AUTH-"));
    verify(crDao).update(eq(pended), any(RequestDetails.class));
    verify(crDao).metaDeleteOperation(argThat(id -> "cr-1".equals(id.getIdPart())),
        argThat(meta -> {
          Meta m = (Meta) meta;
          return m.getTag().stream().anyMatch(t ->
              PasSubmitService.PENDED_TAG_SYSTEM.equals(t.getSystem())
                  && PasSubmitService.PENDED_TAG_CODE.equals(t.getCode()));
        }), any(RequestDetails.class));
    verify(notificationService).dispatchResolvedClaimResponse("cr-1");
  }

  @Test
  void resolveNow_returnsTrueWhenAuthorizationResolved() {
    ClaimResponse pended = buildPendedClaimResponse("cr-true");
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(pended);

    assertTrue(service.resolveNow("cr-true"));
  }

  @Test
  void resolveNow_returnsFalseWhenAlreadyResolved() {
    ClaimResponse resolved = new ClaimResponse();
    resolved.setId("cr-false");
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(resolved);

    assertFalse(service.resolveNow("cr-false"));
    verifyNoInteractions(responseBuilder);
  }

  @Test
  void recoverPendingResolutionsOnStartup_skipsDocumentationRequiringPend() {
    ClaimResponse docPend = buildPendedClaimResponse("cr-docs");
    docPend.addCommunicationRequest(new Reference("urn:uuid:doc-request"));
    docPend.getMeta().setLastUpdated(new Date(System.currentTimeMillis() - 60_000));

    when(crDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of(docPend));

    service.recoverPendingResolutionsOnStartup();

    verifyNoInteractions(responseBuilder);
    verifyNoInteractions(notificationService);
    verify(crDao, never()).update(any(), any(RequestDetails.class));
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  void executeResolution_skipsWhenPendedTagAlreadyRemoved() {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("cr-2");
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(cr);

    service.executeResolution("cr-2");

    verifyNoInteractions(responseBuilder);
    verify(crDao, never()).update(any(), any(RequestDetails.class));
  }

  @Test
  void executeResolution_skipsWhenCrNotFound() {
    when(crDao.read(any(IdType.class), any(RequestDetails.class)))
        .thenThrow(new ResourceNotFoundException("Not found"));

    service.executeResolution("cr-gone");

    verifyNoInteractions(responseBuilder);
    verify(crDao, never()).update(any(), any(RequestDetails.class));
  }

  @Test
  void executeResolution_retriesWhenResolutionFails() {
    ClaimResponse pended = buildPendedClaimResponse("cr-retry");
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(pended);
    doThrow(new IllegalStateException("transient write error"))
        .when(crDao).update(eq(pended), any(RequestDetails.class));

    ScheduledFuture<?> retryFuture = mock(ScheduledFuture.class);
    doReturn(retryFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

    service.executeResolution("cr-retry");

    verify(responseBuilder).resolvePendedItems(eq(pended), eq("AUTH-"));
    verify(crDao).update(eq(pended), any(RequestDetails.class));
    verifyNoInteractions(notificationService);
    verify(crDao, never()).metaDeleteOperation(any(), any(Meta.class), any(RequestDetails.class));

    ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(taskScheduler).schedule(any(Runnable.class), instantCaptor.capture());
    Instant retryAt = instantCaptor.getValue();
    Instant expectedMin = Instant.now().plusSeconds(14);
    Instant expectedMax = Instant.now().plusSeconds(16);
    assertTrue(retryAt.isAfter(expectedMin) && retryAt.isBefore(expectedMax),
        "Retry time should be ~15 seconds from now");
  }

  @Test
  void scheduleResolution_capturedRunnableTriggersExecution() {
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

    ClaimResponse pended = buildPendedClaimResponse("cr-exec");
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(pended);

    service.scheduleResolution("cr-exec");

    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

    runnableCaptor.getValue().run();

    verify(responseBuilder).resolvePendedItems(eq(pended), eq("AUTH-"));
    verify(crDao).update(eq(pended), any(RequestDetails.class));
    verify(crDao).metaDeleteOperation(argThat(id -> "cr-exec".equals(id.getIdPart())),
        any(Meta.class), any(RequestDetails.class));
    verify(notificationService).dispatchResolvedClaimResponse("cr-exec");
  }

  @Test
  void recoverPendingResolutionsOnStartup_resolvesClaimResponseAfterDelayExpires() {
    ClaimResponse stale = buildPendedClaimResponse("cr-stale");
    stale.getMeta().setLastUpdated(new Date(System.currentTimeMillis() - 60_000));

    when(crDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of(stale));
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(stale);

    service.recoverPendingResolutionsOnStartup();

    verify(responseBuilder).resolvePendedItems(eq(stale), eq("AUTH-"));
    verify(crDao).update(eq(stale), any(RequestDetails.class));
    verify(notificationService).dispatchResolvedClaimResponse("cr-stale");
  }

  @Test
  void recoverPendingResolutionsOnStartup_recoversMissingScheduleForRecentClaimResponse() {
    ClaimResponse recent = buildPendedClaimResponse("cr-recent");
    recent.getMeta().setLastUpdated(new Date(System.currentTimeMillis() - 5_000));

    when(crDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of(recent));

    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

    service.recoverPendingResolutionsOnStartup();

    ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(taskScheduler).schedule(any(Runnable.class), instantCaptor.capture());
    Instant scheduledAt = instantCaptor.getValue();
    Instant expected = recent.getMeta().getLastUpdated().toInstant().plusSeconds(15);
    assertTrue(!scheduledAt.isBefore(expected.minusSeconds(1))
        && !scheduledAt.isAfter(expected.plusSeconds(1)),
        "Recovered schedule should preserve the original delay window");

    verify(crDao, never()).update(any(), any(RequestDetails.class));
  }

  @Test
  void recoverPendingResolutionsOnStartup_usesSynchronousSearchForResources() {
    ClaimResponse stale = buildPendedClaimResponse("cr-null-size");
    stale.getMeta().setLastUpdated(new Date(System.currentTimeMillis() - 60_000));

    when(crDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of(stale));
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(stale);

    service.recoverPendingResolutionsOnStartup();

    verify(crDao).searchForResources(argThat(map -> map.isLoadSynchronous()),
        any(RequestDetails.class));
    verify(responseBuilder).resolvePendedItems(eq(stale), eq("AUTH-"));
    verify(crDao).update(eq(stale), any(RequestDetails.class));
    verify(notificationService).dispatchResolvedClaimResponse("cr-null-size");
  }

  @Test
  void recoverPendingResolutionsOnStartup_noopWhenNoPendedClaimResponses() {
    when(crDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of());

    service.recoverPendingResolutionsOnStartup();

    verifyNoInteractions(responseBuilder);
    verify(crDao, never()).update(any(), any(RequestDetails.class));
  }

  @Test
  void resolveAuthorization_updatesAndRemovesTag() {
    ClaimResponse pended = buildPendedClaimResponse("cr-auth");

    service.resolveAuthorization(pended);

    verify(responseBuilder).resolvePendedItems(eq(pended), eq("AUTH-"));
    verify(crDao).update(eq(pended), any(RequestDetails.class));
    verify(crDao).metaDeleteOperation(argThat(id -> "cr-auth".equals(id.getIdPart())),
        any(Meta.class), any(RequestDetails.class));
    verify(notificationService).dispatchResolvedClaimResponse("cr-auth");
  }

  @Test
  void resolveNow_completesAssociatedCommunicationRequests() {
    ClaimResponse pended = buildPendedClaimResponse("cr-docs");
    pended.addCommunicationRequest(new Reference("CommunicationRequest/comm-1"));
    when(crDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(pended);

    CommunicationRequest commReq = new CommunicationRequest();
    commReq.setId("comm-1");
    commReq.setStatus(CommunicationRequest.CommunicationRequestStatus.ACTIVE);
    @SuppressWarnings("unchecked")
    IFhirResourceDao<CommunicationRequest> commReqDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(CommunicationRequest.class)).thenReturn(commReqDao);
    when(commReqDao.read(any(IdType.class), any(RequestDetails.class))).thenReturn(commReq);

    service.resolveNow("cr-docs");

    verify(commReqDao).update(argThat(c ->
        c.getStatus() == CommunicationRequest.CommunicationRequestStatus.COMPLETED),
        any(RequestDetails.class));
  }

  private ClaimResponse buildPendedClaimResponse(String id) {
    ClaimResponse cr = new ClaimResponse();
    cr.setId(id);
    cr.getMeta().addTag(PasSubmitService.PENDED_TAG_SYSTEM,
        PasSubmitService.PENDED_TAG_CODE, "Pended Resolution");
    return cr;
  }
}
