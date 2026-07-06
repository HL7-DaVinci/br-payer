package org.hl7.davinci.pas;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

/**
 * Schedules one-shot pended ClaimResponse resolution tasks and performs a one-time
 * startup recovery check for records left pended across restarts.
 */
@Service
@EnableConfigurationProperties(PasProperties.class)
public class PasPendedResolutionService {

  private static final Logger log = LoggerFactory.getLogger(PasPendedResolutionService.class);

  private final DaoRegistry daoRegistry;
  private final PasResponseBuilder responseBuilder;
  private final PasSubscriptionNotificationService notificationService;
  private final PasProperties pasProperties;
  private final TaskScheduler taskScheduler;
  private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

  public PasPendedResolutionService(DaoRegistry daoRegistry, PasResponseBuilder responseBuilder,
      PasSubscriptionNotificationService notificationService, PasProperties pasProperties,
      TaskScheduler taskScheduler) {
    this.daoRegistry = daoRegistry;
    this.responseBuilder = responseBuilder;
    this.notificationService = notificationService;
    this.pasProperties = pasProperties;
    this.taskScheduler = taskScheduler;
  }

  /**
   * Schedules a resolution task for the given ClaimResponse ID.
   * If a task is already scheduled for this ID, it is cancelled and replaced.
   */
  public void scheduleResolution(String crId) {
    cancelResolution(crId);

    Instant runAt = Instant.now().plusSeconds(pasProperties.pendedResolutionDelaySeconds());
    ScheduledFuture<?> future = taskScheduler.schedule(
        () -> executeResolution(crId), runAt);
    if (future == null) {
      log.warn("Task scheduler returned null future for ClaimResponse/{}", crId);
      return;
    }
    scheduledTasks.put(crId, future);

    log.debug("Scheduled pended resolution for ClaimResponse/{} at {}", crId, runAt);
  }

  /**
   * Cancels any pending resolution task for the given ClaimResponse ID.
   */
  public void cancelResolution(String crId) {
    ScheduledFuture<?> existing = scheduledTasks.remove(crId);
    if (existing != null) {
      existing.cancel(false);
      log.debug("Cancelled pending resolution for ClaimResponse/{}", crId);
    }
  }

  /**
   * Resolves a pended ClaimResponse immediately, cancelling any scheduled auto-resolution.
   * Returns true if the authorization was resolved by this call, or false if it was already
   * resolved or no longer exists.
   */
  public boolean resolveNow(String crId) {
    cancelResolution(crId);
    return executeResolution(crId);
  }

