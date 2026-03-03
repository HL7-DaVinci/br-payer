package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

  @Nested
  @DisplayName("parseCanonical")
  class ParseCanonicalTests {

    @Test
    @DisplayName("Canonical with version returns [url, version]")
    void withVersion() {
      String[] result = FhirUtil.parseCanonical("http://example.org/Questionnaire/foo|1.0");
      assertEquals(2, result.length);
      assertEquals("http://example.org/Questionnaire/foo", result[0]);
      assertEquals("1.0", result[1]);
    }

    @Test
    @DisplayName("Canonical without version returns [url]")
    void withoutVersion() {
      String[] result = FhirUtil.parseCanonical("http://example.org/Questionnaire/foo");
      assertEquals(1, result.length);
      assertEquals("http://example.org/Questionnaire/foo", result[0]);
    }

    @Test
    @DisplayName("Null canonical returns empty array")
    void nullCanonical() {
      String[] result = FhirUtil.parseCanonical(null);
      assertEquals(0, result.length);
    }
  }

  @Nested
  @DisplayName("toVersionSpecific")
  class ToVersionSpecificTests {

    @Test
    @DisplayName("URL and version produce versioned canonical")
    void withVersion() {
      String result = FhirUtil.toVersionSpecific("http://example.org/Questionnaire/foo", "1.0");
      assertEquals("http://example.org/Questionnaire/foo|1.0", result);
    }

    @Test
    @DisplayName("URL already containing pipe is returned unchanged")
    void alreadyVersioned() {
      String result = FhirUtil.toVersionSpecific("http://example.org/Questionnaire/foo|2.0", "1.0");
      assertEquals("http://example.org/Questionnaire/foo|2.0", result);
    }

    @Test
    @DisplayName("Null version returns URL unchanged")
    void nullVersion() {
      String result = FhirUtil.toVersionSpecific("http://example.org/Questionnaire/foo", null);
      assertEquals("http://example.org/Questionnaire/foo", result);
    }

    @Test
    @DisplayName("Blank version returns URL unchanged")
    void blankVersion() {
      String result = FhirUtil.toVersionSpecific("http://example.org/Questionnaire/foo", "  ");
      assertEquals("http://example.org/Questionnaire/foo", result);
    }

    @Test
    @DisplayName("Null URL returns null")
    void nullUrl() {
      assertNull(FhirUtil.toVersionSpecific(null, "1.0"));
    }
  }
}
