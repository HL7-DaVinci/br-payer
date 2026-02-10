package org.hl7.davinci.dtr;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores session context for adaptive questionnaire workflows.
 * Each session is keyed by QuestionnaireResponse ID and tracks
 * resolved artifact versions for consistency across $next-question calls.
 */
public interface DtrSessionContextStore {

  record SessionContext(
      String questionnaireResponseId,
      String questionnaireCanonical,
      Map<String, String> libraryVersions,
      Map<String, String> valueSetVersions,
      String coverageReference,
      List<String> orderReferences,
      String callerIdentity,
      long sequenceVersion,
      Instant createdAt,
      Instant expiresAt
  ) {}

  void save(String questionnaireResponseId, SessionContext context);

  Optional<SessionContext> get(String questionnaireResponseId);

  void evict(String questionnaireResponseId);

  boolean exists(String questionnaireResponseId);

  /**
   * Atomically check-and-increment the session version.
   * Returns updated context if expectedVersion matches, empty if stale.
   */
  Optional<SessionContext> checkAndAdvanceVersion(String qrId, long expectedVersion);
}
