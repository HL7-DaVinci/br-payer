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
    when(mockResponseBuilder.buildResponse(any(), any(), any(), any()))
        .thenReturn(new QuestionnaireResponse());
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
        testCoverage, List.of(), List.of(new CanonicalType(canonical)), null);

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
        testCoverage, List.of(), List.of(new CanonicalType("http://example.org/Questionnaire/missing")), null);

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
        testCoverage, List.of(), List.of(new CanonicalType(canonical)), changedsince);

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
        testCoverage, List.of(), List.of(new CanonicalType(canonical)), null);

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
            new CanonicalType("http://example.org/Questionnaire/test2|1.0")), null);

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
            new CanonicalType("http://example.org/Questionnaire/missing")), null);

    assertEquals(1, result.getParameter().stream()
        .filter(p -> "packagebundle".equals(p.getName())).count());
    assertTrue(result.hasParameter("outcome"));
  }

  @Test
  @DisplayName("Output Parameters has correct profile")
  void outputProfile() {
    when(mockResolver.resolve(any(), any(), any())).thenReturn(
        new DtrQuestionnaireResolver.ResolutionResult(List.of(), List.of()));

    Parameters result = service.generatePackages(testCoverage, List.of(), List.of(), null);

    assertTrue(result.getMeta().hasProfile(
        "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-qpackage-output-parameters"));
  }
}
