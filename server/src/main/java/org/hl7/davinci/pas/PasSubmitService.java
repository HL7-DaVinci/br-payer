package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.common.PayorIdentifierUtil;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;

import org.hl7.fhir.r4.model.Meta;

/**
 * Orchestrates the PAS $submit workflow: validate -> extract -> evaluate -> build response -> store.
 * Routes submissions to type-specific handlers based on PAS IG submission semantics:
 * initial, renewal, update, and cancel.
 */
@Service
@EnableConfigurationProperties(PasProperties.class)
public class PasSubmitService {

  static final String PENDED_TAG_SYSTEM = "http://example.org/fhir/us/davinci-pas/internal-tags";
  static final String PENDED_TAG_CODE = "pended-resolution";

  enum SubmissionType { INITIAL, RENEWAL, UPDATE, CANCEL }

  /**
   * Holds item decisions plus the prior ClaimResponse (non-null for update/cancel paths).
   * When priorClaimResponse is present, the existing CR is modified in-place rather than
   * creating a new one.
   */
  record SubmissionResult(
      Map<Integer, CoverageDecision> itemDecisions,
      ClaimResponse priorClaimResponse
  ) {}

  private final PasBundleValidator validator;
  private final PasCoverageEvaluator evaluator;
  private final PasResponseBuilder responseBuilder;
  private final DaoRegistry daoRegistry;
  private final PasBundleReferenceResolver bundleReferenceResolver;
  private final PasPendedResolutionService resolutionService;
  private final PasProperties pasProperties;
  private final String serverBase;

  public PasSubmitService(PasBundleValidator validator, PasCoverageEvaluator evaluator,
      PasResponseBuilder responseBuilder, DaoRegistry daoRegistry,
      PasBundleReferenceResolver bundleReferenceResolver,
      PasPendedResolutionService resolutionService, AppProperties appProperties,
      PasProperties pasProperties) {
    this.validator = validator;
    this.evaluator = evaluator;
    this.responseBuilder = responseBuilder;
    this.daoRegistry = daoRegistry;
    this.bundleReferenceResolver = bundleReferenceResolver;
    this.resolutionService = resolutionService;
    this.pasProperties = pasProperties;
    this.serverBase = FhirUtil.normalizeServerBase(appProperties.getServer_address());
  }

  /**
   * Processes a PAS $submit request bundle and returns a PAS response bundle.
   * Routes to type-specific handling based on submission type detection.
   *
   * @param requestBundle the PAS request bundle containing a Claim as first entry
   * @return PAS response bundle containing a ClaimResponse
   * @throws IllegalArgumentException if the request bundle is invalid
   */
  public Bundle submit(Bundle requestBundle) {
    Claim claim = validator.validateSubmitBundle(requestBundle);
    SubmissionType type = detectSubmissionType(claim);

    Coverage coverage = findCoverage(requestBundle, claim);
    List<Identifier> payorIdentifiers = extractPayorIdentifiers(requestBundle, coverage);
    String patientId = claim.getPatient().getReference();

    SubmissionResult result = switch (type) {
      case CANCEL -> handleCancel(claim, requestBundle);
      case RENEWAL -> handleRenewal(claim, payorIdentifiers, coverage, patientId, requestBundle);
      case UPDATE -> handleUpdate(claim, payorIdentifiers, coverage, patientId, requestBundle);
      case INITIAL -> evaluateAllItems(claim, payorIdentifiers, coverage, patientId, requestBundle);
    };

    String authPrefix = pasProperties.authorizationNumberPrefix();

    // Resolve bundle resources to server-side resources before storing the Claim
    bundleReferenceResolver.resolveAndStoreBundleResources(requestBundle, claim);

    // Store the incoming Claim for audit trail (all paths)
    claim.setId((String) null);
    DaoMethodOutcome claimOutcome = daoRegistry.getResourceDao(Claim.class)
        .create(claim, new SystemRequestDetails());
    String serverClaimId = claimOutcome.getId().getIdPart();

    if (result.priorClaimResponse() != null) {
      return persistUpdatePath(result, authPrefix, requestBundle);
    }
    return persistCreatePath(claim, requestBundle, result, serverClaimId, authPrefix);
  }

