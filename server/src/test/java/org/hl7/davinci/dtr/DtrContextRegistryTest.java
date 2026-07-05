package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.davinci.dtr.DtrContextRegistry.DtrContext;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.junit.jupiter.api.Test;

class DtrContextRegistryTest {

  private final DtrContextRegistry registry = new DtrContextRegistry();

  private static DeviceRequest order() {
    DeviceRequest dr = new DeviceRequest();
    dr.setId("dr-1");
    return dr;
  }

  private static Coverage coverage() {
    Coverage cov = new Coverage();
    cov.setId("cov-1");
    cov.setSubscriberId("MEM-1");
    return cov;
  }

  @Test
  void registeredCrdAssertionIdResolvesToQuestionnairesAndByValueResources() {
    registry.register("CRD-abc-123",
        List.of("http://example.org/fhir/Questionnaire/home-o2-std-questionnaire"),
        order(), coverage());

    DtrContext ctx = registry.lookup("CRD-abc-123").orElseThrow();

    assertEquals(1, ctx.questionnaireCanonicals().size());
    assertEquals("http://example.org/fhir/Questionnaire/home-o2-std-questionnaire",
        ctx.questionnaireCanonicals().get(0));
    assertEquals("DeviceRequest", ctx.order().fhirType());
    assertEquals("MEM-1", ctx.coverage().getSubscriberId());
  }

  @Test
  void storesDefensiveCopiesOfTheRegisteredResources() {
    DeviceRequest original = order();
    registry.register("CRD-copy", List.of("http://example.org/q"), original, null);

    DtrContext ctx = registry.lookup("CRD-copy").orElseThrow();

    assertNotSame(original, ctx.order());
  }

  @Test
  void unknownContextIsEmpty() {
    assertTrue(registry.lookup("nope").isEmpty());
  }

  @Test
  void blankContextIdIsIgnored() {
    registry.register(" ", List.of("http://example.org/q"), null, null);
    assertTrue(registry.lookup(" ").isEmpty());
  }

  @Test
  void reRegistrationReplacesTheContext() {
    registry.register("CRD-1", List.of("http://example.org/q1"), null, null);
    registry.register("CRD-1", List.of("http://example.org/q2"), null, null);
    assertEquals(List.of("http://example.org/q2"),
        registry.lookup("CRD-1").orElseThrow().questionnaireCanonicals());
  }
}
