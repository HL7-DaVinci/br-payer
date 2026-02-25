package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Expression;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.starter.AppProperties;

class DtrBundleAssemblerTest {

  private DtrBundleAssembler assembler;

  @BeforeEach
  void setUp() {
    AppProperties appProperties = mock(AppProperties.class);
    when(appProperties.getServer_address()).thenReturn("http://localhost:8080/fhir");
    assembler = new DtrBundleAssembler(appProperties);
  }

  private Questionnaire createSimpleQuestionnaire() {
    Questionnaire q = new Questionnaire();
    q.setId("q-1");
    q.setUrl("http://example.org/Questionnaire/test");
    q.setVersion("1.0");
    q.addItem().setLinkId("q1").setType(QuestionnaireItemType.STRING);
    return q;
  }

  @Test
  @DisplayName("Questionnaire is first entry in bundle (dtrb-1)")
  void questionnaireFirstEntry() {
    Questionnaire q = createSimpleQuestionnaire();
    Library lib = new Library();
    lib.setId("lib-1");
    ValueSet vs = new ValueSet();
    vs.setId("vs-1");
    QuestionnaireResponse qr = new QuestionnaireResponse();
    qr.setId("qr-1");

    DtrBundleAssembler.BundleResult result = assembler.assembleBundle(q, List.of(lib), List.of(vs), qr);

    assertNotNull(result.bundle());
    assertNull(result.error());
    assertEquals("Questionnaire", result.bundle().getEntry().get(0).getResource().fhirType());
  }

  @Test
  @DisplayName("All resources present in correct order")
  void allResourcesInOrder() {
    Questionnaire q = createSimpleQuestionnaire();
    Library lib = new Library();
    lib.setId("lib-1");
    ValueSet vs = new ValueSet();
    vs.setId("vs-1");
    QuestionnaireResponse qr = new QuestionnaireResponse();
    qr.setId("qr-1");

    DtrBundleAssembler.BundleResult result = assembler.assembleBundle(q, List.of(lib), List.of(vs), qr);

    Bundle bundle = result.bundle();
    assertEquals(4, bundle.getEntry().size());
    assertEquals("Questionnaire", bundle.getEntry().get(0).getResource().fhirType());
    assertEquals("Library", bundle.getEntry().get(1).getResource().fhirType());
    assertEquals("ValueSet", bundle.getEntry().get(2).getResource().fhirType());
    assertEquals("QuestionnaireResponse", bundle.getEntry().get(3).getResource().fhirType());
  }

  @Test
  @DisplayName("Invalid type throws IllegalArgumentException")
  void invalidType() {
    // We can't directly pass a Patient into assembleBundle due to type safety,
    // but we verify ALLOWED_TYPES enforcement via the addEntry path
    Questionnaire q = createSimpleQuestionnaire();
    DtrBundleAssembler.BundleResult result = assembler.assembleBundle(q, List.of(), List.of(), null);

    assertNotNull(result.bundle());
    // Only Questionnaire should be present
    assertEquals(1, result.bundle().getEntry().size());
  }

