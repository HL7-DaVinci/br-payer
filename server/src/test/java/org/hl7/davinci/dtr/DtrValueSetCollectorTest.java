package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.hl7.davinci.common.VsacValueSetResolver;
import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoValueSet;

class DtrValueSetCollectorTest {

  private DtrValueSetCollector collector;
  private DaoRegistry mockDaoRegistry;
  private IFhirResourceDaoValueSet<ValueSet> mockVsDao;
  private VsacValueSetResolver mockResolver;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    mockDaoRegistry = mock(DaoRegistry.class);
    mockVsDao = mock(IFhirResourceDaoValueSet.class);
    mockResolver = mock(VsacValueSetResolver.class);
    when(mockDaoRegistry.getResourceDao(ValueSet.class)).thenReturn(mockVsDao);
    collector = new DtrValueSetCollector(mockDaoRegistry, mockResolver);
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
    when(mockResolver.resolveAndPersist(eq("http://example.org/ValueSet/test"), anyList())).thenReturn(vs);
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
    when(mockResolver.resolveAndPersist(eq("http://example.org/ValueSet/test"), anyList())).thenReturn(vs);
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
  @DisplayName("ValueSet not found: warning added")
  void valueSetNotFound_warning() {
    when(mockResolver.resolveAndPersist(eq("http://example.org/ValueSet/missing"), anyList()))
        .thenReturn(null);

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE)
        .setAnswerValueSet("http://example.org/ValueSet/missing");

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of());

    assertTrue(result.valueSets().isEmpty());
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

    when(mockResolver.resolveAndPersist(eq("http://example.org/ValueSet/snomed-filter"), anyList()))
        .thenReturn(vs);
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
    assertFalse(vs.hasDescription());

    when(mockResolver.resolveAndPersist(eq("http://example.org/ValueSet/test"), anyList())).thenReturn(vs);
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

    when(mockResolver.resolveAndPersist(eq("http://example.org/ValueSet/test"), anyList())).thenReturn(vs);
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
    when(mockResolver.resolveAndPersist(eq("http://example.org/ValueSet/test"), anyList())).thenReturn(vs);
    when(mockVsDao.expand(any(ValueSet.class), any())).thenReturn(vs);

    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.CHOICE)
        .setAnswerValueSet("http://example.org/ValueSet/test");

    Library lib = new Library();
    lib.setId("lib-1");
    lib.addDataRequirement().addCodeFilter().setValueSet("http://example.org/ValueSet/test");

    DtrValueSetCollector.ValueSetCollection result = collector.collectValueSets(q, List.of(lib));

    assertEquals(1, result.valueSets().size());
  }
}
