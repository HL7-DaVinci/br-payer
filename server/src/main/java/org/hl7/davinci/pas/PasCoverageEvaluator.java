package org.hl7.davinci.pas;

import java.util.List;

import org.hl7.davinci.common.CoverageInfoUtil;
import org.hl7.davinci.common.PlanDefinitionService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.RequestGroup;
import org.springframework.stereotype.Component;

/**
 * Bridges CRD coverage evaluation (PlanDefinition/CQL) into PAS review action decisions.
 * Reuses the same PlanDefinitions that drive CDS Hook cards, translating CRD outcomes
 * (pa-needed, covered, doc-needed) into X12 306 review action codes (A1-A4).
 */
@Component
public class PasCoverageEvaluator {

  private final PlanDefinitionService planDefinitionService;

  public PasCoverageEvaluator(PlanDefinitionService planDefinitionService) {
    this.planDefinitionService = planDefinitionService;
  }

  /**
   * Result of coverage evaluation for a single claim item.
   */
  public record CoverageDecision(
      String reviewActionCode,
      String reviewActionDisplay,
      boolean isPended) {}

  /**
   * Evaluates coverage for a single Claim item against CRD PlanDefinitions.
   * Returns the PAS review action decision based on CRD coverage-information output.
   *
   * When multiple PlanDefinitions match, the most restrictive decision wins:
   * A2 (Not Certified) > A4 (Pended) > A1 (Certified) > A3 (Not Required).
   *
   * @param orderCode the service/procedure code from the Claim item
   * @param payorIdentifiers identifiers from the focal Coverage.payor
   * @param coverage the Coverage resource from the request bundle
   * @param patientId the Patient ID for PlanDefinition $apply
   * @param dataBundle the full request bundle as additional data context
   * @return the most restrictive CoverageDecision across all matching PlanDefinitions,
   *         or A3 (Not Required) if no PlanDefinitions match
   */
  public CoverageDecision evaluate(Coding orderCode, List<Identifier> payorIdentifiers,
      Coverage coverage, String patientId, Bundle dataBundle) {

    List<PlanDefinition> plans = planDefinitionService.findPlanDefinitions(
        orderCode, payorIdentifiers, null);

    if (plans.isEmpty()) {
      return new CoverageDecision(PasExtensions.REVIEW_CODE_A3, "Not Required", false);
    }

    CoverageDecision best = null;
    int bestRank = 0;

    for (PlanDefinition plan : plans) {
      RequestGroup requestGroup = planDefinitionService.applyPlanDefinition(
          plan, patientId, dataBundle, null);
      Extension coverageExt = CoverageInfoUtil.extractCoverageExtension(requestGroup, coverage, null);
      if (coverageExt == null) {
        continue;
      }
      CoverageDecision decision = mapCoverageInfoToReviewAction(coverageExt);
      int rank = rankDecision(decision.reviewActionCode());
      if (rank > bestRank) {
        bestRank = rank;
        best = decision;
      }
    }

    // If all plans returned null coverage extensions, default to A3
    if (best == null) {
      return new CoverageDecision(PasExtensions.REVIEW_CODE_A3, "Not Required", false);
    }

    return best;
  }

  /**
   * Assigns a numeric rank to a review action code for selecting the most restrictive decision.
   * Higher rank = more restrictive: A2=4, A4=3, A1=2, A3=1.
   */
  private int rankDecision(String reviewActionCode) {
    return switch (reviewActionCode) {
      case PasExtensions.REVIEW_CODE_A2 -> 4;
      case PasExtensions.REVIEW_CODE_A4 -> 3;
      case PasExtensions.REVIEW_CODE_A1 -> 2;
      default -> 1; // A3
    };
  }

  /**
   * Maps a CRD coverage-information extension to a PAS review action decision.
   * Package-private to allow direct testing of mapping logic.
   */
  CoverageDecision mapCoverageInfoToReviewAction(Extension coverageInfoExt) {
    String covered = CoverageInfoUtil.subExtensionCode(coverageInfoExt, "covered");
    String paNeeded = CoverageInfoUtil.subExtensionCode(coverageInfoExt, "pa-needed");

    if ("not-covered".equals(covered)) {
      return new CoverageDecision(PasExtensions.REVIEW_CODE_A2, "Not Certified", false);
    }
    if ("conditional".equals(covered)) {
      return new CoverageDecision(PasExtensions.REVIEW_CODE_A4, "Pending", true);
    }
    String docNeeded = CoverageInfoUtil.subExtensionCode(coverageInfoExt, "doc-needed");
    if ("auth-needed".equals(paNeeded)) {
      if ("no-doc".equals(docNeeded)) {
        return new CoverageDecision(PasExtensions.REVIEW_CODE_A1, "Certified in total", false);
      }
      return new CoverageDecision(PasExtensions.REVIEW_CODE_A4, "Pending", true);
    }
    // no-auth or no pa-needed value
    return new CoverageDecision(PasExtensions.REVIEW_CODE_A3, "Not Required", false);
  }

}