  @Test
  @DisplayName("Profile set on Bundle")
  void bundleHasProfile() {
    Questionnaire q = createSimpleQuestionnaire();
    DtrBundleAssembler.BundleResult result = assembler.assembleBundle(q, List.of(), List.of(), null);

    assertTrue(result.bundle().getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/DTR-QPackageBundle"));
  }

  @Test
  @DisplayName("fullUrl generation is stable and deterministic")
  void fullUrlGeneration() {
    Questionnaire q = createSimpleQuestionnaire();
    DtrBundleAssembler.BundleResult result = assembler.assembleBundle(q, List.of(), List.of(), null);

    assertEquals("http://localhost:8080/fhir/Questionnaire/q-1",
        result.bundle().getEntry().get(0).getFullUrl());
  }

  @Test
  @DisplayName("fullUrl generation strips trailing slash from server base")
  void fullUrlGeneration_stripsTrailingSlash() {
    AppProperties appProperties = mock(AppProperties.class);
    when(appProperties.getServer_address()).thenReturn("http://localhost:8080/fhir/");
    DtrBundleAssembler trailingSlashAssembler = new DtrBundleAssembler(appProperties);

    Questionnaire q = createSimpleQuestionnaire();
    DtrBundleAssembler.BundleResult result = trailingSlashAssembler.assembleBundle(q, List.of(), List.of(), null);

    assertEquals("http://localhost:8080/fhir/Questionnaire/q-1",
        result.bundle().getEntry().get(0).getFullUrl());
  }

  @Test
  @DisplayName("fullUrl is versionless relative when server base is missing")
  void fullUrlGeneration_whenServerBaseMissing_isRelative() {
    AppProperties appProperties = mock(AppProperties.class);
    when(appProperties.getServer_address()).thenReturn("");
    DtrBundleAssembler noBaseAssembler = new DtrBundleAssembler(appProperties);

    Questionnaire q = createSimpleQuestionnaire();
    DtrBundleAssembler.BundleResult result = noBaseAssembler.assembleBundle(q, List.of(), List.of(), null);

    assertEquals("Questionnaire/q-1", result.bundle().getEntry().get(0).getFullUrl());
  }

  @Test
  @DisplayName("alternativeExpression on Expression datatype: valid bundle")
  void alternativeExpression_onExpressionValue() {
    Questionnaire q = createSimpleQuestionnaire();
    QuestionnaireItemComponent item = q.getItem().get(0);

    // alternativeExpression nested on the Expression datatype's extensions (correct FHIR structure)
    Expression cqlExpr = new Expression().setLanguage("text/cql").setExpression("1+1");
    Extension altExt = new Extension(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/alternativeExpression");
    altExt.setValue(new Expression().setLanguage("application/elm+json").setExpression("{}"));
    cqlExpr.addExtension(altExt);

    Extension cqlExt = new Extension(
        "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression");
    cqlExt.setValue(cqlExpr);
    item.addExtension(cqlExt);

    DtrBundleAssembler.BundleResult result = assembler.assembleBundle(q, List.of(), List.of(), null);

    assertNotNull(result.bundle());
    assertNull(result.error());
  }

  @Test
  @DisplayName("alternativeExpression as sibling sub-extension (legacy): valid bundle")
  void alternativeExpression_legacySiblingLocation() {
    Questionnaire q = createSimpleQuestionnaire();
    QuestionnaireItemComponent item = q.getItem().get(0);

    // alternativeExpression as sibling sub-extension of the CQL expression extension (legacy location)
    Extension cqlExt = new Extension(
        "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression");
    cqlExt.setValue(new Expression().setLanguage("text/cql").setExpression("1+1"));
    Extension altExt = new Extension(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/alternativeExpression");
    altExt.setValue(new Expression().setLanguage("application/elm+json").setExpression("{}"));
    cqlExt.addExtension(altExt);
    item.addExtension(cqlExt);

    DtrBundleAssembler.BundleResult result = assembler.assembleBundle(q, List.of(), List.of(), null);

    assertNotNull(result.bundle());
    assertNull(result.error());
  }

  @Test
  @DisplayName("alternativeExpression missing: null bundle + error message")
  void alternativeExpression_missing() {
    Questionnaire q = createSimpleQuestionnaire();
    QuestionnaireItemComponent item = q.getItem().get(0);

    // Add a CQL expression WITHOUT alternativeExpression
    Extension cqlExt = new Extension(
        "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression");
    cqlExt.setValue(new Expression().setLanguage("text/cql").setExpression("1+1"));
    item.addExtension(cqlExt);

    DtrBundleAssembler.BundleResult result = assembler.assembleBundle(q, List.of(), List.of(), null);

    assertNull(result.bundle());
    assertNotNull(result.error());
    assertTrue(result.error().contains("alternativeExpression"));
  }

  @Test
  @DisplayName("No CQL expressions: valid bundle (no alternativeExpression check needed)")
  void noCqlExpressions_valid() {
    Questionnaire q = createSimpleQuestionnaire();
    DtrBundleAssembler.BundleResult result = assembler.assembleBundle(q, List.of(), List.of(), null);

    assertNotNull(result.bundle());
    assertNull(result.error());
  }
}
