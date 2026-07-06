package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.ADJUDICATION_SYSTEM;
import static org.hl7.davinci.common.FhirConstants.REVIEW_CODE_A1;
import static org.hl7.davinci.common.FhirConstants.REVIEW_CODE_A4;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatchRequest;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

class PasSubscriptionNotificationServiceTest {

  private SubscriptionTopicDispatcher topicDispatcher;
  private PasOrgIdentifierFilterMatcher filterMatcher;
  private PasResponseBuilder responseBuilder;
  private DaoRegistry daoRegistry;
  private IFhirResourceDao<ClaimResponse> crDao;
  private PasSubscriptionNotificationService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    topicDispatcher = mock(SubscriptionTopicDispatcher.class);
    filterMatcher = mock(PasOrgIdentifierFilterMatcher.class);
    responseBuilder = mock(PasResponseBuilder.class);
    daoRegistry = mock(DaoRegistry.class);
    crDao = mock(IFhirResourceDao.class);

    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(crDao);

    service = new PasSubscriptionNotificationService(topicDispatcher, filterMatcher,
        responseBuilder, daoRegistry);
  }

  @Test
  void dispatchResolvedClaimResponse_dispatchesFinalPersistedResource() {
    ClaimResponse finalCr = buildClaimResponseWithReviewAction(REVIEW_CODE_A1);
    finalCr.setId("ClaimResponse/cr-test");

    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(finalCr);

    when(crDao.read(any(IdType.class), any())).thenReturn(finalCr);
    when(responseBuilder.buildNotificationResponseBundle(finalCr, daoRegistry))
        .thenReturn(responseBundle);
    when(topicDispatcher.dispatch(any(SubscriptionTopicDispatchRequest.class))).thenReturn(1);

    service.dispatchResolvedClaimResponse("cr-test");

    ArgumentCaptor<SubscriptionTopicDispatchRequest> captor =
        ArgumentCaptor.forClass(SubscriptionTopicDispatchRequest.class);
    verify(topicDispatcher).dispatch(captor.capture());

    SubscriptionTopicDispatchRequest request = captor.getValue();
    assert request.getTopicUrl().equals(PasConstants.PAS_SUBSCRIPTION_TOPIC);
    assert request.getResources().contains(responseBundle);
  }

  @Test
  void dispatchResolvedClaimResponse_skipsWhenFinalResourceStillPended() {
    ClaimResponse finalCr = buildClaimResponseWithReviewAction(REVIEW_CODE_A4);
    when(crDao.read(any(IdType.class), any())).thenReturn(finalCr);

    service.dispatchResolvedClaimResponse("cr-test");

    verifyNoInteractions(responseBuilder);
    verifyNoInteractions(topicDispatcher);
  }

  @Test
  void dispatchResolvedClaimResponse_skipsWhenPendedTagStillPresent() {
    ClaimResponse finalCr = buildClaimResponseWithReviewAction(REVIEW_CODE_A1);
    finalCr.getMeta().addTag(PasConstants.PENDED_TAG_SYSTEM,
        PasConstants.PENDED_TAG_CODE, "Pended Resolution");
    when(crDao.read(any(IdType.class), any())).thenReturn(finalCr);

    service.dispatchResolvedClaimResponse("cr-test");

    verifyNoInteractions(responseBuilder);
    verifyNoInteractions(topicDispatcher);
  }

  @Test
  void dispatchResolvedClaimResponse_skipsWhenClaimResponseMissing() {
    when(crDao.read(any(IdType.class), any()))
        .thenThrow(new ResourceNotFoundException("gone"));

    service.dispatchResolvedClaimResponse("cr-test");

    verifyNoInteractions(responseBuilder);
    verifyNoInteractions(topicDispatcher);
  }

  private ClaimResponse buildClaimResponseWithReviewAction(String reviewCode) {
    ClaimResponse cr = new ClaimResponse();
    ClaimResponse.ItemComponent item = cr.addItem();
    item.setItemSequence(1);

    ClaimResponse.AdjudicationComponent adj = item.addAdjudication();
    adj.setCategory(new CodeableConcept().addCoding(
        new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")));
    adj.addExtension(PasExtensions.buildReviewActionExtension(reviewCode, "Test", null));

    return cr;
  }
}
