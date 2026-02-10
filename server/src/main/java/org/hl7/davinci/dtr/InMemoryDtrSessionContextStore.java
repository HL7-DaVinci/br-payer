package org.hl7.davinci.dtr;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@EnableConfigurationProperties(DtrAdaptiveProperties.class)
public class InMemoryDtrSessionContextStore implements DtrSessionContextStore {

  private static final Logger logger = LoggerFactory.getLogger(InMemoryDtrSessionContextStore.class);

  private final ConcurrentHashMap<String, SessionContext> store = new ConcurrentHashMap<>();
  private final DtrAdaptiveProperties properties;

  public InMemoryDtrSessionContextStore(DtrAdaptiveProperties properties) {
    this.properties = properties;
  }

  @Override
  public void save(String questionnaireResponseId, SessionContext context) {
    store.put(questionnaireResponseId, context);
  }

  @Override
  public Optional<SessionContext> get(String questionnaireResponseId) {
    SessionContext context = store.get(questionnaireResponseId);
    if (context == null) {
      return Optional.empty();
    }
    if (isExpired(context)) {
      store.remove(questionnaireResponseId);
      return Optional.empty();
    }
    return Optional.of(context);
  }

  @Override
  public void evict(String questionnaireResponseId) {
    store.remove(questionnaireResponseId);
  }

  @Override
  public boolean exists(String questionnaireResponseId) {
    return get(questionnaireResponseId).isPresent();
  }

  @Override
  public Optional<SessionContext> checkAndAdvanceVersion(String qrId, long expectedVersion) {
    final SessionContext[] result = { null };

    store.computeIfPresent(qrId, (key, current) -> {
      if (isExpired(current)) {
        return null; // Remove expired entry
      }
      if (current.sequenceVersion() != expectedVersion) {
        return current; // Version mismatch — keep unchanged
      }
      SessionContext advanced = new SessionContext(
          current.questionnaireResponseId(),
          current.questionnaireCanonical(),
          current.libraryVersions(),
          current.valueSetVersions(),
          current.coverageReference(),
          current.orderReferences(),
          current.callerIdentity(),
          current.sequenceVersion() + 1,
          current.createdAt(),
          current.expiresAt());
      result[0] = advanced;
      return advanced;
    });

    return Optional.ofNullable(result[0]);
  }

  @Scheduled(fixedRate = 300_000) // Every 5 minutes
  void evictExpired() {
    int before = store.size();
    store.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    int removed = before - store.size();
    if (removed > 0) {
      logger.debug("Evicted {} expired session contexts", removed);
    }
  }

  private boolean isExpired(SessionContext context) {
    return Instant.now().isAfter(context.expiresAt());
  }

  long getSessionTtlMinutes() {
    return properties.sessionTtlMinutes();
  }
}
