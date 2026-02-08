package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DtrFhirUtilTest {

  @Nested
  @DisplayName("parseCanonical")
  class ParseCanonicalTests {

    @Test
    @DisplayName("Canonical with version returns [url, version]")
    void withVersion() {
      String[] result = DtrFhirUtil.parseCanonical("http://example.org/Questionnaire/foo|1.0");
      assertEquals(2, result.length);
      assertEquals("http://example.org/Questionnaire/foo", result[0]);
      assertEquals("1.0", result[1]);
    }

    @Test
    @DisplayName("Canonical without version returns [url]")
    void withoutVersion() {
      String[] result = DtrFhirUtil.parseCanonical("http://example.org/Questionnaire/foo");
      assertEquals(1, result.length);
      assertEquals("http://example.org/Questionnaire/foo", result[0]);
    }

    @Test
    @DisplayName("Null canonical returns empty array")
    void nullCanonical() {
      String[] result = DtrFhirUtil.parseCanonical(null);
      assertEquals(0, result.length);
    }
  }

  @Nested
  @DisplayName("toVersionSpecific")
  class ToVersionSpecificTests {

    @Test
    @DisplayName("URL and version produce versioned canonical")
    void withVersion() {
      String result = DtrFhirUtil.toVersionSpecific("http://example.org/Questionnaire/foo", "1.0");
      assertEquals("http://example.org/Questionnaire/foo|1.0", result);
    }

    @Test
    @DisplayName("URL already containing pipe is returned unchanged")
    void alreadyVersioned() {
      String result = DtrFhirUtil.toVersionSpecific("http://example.org/Questionnaire/foo|2.0", "1.0");
      assertEquals("http://example.org/Questionnaire/foo|2.0", result);
    }

    @Test
    @DisplayName("Null version returns URL unchanged")
    void nullVersion() {
      String result = DtrFhirUtil.toVersionSpecific("http://example.org/Questionnaire/foo", null);
      assertEquals("http://example.org/Questionnaire/foo", result);
    }

    @Test
    @DisplayName("Blank version returns URL unchanged")
    void blankVersion() {
      String result = DtrFhirUtil.toVersionSpecific("http://example.org/Questionnaire/foo", "  ");
      assertEquals("http://example.org/Questionnaire/foo", result);
    }

    @Test
    @DisplayName("Null URL returns null")
    void nullUrl() {
      assertNull(DtrFhirUtil.toVersionSpecific(null, "1.0"));
    }
  }
}
