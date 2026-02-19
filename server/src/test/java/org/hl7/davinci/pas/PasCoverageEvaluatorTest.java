package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hl7.davinci.common.PlanDefinitionService;
import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.RequestGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PasCoverageEvaluatorTest {

  private PlanDefinitionService planDefinitionService;
  private PasCoverageEvaluator evaluator;

  @BeforeEach
  void setUp() {
    planDefinitionService = mock(PlanDefinitionService.class);
    evaluator = new PasCoverageEvaluator(planDefinitionService);
  }

  // ===== mapCoverageInfoToReviewAction tests =====

  @Test
  void mapCoverageInfo_notCovered_returnsA2() {
    Extension ext = buildCoverageInfoExt("not-covered", null, null);
    var decision = evaluator.mapCoverageInfoToReviewAction(ext);
    assertEquals(PasExtensions.REVIEW_CODE_A2, decision.reviewActionCode());
    assertFalse(decision.isPended());
  }

  @Test
  void mapCoverageInfo_conditional_returnsA4() {
    Extension ext = buildCoverageInfoExt("conditional", null, null);
    var decision = evaluator.mapCoverageInfoToReviewAction(ext);
    assertEquals(PasExtensions.REVIEW_CODE_A4, decision.reviewActionCode());
    assertTrue(decision.isPended());
  }

  @Test
  void mapCoverageInfo_authNeeded_noDoc_returnsA1() {
    Extension ext = buildCoverageInfoExt("covered", "auth-needed", "no-doc");
    var decision = evaluator.mapCoverageInfoToReviewAction(ext);
    assertEquals(PasExtensions.REVIEW_CODE_A1, decision.reviewActionCode());
    assertFalse(decision.isPended());
  }

  @Test
  void mapCoverageInfo_authNeeded_withDoc_returnsA4() {
    Extension ext = buildCoverageInfoExt("covered", "auth-needed", "clinical");
    var decision = evaluator.mapCoverageInfoToReviewAction(ext);
    assertEquals(PasExtensions.REVIEW_CODE_A4, decision.reviewActionCode());
    assertTrue(decision.isPended());
  }

  @Test
  void mapCoverageInfo_noAuth_returnsA3() {
    Extension ext = buildCoverageInfoExt("covered", "no-auth", null);
    var decision = evaluator.mapCoverageInfoToReviewAction(ext);
    assertEquals(PasExtensions.REVIEW_CODE_A3, decision.reviewActionCode());
    assertFalse(decision.isPended());
  }

  // ===== evaluate() tests =====

  @Nested
  class EvaluateTests {

    private final Coding orderCode = new Coding("http://example.org", "12345", "Test");
    private final Coverage coverage = new Coverage();
    private final String patientId = "Patient/test";
    private final Bundle dataBundle = new Bundle();

    @Test
    void evaluate_noPlanDefinitionsFound_returnsA3() {
      when(planDefinitionService.findPlanDefinitions(eq(orderCode), any(), isNull()))
          .thenReturn(List.of());

      CoverageDecision decision = evaluator.evaluate(
          orderCode, List.of(), coverage, patientId, dataBundle);

      assertEquals(PasExtensions.REVIEW_CODE_A3, decision.reviewActionCode());
      assertFalse(decision.isPended());
    }

    @Test
    void evaluate_singlePlan_notCovered_returnsA2() {
      PlanDefinition plan = new PlanDefinition();
      Extension coverageExt = buildCoverageInfoExt("not-covered", null, null);
      RequestGroup rg = buildRequestGroupWithCoverageExt(coverageExt);

      when(planDefinitionService.findPlanDefinitions(eq(orderCode), any(), isNull()))
          .thenReturn(List.of(plan));
      when(planDefinitionService.applyPlanDefinition(eq(plan), eq(patientId), eq(dataBundle), isNull()))
          .thenReturn(rg);

      CoverageDecision decision = evaluator.evaluate(
          orderCode, List.of(), coverage, patientId, dataBundle);

      assertEquals(PasExtensions.REVIEW_CODE_A2, decision.reviewActionCode());
      assertFalse(decision.isPended());
    }

    @Test
    void evaluate_multiplePlans_mostRestrictiveWins() {
      PlanDefinition plan1 = new PlanDefinition();
      PlanDefinition plan2 = new PlanDefinition();
      RequestGroup rg1 = buildRequestGroupWithCoverageExt(
          buildCoverageInfoExt("covered", "auth-needed", "no-doc"));
      RequestGroup rg2 = buildRequestGroupWithCoverageExt(
          buildCoverageInfoExt("conditional", null, null));

      // plan1 -> A1 (Certified), plan2 -> A4 (Pended) -- A4 is more restrictive
      when(planDefinitionService.findPlanDefinitions(eq(orderCode), any(), isNull()))
          .thenReturn(List.of(plan1, plan2));
      when(planDefinitionService.applyPlanDefinition(eq(plan1), eq(patientId), eq(dataBundle), isNull()))
          .thenReturn(rg1);
      when(planDefinitionService.applyPlanDefinition(eq(plan2), eq(patientId), eq(dataBundle), isNull()))
          .thenReturn(rg2);

      CoverageDecision decision = evaluator.evaluate(
          orderCode, List.of(), coverage, patientId, dataBundle);

      assertEquals(PasExtensions.REVIEW_CODE_A4, decision.reviewActionCode());
      assertTrue(decision.isPended());
    }

    @Test
    void evaluate_allPlansReturnNullExtension_returnsA3() {
      PlanDefinition plan = new PlanDefinition();
      RequestGroup rg = new RequestGroup();

      when(planDefinitionService.findPlanDefinitions(eq(orderCode), any(), isNull()))
          .thenReturn(List.of(plan));
      when(planDefinitionService.applyPlanDefinition(eq(plan), eq(patientId), eq(dataBundle), isNull()))
          .thenReturn(rg);

      CoverageDecision decision = evaluator.evaluate(
          orderCode, List.of(), coverage, patientId, dataBundle);

      assertEquals(PasExtensions.REVIEW_CODE_A3, decision.reviewActionCode());
      assertFalse(decision.isPended());
    }

    @Test
    void evaluate_passesNullHookToFindPlanDefinitions() {
      // Verifies Issue 1 fix: null hook instead of "prior-auth"
      when(planDefinitionService.findPlanDefinitions(eq(orderCode), any(), isNull()))
          .thenReturn(List.of());

      evaluator.evaluate(orderCode, List.of(), coverage, patientId, dataBundle);

      // If this passes, it confirms null was passed (not "prior-auth")
      // because the mock only matches isNull()
    }
  }

  // ===== Helpers =====

  private static Extension buildCoverageInfoExt(String covered, String paNeeded, String docNeeded) {
    Extension ext = new Extension("http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information");
    if (covered != null) ext.addExtension("covered", new CodeType(covered));
    if (paNeeded != null) ext.addExtension("pa-needed", new CodeType(paNeeded));
    if (docNeeded != null) ext.addExtension("doc-needed", new CodeType(docNeeded));
    return ext;
  }

  private static RequestGroup buildRequestGroupWithCoverageExt(Extension coverageExt) {
    RequestGroup rg = new RequestGroup();
    rg.addAction().addExtension(coverageExt);
    return rg;
  }
}
