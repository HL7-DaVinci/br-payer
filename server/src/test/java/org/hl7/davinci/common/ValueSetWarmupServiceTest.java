package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;

class ValueSetWarmupServiceTest {

  private ValueSetWarmupService warmupService;
  private DaoRegistry mockDaoRegistry;
  private VsacValueSetResolver mockResolver;
  private IFhirResourceDao<Library> mockLibraryDao;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    mockDaoRegistry = mock(DaoRegistry.class);
    mockResolver = mock(VsacValueSetResolver.class);
    mockLibraryDao = mock(IFhirResourceDao.class);
    when(mockDaoRegistry.getResourceDao(Library.class)).thenReturn(mockLibraryDao);
    warmupService = new ValueSetWarmupService(mockDaoRegistry, mockResolver);
  }

  private Library createLibraryWithVsacUrl(String id, String... vsUrls) {
    Library lib = new Library();
    lib.setId(id);
    for (String url : vsUrls) {
      DataRequirement dr = lib.addDataRequirement();
      dr.setType("Condition");
      dr.addCodeFilter().setValueSet(url);
    }
    return lib;
  }

  private void stubLibrarySearch(Library... libraries) {
    when(mockLibraryDao.searchForResources(any(), any())).thenReturn(List.of(libraries));
  }

  @Test
  @DisplayName("Discovers VSAC URLs from Library dataRequirements")
  void discoversVsacUrls() {
    String vsacUrl = "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.132";
    stubLibrarySearch(createLibraryWithVsacUrl("lib-1", vsacUrl));

    Set<String> urls = warmupService.discoverVsacUrls();

    assertEquals(1, urls.size());
    assertTrue(urls.contains(vsacUrl));
  }

  @Test
  @DisplayName("Ignores non-VSAC URLs")
  void ignoresNonVsacUrls() {
    stubLibrarySearch(createLibraryWithVsacUrl("lib-1",
        "http://example.org/ValueSet/local",
        "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.132"));

    Set<String> urls = warmupService.discoverVsacUrls();

    assertEquals(1, urls.size());
    assertFalse(urls.contains("http://example.org/ValueSet/local"));
  }

  @Test
  @DisplayName("Deduplicates URLs across multiple Libraries")
  void deduplicatesAcrossLibraries() {
    String sharedUrl = "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.132";
    stubLibrarySearch(
        createLibraryWithVsacUrl("lib-1", sharedUrl),
        createLibraryWithVsacUrl("lib-2", sharedUrl));

    Set<String> urls = warmupService.discoverVsacUrls();

    assertEquals(1, urls.size());
  }

  @Test
  @DisplayName("Empty library list results in no URLs")
  void emptyLibraries() {
    when(mockLibraryDao.searchForResources(any(), any())).thenReturn(List.of());

    Set<String> urls = warmupService.discoverVsacUrls();

    assertTrue(urls.isEmpty());
  }

  @Test
  @DisplayName("Warmup resolves each discovered VSAC URL")
  void warmupResolvesEachUrl() {
    String url1 = "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.132";
    String url2 = "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113883.3.6037.1001.23.93.72";
    stubLibrarySearch(createLibraryWithVsacUrl("lib-1", url1, url2));

    ValueSet vs = new ValueSet();
    vs.setUrl(url1);
    when(mockResolver.resolveAndPersist(anyString(), anyList())).thenReturn(vs);

    warmupService.warmup();

    verify(mockResolver).resolveAndPersist(eq(url1), anyList());
    verify(mockResolver).resolveAndPersist(eq(url2), anyList());
  }

  @Test
  @DisplayName("Individual failure does not prevent other ValueSets from resolving")
  void individualFailureIsolated() {
    String url1 = "http://cts.nlm.nih.gov/fhir/ValueSet/failing";
    String url2 = "http://cts.nlm.nih.gov/fhir/ValueSet/succeeding";
    stubLibrarySearch(createLibraryWithVsacUrl("lib-1", url1, url2));

    when(mockResolver.resolveAndPersist(eq(url1), anyList()))
        .thenThrow(new RuntimeException("VSAC timeout"));
    ValueSet vs = new ValueSet();
    vs.setUrl(url2);
    when(mockResolver.resolveAndPersist(eq(url2), anyList())).thenReturn(vs);

    warmupService.warmup();

    verify(mockResolver).resolveAndPersist(eq(url2), anyList());
  }

  @Test
  @DisplayName("Library without dataRequirements is skipped")
  void libraryWithoutDataRequirements() {
    Library lib = new Library();
    lib.setId("lib-empty");
    stubLibrarySearch(lib);

    Set<String> urls = warmupService.discoverVsacUrls();

    assertTrue(urls.isEmpty());
  }
}