  /**
   * Create path (initial/renewal): builds a new ClaimResponse, tags if pended, stores via .create().
   */
  private Bundle persistCreatePath(Claim claim, Bundle requestBundle,
      SubmissionResult result, String serverClaimId, String authPrefix) {

    Bundle responseBundle = responseBuilder.buildSubmitResponse(
        claim, requestBundle, result.itemDecisions(), authPrefix);

    ClaimResponse claimResponse = (ClaimResponse) responseBundle.getEntryFirstRep().getResource();

    boolean anyPended = result.itemDecisions().values().stream()
        .anyMatch(CoverageDecision::isPended);
    if (anyPended) {
      claimResponse.getMeta().addTag(PENDED_TAG_SYSTEM, PENDED_TAG_CODE, "Pended Resolution");
    }

    claimResponse.setRequest(new Reference("Claim/" + serverClaimId));

    claimResponse.setId((String) null);
    DaoMethodOutcome crOutcome = daoRegistry.getResourceDao(ClaimResponse.class)
        .create(claimResponse, new SystemRequestDetails());

    String crId = crOutcome.getId().getIdPart();
    claimResponse.setId(crId);

    if (anyPended) {
      resolutionService.scheduleResolution(crId);
    }

    String fullUrl = FhirUtil.buildVersionlessResourceUrl(serverBase, "ClaimResponse", crId);
    if (fullUrl != null) {
      responseBundle.getEntryFirstRep().setFullUrl(fullUrl);
    }

    return responseBundle;
  }

  /**
   * Update path (update/cancel): modifies the existing ClaimResponse in-place via .update().
   * Maintains the pended scheduler tag based on post-update item state.
   */
  private Bundle persistUpdatePath(SubmissionResult result, String authPrefix, Bundle requestBundle) {
    ClaimResponse existingCr = result.priorClaimResponse();
    boolean hadPendedTag = existingCr.getMeta().getTag(PENDED_TAG_SYSTEM, PENDED_TAG_CODE) != null;

    // Compute pended state from decisions + uncovered items BEFORE modifying the CR
    boolean anyStillPended = result.itemDecisions().values().stream()
        .anyMatch(CoverageDecision::isPended);
    if (!anyStillPended) {
      // Items not covered by new decisions retain their current state
      var coveredSequences = result.itemDecisions().keySet();
      anyStillPended = existingCr.getItem().stream()
          .filter(item -> !coveredSequences.contains(item.getItemSequence()))
          .anyMatch(item -> REVIEW_CODE_A4.equals(
              PasExtensions.extractReviewActionCode(item)));
    }

    if (anyStillPended && !hadPendedTag) {
      existingCr.getMeta().addTag(PENDED_TAG_SYSTEM, PENDED_TAG_CODE, "Pended Resolution");
    }

    responseBuilder.applyItemDecisions(existingCr, result.itemDecisions(), authPrefix);

    var crDao = daoRegistry.getResourceDao(ClaimResponse.class);
    crDao.update(existingCr, new SystemRequestDetails());

    // Remove pended tag if no items are still pended
    if (!anyStillPended && hadPendedTag) {
      Meta tagToRemove = new Meta();
      tagToRemove.addTag(PENDED_TAG_SYSTEM, PENDED_TAG_CODE, null);
      crDao.metaDeleteOperation(existingCr.getIdElement().toUnqualifiedVersionless(),
          tagToRemove, new SystemRequestDetails());
    }

    String crId = existingCr.getIdElement().getIdPart();
    if (anyStillPended) {
      resolutionService.scheduleResolution(crId);
    } else {
      resolutionService.cancelResolution(crId);
    }

    return responseBuilder.wrapInResponseBundle(existingCr, requestBundle);
  }

  // ===== Submission Type Detection =====

  /**
   * Determines the PAS submission type from structural elements of the Claim.
   * Does not rely on meta.profile declarations, which are optional per the FHIR spec.
   *
   * Detection uses the structural difference between the PAS Claim and Claim Update profiles:
   * - Claim.related is prohibited (0..0) on the base PAS Claim profile (initial/renewal)
   * - Claim.related is required (1..1) on the PAS Claim Update profile (update/cancel)
   *
   * Within updates: Claim-level certificationType "3" distinguishes cancel from update.
   * Within initial claims: item-level certificationType "R" distinguishes renewal from initial.
   */
  SubmissionType detectSubmissionType(Claim claim) {
    // Claim.related with a claim reference = update or cancel
    if (claim.hasRelated() && claim.getRelatedFirstRep().hasClaim()) {
      // Claim-level certificationType "3" = whole-authorization cancel
      if (hasClaimLevelCertificationType(claim, CERT_TYPE_CANCEL)) {
        return SubmissionType.CANCEL;
      }
      return SubmissionType.UPDATE;
    }

    if (claim.getItem().stream()
        .anyMatch(item -> hasCertificationType(item, CERT_TYPE_RENEWAL))) {
      return SubmissionType.RENEWAL;
    }

    return SubmissionType.INITIAL;
  }

  // ===== Type-Specific Handlers =====

