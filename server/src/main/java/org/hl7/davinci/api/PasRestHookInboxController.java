package org.hl7.davinci.api;

import java.util.List;
import java.util.Map;

import org.hl7.davinci.pas.PasRestHookInboxService;
import org.hl7.davinci.pas.PasRestHookInboxService.InboxPage;
import org.hl7.davinci.pas.PasRestHookInboxService.StoredNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Receives HAPI REST hook subscription callbacks and exposes a polling
 * inbox for the frontend. Uses a single POST endpoint for all subscriptions,
 * extracting the subscription ID from the notification Bundle payload.
 */
@RestController
@RequestMapping("/api/pas/resthook-inbox")
public class PasRestHookInboxController {

  private static final Logger log = LoggerFactory.getLogger(PasRestHookInboxController.class);
  private static final MediaType FHIR_JSON = MediaType.valueOf("application/fhir+json");

  /** Empty Bundle response matching the resource type HAPI expects back from a transaction POST. */
  private static final String OK_BUNDLE = """
      {"resourceType":"Bundle","type":"transaction-response","entry":[]}""";

  private final PasRestHookInboxService inboxService;
  private final ObjectMapper objectMapper;

  public PasRestHookInboxController(PasRestHookInboxService inboxService, ObjectMapper objectMapper) {
    this.inboxService = inboxService;
    this.objectMapper = objectMapper;
  }

  /**
   * Receives a REST hook notification Bundle from HAPI. Extracts the subscription ID
   * from the SubscriptionStatus Parameters in entry[0] and stores the raw payload.
   */
  @PostMapping(consumes = "application/fhir+json", produces = "application/fhir+json")
  public ResponseEntity<String> receiveNotification(@RequestBody String body) {
    try {
      String subscriptionId = extractSubscriptionId(body);
      if (subscriptionId == null) {
        log.warn("REST hook notification missing subscription ID, discarding");
        return ResponseEntity.ok().contentType(FHIR_JSON).body(OK_BUNDLE);
      }

      inboxService.store(subscriptionId, body);
      log.debug("Stored REST hook notification for Subscription/{}", subscriptionId);
    } catch (Exception e) {
      log.warn("Failed to process REST hook notification", e);
    }

    return ResponseEntity.ok().contentType(FHIR_JSON).body(OK_BUNDLE);
  }

  /** Polls for new notifications by subscription ID and sequence cursor. */
  @GetMapping
  public ResponseEntity<Map<String, Object>> poll(
      @RequestParam("subscriptionId") String subscriptionId,
      @RequestParam(value = "after", defaultValue = "0") long after) {

    InboxPage page = inboxService.retrieve(subscriptionId, after);

    List<Map<String, Object>> notifications = page.notifications().stream()
        .map(this::toNotificationDto)
        .toList();

    return ResponseEntity.ok(Map.of(
        "notifications", notifications,
        "lastSequence", page.lastSequence()));
  }

  /** Clears stored notifications for a subscription. */
  @DeleteMapping
  public ResponseEntity<Void> clear(@RequestParam("subscriptionId") String subscriptionId) {
    inboxService.clear(subscriptionId);
    return ResponseEntity.ok().build();
  }

  /**
   * Extracts the subscription ID from entry[0] SubscriptionStatus Parameters.
   * The subscription reference is in a parameter named "subscription" with valueReference.
   */
  String extractSubscriptionId(String body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode entries = root.path("entry");
      if (!entries.isArray() || entries.isEmpty()) return null;

      JsonNode statusResource = entries.get(0).path("resource");
      if (!"Parameters".equals(statusResource.path("resourceType").asText())) return null;

      JsonNode parameters = statusResource.path("parameter");
      if (!parameters.isArray()) return null;

      for (JsonNode param : parameters) {
        if ("subscription".equals(param.path("name").asText())) {
          String ref = param.path("valueReference").path("reference").asText(null);
          if (ref != null && ref.startsWith("Subscription/")) {
            return ref.substring("Subscription/".length());
          }
          return ref;
        }
      }
    } catch (Exception e) {
      log.debug("Failed to parse subscription ID from notification", e);
    }
    return null;
  }

  private Map<String, Object> toNotificationDto(StoredNotification n) {
    Object payload;
    try {
      payload = objectMapper.readTree(n.payload());
    } catch (Exception e) {
      payload = n.payload();
    }

    return Map.of(
        "sequence", n.sequence(),
        "receivedAt", n.receivedAt().toString(),
        "payload", payload);
  }
}
