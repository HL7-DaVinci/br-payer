package org.hl7.davinci.pas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hl7.davinci.common.PayorIdentifierUtil;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * Orchestrates the PAS $submit workflow: validate -> extract -> evaluate -> build response -> store.
 * Coordinates PasBundleValidator, PasCoverageEvaluator, PasResponseBuilder, and DaoRegistry
 * so the provider classes stay thin and the business logic is testable in isolation.
 */
@Service
public class PasSubmitService {

  static final String PENDED_TAG_SYSTEM = "http://hl7.org/fhir/us/davinci-pas/tag";
  static final String PENDED_TAG_CODE = "pended-resolution";

  private static final String AUTH_PREFIX = "AUTH-";

  private final PasBundleValidator validator;
  private final PasCoverageEvaluator evaluator;
  private final PasResponseBuilder responseBuilder;
  private final DaoRegistry daoRegistry;
  private final String serverBase;

  public PasSubmitService(PasBundleValidator validator, PasCoverageEvaluator evaluator,
      PasResponseBuilder responseBuilder, DaoRegistry daoRegistry, AppProperties appProperties) {
    this.validator = validator;
    this.evaluator = evaluator;
    this.responseBuilder = responseBuilder;
    this.daoRegistry = daoRegistry;
    String base = appProperties.getServer_address();
    this.serverBase = (base != null && base.endsWith("/"))
        ? base.substring(0, base.length() - 1) : base;
  }

  /**
   * Processes a PAS $submit request bundle and returns a PAS response bundle.
   *
   * @param requestBundle the PAS request bundle containing a Claim as first entry
   * @return PAS response bundle containing a ClaimResponse
   * @throws IllegalArgumentException if the request bundle is invalid
   */
  public Bundle submit(Bundle requestBundle) {
    Claim claim = validator.validateSubmitBundle(requestBundle);

    Coverage coverage = findCoverage(requestBundle, claim);
    List<Identifier> payorIdentifiers = extractPayorIdentifiers(requestBundle, coverage);
    String patientId = claim.getPatient().getReference();

    // Evaluate coverage for each Claim item
    Map<Integer, CoverageDecision> itemDecisions = new LinkedHashMap<>();
    boolean anyPended = false;
    for (Claim.ItemComponent item : claim.getItem()) {
      Coding orderCode = item.getProductOrService().getCodingFirstRep();
      CoverageDecision decision = evaluator.evaluate(
          orderCode, payorIdentifiers, coverage, patientId, requestBundle);
      itemDecisions.put(item.getSequence(), decision);
      if (decision.isPended()) {
        anyPended = true;
      }
    }

    Bundle responseBundle = responseBuilder.buildSubmitResponse(
        claim, requestBundle, itemDecisions, AUTH_PREFIX);

    // Tag ClaimResponse for scheduler discovery if any item is pended
    ClaimResponse claimResponse = (ClaimResponse) responseBundle.getEntryFirstRep().getResource();
    if (anyPended) {
      claimResponse.getMeta().addTag(PENDED_TAG_SYSTEM, PENDED_TAG_CODE, "Pended Resolution");
    }

    // The Claim arrives with the provider's server ID — strip it so the payer assigns its own.
    // This prevents ID collisions and correctly separates provider vs payer identity.
    claim.setId((String) null);
    DaoMethodOutcome claimOutcome = daoRegistry.getResourceDao(Claim.class)
        .create(claim, new SystemRequestDetails());

    // Point ClaimResponse.request at the payer's stored copy of the Claim
    String serverClaimId = claimOutcome.getId().getIdPart();
    claimResponse.setRequest(new Reference("Claim/" + serverClaimId));

    // Store the ClaimResponse with a payer-assigned ID
    claimResponse.setId((String) null);
    DaoMethodOutcome crOutcome = daoRegistry.getResourceDao(ClaimResponse.class)
        .create(claimResponse, new SystemRequestDetails());

    // Update the response with the server-assigned ID and a resolvable fullUrl
    String crId = crOutcome.getId().getIdPart();
    claimResponse.setId(crId);
    responseBundle.getEntryFirstRep()
        .setFullUrl(serverBase + "/ClaimResponse/" + crId);

    return responseBundle;
  }

  /**
   * Finds the Coverage resource in the bundle by following the Claim's focal insurance reference.
   */
  private Coverage findCoverage(Bundle bundle, Claim claim) {
    if (!claim.hasInsurance()) return null;
    String coverageRef = claim.getInsuranceFirstRep().getCoverage().getReference();
    if (coverageRef == null) return null;

    Coverage coverage = ResourceResolver.findInBundle(coverageRef, Coverage.class, bundle);
    if (coverage != null) {
      return coverage;
    }

    // Fall back to first Coverage in bundle
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (entry.getResource() instanceof Coverage cov) return cov;
    }
    return null;
  }

  /**
   * Extracts payor Organization identifiers from the bundle via Coverage.payor references.
   */
  private List<Identifier> extractPayorIdentifiers(Bundle bundle, Coverage coverage) {
    return PayorIdentifierUtil.extractFirstFromCoverageAndBundle(coverage, bundle);
  }
}
