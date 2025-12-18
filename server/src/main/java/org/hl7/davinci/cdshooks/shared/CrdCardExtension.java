package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsHooksExtension;

/**
 * CDS Hooks extension for CRD-specific card properties.
 *
 * @see <a href=
 *      "https://build.fhir.org/ig/HL7/davinci-crd/en/StructureDefinition-CDSHookServiceResponseExtensionAssociatedResource.html">Associated
 *      Resource Extension</a>
 */
public class CrdCardExtension extends CdsHooksExtension {

  /**
   * References to FHIR resources that this card is associated with.
   * Format: "ResourceType/id" (e.g., "DeviceRequest/123")
   */
  @JsonProperty("davinci-crd.associated-resource")
  private List<String> associatedResources = new ArrayList<>();

  public List<String> getAssociatedResources() {
    return associatedResources;
  }

  public void setAssociatedResources(List<String> associatedResources) {
    this.associatedResources = associatedResources;
  }

  public void addAssociatedResource(String resourceReference) {
    this.associatedResources.add(resourceReference);
  }
}