  /**
   * Evaluates coverage for every Claim item via CQL/PlanDefinitions (initial submission path).
   */
  private SubmissionResult evaluateAllItems(Claim claim, List<Identifier> payorIdentifiers,
      Coverage coverage, String patientId, Bundle requestBundle) {
    Map<Integer, CoverageDecision> itemDecisions = new LinkedHashMap<>();
    for (Claim.ItemComponent item : claim.getItem()) {
      Coding orderCode = item.getProductOrService().getCodingFirstRep();
      CoverageDecision decision = evaluator.evaluate(
          orderCode, payorIdentifiers, coverage, patientId, requestBundle);
      itemDecisions.put(item.getSequence(), decision);
    }
    return new SubmissionResult(itemDecisions, null);
  }

  /**
   * Handles renewal submissions by evaluating all items via CQL/PlanDefinitions.
   * Handling this the same as initial, but separated here in case we want to add renewal-specific logic in the future.
   */
  private SubmissionResult handleRenewal(Claim claim, List<Identifier> payorIdentifiers,
      Coverage coverage, String patientId, Bundle requestBundle) {
    return evaluateAllItems(claim, payorIdentifiers, coverage, patientId, requestBundle);
  }

  /**
   * Handles update submissions. Re-evaluates changed items, cancels items marked with
   * infoCancelled, and carries forward prior decisions for unchanged items.
   * PAS IG prohibits updates to denied requests.
   */
  private SubmissionResult handleUpdate(Claim claim, List<Identifier> payorIdentifiers,
      Coverage coverage, String patientId, Bundle requestBundle) {
    Claim storedPriorClaim = resolvePriorClaim(claim, requestBundle);
    if (storedPriorClaim == null) {
      throw new IllegalArgumentException(
          "No stored authorization found matching the prior Claim identifier");
    }

    ClaimResponse prior = findPriorClaimResponse(storedPriorClaim);
    if (prior == null) {
      throw new IllegalArgumentException(
          "Prior authorization ClaimResponse not found for the stored Claim");
    }

    String priorOverallCode = getMostRestrictiveReviewCode(prior);
    if (REVIEW_CODE_A2.equals(priorOverallCode)) {
      throw new IllegalArgumentException(
          "Cannot update a denied prior authorization (review action A2)");
    }

    // Build prior decision map by item sequence
    Map<Integer, String> priorDecisionMap = new LinkedHashMap<>();
    for (ClaimResponse.ItemComponent priorItem : prior.getItem()) {
      String code = PasExtensions.extractReviewActionCode(priorItem);
      if (code != null) {
        priorDecisionMap.put(priorItem.getItemSequence(), code);
      }
    }

    Map<Integer, CoverageDecision> itemDecisions = new LinkedHashMap<>();
    for (Claim.ItemComponent item : claim.getItem()) {
      int seq = item.getSequence();

      if (hasInfoCancelled(item)) {
        // infoCancelled takes precedence
        itemDecisions.put(seq, new CoverageDecision(
            REVIEW_CODE_A2, "Not Certified", false));
      } else if (hasInfoChanged(item)) {
        Coding orderCode = item.getProductOrService().getCodingFirstRep();
        CoverageDecision decision = evaluator.evaluate(
            orderCode, payorIdentifiers, coverage, patientId, requestBundle);
        itemDecisions.put(seq, decision);
      } else {
        // Unchanged: leave existing prior item unchanged to preserve prior decision state
        // (including existing authorization numbers). If this sequence wasn't on the
        // prior authorization, add a default A3 decision.
        String priorCode = priorDecisionMap.get(seq);
        if (priorCode == null) {
          itemDecisions.put(seq, new CoverageDecision(
              REVIEW_CODE_A3, "Not Required", false));
        }
      }
    }
    return new SubmissionResult(itemDecisions, prior);
  }

  /**
   * Handles whole-authorization cancel submissions. All items in the prior ClaimResponse
   * receive A2 (Not Certified) regardless of what items the cancel Claim lists.
   * This reflects that cancel affects the entire existing authorization.
   */
  private SubmissionResult handleCancel(Claim claim, Bundle requestBundle) {
    Claim storedPriorClaim = resolvePriorClaim(claim, requestBundle);
    if (storedPriorClaim == null) {
      throw new IllegalArgumentException(
          "No stored authorization found matching the prior Claim identifier");
    }

    ClaimResponse prior = findPriorClaimResponse(storedPriorClaim);
    if (prior == null) {
      throw new IllegalArgumentException(
          "Prior authorization ClaimResponse not found for the stored Claim");
    }

    String priorOverallCode = getMostRestrictiveReviewCode(prior);
    if (REVIEW_CODE_A2.equals(priorOverallCode)) {
      throw new IllegalArgumentException(
          "Cannot cancel a denied prior authorization (review action A2)");
    }

    // Cancel all items in the existing authorization, not just items on the cancel Claim
    Map<Integer, CoverageDecision> itemDecisions = new LinkedHashMap<>();
    for (ClaimResponse.ItemComponent item : prior.getItem()) {
      itemDecisions.put(item.getItemSequence(), new CoverageDecision(
          REVIEW_CODE_A2, "Not Certified", false));
    }
    return new SubmissionResult(itemDecisions, prior);
  }

