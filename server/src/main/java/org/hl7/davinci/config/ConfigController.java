package org.hl7.davinci.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

  @Value("${app.fhir.servers:}")
  private String fhirServersJson;

  @Value("${app.cds.servers:}")
  private String cdsServersJson;

  @GetMapping(value = "/config.js", produces = "application/javascript")
  public String getConfig() {
    StringBuilder config = new StringBuilder("window.APP_CONFIG = {");

    // FHIR servers
    String fhirServers = fhirServersJson.isEmpty() ? "[]" : fhirServersJson;
    config.append(" fhirServers: ").append(fhirServers);

    // CDS servers
    String cdsServers = cdsServersJson.isEmpty() ? "[]" : cdsServersJson;
    config.append(", cdsServers: ").append(cdsServers);

    config.append(" };");
    return config.toString();
  }
}
