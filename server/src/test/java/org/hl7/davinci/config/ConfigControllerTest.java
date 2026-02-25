package org.hl7.davinci.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConfigControllerTest {

  @Test
  void getConfig_defaultsToEmptyArraysWhenPropertiesMissing() {
    ConfigController controller = new ConfigController();
    ReflectionTestUtils.setField(controller, "fhirServersJson", "");
    ReflectionTestUtils.setField(controller, "cdsServersJson", "");

    String config = controller.getConfig();

    assertTrue(config.contains("fhirServers: []"));
    assertTrue(config.contains("cdsServers: []"));
  }

  @Test
  void getConfig_usesProvidedJsonStrings() {
    ConfigController controller = new ConfigController();
    ReflectionTestUtils.setField(controller, "fhirServersJson", "[{\"name\":\"FHIR\"}]");
    ReflectionTestUtils.setField(controller, "cdsServersJson", "[{\"name\":\"CRD\"}]");

    String config = controller.getConfig();

    assertTrue(config.contains("fhirServers: [{\"name\":\"FHIR\"}]"));
    assertTrue(config.contains("cdsServers: [{\"name\":\"CRD\"}]"));
  }
}
