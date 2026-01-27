package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsHooksExtension;

/**
 * CRD extension for CDS Hooks service discovery response.
 * Advertises server capabilities to clients.
 */
public class CrdServiceExtension extends CdsHooksExtension {

  @JsonProperty("davinci-crd.version")
  private List<String> versions = new ArrayList<>();

  @JsonProperty("davinci-crd.configuration-options")
  private List<CrdConfigurationOption> configurationOptions = new ArrayList<>();

  public List<String> getVersions() {
    return versions;
  }

  public void setVersions(List<String> versions) {
    this.versions = versions;
  }

  public List<CrdConfigurationOption> getConfigurationOptions() {
    return configurationOptions;
  }

  public void setConfigurationOptions(List<CrdConfigurationOption> configurationOptions) {
    this.configurationOptions = configurationOptions;
  }

  public static class CrdConfigurationOption {

    @JsonProperty("code")
    private String code;

    @JsonProperty("type")
    private String type;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("default")
    private Object defaultValue;

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public Object getDefaultValue() {
      return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
      this.defaultValue = defaultValue;
    }
  }
}
