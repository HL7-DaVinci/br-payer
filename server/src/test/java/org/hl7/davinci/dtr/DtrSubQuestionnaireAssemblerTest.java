package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.IBundleProvider;

class DtrSubQuestionnaireAssemblerTest {

  private static final String SUB_Q_EXT_URL =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-subQuestionnaire";

  private DtrSubQuestionnaireAssembler assembler;
  private DaoRegistry mockDaoRegistry;
  private IFhirResourceDao<Questionnaire> mockQDao;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    mockDaoRegistry = mock(DaoRegistry.class);
    mockQDao = mock(IFhirResourceDao.class);
    when(mockDaoRegistry.getResourceDao(Questionnaire.class)).thenReturn(mockQDao);
    assembler = new DtrSubQuestionnaireAssembler(mockDaoRegistry);
  }

  @Test
  @DisplayName("No sub-questionnaire extensions: passthrough, no warnings")
  void noSubQExtensions_passthrough() {
    Questionnaire q = new Questionnaire();
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.STRING);

    List<String> warnings = assembler.assemble(q);

    assertTrue(warnings.isEmpty());
    assertEquals(1, q.getItem().size());
    assertEquals("q1", q.getItem().get(0).getLinkId());
  }

  @Test
  @DisplayName("Single sub-questionnaire: items inlined, extension removed, linkIds prefixed")
  void singleSubQ_inlined() {
    // Sub-questionnaire
    Questionnaire subQ = new Questionnaire();
    subQ.setUrl("http://example.org/Questionnaire/sub1");
    subQ.addItem().setLinkId("s1").setType(QuestionnaireItemType.STRING);
    subQ.addItem().setLinkId("s2").setType(QuestionnaireItemType.BOOLEAN);

    IBundleProvider results = mock(IBundleProvider.class);
    when(results.isEmpty()).thenReturn(false);
    when(results.getResources(0, 1)).thenReturn(List.of(subQ));
    when(mockQDao.search(any(), any())).thenReturn(results);

    // Parent questionnaire
    Questionnaire q = new Questionnaire();
    q.setUrl("http://example.org/Questionnaire/parent");
    QuestionnaireItemComponent displayItem = q.addItem();
    displayItem.setLinkId("group1");
    displayItem.setType(QuestionnaireItemType.DISPLAY);
    displayItem.addExtension(new Extension(SUB_Q_EXT_URL,
        new CanonicalType("http://example.org/Questionnaire/sub1")));

    List<String> warnings = assembler.assemble(q);

    assertTrue(warnings.isEmpty());
    // Extension removed
    assertNull(q.getItem().get(0).getExtensionByUrl(SUB_Q_EXT_URL));
    // Items inlined with prefixed linkIds
    assertEquals(2, q.getItem().get(0).getItem().size());
    assertEquals("group1.s1", q.getItem().get(0).getItem().get(0).getLinkId());
    assertEquals("group1.s2", q.getItem().get(0).getItem().get(1).getLinkId());
  }

  @Test
  @DisplayName("Circular reference: detected, warning returned, no infinite loop")
  void circularReference_detected() {
    // Sub-Q references itself
    Questionnaire subQ = new Questionnaire();
    subQ.setUrl("http://example.org/Questionnaire/circular");
    QuestionnaireItemComponent selfRef = subQ.addItem();
    selfRef.setLinkId("loop");
    selfRef.setType(QuestionnaireItemType.DISPLAY);
    selfRef.addExtension(new Extension(SUB_Q_EXT_URL,
        new CanonicalType("http://example.org/Questionnaire/circular")));

    IBundleProvider results = mock(IBundleProvider.class);
    when(results.isEmpty()).thenReturn(false);
    when(results.getResources(0, 1)).thenReturn(List.of(subQ));
    when(mockQDao.search(any(), any())).thenReturn(results);

    Questionnaire q = new Questionnaire();
    q.setUrl("http://example.org/Questionnaire/parent");
    QuestionnaireItemComponent item = q.addItem();
    item.setLinkId("ref");
    item.setType(QuestionnaireItemType.DISPLAY);
    item.addExtension(new Extension(SUB_Q_EXT_URL,
        new CanonicalType("http://example.org/Questionnaire/circular")));

    List<String> warnings = assembler.assemble(q);

    assertTrue(warnings.stream().anyMatch(w -> w.contains("Circular")));
  }

  @Test
  @DisplayName("Missing sub-questionnaire: warning returned, item left as-is")
  void missingSubQ_warning() {
    IBundleProvider emptyResults = mock(IBundleProvider.class);
    when(emptyResults.isEmpty()).thenReturn(true);
    when(mockQDao.search(any(), any())).thenReturn(emptyResults);

    Questionnaire q = new Questionnaire();
    QuestionnaireItemComponent item = q.addItem();
    item.setLinkId("ref");
    item.setType(QuestionnaireItemType.DISPLAY);
    item.addExtension(new Extension(SUB_Q_EXT_URL,
        new CanonicalType("http://example.org/Questionnaire/missing")));

    List<String> warnings = assembler.assemble(q);

    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("not found"));
    // Extension still present since resolution failed
    assertNotNull(item.getExtensionByUrl(SUB_Q_EXT_URL));
  }
}
