package org.hl7.davinci.pas;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Date;
import java.util.List;

import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Meta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;

class PasPendedResolutionSchedulerTest {

  private DaoRegistry daoRegistry;
  private PasResponseBuilder responseBuilder;
  private PasProperties pasProperties;
  private PasPendedResolutionScheduler scheduler;
  private IFhirResourceDao<ClaimResponse> crDao;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    daoRegistry = mock(DaoRegistry.class);
    responseBuilder = mock(PasResponseBuilder.class);
    pasProperties = new PasProperties(30, "AUTH-");

    crDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(crDao);

    scheduler = new PasPendedResolutionScheduler(daoRegistry, responseBuilder, pasProperties);
  }

  @Test
  void resolvesPendedClaimResponse_afterDelayExpires() {
    ClaimResponse pended = buildPendedClaimResponse("cr-1");
    pended.getMeta().setLastUpdated(new Date(System.currentTimeMillis() - 60_000));

    IBundleProvider searchResults = mock(IBundleProvider.class);
    when(searchResults.size()).thenReturn(1);
    when(searchResults.getResources(0, 1)).thenReturn(List.of(pended));
    when(crDao.search(any(), any(RequestDetails.class))).thenReturn(searchResults);

    scheduler.resolvePendedAuthorizations();

    verify(responseBuilder).resolvePendedItems(eq(pended), eq("AUTH-"));
    verify(crDao).update(eq(pended), any(RequestDetails.class));
  }

  @Test
  void skipsClaimResponse_beforeDelayExpires() {
    ClaimResponse recent = buildPendedClaimResponse("cr-2");
    recent.getMeta().setLastUpdated(new Date());

    IBundleProvider searchResults = mock(IBundleProvider.class);
    when(searchResults.size()).thenReturn(1);
    when(searchResults.getResources(0, 1)).thenReturn(List.of(recent));
    when(crDao.search(any(), any(RequestDetails.class))).thenReturn(searchResults);

    scheduler.resolvePendedAuthorizations();

    verify(crDao, never()).update(any(), any(RequestDetails.class));
    verifyNoInteractions(responseBuilder);
  }

  @Test
  void resolvesPendedClaimResponse_whenSearchSizeIsUnknown() {
    ClaimResponse pended = buildPendedClaimResponse("cr-null-size");
    pended.getMeta().setLastUpdated(new Date(System.currentTimeMillis() - 60_000));

    IBundleProvider searchResults = mock(IBundleProvider.class);
    when(searchResults.size()).thenReturn(null);
    when(searchResults.getResources(anyInt(), anyInt()))
        .thenReturn(List.of(pended))
        .thenReturn(List.of());
    when(crDao.search(any(), any(RequestDetails.class))).thenReturn(searchResults);

    scheduler.resolvePendedAuthorizations();

    verify(responseBuilder).resolvePendedItems(eq(pended), eq("AUTH-"));
    verify(crDao).update(eq(pended), any(RequestDetails.class));
  }

  @Test
  void removesPendedTag_viaMetaDeleteOperation() {
    ClaimResponse pended = buildPendedClaimResponse("cr-3");
    pended.getMeta().setLastUpdated(new Date(System.currentTimeMillis() - 60_000));

    IBundleProvider searchResults = mock(IBundleProvider.class);
    when(searchResults.size()).thenReturn(1);
    when(searchResults.getResources(0, 1)).thenReturn(List.of(pended));
    when(crDao.search(any(), any(RequestDetails.class))).thenReturn(searchResults);

    scheduler.resolvePendedAuthorizations();

    // HAPI JPA tags are additive, so metaDeleteOperation must be called to actually remove the tag
    verify(crDao).metaDeleteOperation(argThat(id -> "cr-3".equals(id.getIdPart())),
        argThat(meta -> {
          Meta m = (Meta) meta;
          return m.getTag().stream().anyMatch(t ->
              PasSubmitService.PENDED_TAG_SYSTEM.equals(t.getSystem())
                  && PasSubmitService.PENDED_TAG_CODE.equals(t.getCode()));
        }), any(RequestDetails.class));
  }

  @Test
  void noop_whenNoPendedClaimResponses() {
    IBundleProvider searchResults = mock(IBundleProvider.class);
    when(searchResults.size()).thenReturn(0);
    when(searchResults.getResources(0, 0)).thenReturn(List.of());
    when(crDao.search(any(), any(RequestDetails.class))).thenReturn(searchResults);

    scheduler.resolvePendedAuthorizations();

    verifyNoInteractions(responseBuilder);
    verify(crDao, never()).update(any(), any(RequestDetails.class));
  }

  private ClaimResponse buildPendedClaimResponse(String id) {
    ClaimResponse cr = new ClaimResponse();
    cr.setId(id);
    cr.getMeta().addTag(PasSubmitService.PENDED_TAG_SYSTEM,
        PasSubmitService.PENDED_TAG_CODE, "Pended Resolution");
    return cr;
  }
}
