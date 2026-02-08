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
}
