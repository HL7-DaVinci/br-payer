package org.hl7.davinci.cdshooks.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.Test;

class PlanDefinitionFinderTest {

  @Test
  void buildCqlParameters_carriesHookAndContextResourceId() {
    DeviceRequest order = new DeviceRequest();
    order.setId("draft-dme-e0424");

    Parameters params = PlanDefinitionFinder.buildCqlParameters("order-sign", order);

    assertEquals("order-sign", params.getParameterValue("Hook").primitiveValue());
    assertEquals("draft-dme-e0424", params.getParameterValue("ContextResourceId").primitiveValue());
  }

  @Test
  void buildCqlParameters_givesAnIdlessResourceAnIdTheRulesCanMatch() {
    DeviceRequest order = new DeviceRequest();

    Parameters params = PlanDefinitionFinder.buildCqlParameters(null, order);

    String contextId = params.getParameterValue("ContextResourceId").primitiveValue();
    assertNotNull(contextId);
    assertEquals(order.getIdElement().getIdPart(), contextId);
    assertNull(params.getParameterValue("Hook"));
  }

  @Test
  void buildCqlParameters_keepsUrnIdsIntact() {
    DeviceRequest order = new DeviceRequest();
    order.setId("urn:uuid:4d26eba1-9291-4071-be7d-4d31b0f29a59");

    Parameters params = PlanDefinitionFinder.buildCqlParameters("order-sign", order);

    assertEquals("urn:uuid:4d26eba1-9291-4071-be7d-4d31b0f29a59",
        params.getParameterValue("ContextResourceId").primitiveValue());
  }
}
