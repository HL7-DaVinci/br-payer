package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoValueSet;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

class DtrValueSetCollectorTest {

  private DtrValueSetCollector collector;
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
    collector = new DtrValueSetCollector(mockDaoRegistry, mockValidationSupport);
  }

  private ValueSet createValueSet(String id, String url, int conceptCount) {
    ValueSet vs = new ValueSet();
    vs.setId(id);
    vs.setUrl(url);
    vs.setVersion("1.0");
    ValueSet.ConceptSetComponent include = vs.getCompose().addInclude()
        .setSystem("http://example.org/cs");
    for (int i = 0; i < conceptCount; i++) {
      include.addConcept().setCode("code-" + i);
    }
    return vs;
  }

  @Test
  @DisplayName("Collect from answerValueSet")
  void collectFromAnswerValueSet() {
    ValueSet vs = createValueSet("vs-1", "http://example.org/ValueSet/test", 5);

    IBundleProvider results = mock(IBundleProvider.class);
    when(results.isEmpty()).thenReturn(false);
    when(results.getResources(0, 1)).thenReturn(List.of(vs));
    when(mockVsDao.search(any(), any())).thenReturn(results);
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(vs);

    Questionnaire q = new Questionnaire();
    QuestionnaireItemComponent item = q.addItem();
    item.setLinkId("q1");
    item.setType(QuestionnaireItemType.CHOICE);
    item.setAnswerValueSet("http://example.org/ValueSet/test");

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    assertEquals(1, result.valueSets().size());
    assertTrue(result.warnings().isEmpty());
  }

  @Test
  @DisplayName("Collect from Library dataRequirement")
  void collectFromLibraryDataRequirement() {
    ValueSet vs = createValueSet("vs-1", "http://example.org/ValueSet/test", 5);

    IBundleProvider results = mock(IBundleProvider.class);
    when(results.isEmpty()).thenReturn(false);
    when(results.getResources(0, 1)).thenReturn(List.of(vs));
    when(mockVsDao.search(any(), any())).thenReturn(results);
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(vs);

    Library lib = new Library();
    lib.setId("lib-1");
    DataRequirement dr = lib.addDataRequirement();
    dr.setType("Condition");
    dr.addCodeFilter().setValueSet("http://example.org/ValueSet/test");

    Questionnaire q = new Questionnaire();

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of(lib));

    assertEquals(1, result.valueSets().size());
  }

  @Test
  @DisplayName("ValueSet resolved via validation support fallback")
  void valueSetResolvedViaValidationSupport() {
    // Not in local repository
    IBundleProvider emptyResults = mock(IBundleProvider.class);
    when(emptyResults.isEmpty()).thenReturn(true);
    when(mockVsDao.search(any(), any())).thenReturn(emptyResults);

    // Available via validation support chain
    ValueSet vs = createValueSet("vs-1", "http://hl7.org/fhir/ValueSet/request-intent", 3);
    when(mockValidationSupport.fetchValueSet("http://hl7.org/fhir/ValueSet/request-intent"))
        .thenReturn(vs);
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(vs);

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE)
        .setAnswerValueSet("http://hl7.org/fhir/ValueSet/request-intent");

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    assertEquals(1, result.valueSets().size());
    assertTrue(result.warnings().isEmpty());
  }

  @Test
  @DisplayName("ValueSet not in repo or validation support: warning")
  void valueSetNotFound_warning() {
    IBundleProvider emptyResults = mock(IBundleProvider.class);
    when(emptyResults.isEmpty()).thenReturn(true);
    when(mockVsDao.search(any(), any())).thenReturn(emptyResults);

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE)
        .setAnswerValueSet("http://example.org/ValueSet/missing");

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    assertTrue(result.valueSets().isEmpty());
    assertEquals(1, result.warnings().size());
    assertTrue(result.warnings().get(0).contains("not found"));
  }

  @Test
  @DisplayName("SNOMED concept '=' filters are converted to explicit concepts")
  void snomedConceptEqualsFilter_convertedToConcept() {
    ValueSet vs = new ValueSet();
    vs.setId("vs-snomed-filter");
    vs.setUrl("http://example.org/ValueSet/snomed-filter");
    vs.setVersion("1.0");
    vs.getCompose().addInclude()
        .setSystem("http://snomed.info/sct")
        .addFilter()
        .setProperty("concept")
        .setOp(ValueSet.FilterOperator.EQUAL)
        .setValue("161665007");

    IBundleProvider results = mock(IBundleProvider.class);
    when(results.isEmpty()).thenReturn(false);
    when(results.getResources(0, 1)).thenReturn(List.of(vs));
    when(mockVsDao.search(any(), any())).thenReturn(results);
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(vs);

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE)
        .setAnswerValueSet("http://example.org/ValueSet/snomed-filter");

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    assertEquals(1, result.valueSets().size());
    ValueSet.ConceptSetComponent include = result.valueSets().get(0).getCompose().getIncludeFirstRep();
    assertTrue(include.getFilter().isEmpty(), "Snomed concept '=' filters should be removed");
    assertEquals(1, include.getConcept().size(), "Converted include should contain explicit concept codes");
    assertEquals("161665007", include.getConceptFirstRep().getCode());
  }

  @Test
  @DisplayName("ValueSet without description gets default from title")
  void valueSetWithoutDescription_defaultsFromTitle() {
    ValueSet vs = createValueSet("vs-1", "http://example.org/ValueSet/test", 5);
    vs.setTitle("Test Value Set");
    // Ensure no description is set
    assertFalse(vs.hasDescription());

    IBundleProvider results = mock(IBundleProvider.class);
    when(results.isEmpty()).thenReturn(false);
    when(results.getResources(0, 1)).thenReturn(List.of(vs));
    when(mockVsDao.search(any(), any())).thenReturn(results);
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(vs);

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE)
        .setAnswerValueSet("http://example.org/ValueSet/test");

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    assertEquals(1, result.valueSets().size());
    assertEquals("Test Value Set", result.valueSets().get(0).getDescription());
  }

  @Test
  @DisplayName("ValueSet with existing description is not overwritten")
  void valueSetWithDescription_notOverwritten() {
    ValueSet vs = createValueSet("vs-1", "http://example.org/ValueSet/test", 5);
    vs.setTitle("Title");
    vs.setDescription("Original description");

    IBundleProvider results = mock(IBundleProvider.class);
    when(results.isEmpty()).thenReturn(false);
    when(results.getResources(0, 1)).thenReturn(List.of(vs));
    when(mockVsDao.search(any(), any())).thenReturn(results);
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(vs);

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE)
        .setAnswerValueSet("http://example.org/ValueSet/test");

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    assertEquals(1, result.valueSets().size());
    assertEquals("Original description", result.valueSets().get(0).getDescription());
  }

  @Test
  @DisplayName("Deduplication: same URL from Q and Library yields one ValueSet")
  void deduplication() {
    ValueSet vs = createValueSet("vs-1", "http://example.org/ValueSet/test", 5);

    IBundleProvider results = mock(IBundleProvider.class);
    when(results.isEmpty()).thenReturn(false);
    when(results.getResources(0, 1)).thenReturn(List.of(vs));
    when(mockVsDao.search(any(), any())).thenReturn(results);
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(vs);

    // Same URL in Questionnaire and Library
    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE)
        .setAnswerValueSet("http://example.org/ValueSet/test");

    Library lib = new Library();
    lib.setId("lib-1");
    lib.addDataRequirement().addCodeFilter().setValueSet("http://example.org/ValueSet/test");

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of(lib));

    // Only one ValueSet despite two references
    assertEquals(1, result.valueSets().size());
  }

  @Test
  @DisplayName("External ValueSet is persisted to JPA store with expansion")
  void externalValueSetPersistedWithExpansion() {
    // Not in local repository
    IBundleProvider emptyResults = mock(IBundleProvider.class);
    when(emptyResults.isEmpty()).thenReturn(true);
    when(mockVsDao.search(any(), any())).thenReturn(emptyResults);

    // Available via validation support chain
    String vsUrl = "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.132";
    ValueSet vs = createValueSet("vs-vsac", vsUrl, 5);
    when(mockValidationSupport.fetchValueSet(vsUrl)).thenReturn(vs);

    // Expansion succeeds
    ValueSet expanded = new ValueSet();
    expanded.getExpansion().addContains().setSystem("http://example.org/cs").setCode("code-0");
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(expanded);

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE).setAnswerValueSet(vsUrl);

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    assertEquals(1, result.valueSets().size());
    // Verify expansion was set on the ValueSet
    assertTrue(result.valueSets().get(0).hasExpansion());
    // Verify persisted to JPA store
    verify(mockVsDao).update(eq(vs), any(SystemRequestDetails.class));
  }

  @Test
  @DisplayName("Expansion failure during persist does not prevent collection")
  void expansionFailureDuringPersist_stillCollects() {
    IBundleProvider emptyResults = mock(IBundleProvider.class);
    when(emptyResults.isEmpty()).thenReturn(true);
    when(mockVsDao.search(any(), any())).thenReturn(emptyResults);

    String vsUrl = "http://cts.nlm.nih.gov/fhir/ValueSet/example";
    ValueSet vs = createValueSet("vs-vsac", vsUrl, 5);
    when(mockValidationSupport.fetchValueSet(vsUrl)).thenReturn(vs);

    // Expansion fails
    when(mockVsDao.expand(any(ValueSet.class), any()))
        .thenThrow(new RuntimeException("Terminology server unavailable"));

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE).setAnswerValueSet(vsUrl);

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    // ValueSet still collected despite expansion failure
    assertEquals(1, result.valueSets().size());
    // Persist still attempted (without expansion)
    verify(mockVsDao).update(eq(vs), any(SystemRequestDetails.class));
    // Warning recorded for expansion failure
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("expansion failed during persist")));
  }

  // --- resolveAndPersist tests ---

  @Test
  @DisplayName("resolveAndPersist returns existing JPA ValueSet without hitting VSAC")
  void resolveAndPersist_returnsExistingJpaValueSet() {
    ValueSet vs = createValueSet("vs-1", "http://cts.nlm.nih.gov/fhir/ValueSet/example", 5);

    IBundleProvider results = mock(IBundleProvider.class);
    when(results.isEmpty()).thenReturn(false);
    when(results.getResources(0, 1)).thenReturn(List.of(vs));
    when(mockVsDao.search(any(), any())).thenReturn(results);

    List<String> warnings = new java.util.ArrayList<>();
    ValueSet result = collector.resolveAndPersist(
        "http://cts.nlm.nih.gov/fhir/ValueSet/example", warnings);

    assertNotNull(result);
    assertEquals("http://cts.nlm.nih.gov/fhir/ValueSet/example", result.getUrl());
    assertTrue(warnings.isEmpty());
    // VSAC not consulted
    verify(mockValidationSupport, never()).fetchValueSet(anyString());
  }

  @Test
  @DisplayName("resolveAndPersist fetches from VSAC and persists when not in JPA")
  void resolveAndPersist_fetchesFromVsacAndPersists() {
    IBundleProvider emptyResults = mock(IBundleProvider.class);
    when(emptyResults.isEmpty()).thenReturn(true);
    when(mockVsDao.search(any(), any())).thenReturn(emptyResults);

    String vsUrl = "http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113762.1.4.1219.132";
    ValueSet vs = createValueSet("vs-vsac", vsUrl, 3);
    when(mockValidationSupport.fetchValueSet(vsUrl)).thenReturn(vs);

    ValueSet expanded = new ValueSet();
    expanded.getExpansion().addContains().setSystem("http://example.org/cs").setCode("code-0");
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(expanded);

    List<String> warnings = new java.util.ArrayList<>();
    ValueSet result = collector.resolveAndPersist(vsUrl, warnings);

    assertNotNull(result);
    assertTrue(result.hasExpansion());
    verify(mockVsDao).update(eq(vs), any(SystemRequestDetails.class));
    assertTrue(warnings.isEmpty());
  }

  @Test
  @DisplayName("resolveAndPersist returns null and adds warning when not found anywhere")
  void resolveAndPersist_returnsNullWhenNotFound() {
    IBundleProvider emptyResults = mock(IBundleProvider.class);
    when(emptyResults.isEmpty()).thenReturn(true);
    when(mockVsDao.search(any(), any())).thenReturn(emptyResults);

    List<String> warnings = new java.util.ArrayList<>();
    ValueSet result = collector.resolveAndPersist("http://example.org/ValueSet/missing", warnings);

    assertNull(result);
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("not found"));
  }

  @Test
  @DisplayName("Persistence failure does not prevent collection")
  void persistenceFailure_stillCollects() {
    IBundleProvider emptyResults = mock(IBundleProvider.class);
    when(emptyResults.isEmpty()).thenReturn(true);
    when(mockVsDao.search(any(), any())).thenReturn(emptyResults);

    String vsUrl = "http://cts.nlm.nih.gov/fhir/ValueSet/example2";
    ValueSet vs = createValueSet("vs-vsac", vsUrl, 5);
    when(mockValidationSupport.fetchValueSet(vsUrl)).thenReturn(vs);

    // Expansion succeeds
    ValueSet expanded = new ValueSet();
    expanded.getExpansion().addContains().setSystem("http://example.org/cs").setCode("code-0");
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(expanded);

    // Persist fails
    doThrow(new RuntimeException("Database error"))
        .when(mockVsDao).update(any(ValueSet.class), any(SystemRequestDetails.class));

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE).setAnswerValueSet(vsUrl);

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    // ValueSet still collected despite persistence failure
    assertEquals(1, result.valueSets().size());
    // Warning recorded for persistence failure
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Failed to persist")));
  }
}
