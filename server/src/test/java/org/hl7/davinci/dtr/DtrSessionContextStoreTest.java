package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DtrSessionContextStoreTest {

  private InMemoryDtrSessionContextStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryDtrSessionContextStore(new DtrAdaptiveProperties("", 60));
  }

  private DtrSessionContextStore.SessionContext createContext(String qrId) {
    Instant now = Instant.now();
    return new DtrSessionContextStore.SessionContext(
        qrId,
        "http://example.org/Questionnaire/test|1.0",
        Map.of("http://example.org/Library/test", "1.0"),
        Map.of("http://example.org/ValueSet/test", "1.0"),
        "Coverage/cov-1",
        List.of("DeviceRequest/dr-1"),
        "127.0.0.1",
        1L,
        now,
        now.plusSeconds(3600));
  }

  private DtrSessionContextStore.SessionContext createExpiredContext(String qrId) {
    Instant past = Instant.now().minusSeconds(7200);
    return new DtrSessionContextStore.SessionContext(
        qrId,
        "http://example.org/Questionnaire/test|1.0",
        Map.of(),
        Map.of(),
        "Coverage/cov-1",
        List.of(),
        "",
        1L,
        past,
        past.plusSeconds(3600)); // Expired an hour ago
  }

  @Nested
  @DisplayName("Basic CRUD Operations")
  class CrudTests {

    @Test
    @DisplayName("Store and retrieve by QR ID")
    void saveAndGet() {
      var context = createContext("qr-1");
      store.save("qr-1", context);

      Optional<DtrSessionContextStore.SessionContext> result = store.get("qr-1");
      assertTrue(result.isPresent());
      assertEquals("qr-1", result.get().questionnaireResponseId());
      assertEquals("http://example.org/Questionnaire/test|1.0", result.get().questionnaireCanonical());
    }

    @Test
    @DisplayName("Retrieve returns empty for unknown ID")
    void getUnknown() {
      Optional<DtrSessionContextStore.SessionContext> result = store.get("nonexistent");
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Expired entry returns empty on get")
    void getExpired() {
      var context = createExpiredContext("qr-expired");
      store.save("qr-expired", context);

      Optional<DtrSessionContextStore.SessionContext> result = store.get("qr-expired");
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Evict removes entry")
    void evict() {
      var context = createContext("qr-evict");
      store.save("qr-evict", context);
      assertTrue(store.exists("qr-evict"));

      store.evict("qr-evict");
      assertFalse(store.exists("qr-evict"));
    }

    @Test
    @DisplayName("Exists returns correct boolean")
    void exists() {
      assertFalse(store.exists("qr-missing"));

      store.save("qr-exists", createContext("qr-exists"));
      assertTrue(store.exists("qr-exists"));
    }

    @Test
    @DisplayName("Exists returns false for expired entry")
    void existsExpired() {
      store.save("qr-expired", createExpiredContext("qr-expired"));
      assertFalse(store.exists("qr-expired"));
    }

    @Test
    @DisplayName("Caller identity stored and retrievable")
    void callerIdentity() {
      var context = createContext("qr-caller");
      store.save("qr-caller", context);

      assertEquals("127.0.0.1", store.get("qr-caller").get().callerIdentity());
    }
  }

  @Nested
  @DisplayName("Version Check and Advance")
  class VersionTests {

    @Test
    @DisplayName("checkAndAdvanceVersion with correct version returns updated context")
    void correctVersion() {
      store.save("qr-v1", createContext("qr-v1"));

      Optional<DtrSessionContextStore.SessionContext> result = store.checkAndAdvanceVersion("qr-v1", 1L);
      assertTrue(result.isPresent());
      assertEquals(2L, result.get().sequenceVersion());
    }

    @Test
    @DisplayName("checkAndAdvanceVersion with stale version returns empty")
    void staleVersion() {
      store.save("qr-v1", createContext("qr-v1"));

      Optional<DtrSessionContextStore.SessionContext> result = store.checkAndAdvanceVersion("qr-v1", 99L);
      assertTrue(result.isEmpty());

      // Original entry is unchanged
      assertEquals(1L, store.get("qr-v1").get().sequenceVersion());
    }

    @Test
    @DisplayName("checkAndAdvanceVersion on expired session returns empty")
    void expiredSession() {
      store.save("qr-expired", createExpiredContext("qr-expired"));

      Optional<DtrSessionContextStore.SessionContext> result = store.checkAndAdvanceVersion("qr-expired", 1L);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("checkAndAdvanceVersion on unknown ID returns empty")
    void unknownId() {
      Optional<DtrSessionContextStore.SessionContext> result = store.checkAndAdvanceVersion("nonexistent", 1L);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Concurrent checkAndAdvanceVersion calls — only one succeeds")
    void concurrentVersionAdvance() throws InterruptedException {
      store.save("qr-concurrent", createContext("qr-concurrent"));

      int threadCount = 10;
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      CountDownLatch latch = new CountDownLatch(1);
      AtomicInteger successes = new AtomicInteger(0);

      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            latch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          Optional<DtrSessionContextStore.SessionContext> result =
              store.checkAndAdvanceVersion("qr-concurrent", 1L);
          if (result.isPresent()) {
            successes.incrementAndGet();
          }
        });
      }

      latch.countDown();
      executor.shutdown();
      executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

      assertEquals(1, successes.get(), "Exactly one thread should succeed in advancing the version");
      assertEquals(2L, store.get("qr-concurrent").get().sequenceVersion());
    }
  }

  @Nested
  @DisplayName("Concurrent Access Safety")
  class ConcurrencyTests {

    @Test
    @DisplayName("Parallel writes and reads do not throw")
    void parallelWritesAndReads() throws InterruptedException {
      int threadCount = 20;
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      CountDownLatch latch = new CountDownLatch(1);
      List<Throwable> errors = new ArrayList<>();

      for (int i = 0; i < threadCount; i++) {
        final int idx = i;
        executor.submit(() -> {
          try {
            latch.await();
            String id = "qr-" + idx;
            store.save(id, createContext(id));
            store.get(id);
            store.exists(id);
            if (idx % 3 == 0) {
              store.evict(id);
            }
          } catch (Throwable t) {
            errors.add(t);
          }
        });
      }

      latch.countDown();
      executor.shutdown();
      executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

      assertTrue(errors.isEmpty(), "No errors during concurrent access");
    }
  }

  @Nested
  @DisplayName("Scheduled Eviction")
  class EvictionTests {

    @Test
    @DisplayName("evictExpired removes only expired entries")
    void evictExpiredEntries() {
      store.save("qr-active", createContext("qr-active"));
      store.save("qr-expired", createExpiredContext("qr-expired"));

      store.evictExpired();

      assertTrue(store.exists("qr-active"));
      // Expired entry was removed by eviction
      assertFalse(store.get("qr-expired").isPresent());
    }
  }
}
