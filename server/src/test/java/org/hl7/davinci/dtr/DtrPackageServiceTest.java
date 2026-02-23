package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DtrPackageServiceTest {

  private DtrPackageService service;
  private DtrQuestionnaireResolver mockResolver;
  private DtrSubQuestionnaireAssembler mockSubQAssembler;
  private DtrLibraryResolver mockLibResolver;
  private DtrValueSetCollector mockVsCollector;
  private DtrBundleAssembler mockBundleAssembler;
  private DtrResponseBuilder mockResponseBuilder;

  private Coverage testCoverage;

  @BeforeEach
  void setUp() {
    mockResolver = mock(DtrQuestionnaireResolver.class);
    mockSubQAssembler = mock(DtrSubQuestionnaireAssembler.class);
    mockLibResolver = mock(DtrLibraryResolver.class);
    mockVsCollector = mock(DtrValueSetCollector.class);
    mockBundleAssembler = mock(DtrBundleAssembler.class);
    mockResponseBuilder = mock(DtrResponseBuilder.class);

    service = new DtrPackageService(
        mockResolver, mockSubQAssembler, mockLibResolver,
        mockVsCollector, mockBundleAssembler, mockResponseBuilder);

    testCoverage = new Coverage();
    testCoverage.setId("cov-1");
    testCoverage.setBeneficiary(new Reference("Patient/pat-1"));

    // Default stubs
    when(mockSubQAssembler.assemble(any())).thenReturn(List.of());
    when(mockLibResolver.resolveLibraries(any()))
        .thenReturn(new DtrLibraryResolver.LibraryResolution(List.of(), List.of()));
    when(mockVsCollector.collectValueSets(any(), any()))
        .thenReturn(new DtrValueSetCollector.ValueSetCollection(List.of(), List.of()));
    when(mockResponseBuilder.buildResponse(any(), any(), any(), any(), any()))
        .thenReturn(new DtrResponseBuilder.PrepopulationResult(new QuestionnaireResponse(), List.of()));
    when(mockResponseBuilder.buildAdaptiveResponse(any(), any(), any(), any(), any()))
        .thenReturn(new DtrResponseBuilder.PrepopulationResult(
            createAdaptiveQr("adapt-qr-1"), List.of()));
  }

  private QuestionnaireResponse createAdaptiveQr(String id) {
    QuestionnaireResponse qr = new QuestionnaireResponse();
    qr.setId(id);
    return qr;
  }

  private Questionnaire createTestQ(String id, String url, String version) {
    Questionnaire q = new Questionnaire();
    q.setId(id);
    q.setUrl(url);
    q.setVersion(version);
    return q;
  }

  @Test
  @DisplayName("Single questionnaire produces one packagebundle")
  void singleQuestionnaire_onePackage() {
    Questionnaire q = createTestQ("q-1", "http://example.org/Questionnaire/test", "1.0");
    String canonical = "http://example.org/Questionnaire/test|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, q, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    Parameters result = service.generatePackages(
        testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, null);

    assertTrue(result.hasParameter("packagebundle"));
    assertEquals(1, result.getParameter().stream()
        .filter(p -> "packagebundle".equals(p.getName())).count());
  }

  @Test
  @DisplayName("No questionnaires found produces empty Parameters with warning")
  void noQuestionnaires_emptyWithWarning() {
    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                "http://example.org/Questionnaire/missing", null,
                DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(),
                "Questionnaire not found")),
            List.of()));

    Parameters result = service.generatePackages(
        testCoverage, List.of(), List.of(new CanonicalType("http://example.org/Questionnaire/missing")), null, null);

    assertFalse(result.hasParameter("packagebundle"));
    assertTrue(result.hasParameter("outcome"));
  }

  @Test
  @DisplayName("changedsince filters out unchanged package")
  void changedsince_filtersUnchanged() {
    Questionnaire q = createTestQ("q-1", "http://example.org/Questionnaire/test", "1.0");
    // Set lastUpdated to a past date
    q.getMeta().setLastUpdated(new Date(System.currentTimeMillis() - 86400000)); // yesterday
    String canonical = "http://example.org/Questionnaire/test|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, q, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null)),
            List.of()));

    // changedsince in the future
    InstantType changedsince = new InstantType(new Date(System.currentTimeMillis() + 86400000));

    Parameters result = service.generatePackages(
        testCoverage, List.of(), List.of(new CanonicalType(canonical)), changedsince, null);

    // Package should be filtered out
    assertFalse(result.hasParameter("packagebundle"));
  }

  @Test
  @DisplayName("alternativeExpression failure excludes package with error in outcome")
  void alternativeExpression_failure() {
    Questionnaire q = createTestQ("q-1", "http://example.org/Questionnaire/test", "1.0");
    String canonical = "http://example.org/Questionnaire/test|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, q, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null)),
            List.of()));

    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(null, "Missing alternativeExpression"));

    Parameters result = service.generatePackages(
        testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, null);

    assertFalse(result.hasParameter("packagebundle"));
    assertTrue(result.hasParameter("outcome"));
    OperationOutcome outcome = (OperationOutcome) result.getParameter("outcome").getResource();
    assertTrue(outcome.getIssue().stream()
        .anyMatch(i -> i.getDiagnostics().contains("alternativeExpression")));
  }

  @Test
  @DisplayName("Multiple questionnaires produce multiple packagebundles")
  void multipleQuestionnaires_multiplePackages() {
    Questionnaire q1 = createTestQ("q-1", "http://example.org/Questionnaire/test1", "1.0");
    Questionnaire q2 = createTestQ("q-2", "http://example.org/Questionnaire/test2", "1.0");

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                "http://example.org/Questionnaire/test1|1.0", q1,
                DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null),
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                "http://example.org/Questionnaire/test2|1.0", q2,
                DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    Parameters result = service.generatePackages(
        testCoverage, List.of(), List.of(
            new CanonicalType("http://example.org/Questionnaire/test1|1.0"),
            new CanonicalType("http://example.org/Questionnaire/test2|1.0")), null, null);

    assertEquals(2, result.getParameter().stream()
        .filter(p -> "packagebundle".equals(p.getName())).count());
  }

  @Test
  @DisplayName("Partial success: one resolves, one fails, yields one package + warning")
  void partialSuccess() {
    Questionnaire q1 = createTestQ("q-1", "http://example.org/Questionnaire/test1", "1.0");

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                "http://example.org/Questionnaire/test1|1.0", q1,
                DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null),
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                "http://example.org/Questionnaire/missing", null,
                DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(),
                "Questionnaire not found")),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    Parameters result = service.generatePackages(
        testCoverage, List.of(), List.of(
            new CanonicalType("http://example.org/Questionnaire/test1|1.0"),
            new CanonicalType("http://example.org/Questionnaire/missing")), null, null);

    assertEquals(1, result.getParameter().stream()
        .filter(p -> "packagebundle".equals(p.getName())).count());
    assertTrue(result.hasParameter("outcome"));
  }

  @Test
  @DisplayName("Output Parameters has correct profile")
  void outputProfile() {
    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(), List.of()));

    Parameters result = service.generatePackages(testCoverage, List.of(), List.of(), null, null);

    assertTrue(result.getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-qpackage-output-parameters"));
  }

  @Test
  @DisplayName("Adaptive questionnaire routes to buildAdaptiveResponse")
  void adaptiveQuestionnaire_routesToAdaptive() {
    Questionnaire adaptiveQ = createTestQ("q-adapt", "http://example.org/Questionnaire/adaptive", "1.0");
    adaptiveQ.getMeta().addProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt");
    String canonical = "http://example.org/Questionnaire/adaptive|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, adaptiveQ, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE,
                new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    service.generatePackages(testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, null);

    verify(mockResponseBuilder).buildAdaptiveResponse(any(), any(), any(), any(), any());
    verify(mockResponseBuilder, never()).buildResponse(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Adaptive questionnaire default mode includes initial items with adapt profile")
  void adaptiveQuestionnaire_defaultMode_includesInitialItems() {
    Questionnaire adaptiveQ = createTestQ("q-adapt", "http://example.org/Questionnaire/adaptive", "1.0");
    adaptiveQ.getMeta().addProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt");
    adaptiveQ.addItem().setLinkId("1").setText("Some question");
    String canonical = "http://example.org/Questionnaire/adaptive|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, adaptiveQ, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE,
                new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    service.generatePackages(testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, null);

    ArgumentCaptor<Questionnaire> qCaptor = ArgumentCaptor.forClass(Questionnaire.class);
    verify(mockBundleAssembler).assembleBundle(qCaptor.capture(), any(), any(), any());
    Questionnaire bundledQ = qCaptor.getValue();

    assertEquals(1, bundledQ.getItem().size(),
        "Default mode should include initial items without enableWhen");
    assertTrue(bundledQ.getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt"),
        "Bundle Q with initial items should have adapt profile");
    assertFalse(bundledQ.getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt-search"),
        "Should not have adapt-search profile when items are present");
  }

  @Test
  @DisplayName("Adaptive questionnaire search mode produces item-less adapt-search shell")
  void adaptiveQuestionnaire_searchMode_bundleIsItemless() {
    Questionnaire adaptiveQ = createTestQ("q-adapt", "http://example.org/Questionnaire/adaptive", "1.0");
    adaptiveQ.getMeta().addProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt");
    adaptiveQ.addItem().setLinkId("1").setText("Some question");
    String canonical = "http://example.org/Questionnaire/adaptive|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, adaptiveQ, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE,
                new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    service.generatePackages(testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, "search");

    ArgumentCaptor<Questionnaire> qCaptor = ArgumentCaptor.forClass(Questionnaire.class);
    verify(mockBundleAssembler).assembleBundle(qCaptor.capture(), any(), any(), any());
    Questionnaire bundledQ = qCaptor.getValue();

    assertTrue(bundledQ.getItem().isEmpty(),
        "Search mode should produce item-less bundle Q");
    assertTrue(bundledQ.getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt-search"),
        "Search mode should have adapt-search profile");
    assertFalse(bundledQ.getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt"),
        "Search mode should not have adapt profile");
  }

  @Test
  @DisplayName("Adaptive questionnaire with all conditional groups defaults to adapt-search")
  void adaptiveQuestionnaire_allConditional_defaultsToSearch() {
    Questionnaire adaptiveQ = createTestQ("q-adapt", "http://example.org/Questionnaire/adaptive", "1.0");
    adaptiveQ.getMeta().addProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt");
    // All groups have enableWhen, so collectInitialItems returns empty
    adaptiveQ.addItem().setLinkId("1").setText("Conditional group")
        .addEnableWhen().setQuestion("0.1").setOperator(Questionnaire.QuestionnaireItemOperator.EXISTS)
        .setAnswer(new org.hl7.fhir.r4.model.BooleanType(false));
    String canonical = "http://example.org/Questionnaire/adaptive|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, adaptiveQ, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE,
                new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    service.generatePackages(testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, null);

    ArgumentCaptor<Questionnaire> qCaptor = ArgumentCaptor.forClass(Questionnaire.class);
    verify(mockBundleAssembler).assembleBundle(qCaptor.capture(), any(), any(), any());
    Questionnaire bundledQ = qCaptor.getValue();

    assertTrue(bundledQ.getItem().isEmpty(),
        "All-conditional Q should produce item-less bundle Q");
    assertTrue(bundledQ.getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt-search"),
        "All-conditional Q should default to adapt-search profile");
  }

  @Test
  @DisplayName("collectInitialItems stops at first group with enableWhen")
  void adaptiveQuestionnaire_initialMode_stopsAtFirstEnableWhen() {
    Questionnaire adaptiveQ = createTestQ("q-adapt", "http://example.org/Questionnaire/adaptive", "1.0");
    adaptiveQ.getMeta().addProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt");
    adaptiveQ.addItem().setLinkId("1").setText("Always shown");
    adaptiveQ.addItem().setLinkId("2").setText("Also always shown");
    adaptiveQ.addItem().setLinkId("3").setText("Conditional")
        .addEnableWhen().setQuestion("2.1").setOperator(Questionnaire.QuestionnaireItemOperator.EXISTS)
        .setAnswer(new org.hl7.fhir.r4.model.BooleanType(true));
    adaptiveQ.addItem().setLinkId("4").setText("After conditional");
    String canonical = "http://example.org/Questionnaire/adaptive|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, adaptiveQ, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE,
                new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    service.generatePackages(testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, null);

    ArgumentCaptor<Questionnaire> qCaptor = ArgumentCaptor.forClass(Questionnaire.class);
    verify(mockBundleAssembler).assembleBundle(qCaptor.capture(), any(), any(), any());
    Questionnaire bundledQ = qCaptor.getValue();

    assertEquals(2, bundledQ.getItem().size(),
        "Should include groups 1 and 2 but stop before group 3 (has enableWhen)");
    assertEquals("1", bundledQ.getItem().get(0).getLinkId());
    assertEquals("2", bundledQ.getItem().get(1).getLinkId());
  }

  @Test
  @DisplayName("Adaptive questionnaire declaring adapt-search profile defaults to empty items")
  void adaptiveQuestionnaire_adaptSearchProfile_defaultsToEmpty() {
    Questionnaire adaptiveQ = createTestQ("q-adapt", "http://example.org/Questionnaire/adaptive", "1.0");
    adaptiveQ.getMeta().addProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt-search");
    // Unconditional item that collectInitialItems would normally include
    adaptiveQ.addItem().setLinkId("1").setText("Service Category Selection");
    String canonical = "http://example.org/Questionnaire/adaptive|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, adaptiveQ, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE,
                new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    service.generatePackages(testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, null);

    ArgumentCaptor<Questionnaire> qCaptor = ArgumentCaptor.forClass(Questionnaire.class);
    verify(mockBundleAssembler).assembleBundle(qCaptor.capture(), any(), any(), any());
    Questionnaire bundledQ = qCaptor.getValue();

    assertTrue(bundledQ.getItem().isEmpty(),
        "Declared adapt-search profile should produce empty items regardless of structure");
    assertTrue(bundledQ.getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt-search"));
  }

  @Test
  @DisplayName("adaptiveMode 'initial' overrides adapt-search profile to include items")
  void adaptiveQuestionnaire_initialOverridesProfile() {
    Questionnaire adaptiveQ = createTestQ("q-adapt", "http://example.org/Questionnaire/adaptive", "1.0");
    adaptiveQ.getMeta().addProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt-search");
    adaptiveQ.addItem().setLinkId("1").setText("Unconditional item");
    String canonical = "http://example.org/Questionnaire/adaptive|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, adaptiveQ, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE,
                new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    service.generatePackages(testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, "initial");

    ArgumentCaptor<Questionnaire> qCaptor = ArgumentCaptor.forClass(Questionnaire.class);
    verify(mockBundleAssembler).assembleBundle(qCaptor.capture(), any(), any(), any());
    Questionnaire bundledQ = qCaptor.getValue();

    assertEquals(1, bundledQ.getItem().size(),
        "Header 'initial' should override adapt-search profile and include items");
    assertTrue(bundledQ.getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt"),
        "Should use adapt profile when items are present");
  }

  @Test
  @DisplayName("Standard questionnaire still routes to buildResponse (regression)")
  void standardQuestionnaire_routesToStandard() {
    Questionnaire q = createTestQ("q-1", "http://example.org/Questionnaire/test", "1.0");
    String canonical = "http://example.org/Questionnaire/test|1.0";

    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(
            new DtrQuestionnaireResolver.ResolvedQuestionnaire(
                canonical, q, DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE,
                new ArrayList<>(), null)),
            List.of()));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(mockBundleAssembler.assembleBundle(any(), any(), any(), any()))
        .thenReturn(new DtrBundleAssembler.BundleResult(bundle, null));

    service.generatePackages(testCoverage, List.of(), List.of(new CanonicalType(canonical)), null, null);

    verify(mockResponseBuilder).buildResponse(any(), any(), any(), any(), any());
    verify(mockResponseBuilder, never()).buildAdaptiveResponse(any(), any(), any(), any(), any());
  }
}
