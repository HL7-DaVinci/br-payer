package org.hl7.davinci.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

  @Value("${app.fhir.servers:}")
  private String fhirServersJson;

  @GetMapping(value = "/config.js", produces = "application/javascript")
  public String getConfig() {
    String servers = fhirServersJson.isEmpty()
        ? "[]"
        : fhirServersJson;

    return "window.APP_CONFIG = { fhirServers: " + servers + " };";
  }
}
