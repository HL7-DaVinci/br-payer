package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.REVIEW_CODE_A4;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.IdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatchRequest;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

/**
 * Dispatches PAS subscription notifications from the final persisted ClaimResponse state.
 */
@Service
public class PasSubscriptionNotificationService {

  private static final Logger log = LoggerFactory.getLogger(PasSubscriptionNotificationService.class);

  // A subscription created moments before a resolution (fresh server, first
  // submit) is still activating asynchronously and may miss the dispatch, so a
  // zero-subscriber dispatch is retried on these delays before giving up.
  private static final long[] RETRY_DELAYS_MILLIS = {2_000, 5_000, 15_000, 30_000};

  private final SubscriptionTopicDispatcher topicDispatcher;
  private final PasOrgIdentifierFilterMatcher filterMatcher;
  private final PasResponseBuilder responseBuilder;
  private final DaoRegistry daoRegistry;
  private final ScheduledExecutorService retryExecutor;

  public PasSubscriptionNotificationService(SubscriptionTopicDispatcher topicDispatcher,
      PasOrgIdentifierFilterMatcher filterMatcher, PasResponseBuilder responseBuilder,
      DaoRegistry daoRegistry) {
    this.topicDispatcher = topicDispatcher;
    this.filterMatcher = filterMatcher;
    this.responseBuilder = responseBuilder;
    this.daoRegistry = daoRegistry;
    this.retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "pas-subscription-notification-retry");
      thread.setDaemon(true);
      return thread;
    });
  }

  @PreDestroy
  void shutdown() {
    retryExecutor.shutdownNow();
  }

  public void dispatchResolvedClaimResponse(String claimResponseId) {
    dispatchResolvedClaimResponse(claimResponseId, 0);
  }

  private void dispatchResolvedClaimResponse(String claimResponseId, int attempt) {
    if (claimResponseId == null || claimResponseId.isBlank()) {
      return;
    }

    try {
      ClaimResponse finalCr = daoRegistry.getResourceDao(ClaimResponse.class)
          .read(new IdType("ClaimResponse/" + claimResponseId), new SystemRequestDetails());

      if (hasPendedItems(finalCr)) {
        log.debug("ClaimResponse/{} still has pended items, skipping PAS subscription dispatch",
            claimResponseId);
        return;
      }

      if (finalCr.getMeta().getTag(PasConstants.PENDED_TAG_SYSTEM,
          PasConstants.PENDED_TAG_CODE) != null) {
        log.debug("ClaimResponse/{} still has the pended-resolution tag, skipping PAS subscription dispatch",
            claimResponseId);
        return;
      }

      Bundle responseBundle = responseBuilder.buildNotificationResponseBundle(finalCr, daoRegistry);
      SubscriptionTopicDispatchRequest request = new SubscriptionTopicDispatchRequest(
          PasConstants.PAS_SUBSCRIPTION_TOPIC,
          List.of(responseBundle),
          filterMatcher,
          RestOperationTypeEnum.UPDATE,
          null,
          null,
          null);

      int dispatched = topicDispatcher.dispatch(request);
      log.info("Dispatched {} PAS subscription notification(s) for ClaimResponse/{}",
          dispatched, claimResponseId);
      if (dispatched == 0 && attempt < RETRY_DELAYS_MILLIS.length) {
        long delay = RETRY_DELAYS_MILLIS[attempt];
        log.info("No subscribers matched for ClaimResponse/{}. Retrying dispatch in {} ms",
            claimResponseId, delay);
        retryExecutor.schedule(() -> dispatchResolvedClaimResponse(claimResponseId, attempt + 1),
            delay, TimeUnit.MILLISECONDS);
      }
    } catch (ResourceNotFoundException e) {
      log.debug("ClaimResponse/{} no longer exists, skipping PAS subscription dispatch", claimResponseId);
    } catch (RuntimeException e) {
      log.warn("Failed to dispatch PAS subscription notification for ClaimResponse/{}",
          claimResponseId, e);
    }
  }

  private boolean hasPendedItems(ClaimResponse claimResponse) {
    return claimResponse.getItem().stream()
        .map(PasExtensions::extractReviewActionCode)
        .anyMatch(REVIEW_CODE_A4::equals);
  }
}
