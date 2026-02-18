package org.hl7.davinci.pas;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

/**
 * Constructs PAS-conformant ClaimResponse bundles and inquiry response Parameters.
 * Handles correct extension placement (reviewAction on adjudication), item sequence echoing,
 * and bundle wrapping per the PAS IG profiles.
 */
@Component
public class PasResponseBuilder {

  private static final String ADJUDICATION_SYSTEM = "http://terminology.hl7.org/CodeSystem/adjudication";

  private final AtomicLong authCounter = new AtomicLong(0);

  /**
   * Builds a PAS Response Bundle from a submitted Claim and per-item coverage decisions.
   *
   * @param requestClaim the original Claim from the request bundle
   * @param requestBundle the full request bundle (for resource references)
   * @param itemDecisions map of Claim.item.sequence -> CoverageDecision
   * @param authNumberPrefix prefix for generated authorization numbers
   * @return a PAS Response Bundle with ClaimResponse as the first entry
   */
  public Bundle buildSubmitResponse(Claim requestClaim, Bundle requestBundle,
      Map<Integer, CoverageDecision> itemDecisions, String authNumberPrefix) {

    ClaimResponse claimResponse = buildClaimResponse(requestClaim, itemDecisions, authNumberPrefix);
    return wrapInResponseBundle(claimResponse);
  }

  /**
   * Builds a Parameters resource wrapping each matching ClaimResponse in a "responseBundle" parameter.
   * Per PAS IG narrative/examples for $inquire output.
   */
  public Parameters buildInquiryResponse(List<ClaimResponse> matches) {
    Parameters params = new Parameters();
    for (ClaimResponse cr : matches) {
      Bundle responseBundle = wrapInResponseBundle(cr);
      responseBundle.getMeta().getProfile().clear();
      responseBundle.getMeta().addProfile(PasExtensions.PROFILE_PAS_INQUIRY_RESPONSE_BUNDLE);
      params.addParameter().setName("responseBundle").setResource(responseBundle);
    }
    return params;
  }

  /**
   * Builds a resolved response bundle by upgrading a pended ClaimResponse (A4) to A1.
   * Used by PasSubscriptionService when pended authorizations resolve.
   */
  public Bundle buildResolvedResponse(ClaimResponse pendedResponse, String authNumberPrefix) {
    // Deep copy to avoid mutating the original
    ClaimResponse resolved = pendedResponse.copy();

    for (ClaimResponse.ItemComponent item : resolved.getItem()) {
      for (ClaimResponse.AdjudicationComponent adj : item.getAdjudication()) {
        // Remove existing reviewAction extension and replace with A1
        adj.getExtension().removeIf(e -> PasExtensions.REVIEW_ACTION.equals(e.getUrl()));
        String authNumber = authNumberPrefix + String.format("%04d", authCounter.incrementAndGet());
        adj.addExtension(PasExtensions.buildReviewActionExtension(
            PasExtensions.REVIEW_CODE_A1, "Certified in total", authNumber));
      }
      // Add preAuthPeriod if not already present
      if (item.getExtensionByUrl(PasExtensions.ITEM_PREAUTH_PERIOD) == null) {
        item.addExtension(buildDefaultPreAuthPeriod());
      }
    }

    return wrapInResponseBundle(resolved);
  }

  // ===== Internal =====

  private ClaimResponse buildClaimResponse(Claim requestClaim,
      Map<Integer, CoverageDecision> itemDecisions, String authNumberPrefix) {

    ClaimResponse cr = new ClaimResponse();
    cr.setId(UUID.randomUUID().toString());
    cr.getMeta().addProfile(PasExtensions.PROFILE_PAS_CLAIM_RESPONSE);

    cr.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);
    cr.setType(requestClaim.getType().copy());
    cr.setUse(ClaimResponse.Use.PREAUTHORIZATION);
    cr.setPatient(requestClaim.getPatient().copy());
    cr.setCreated(new Date());
    cr.setInsurer(requestClaim.getInsurer().copy());
    cr.setRequestor(requestClaim.getProvider().copy());
    cr.setOutcome(ClaimResponse.RemittanceOutcome.COMPLETE);

    // Reference back to the original Claim
    String claimId = requestClaim.getIdElement().getIdPart();
    if (claimId != null) {
      cr.setRequest(new Reference("Claim/" + claimId));
    }

    // Build response items echoing request item sequences
    for (Claim.ItemComponent requestItem : requestClaim.getItem()) {
      int seq = requestItem.getSequence();
      CoverageDecision decision = itemDecisions.getOrDefault(seq,
          new CoverageDecision(PasExtensions.REVIEW_CODE_A3, "Not Required", false));

      ClaimResponse.ItemComponent responseItem = cr.addItem();
      responseItem.setItemSequence(seq);

      // Adjudication with reviewAction extension
      ClaimResponse.AdjudicationComponent adj = responseItem.addAdjudication();
      adj.setCategory(new CodeableConcept().addCoding(
          new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")));

      boolean isApproved = PasExtensions.REVIEW_CODE_A1.equals(decision.reviewActionCode());
      String authNumber = null;
      if (isApproved) {
        authNumber = authNumberPrefix + String.format("%04d", authCounter.incrementAndGet());
      }

      adj.addExtension(PasExtensions.buildReviewActionExtension(
          decision.reviewActionCode(), decision.reviewActionDisplay(), authNumber));

      // Approved items get additional extensions per PAS IG
      if (isApproved) {
        responseItem.addExtension(buildDefaultPreAuthPeriod());
      }
    }

    return cr;
  }

  private Bundle wrapInResponseBundle(ClaimResponse claimResponse) {
    Bundle bundle = new Bundle();
    bundle.getMeta().addProfile(PasExtensions.PROFILE_PAS_RESPONSE_BUNDLE);
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.setTimestamp(new Date());
    bundle.setIdentifier(new Identifier()
        .setSystem("http://example.org/SUBMITTER_TRANSACTION_IDENTIFIER")
        .setValue(UUID.randomUUID().toString()));

    bundle.addEntry()
        .setFullUrl("http://example.org/fhir/ClaimResponse/" + claimResponse.getId())
        .setResource(claimResponse);

    return bundle;
  }

  private Extension buildDefaultPreAuthPeriod() {
    Date now = new Date();
    Calendar cal = Calendar.getInstance();
    cal.setTime(now);
    cal.add(Calendar.MONTH, 1);
    Date oneMonthLater = cal.getTime();

    Period period = new Period();
    period.setStart(now);
    period.setEnd(oneMonthLater);
    return new Extension(PasExtensions.ITEM_PREAUTH_PERIOD, period);
  }
}
