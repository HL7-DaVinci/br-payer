package org.hl7.davinci.cql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Library;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

class CqlFileResolverTest {

  @Test
  void resolveExternalContent_embedsRelativeCqlAndClearsUrl() throws Exception {
    Path dir = Files.createTempDirectory("cql-resolver");
    Path libraryJson = dir.resolve("Library-Test.json");
    Path cql = dir.resolve("TestLibrary.cql");
    Files.writeString(libraryJson, "{\"resourceType\":\"Library\"}");
    Files.writeString(cql, "library TestLibrary version '1.0.0'");

    Library library = new Library();
    library.setId("Library/TestLibrary");
    Attachment content = new Attachment();
    content.setContentType("text/cql");
    content.setUrl("TestLibrary.cql");
    library.addContent(content);

    CqlFileResolver resolver = new CqlFileResolver();
    ReflectionTestUtils.setField(resolver, "resourceLoader", new DefaultResourceLoader());

    boolean resolved = resolver.resolveExternalContent(library, new FileSystemResource(libraryJson));

    assertTrue(resolved);
    assertTrue(library.getContentFirstRep().hasData());
    assertFalse(library.getContentFirstRep().hasUrl());
  }

  @Test
  void resolveExternalContent_skipsAbsoluteUrls() throws Exception {
    Path dir = Files.createTempDirectory("cql-resolver-abs");
    Path libraryJson = dir.resolve("Library-Test.json");
    Files.writeString(libraryJson, "{\"resourceType\":\"Library\"}");

    Library library = new Library();
    library.setId("Library/TestLibrary");
    library.addContent(new Attachment().setUrl("https://example.org/TestLibrary.cql"));

    CqlFileResolver resolver = new CqlFileResolver();
    ReflectionTestUtils.setField(resolver, "resourceLoader", new DefaultResourceLoader());

    boolean resolved = resolver.resolveExternalContent(library, new FileSystemResource(libraryJson));

    assertFalse(resolved);
    assertTrue(library.getContentFirstRep().hasUrl());
  }

  @Test
  void resolveExternalContent_returnsFalseWhenFileMissing() throws Exception {
    Path dir = Files.createTempDirectory("cql-resolver-missing");
    Path libraryJson = dir.resolve("Library-Test.json");
    Files.writeString(libraryJson, "{\"resourceType\":\"Library\"}");

    Library library = new Library();
    library.setId("Library/TestLibrary");
    library.addContent(new Attachment().setUrl("Missing.cql"));

    CqlFileResolver resolver = new CqlFileResolver();
    ReflectionTestUtils.setField(resolver, "resourceLoader", new DefaultResourceLoader());

    boolean resolved = resolver.resolveExternalContent(library, new FileSystemResource(libraryJson));

    assertFalse(resolved);
    assertTrue(library.getContentFirstRep().hasUrl());
    assertFalse(library.getContentFirstRep().hasData());
  }
}
