package org.hl7.davinci.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vsac")
public record VsacProperties(String url, String apiKey, Warmup warmup) {

  public record Warmup(boolean enabled) {}

  public VsacProperties {
    if (url == null || url.isBlank()) {
      url = "https://cts.nlm.nih.gov/fhir";
    }
    if (warmup == null) {
      warmup = new Warmup(true);
    }
  }
}
