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
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.starter.AppProperties;

/**
 * Constructs PAS-conformant ClaimResponse bundles and inquiry response Parameters.
 * Handles correct extension placement (reviewAction on adjudication), item sequence echoing,
 * and bundle wrapping per the PAS IG profiles.
 */
@Component
public class PasResponseBuilder {

  private static final String ADJUDICATION_SYSTEM = "http://terminology.hl7.org/CodeSystem/adjudication";

  private final AtomicLong authCounter = new AtomicLong(0);
  private final String serverBase;

  public PasResponseBuilder(AppProperties appProperties) {
    String base = appProperties.getServer_address();
    this.serverBase = (base != null && base.endsWith("/"))
        ? base.substring(0, base.length() - 1) : base;
  }

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
      ClaimResponse sanitized = sanitizeForBundle(cr);
      Bundle responseBundle = wrapInResponseBundle(sanitized);
      responseBundle.getMeta().getProfile().clear();
      responseBundle.getMeta().addProfile(PasConstants.PROFILE_PAS_INQUIRY_RESPONSE_BUNDLE);
      params.addParameter().setName("responseBundle").setResource(responseBundle);
    }
    return params;
  }

  /**
   * Finalizes pended adjudications in-place, transitioning A4 to A1 (Certified).
   */
  public void resolvePendedItems(ClaimResponse claimResponse, String authNumberPrefix) {
    finalizePendedItems(claimResponse, PasConstants.REVIEW_CODE_A1, "Certified in total", authNumberPrefix);
  }

  /**
   * Finalizes pended adjudications in-place, transitioning A4 to A6 (Modified).
   */
  public void modifyPendedItems(ClaimResponse claimResponse, String authNumberPrefix) {
    finalizePendedItems(claimResponse, PasConstants.REVIEW_CODE_A6, "Modified", authNumberPrefix);
  }

  // ===== Internal =====

  private void finalizePendedItems(ClaimResponse claimResponse,
      String targetCode, String targetDisplay, String authNumberPrefix) {
    for (ClaimResponse.ItemComponent item : claimResponse.getItem()) {
      boolean itemWasFinalized = false;
      for (ClaimResponse.AdjudicationComponent adj : item.getAdjudication()) {
        if (!isPendedReviewAction(adj)) {
          continue;
        }

        adj.getExtension().removeIf(e -> PasConstants.REVIEW_ACTION.equals(e.getUrl()));
        String authNumber = authNumberPrefix + String.format("%04d", authCounter.incrementAndGet());
        adj.addExtension(PasConstants.buildReviewActionExtension(targetCode, targetDisplay, authNumber));
        itemWasFinalized = true;
      }

      if (itemWasFinalized && item.getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD) == null) {
        item.addExtension(buildDefaultPreAuthPeriod());
      }
    }
  }

  private ClaimResponse buildClaimResponse(Claim requestClaim,
      Map<Integer, CoverageDecision> itemDecisions, String authNumberPrefix) {

    ClaimResponse cr = new ClaimResponse();
    cr.setId(UUID.randomUUID().toString());
    cr.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_RESPONSE);

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
          new CoverageDecision(PasConstants.REVIEW_CODE_A3, "Not Required", false));

      ClaimResponse.ItemComponent responseItem = cr.addItem();
      responseItem.setItemSequence(seq);

      // Adjudication with reviewAction extension
      ClaimResponse.AdjudicationComponent adj = responseItem.addAdjudication();
      adj.setCategory(new CodeableConcept().addCoding(
          new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")));

      boolean isApproved = PasConstants.REVIEW_CODE_A1.equals(decision.reviewActionCode());
      String authNumber = null;
      if (isApproved) {
        authNumber = authNumberPrefix + String.format("%04d", authCounter.incrementAndGet());
      }

      adj.addExtension(PasConstants.buildReviewActionExtension(
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
    bundle.getMeta().addProfile(PasConstants.PROFILE_PAS_RESPONSE_BUNDLE);
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.setTimestamp(new Date());
    bundle.setIdentifier(new Identifier()
        .setSystem("http://example.org/SUBMITTER_TRANSACTION_IDENTIFIER")
        .setValue(UUID.randomUUID().toString()));

    String idPart = extractClaimResponseIdPart(claimResponse);
    if (idPart == null || idPart.isBlank()) {
      idPart = UUID.randomUUID().toString();
      claimResponse.setId(idPart);
    }

    bundle.addEntry()
        .setFullUrl(serverBase + "/ClaimResponse/" + idPart)
        .setResource(claimResponse);

    return bundle;
  }

  private boolean isPendedReviewAction(ClaimResponse.AdjudicationComponent adjudication) {
    Extension reviewAction = adjudication.getExtensionByUrl(PasConstants.REVIEW_ACTION);
    if (reviewAction == null) {
      return false;
    }

    Extension codeExt = reviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    if (codeExt == null || !(codeExt.getValue() instanceof CodeableConcept codeableConcept)) {
      return false;
    }

    return codeableConcept.getCoding().stream()
        .anyMatch(coding -> PasConstants.REVIEW_CODE_A4.equals(coding.getCode()));
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
    return new Extension(PasConstants.ITEM_PREAUTH_PERIOD, period);
  }

  private ClaimResponse sanitizeForBundle(ClaimResponse source) {
    ClaimResponse copy = source.copy();
    String idPart = extractClaimResponseIdPart(copy);
    if (idPart != null && !idPart.isBlank()) {
      copy.setId(idPart);
    }

    return copy;
  }

  private String extractClaimResponseIdPart(ClaimResponse claimResponse) {
    String idPart = claimResponse.getIdElement().getIdPart();
    if (idPart != null && !idPart.isBlank()) {
      return idPart;
    }

    String rawId = claimResponse.getId();
    if (rawId == null || rawId.isBlank()) {
      return null;
    }

    String parsed = new IdType(rawId).getIdPart();
    if (parsed != null && !parsed.isBlank()) {
      return parsed;
    }
    return rawId;
  }
}
