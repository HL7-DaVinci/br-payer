package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsHooksExtension;

/**
 * CRD extension for CDS Hooks request payload.
 * Contains client-specified configuration values.
 */
public class CrdRequestExtension extends CdsHooksExtension {

  @JsonProperty("davinci-crd.requestedVersion")
  private List<String> requestedVersion = new ArrayList<>();

  @JsonProperty("davinci-crd.configuration")
  private Map<String, Object> configuration = new HashMap<>();

  public List<String> getRequestedVersion() {
    return requestedVersion;
  }

  public void setRequestedVersion(List<String> requestedVersion) {
    this.requestedVersion = requestedVersion;
  }

  public Map<String, Object> getConfiguration() {
    return configuration;
  }

  public void setConfiguration(Map<String, Object> configuration) {
    this.configuration = configuration;
  }
}
