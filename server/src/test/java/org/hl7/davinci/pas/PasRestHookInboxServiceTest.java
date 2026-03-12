package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.hl7.davinci.pas.PasRestHookInboxService.InboxPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasRestHookInboxServiceTest {

  private PasRestHookInboxService service;

  @BeforeEach
  void setUp() {
    PasProperties props = new PasProperties(30, "AUTH-", 5);
    service = new PasRestHookInboxService(props);
  }

  @Test
  void store_andRetrieve_returnsStoredNotification() {
    service.store("sub-1", "{\"resourceType\":\"Bundle\"}");

    InboxPage page = service.retrieve("sub-1", 0);
    assertEquals(1, page.notifications().size());
    assertEquals(1, page.lastSequence());
    assertEquals("{\"resourceType\":\"Bundle\"}", page.notifications().get(0).payload());
  }

  @Test
  void retrieve_withAfterCursor_filtersOlderEntries() {
    service.store("sub-1", "payload-1");
    service.store("sub-1", "payload-2");
    service.store("sub-1", "payload-3");

    InboxPage page = service.retrieve("sub-1", 2);
    assertEquals(1, page.notifications().size());
    assertEquals("payload-3", page.notifications().get(0).payload());
    assertEquals(3, page.lastSequence());
  }

  @Test
  void retrieve_unknownSubscription_returnsEmptyPage() {
    InboxPage page = service.retrieve("unknown", 0);
    assertTrue(page.notifications().isEmpty());
    assertEquals(0, page.lastSequence());
  }

  @Test
  void store_evictsOldestWhenOverCapacity() {
    for (int i = 1; i <= 7; i++) {
      service.store("sub-1", "payload-" + i);
    }

    InboxPage page = service.retrieve("sub-1", 0);
    assertEquals(5, page.notifications().size());
    // Oldest two (payload-1, payload-2) should be evicted
    assertEquals("payload-3", page.notifications().get(0).payload());
    assertEquals("payload-7", page.notifications().get(4).payload());
    assertEquals(7, page.lastSequence());
  }

  @Test
  void clear_removesAllNotifications() {
    service.store("sub-1", "payload-1");
    service.store("sub-1", "payload-2");

    service.clear("sub-1");

    InboxPage page = service.retrieve("sub-1", 0);
    assertTrue(page.notifications().isEmpty());
  }

  @Test
  void store_isolatesSubscriptions() {
    service.store("sub-1", "payload-a");
    service.store("sub-2", "payload-b");

    InboxPage page1 = service.retrieve("sub-1", 0);
    InboxPage page2 = service.retrieve("sub-2", 0);

    assertEquals(1, page1.notifications().size());
    assertEquals("payload-a", page1.notifications().get(0).payload());
    assertEquals(1, page2.notifications().size());
    assertEquals("payload-b", page2.notifications().get(0).payload());
  }

  @Test
  void sequenceNumbers_incrementMonotonically() {
    service.store("sub-1", "a");
    service.store("sub-1", "b");
    service.store("sub-1", "c");

    InboxPage page = service.retrieve("sub-1", 0);
    long prevSeq = 0;
    for (var n : page.notifications()) {
      assertTrue(n.sequence() > prevSeq);
      prevSeq = n.sequence();
    }
  }

  @Test
  void concurrentStores_maintainCapacityLimit() throws InterruptedException {
    int threads = 4;
    int storesPerThread = 20;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);
    List<Throwable> errors = new ArrayList<>();

    for (int t = 0; t < threads; t++) {
      final int threadId = t;
      executor.submit(() -> {
        try {
          for (int i = 0; i < storesPerThread; i++) {
            service.store("sub-1", "t" + threadId + "-" + i);
          }
        } catch (Throwable e) {
          synchronized (errors) {
            errors.add(e);
          }
        } finally {
          latch.countDown();
        }
      });
    }

    latch.await();
    executor.shutdown();
    assertTrue(errors.isEmpty(), "Concurrent store errors: " + errors);

    InboxPage page = service.retrieve("sub-1", 0);
    assertTrue(page.notifications().size() <= 5,
        "Inbox should not exceed max size, got " + page.notifications().size());
  }
}
