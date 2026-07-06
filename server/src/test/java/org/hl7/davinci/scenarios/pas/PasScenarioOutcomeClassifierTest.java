package org.hl7.davinci.scenarios.pas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.hl7.davinci.pas.PasCoverageEvaluator;
import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.davinci.scenarios.pas.PasScenarioOutcomeClassifier.ExpectedOutcome;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasScenarioOutcomeClassifierTest {

  private PasCoverageEvaluator evaluator;
  private PasScenarioOutcomeClassifier classifier;

  @BeforeEach
  void setUp() {
    evaluator = mock(PasCoverageEvaluator.class);
    classifier = new PasScenarioOutcomeClassifier(evaluator);
  }

  private Bundle initialBundle() {
    Claim claim = new Claim();
    claim.setId("demo-claim");
    claim.setPatient(new Reference("Patient/BeneficiaryExample"));
    claim.addInsurance().setSequence(1).setFocal(true)
        .setCoverage(new Reference("Coverage/InsuranceExample"));
    claim.addItem().setSequence(1).setProductOrService(new CodeableConcept().addCoding(
        new Coding("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "E0466", null)));

    Organization insurer = new Organization();
    insurer.setId("InsurerExample");
    insurer.addIdentifier(new Identifier()
        .setSystem("http://hl7.org/fhir/sid/us-npi").setValue("1234567893"));

    Coverage coverage = new Coverage();
    coverage.setId("InsuranceExample");
    coverage.addPayor(new Reference("Organization/InsurerExample"));

    Bundle bundle = new Bundle();
    bundle.addEntry().setFullUrl("http://example.org/fhir/Claim/demo-claim").setResource(claim);
    bundle.addEntry().setFullUrl("http://example.org/fhir/Coverage/InsuranceExample").setResource(coverage);
    bundle.addEntry().setFullUrl("http://example.org/fhir/Organization/InsurerExample").setResource(insurer);
    return bundle;
  }

  @Test
  void classifiesPendedScenarioWithDocumentation() {
    when(evaluator.evaluate(any(), anyList(), any(), anyString(), any()))
        .thenReturn(new CoverageDecision("A4", "Pending", true, null, null,
            "clinical", List.of("http://example.org/fhir/Questionnaire/DocumentationRequired")));

    ExpectedOutcome outcome = classifier.classify("documentation-required", initialBundle());

    assertNotNull(outcome);
    assertEquals("A4", outcome.reviewActionCode());
    assertTrue(outcome.documentationNeeded());
  }

  @Test
  void cachesClassificationPerScenarioId() {
    when(evaluator.evaluate(any(), anyList(), any(), anyString(), any()))
        .thenReturn(new CoverageDecision("A1", "Certified in total", false));

    ExpectedOutcome first = classifier.classify("prior-auth-required", initialBundle());
    ExpectedOutcome second = classifier.classify("prior-auth-required", initialBundle());

    assertEquals("A1", first.reviewActionCode());
    assertFalse(first.documentationNeeded());
    assertEquals(first, second);
    verify(evaluator, times(1)).evaluate(any(), anyList(), any(), anyString(), any());
  }

  @Test
  void evaluationFailureReturnsNullAndIsNotCached() {
    when(evaluator.evaluate(any(), anyList(), any(), anyString(), any()))
        .thenThrow(new RuntimeException("cql failure"))
        .thenReturn(new CoverageDecision("A3", "Not Required", false));

    assertNull(classifier.classify("flaky", initialBundle()));

    ExpectedOutcome retried = classifier.classify("flaky", initialBundle());
    assertNotNull(retried);
    assertEquals("A3", retried.reviewActionCode());
  }

  @Test
  void bundleWithoutClaimReturnsNull() {
    assertNull(classifier.classify("empty", new Bundle()));
  }
}
