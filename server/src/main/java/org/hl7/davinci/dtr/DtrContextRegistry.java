package org.hl7.davinci.dtr;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * In-memory registry mapping CRD coverage-assertion-ids to the DTR
 * questionnaires, order, and coverage the assertion referred to. Written at CRD
 * card time and consulted by the $questionnaire-package context resolution
 * fallback (oper-8). The order and coverage are held by value: they are
 * provider resources whose logical ids carry no meaning on this server, so
 * they must never be re-read from the local store by id.
 */
@Component
public class DtrContextRegistry {

  public record DtrContext(
      List<String> questionnaireCanonicals,
      Resource order,
      Coverage coverage) {
  }

  private final Map<String, DtrContext> contexts = new ConcurrentHashMap<>();

  public void register(String contextId, List<String> questionnaireCanonicals,
      Resource order, Coverage coverage) {
    if (contextId == null || contextId.isBlank()) {
      return;
    }
    List<String> canonicals = questionnaireCanonicals == null
        ? List.of()
        : questionnaireCanonicals.stream()
            .filter(c -> c != null && !c.isBlank())
            .toList();
    contexts.put(contextId, new DtrContext(
        canonicals,
        order != null ? order.copy() : null,
        coverage != null ? coverage.copy() : null));
  }

  public Optional<DtrContext> lookup(String contextId) {
    if (contextId == null || contextId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(contexts.get(contextId));
  }
}
