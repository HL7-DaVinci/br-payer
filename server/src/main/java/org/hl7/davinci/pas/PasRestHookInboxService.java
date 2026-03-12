package org.hl7.davinci.pas;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * In-memory inbox for REST hook subscription notifications.
 * HAPI POSTs notification Bundles to the inbox controller, which stores them here
 * keyed by subscription ID. The frontend polls to retrieve new entries.
 */
@Service
@EnableConfigurationProperties(PasProperties.class)
public class PasRestHookInboxService {

  private final int maxSize;
  private final ConcurrentHashMap<String, InboxEntry> inboxes = new ConcurrentHashMap<>();

  public PasRestHookInboxService(PasProperties pasProperties) {
    this.maxSize = pasProperties.resthookInboxMaxSize();
  }

  public void store(String subscriptionId, String payload) {
    InboxEntry entry = inboxes.computeIfAbsent(subscriptionId, k -> new InboxEntry());
    synchronized (entry) {
      long seq = entry.sequenceCounter.incrementAndGet();
      entry.notifications.add(new StoredNotification(seq, Instant.now(), payload));

      // FIFO eviction when over capacity
      while (entry.notifications.size() > maxSize) {
        entry.notifications.remove(0);
      }
    }
  }

  public InboxPage retrieve(String subscriptionId, long afterSequence) {
    InboxEntry entry = inboxes.get(subscriptionId);
    if (entry == null) {
      return new InboxPage(Collections.emptyList(), 0);
    }

    synchronized (entry) {
      List<StoredNotification> result = new ArrayList<>();
      long lastSeq = afterSequence;

      for (StoredNotification n : entry.notifications) {
        if (n.sequence() > afterSequence) {
          result.add(n);
          lastSeq = Math.max(lastSeq, n.sequence());
        }
      }

      return new InboxPage(result, lastSeq);
    }
  }

  public void clear(String subscriptionId) {
    inboxes.remove(subscriptionId);
  }

  // ---- Inner types ----

  static class InboxEntry {
    final AtomicLong sequenceCounter = new AtomicLong(0);
    final List<StoredNotification> notifications = new ArrayList<>();
  }

  public record StoredNotification(long sequence, Instant receivedAt, String payload) {}

  public record InboxPage(List<StoredNotification> notifications, long lastSequence) {}
}
