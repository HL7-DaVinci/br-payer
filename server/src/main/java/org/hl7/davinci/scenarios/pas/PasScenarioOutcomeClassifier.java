package org.hl7.davinci.scenarios.pas;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hl7.davinci.common.PayorIdentifierUtil;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.davinci.pas.PasCoverageEvaluator;
import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Classifies generated PAS scenarios by the review action their initial
 * submission would produce, using the same coverage evaluation as
 * Claim/$submit. The dry run is read-only: it searches PlanDefinitions and
 * runs $apply against the scenario bundle without persisting anything.
 *
 * Results are cached per scenario id for the server's lifetime because
 * library PlanDefinitions and CQL are fixed after startup. Failed
 * evaluations are not cached so a transient failure does not stick.
 */
@Service
public class PasScenarioOutcomeClassifier {

  private static final Logger logger = LoggerFactory.getLogger(PasScenarioOutcomeClassifier.class);

  /** The review action an initial $submit is expected to produce. */
  public record ExpectedOutcome(String reviewActionCode, boolean documentationNeeded) {
  }

  private final PasCoverageEvaluator evaluator;
  private final Map<String, ExpectedOutcome> cache = new ConcurrentHashMap<>();

  public PasScenarioOutcomeClassifier(PasCoverageEvaluator evaluator) {
    this.evaluator = evaluator;
  }

  /**
   * Returns the expected outcome for a scenario's initial submission, or null
   * when the bundle cannot be evaluated. Scenario claims carry a single item,
   * so the first item's decision is the scenario's outcome.
   */
  public ExpectedOutcome classify(String scenarioId, Bundle initialBundle) {
    ExpectedOutcome cached = cache.get(scenarioId);
    if (cached != null) {
      return cached;
    }
    ExpectedOutcome outcome = dryRun(scenarioId, initialBundle);
    if (outcome != null) {
      cache.put(scenarioId, outcome);
    }
    return outcome;
  }

  private ExpectedOutcome dryRun(String scenarioId, Bundle bundle) {
    if (bundle == null || !bundle.hasEntry()
        || !(bundle.getEntryFirstRep().getResource() instanceof Claim claim)
        || !claim.hasItem()) {
      return null;
    }
    try {
      Coverage coverage = resolveCoverage(claim, bundle);
      List<Identifier> payorIdentifiers =
          PayorIdentifierUtil.extractFirstFromCoverageAndBundle(coverage, bundle);
      Coding orderCode = claim.getItemFirstRep().getProductOrService().getCodingFirstRep();
      String patientId = claim.getPatient().getReference();

      CoverageDecision decision = evaluator.evaluate(
          orderCode, payorIdentifiers, coverage, patientId, bundle);
      return new ExpectedOutcome(
          decision.reviewActionCode(), decision.hasAdditionalDocumentationInfo());
    } catch (RuntimeException e) {
      logger.warn("Could not classify expected outcome for scenario {}", scenarioId, e);
      return null;
    }
  }

  private Coverage resolveCoverage(Claim claim, Bundle bundle) {
    if (!claim.hasInsurance() || !claim.getInsuranceFirstRep().hasCoverage()) {
      return null;
    }
    String ref = claim.getInsuranceFirstRep().getCoverage().getReference();
    return ResourceResolver.findInBundle(ref, Coverage.class, bundle);
  }
}
