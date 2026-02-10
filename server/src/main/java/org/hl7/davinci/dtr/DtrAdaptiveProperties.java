package org.hl7.davinci.dtr;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dtr.adaptive")
public record DtrAdaptiveProperties(
    String nextQuestionUrl,
    long sessionTtlMinutes
) {
  public DtrAdaptiveProperties {
    if (nextQuestionUrl == null) {
      nextQuestionUrl = "";
    }
    if (sessionTtlMinutes <= 0) {
      sessionTtlMinutes = 60;
    }
  }
}
