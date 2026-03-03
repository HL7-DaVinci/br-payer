package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoValueSet;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

class VsacValueSetResolverTest {

  private VsacValueSetResolver resolver;
  private DaoRegistry mockDaoRegistry;
  private IFhirResourceDaoValueSet<ValueSet> mockVsDao;
  private IValidationSupport mockValidationSupport;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    mockDaoRegistry = mock(DaoRegistry.class);
    mockVsDao = mock(IFhirResourceDaoValueSet.class);
    mockValidationSupport = mock(IValidationSupport.class);
    when(mockDaoRegistry.getResourceDao(ValueSet.class)).thenReturn(mockVsDao);
    resolver = new VsacValueSetResolver(mockDaoRegistry, mockValidationSupport);
  }

  private ValueSet createValueSet(String id, String url) {
    ValueSet vs = new ValueSet();
    vs.setId(id);
    vs.setUrl(url);
    vs.setVersion("1.0");
    vs.getCompose().addInclude().setSystem("http://example.org/cs").addConcept().setCode("code-0");
    return vs;
  }

  @Test
  @DisplayName("Returns existing JPA ValueSet without hitting VSAC")
  void returnsExistingJpaValueSet() {
    ValueSet vs = createValueSet("vs-1", "http://cts.nlm.nih.gov/fhir/ValueSet/example");
    when(mockVsDao.searchForResources(any(), any())).thenReturn(List.of(vs));

    List<String> warnings = new ArrayList<>();
    ValueSet result = resolver.resolveAndPersist("http://cts.nlm.nih.gov/fhir/ValueSet/example", warnings);

    assertNotNull(result);
    assertEquals("http://cts.nlm.nih.gov/fhir/ValueSet/example", result.getUrl());
    assertTrue(warnings.isEmpty());
    verify(mockValidationSupport, never()).fetchValueSet(anyString());
  }

  @Test
  @DisplayName("Fetches from VSAC and persists when not in JPA")
  void fetchesFromVsacAndPersists() {
    when(mockVsDao.searchForResources(any(), any())).thenReturn(List.of());

    String vsUrl = "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.132";
    ValueSet vs = createValueSet("vs-vsac", vsUrl);
    when(mockValidationSupport.fetchValueSet(vsUrl)).thenReturn(vs);

    ValueSet expanded = new ValueSet();
    expanded.getExpansion().addContains().setSystem("http://example.org/cs").setCode("code-0");
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(expanded);

    List<String> warnings = new ArrayList<>();
    ValueSet result = resolver.resolveAndPersist(vsUrl, warnings);

    assertNotNull(result);
    assertTrue(result.hasExpansion());
    verify(mockVsDao).update(eq(vs), any(SystemRequestDetails.class));
    assertTrue(warnings.isEmpty());
  }

  @Test
  @DisplayName("Returns null and adds warning when not found anywhere")
  void returnsNullWhenNotFound() {
    when(mockVsDao.searchForResources(any(), any())).thenReturn(List.of());

    List<String> warnings = new ArrayList<>();
    ValueSet result = resolver.resolveAndPersist("http://example.org/ValueSet/missing", warnings);

    assertNull(result);
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("not found"));
  }

  @Test
  @DisplayName("Expansion failure during persist adds warning but still returns ValueSet")
  void expansionFailureDuringPersist() {
    when(mockVsDao.searchForResources(any(), any())).thenReturn(List.of());

    String vsUrl = "http://cts.nlm.nih.gov/fhir/ValueSet/example";
    ValueSet vs = createValueSet("vs-vsac", vsUrl);
    when(mockValidationSupport.fetchValueSet(vsUrl)).thenReturn(vs);
    when(mockVsDao.expand(any(ValueSet.class), any()))
        .thenThrow(new RuntimeException("Terminology server unavailable"));

    List<String> warnings = new ArrayList<>();
    ValueSet result = resolver.resolveAndPersist(vsUrl, warnings);

    assertNotNull(result);
    verify(mockVsDao).update(eq(vs), any(SystemRequestDetails.class));
    assertTrue(warnings.stream().anyMatch(w -> w.contains("expansion failed during persist")));
  }

  @Test
  @DisplayName("Persistence failure adds warning but still returns ValueSet")
  void persistenceFailure() {
    when(mockVsDao.searchForResources(any(), any())).thenReturn(List.of());

    String vsUrl = "http://cts.nlm.nih.gov/fhir/ValueSet/example2";
    ValueSet vs = createValueSet("vs-vsac", vsUrl);
    when(mockValidationSupport.fetchValueSet(vsUrl)).thenReturn(vs);

    ValueSet expanded = new ValueSet();
    expanded.getExpansion().addContains().setSystem("http://example.org/cs").setCode("code-0");
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(expanded);
    doThrow(new RuntimeException("Database error"))
        .when(mockVsDao).update(any(ValueSet.class), any(SystemRequestDetails.class));

    List<String> warnings = new ArrayList<>();
    ValueSet result = resolver.resolveAndPersist(vsUrl, warnings);

    assertNotNull(result);
    assertTrue(warnings.stream().anyMatch(w -> w.contains("Failed to persist")));
  }
}
