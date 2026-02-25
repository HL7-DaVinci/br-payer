package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FhirUtilTest {

  @Test
  @DisplayName("normalizeServerBase strips one trailing slash")
  void normalizeServerBase_stripsOneTrailingSlash() {
    assertEquals("http://example.org/fhir", FhirUtil.normalizeServerBase("http://example.org/fhir/"));
    assertEquals("http://example.org/fhir/", FhirUtil.normalizeServerBase("http://example.org/fhir//"));
  }

  @Test
  @DisplayName("normalizeServerBase preserves values without trailing slash")
  void normalizeServerBase_preservesNonTrailingSlashValues() {
    assertEquals("http://example.org/fhir", FhirUtil.normalizeServerBase("http://example.org/fhir"));
    assertEquals("", FhirUtil.normalizeServerBase(""));
    assertNull(FhirUtil.normalizeServerBase(null));
  }

  @Test
  @DisplayName("buildVersionlessResourceUrl builds versionless absolute URLs")
  void buildVersionlessResourceUrl_buildsVersionlessAbsoluteUrls() {
    assertEquals("http://example.org/fhir/ClaimResponse/123",
        FhirUtil.buildVersionlessResourceUrl("http://example.org/fhir/", "ClaimResponse", "123"));
    assertEquals("http://example.org/fhir/ClaimResponse/123",
        FhirUtil.buildVersionlessResourceUrl("http://example.org/fhir", "ClaimResponse", "123"));
  }

  @Test
  @DisplayName("buildVersionlessResourceUrl returns relative URL when server base is absent")
  void buildVersionlessResourceUrl_returnsRelativeUrlWhenServerBaseAbsent() {
    assertEquals("ClaimResponse/123",
        FhirUtil.buildVersionlessResourceUrl(null, "ClaimResponse", "123"));
    assertEquals("ClaimResponse/123",
        FhirUtil.buildVersionlessResourceUrl("", "ClaimResponse", "123"));
  }

  @Test
  @DisplayName("buildVersionlessResourceUrl returns null for missing type or id")
  void buildVersionlessResourceUrl_returnsNullForMissingInputs() {
    assertNull(FhirUtil.buildVersionlessResourceUrl("http://example.org/fhir", null, "123"));
    assertNull(FhirUtil.buildVersionlessResourceUrl("http://example.org/fhir", "", "123"));
    assertNull(FhirUtil.buildVersionlessResourceUrl("http://example.org/fhir", "ClaimResponse", null));
    assertNull(FhirUtil.buildVersionlessResourceUrl("http://example.org/fhir", "ClaimResponse", ""));
  }
}
