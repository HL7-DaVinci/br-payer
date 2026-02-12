package org.hl7.davinci.dtr;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dtr.adaptive")
public record DtrAdaptiveProperties(
    String nextQuestionUrl
) {
  public DtrAdaptiveProperties {
    if (nextQuestionUrl == null) {
      nextQuestionUrl = "";
    }
  }
}
