package org.hl7.davinci.pas;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas")
public record PasProperties(
    int pendedResolutionDelaySeconds,
    String authorizationNumberPrefix
) {
  public PasProperties {
    if (pendedResolutionDelaySeconds <= 0) pendedResolutionDelaySeconds = 30;
    if (authorizationNumberPrefix == null) authorizationNumberPrefix = "AUTH-";
  }
}
