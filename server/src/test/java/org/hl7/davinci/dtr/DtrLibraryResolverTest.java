package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.hl7.davinci.cql.DaoLibrarySourceProvider;
import org.hl7.davinci.cql.ElmCompiler;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.RelatedArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;

class DtrLibraryResolverTest {

  private static final String CQF_LIBRARY_EXT =
      "http://hl7.org/fhir/StructureDefinition/cqf-library";

  private DtrLibraryResolver resolver;
  private DaoRegistry mockDaoRegistry;
  private IFhirResourceDao<Library> mockLibDao;
  private ElmCompiler mockElmCompiler;
  private DaoLibrarySourceProvider mockSourceProvider;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    mockDaoRegistry = mock(DaoRegistry.class);
    mockLibDao = mock(IFhirResourceDao.class);
    mockElmCompiler = mock(ElmCompiler.class);
    mockSourceProvider = mock(DaoLibrarySourceProvider.class);
    when(mockDaoRegistry.getResourceDao(Library.class)).thenReturn(mockLibDao);
    resolver = new DtrLibraryResolver(mockDaoRegistry, mockElmCompiler, mockSourceProvider);
  }

  private Library createLibraryWithElm(String id, String url, String version) {
    Library lib = new Library();
    lib.setId(id);
    lib.setUrl(url);
    lib.setVersion(version);
    lib.setName("TestLib-" + id);
    Attachment elm = new Attachment();
    elm.setContentType("application/elm+json");
    elm.setData("{}".getBytes(StandardCharsets.UTF_8));
    lib.addContent(elm);
    return lib;
  }

  @Test
  @DisplayName("Single library with ELM: resolved, version-specific URL")
  void singleLibraryWithElm() {
    Library lib = createLibraryWithElm("lib-1", "http://example.org/Library/test", "1.0");

    when(mockLibDao.searchForResources(any(), any())).thenReturn(List.of(lib));

    Questionnaire q = new Questionnaire();
    q.addExtension(new Extension(CQF_LIBRARY_EXT,
        new CanonicalType("http://example.org/Library/test|1.0")));

    DtrLibraryResolver.LibraryResolution resolution = resolver.resolveLibraries(q);

    assertEquals(1, resolution.libraries().size());
    assertTrue(resolution.warnings().isEmpty());
  }

  @Test
  @DisplayName("Library without ELM: compiled on-demand")
  void libraryWithoutElm_compiled() {
    Library lib = new Library();
    lib.setId("lib-1");
    lib.setUrl("http://example.org/Library/test");
    lib.setVersion("1.0");
    lib.setName("TestLib");
    Attachment cql = new Attachment();
    cql.setContentType("text/cql");
    cql.setData("library TestLib version '1.0'".getBytes(StandardCharsets.UTF_8));
    lib.addContent(cql);

    when(mockLibDao.searchForResources(any(), any())).thenReturn(List.of(lib));

    when(mockElmCompiler.compileAndAttachElm(any(), any())).thenReturn(true);

    Questionnaire q = new Questionnaire();
    q.addExtension(new Extension(CQF_LIBRARY_EXT,
        new CanonicalType("http://example.org/Library/test|1.0")));

    DtrLibraryResolver.LibraryResolution resolution = resolver.resolveLibraries(q);

    assertEquals(1, resolution.libraries().size());
    verify(mockElmCompiler).compileAndAttachElm(eq(lib), eq(mockSourceProvider));
  }

  @Test
  @DisplayName("Library with depends-on: recursive resolution")
  void dependsOn_recursive() {
    Library mainLib = createLibraryWithElm("main-lib", "http://example.org/Library/main", "1.0");
    mainLib.addRelatedArtifact()
        .setType(RelatedArtifact.RelatedArtifactType.DEPENDSON)
        .setResource("http://example.org/Library/helper|1.0");

    Library helperLib = createLibraryWithElm("helper-lib", "http://example.org/Library/helper", "1.0");

    when(mockLibDao.searchForResources(any(), any())).thenReturn(List.of(mainLib), List.of(helperLib));

    Questionnaire q = new Questionnaire();
    q.addExtension(new Extension(CQF_LIBRARY_EXT,
        new CanonicalType("http://example.org/Library/main|1.0")));

    DtrLibraryResolver.LibraryResolution resolution = resolver.resolveLibraries(q);

    assertEquals(2, resolution.libraries().size());
  }

  @Test
  @DisplayName("FHIRHelpers dependency: skipped without warning or repository lookup")
  void classpathLibrary_skipped() {
    Library mainLib = createLibraryWithElm("main-lib", "http://example.org/Library/main", "1.0");
    mainLib.addRelatedArtifact()
        .setType(RelatedArtifact.RelatedArtifactType.DEPENDSON)
        .setResource("http://hl7.org/fhir/Library/FHIRHelpers|4.0.1");

    // Only one search call should occur (for main lib), none for FHIRHelpers
    when(mockLibDao.searchForResources(any(), any())).thenReturn(List.of(mainLib));

    Questionnaire q = new Questionnaire();
    q.addExtension(new Extension(CQF_LIBRARY_EXT,
        new CanonicalType("http://example.org/Library/main|1.0")));

    DtrLibraryResolver.LibraryResolution resolution = resolver.resolveLibraries(q);

    assertEquals(1, resolution.libraries().size());
    assertTrue(resolution.warnings().isEmpty(), "No warnings should be emitted for classpath libraries");
    // Only one search call (for the main library)
    verify(mockLibDao, times(1)).searchForResources(any(), any());
  }

  @Test
  @DisplayName("Missing library: warning, continue")
  void missingLibrary_warning() {
    when(mockLibDao.searchForResources(any(), any())).thenReturn(List.of());

    Questionnaire q = new Questionnaire();
    q.addExtension(new Extension(CQF_LIBRARY_EXT,
        new CanonicalType("http://example.org/Library/missing|1.0")));

    DtrLibraryResolver.LibraryResolution resolution = resolver.resolveLibraries(q);

    assertTrue(resolution.libraries().isEmpty());
    assertEquals(1, resolution.warnings().size());
    assertTrue(resolution.warnings().get(0).contains("not found"));
  }

  @Test
  @DisplayName("Name collision: warning")
  void nameCollision_warning() {
    Library lib1 = createLibraryWithElm("lib-1", "http://example.org/Library/test1", "1.0");
    lib1.setName("SameName");
    Library lib2 = createLibraryWithElm("lib-2", "http://example.org/Library/test2", "1.0");
    lib2.setName("SameName");

    when(mockLibDao.searchForResources(any(), any())).thenReturn(List.of(lib1), List.of(lib2));

    Questionnaire q = new Questionnaire();
    q.addExtension(new Extension(CQF_LIBRARY_EXT,
        new CanonicalType("http://example.org/Library/test1|1.0")));
    q.addExtension(new Extension(CQF_LIBRARY_EXT,
        new CanonicalType("http://example.org/Library/test2|1.0")));

    DtrLibraryResolver.LibraryResolution resolution = resolver.resolveLibraries(q);

    assertEquals(2, resolution.libraries().size());
    assertTrue(resolution.warnings().stream().anyMatch(w -> w.contains("Duplicate library name")));
  }
}