  /**
   * Runs once on startup to recover any pended ClaimResponses left behind by a prior shutdown.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverPendingResolutionsOnStartup() {
    SearchParameterMap params = new SearchParameterMap();
    params.add(Constants.PARAM_TAG,
        new TokenParam(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE));
    params.setLoadSynchronous(true);

    List<ClaimResponse> resources = daoRegistry.getResourceDao(ClaimResponse.class)
        .searchForResources(params, new SystemRequestDetails());

    if (resources.isEmpty()) {
      return;
    }

    Instant cutoff = Instant.now().minusSeconds(pasProperties.pendedResolutionDelaySeconds());
    for (ClaimResponse cr : resources) {
      String crId = cr.getIdElement().getIdPart();
      if (crId == null || crId.isBlank()) {
        continue;
      }

      // A pend that requested documentation resolves on attachment arrival, not the timer; leave it pended.
      if (cr.hasCommunicationRequest()) {
        continue;
      }

      Instant lastUpdated = cr.getMeta().getLastUpdated() == null
          ? null
          : cr.getMeta().getLastUpdated().toInstant();

      if (lastUpdated != null && lastUpdated.isAfter(cutoff)) {
        // Preserve remaining delay for recently-updated records.
        Instant runAt = lastUpdated.plusSeconds(pasProperties.pendedResolutionDelaySeconds());
        if (runAt.isBefore(Instant.now())) {
          runAt = Instant.now();
        }
        ScheduledFuture<?> future = taskScheduler.schedule(() -> executeResolution(crId), runAt);
        if (future != null) {
          scheduledTasks.put(crId, future);
          log.debug("Recovered pending resolution for ClaimResponse/{} at {}", crId, runAt);
        } else {
          log.warn("Task scheduler returned null future for ClaimResponse/{}", crId);
        }
        continue;
      }

      executeResolution(crId);
    }
  }

  /**
   * Executes resolution for a specific ClaimResponse. Reads the CR from the database,
   * guards against already-resolved state, then delegates to {@link #resolveAuthorization}.
   */
  boolean executeResolution(String crId) {
    try {
      ClaimResponse cr = daoRegistry.getResourceDao(ClaimResponse.class)
          .read(new org.hl7.fhir.r4.model.IdType("ClaimResponse/" + crId),
              new SystemRequestDetails());

      // Guard: skip if pended tag was already removed (e.g., by an update/cancel or a prior resolution)
      if (cr.getMeta().getTag(PasConstants.PENDED_TAG_SYSTEM,
          PasConstants.PENDED_TAG_CODE) == null) {
        scheduledTasks.remove(crId);
        log.debug("ClaimResponse/{} no longer pended, skipping resolution", crId);
        return false;
      }

      resolveAuthorization(cr);
      scheduledTasks.remove(crId);
      return true;
    } catch (ResourceNotFoundException e) {
      scheduledTasks.remove(crId);
      log.debug("ClaimResponse/{} no longer exists, skipping resolution", crId);
      return false;
    } catch (RuntimeException e) {
      log.warn("Pended resolution failed for ClaimResponse/{}, retrying in {}s",
          crId, pasProperties.pendedResolutionDelaySeconds(), e);
      scheduleResolution(crId);
      return false;
    }
  }

  void resolveAuthorization(ClaimResponse pendedCr) {
    responseBuilder.resolvePendedItems(pendedCr, pasProperties.authorizationNumberPrefix());

    var crDao = daoRegistry.getResourceDao(ClaimResponse.class);
    crDao.update(pendedCr, new SystemRequestDetails());

    // HAPI JPA treats tags as additive across versions, so the update alone
    // won't remove the pended tag. Use metaDeleteOperation to explicitly remove it.
    Meta tagToRemove = new Meta();
    tagToRemove.addTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE, null);
    crDao.metaDeleteOperation(pendedCr.getIdElement().toUnqualifiedVersionless(),
        tagToRemove, new SystemRequestDetails());

    completeCommunicationRequests(pendedCr);

    String claimResponseId = pendedCr.getIdElement().getIdPart();
    notificationService.dispatchResolvedClaimResponse(claimResponseId);

    log.info("Resolved pended authorization: ClaimResponse/{}", claimResponseId);
  }

  /** Marks the resolved ClaimResponse's documentation CommunicationRequests completed. */
  private void completeCommunicationRequests(ClaimResponse claimResponse) {
    if (claimResponse.getCommunicationRequest().isEmpty()) {
      return;
    }
    var commReqDao = daoRegistry.getResourceDao(CommunicationRequest.class);
    for (Reference ref : claimResponse.getCommunicationRequest()) {
      if (!ref.hasReference()) {
        continue;
      }
      try {
        CommunicationRequest commReq = commReqDao.read(
            new org.hl7.fhir.r4.model.IdType(ref.getReference()), new SystemRequestDetails());
        if (commReq.getStatus() == CommunicationRequest.CommunicationRequestStatus.COMPLETED) {
          continue;
        }
        commReq.setStatus(CommunicationRequest.CommunicationRequestStatus.COMPLETED);
        commReqDao.update(commReq, new SystemRequestDetails());
      } catch (ResourceNotFoundException e) {
        log.debug("CommunicationRequest {} no longer exists, skipping completion", ref.getReference());
      }
    }
  }
}