  // ===== Prior Authorization Lookup =====

  /**
   * Resolves the prior Claim to a stored copy on this server. The provider's prior Claim
   * is included in the bundle but uses the provider's resource ID. The linkage
   * to our stored copy is through Claim.identifier, which is required on the
   * PAS Claim profile and preserved across both systems.
   *
   * @return the stored Claim, or null if no matching stored Claim found
   * @throws IllegalArgumentException if the prior Claim is missing from the bundle or has no identifier
   */
  private Claim resolvePriorClaim(Claim claim, Bundle bundle) {
    String priorClaimRef = claim.getRelatedFirstRep().getClaim().getReference();
    Claim priorInBundle = ResourceResolver.findInBundle(priorClaimRef, Claim.class, bundle);
    if (priorInBundle == null) {
      throw new IllegalArgumentException(
          "The prior Claim referenced in Claim.related.claim must be included in the Bundle");
    }

    if (!priorInBundle.hasIdentifier()) {
      throw new IllegalArgumentException(
          "Prior Claim must have an identifier for matching to stored authorization");
    }

    Identifier traceId = priorInBundle.getIdentifierFirstRep();
    SearchParameterMap params = new SearchParameterMap();
    params.add("identifier", new TokenParam(traceId.getSystem(), traceId.getValue()));
    params.setSort(new ca.uhn.fhir.rest.api.SortSpec("_lastUpdated",
        ca.uhn.fhir.rest.api.SortOrderEnum.DESC));

    IBundleProvider results = daoRegistry.getResourceDao(Claim.class)
        .search(params, new SystemRequestDetails());

    return results.getResources(0, 1).stream()
        .filter(Claim.class::isInstance)
        .map(Claim.class::cast)
        .findFirst()
        .orElse(null);
  }

  /**
   * Finds the ClaimResponse associated with a stored Claim using the server-assigned
   * Claim ID. This works because ClaimResponse.request is set to reference the
   * server-assigned Claim ID at storage time.
   */
  private ClaimResponse findPriorClaimResponse(Claim storedPriorClaim) {
    String serverClaimId = storedPriorClaim.getIdElement().getIdPart();
    if (serverClaimId == null || serverClaimId.isBlank()) {
      return null;
    }

    SearchParameterMap params = new SearchParameterMap();
    params.add("request", new ReferenceParam("Claim/" + serverClaimId));

    IBundleProvider results = daoRegistry.getResourceDao(ClaimResponse.class)
        .search(params, new SystemRequestDetails());

    return results.getResources(0, 1).stream()
        .filter(ClaimResponse.class::isInstance)
        .map(ClaimResponse.class::cast)
        .findFirst()
        .orElse(null);
  }

  // ===== Extension Helpers =====

  private boolean hasClaimLevelCertificationType(Claim claim, String code) {
    Extension ext = claim.getExtensionByUrl(PasConstants.CERTIFICATION_TYPE);
    if (ext == null || !(ext.getValue() instanceof CodeableConcept cc)) {
      return false;
    }
    return cc.getCoding().stream().anyMatch(c -> code.equals(c.getCode()));
  }

  private boolean hasCertificationType(Claim.ItemComponent item, String code) {
    Extension ext = item.getExtensionByUrl(PasConstants.CERTIFICATION_TYPE);
    if (ext == null || !(ext.getValue() instanceof CodeableConcept cc)) {
      return false;
    }
    return cc.getCoding().stream().anyMatch(c -> code.equals(c.getCode()));
  }

  private boolean hasInfoCancelled(Claim.ItemComponent item) {
    return item.getModifierExtension().stream()
        .anyMatch(ext -> PasConstants.INFO_CANCELLED.equals(ext.getUrl()));
  }

  private boolean hasInfoChanged(Claim.ItemComponent item) {
    return !item.getExtensionsByUrl(PasConstants.INFO_CHANGED).isEmpty();
  }

  private String getMostRestrictiveReviewCode(ClaimResponse claimResponse) {
    String worstCode = REVIEW_CODE_A3;
    int worstRank = 1;

    for (ClaimResponse.ItemComponent item : claimResponse.getItem()) {
      String code = PasExtensions.extractReviewActionCode(item);
      if (code != null) {
        int rank = rankReviewCode(code);
        if (rank > worstRank) {
          worstRank = rank;
          worstCode = code;
        }
      }
    }
    return worstCode;
  }

  private int rankReviewCode(String code) {
    return switch (code) {
      case REVIEW_CODE_A2 -> 4;
      case REVIEW_CODE_A4 -> 3;
      case REVIEW_CODE_A1 -> 2;
      default -> 1; // A3
    };
  }

  // ===== Coverage Helpers =====

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
