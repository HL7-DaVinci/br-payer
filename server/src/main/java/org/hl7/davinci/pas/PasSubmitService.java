package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.common.PayorIdentifierUtil;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.AppProperties;
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
  private final PasSubscriptionNotificationService notificationService;
  private final PasProperties pasProperties;
  private final String serverBase;

  public PasSubmitService(PasBundleValidator validator, PasCoverageEvaluator evaluator,
      PasResponseBuilder responseBuilder, DaoRegistry daoRegistry,
      PasBundleReferenceResolver bundleReferenceResolver,
      PasPendedResolutionService resolutionService,
      PasSubscriptionNotificationService notificationService, AppProperties appProperties,
      PasProperties pasProperties) {
    this.validator = validator;
    this.evaluator = evaluator;
    this.responseBuilder = responseBuilder;
    this.daoRegistry = daoRegistry;
    this.bundleReferenceResolver = bundleReferenceResolver;
    this.resolutionService = resolutionService;
    this.notificationService = notificationService;
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
    result = new SubmissionResult(
        applyAttachedDocumentation(result.itemDecisions(), claim, requestBundle),
        result.priorClaimResponse());

    String authPrefix = pasProperties.authorizationNumberPrefix();

    // Resolve bundle resources to server-side resources before storing the Claim
    bundleReferenceResolver.resolveReferences(requestBundle, claim, true);

    // Store the incoming Claim for audit trail (all paths)
    claim.setId((String) null);
    DaoMethodOutcome claimOutcome = daoRegistry.getResourceDao(Claim.class)
        .create(claim, new SystemRequestDetails());
    String serverClaimId = claimOutcome.getId().getIdPart();

    if (result.priorClaimResponse() != null) {
      return persistUpdatePath(claim, result, authPrefix, requestBundle);
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
    boolean awaitsDocumentation = persistDocumentationRequestsAndAwaits(
        responseBundle, claimResponse, anyPended);
    if (anyPended) {
      claimResponse.getMeta().addTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE, "Pended Resolution");
    }

    claimResponse.setRequest(new Reference("Claim/" + serverClaimId));

    claimResponse.setId((String) null);
    DaoMethodOutcome crOutcome = daoRegistry.getResourceDao(ClaimResponse.class)
        .create(claimResponse, new SystemRequestDetails());

    String crId = crOutcome.getId().getIdPart();
    claimResponse.setId(crId);

    if (anyPended && !awaitsDocumentation) {
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
  private Bundle persistUpdatePath(Claim claim, SubmissionResult result, String authPrefix,
      Bundle requestBundle) {
    ClaimResponse existingCr = result.priorClaimResponse();
    boolean hadPendedTag = existingCr.getMeta().getTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE) != null;
    boolean resolvedPendedAuthorization = false;

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
      existingCr.getMeta().addTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE, "Pended Resolution");
    }

    responseBuilder.applyItemDecisions(existingCr, result.itemDecisions(), authPrefix);

    // A re-evaluated item that pends with a new documentation need must get the same
    // CommunicationRequest treatment as the create path, not just the existing CR's prior state.
    Bundle responseBundle = responseBuilder.wrapInResponseBundle(existingCr, requestBundle);
    if (anyStillPended) {
      responseBuilder.addCommunicationRequests(responseBundle, claim, existingCr, result.itemDecisions());
    }
    boolean awaitsDocumentation = persistDocumentationRequestsAndAwaits(
        responseBundle, existingCr, anyStillPended);

    var crDao = daoRegistry.getResourceDao(ClaimResponse.class);
    crDao.update(existingCr, new SystemRequestDetails());

    // Remove pended tag if no items are still pended
    if (!anyStillPended && hadPendedTag) {
      Meta tagToRemove = new Meta();
      tagToRemove.addTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE, null);
      crDao.metaDeleteOperation(existingCr.getIdElement().toUnqualifiedVersionless(),
          tagToRemove, new SystemRequestDetails());
      resolvedPendedAuthorization = true;
    }

    String crId = existingCr.getIdElement().getIdPart();
    // A still-pended state that requested documentation (it carries a CommunicationRequest) resolves on
    // attachment arrival via $submit-attachment, not the timer, mirroring the create path.
    if (anyStillPended && !awaitsDocumentation) {
      resolutionService.scheduleResolution(crId);
    } else {
      resolutionService.cancelResolution(crId);
    }

    if (resolvedPendedAuthorization) {
      notificationService.dispatchResolvedClaimResponse(crId);
    }

    return responseBundle;
  }

  /**
   * Persists any CommunicationRequests carried in the response bundle and computes whether the
   * pended state awaits attachment-driven resolution (excluded from the auto-resolve timer) rather
   * than the straightforward pend case. Shared by the create and update paths so a re-evaluated
   * pend that newly needs documentation is treated the same as a freshly-pended one.
   */
  private boolean persistDocumentationRequestsAndAwaits(Bundle responseBundle,
      ClaimResponse claimResponse, boolean isPended) {
    persistCommunicationRequests(responseBundle, claimResponse);
    return isPended && claimResponse.hasCommunicationRequest();
  }

  /**
   * Persists each CommunicationRequest carried in the response bundle so its dangling urn:uuid
   * reference becomes a resolvable server id. When the ClaimResponse already references an
   * equivalent OPEN documentation request (same questionnaire TRN, or same attachment codes and
   * service line), that request is reused instead of minting a duplicate, so repeated pended
   * updates do not accumulate identical outstanding requests. A completed request never blocks
   * a new one: the payer may legitimately re-request documentation after an update.
   */
  private void persistCommunicationRequests(Bundle responseBundle, ClaimResponse claimResponse) {
    Map<String, String> referenceRewrites = new LinkedHashMap<>();
    Map<String, CommunicationRequest> openExisting = loadOpenDocumentationRequestsByKey(claimResponse);

    for (Bundle.BundleEntryComponent entry : responseBundle.getEntry()) {
      if (!(entry.getResource() instanceof CommunicationRequest communicationRequest)
          || (entry.getFullUrl() != null && !entry.getFullUrl().startsWith("urn:uuid:"))) {
        continue;
      }
      String oldFullUrl = entry.getFullUrl();

      String key = documentationRequestKey(communicationRequest);
      CommunicationRequest existing = key != null ? openExisting.get(key) : null;
      if (existing != null) {
        entry.setResource(existing);
        String existingId = existing.getIdElement().getIdPart();
        if (oldFullUrl != null) {
          referenceRewrites.put(oldFullUrl, "CommunicationRequest/" + existingId);
        }
        String existingUrl = FhirUtil.buildVersionlessResourceUrl(
            serverBase, "CommunicationRequest", existingId);
        if (existingUrl != null) {
          entry.setFullUrl(existingUrl);
        }
        continue;
      }

      communicationRequest.setId((String) null);
      DaoMethodOutcome outcome = daoRegistry.getResourceDao(CommunicationRequest.class)
          .create(communicationRequest, new SystemRequestDetails());
      String persistedId = outcome.getId().getIdPart();
      communicationRequest.setId(persistedId);

      if (oldFullUrl != null) {
        referenceRewrites.put(oldFullUrl, "CommunicationRequest/" + persistedId);
      }
      String fullUrl = FhirUtil.buildVersionlessResourceUrl(
          serverBase, "CommunicationRequest", persistedId);
      if (fullUrl != null) {
        entry.setFullUrl(fullUrl);
      }
    }

    for (Reference ref : claimResponse.getCommunicationRequest()) {
      String rewrite = referenceRewrites.get(ref.getReference());
      if (rewrite != null) {
        ref.setReference(rewrite);
      }
    }

    Set<String> seenReferences = new HashSet<>();
    claimResponse.getCommunicationRequest().removeIf(ref ->
        ref.hasReference() && !seenReferences.add(ref.getReference()));
  }

  /**
   * Loads the ClaimResponse's already-persisted, still-open documentation requests keyed for
   * equivalence matching. Unresolvable references are skipped.
   */
  private Map<String, CommunicationRequest> loadOpenDocumentationRequestsByKey(ClaimResponse claimResponse) {
    Map<String, CommunicationRequest> byKey = new LinkedHashMap<>();
    var dao = daoRegistry.getResourceDao(CommunicationRequest.class);
    for (Reference ref : claimResponse.getCommunicationRequest()) {
      String reference = ref.getReference();
      if (reference == null || !reference.startsWith("CommunicationRequest/")) {
        continue;
      }
      try {
        CommunicationRequest cr = dao.read(new IdType(reference), new SystemRequestDetails());
        if (cr.getStatus() != CommunicationRequest.CommunicationRequestStatus.COMPLETED) {
          String key = documentationRequestKey(cr);
          if (key != null) {
            byKey.putIfAbsent(key, cr);
          }
        }
      } catch (RuntimeException e) {
        // Skip references that no longer resolve; they cannot be reused.
      }
    }
    return byKey;
  }

  /**
   * Equivalence key for a documentation request: questionnaire requests are identified by their
   * TRN identifier (stable per questionnaire), attachment-code requests by payload codes plus
   * service line (their identifier is a random trace value).
   */
  private String documentationRequestKey(CommunicationRequest cr) {
    List<String> codes = cr.getPayload().stream()
        .map(p -> p.getContent() instanceof StringType s ? s.getValue() : null)
        .filter(c -> c != null && !c.isBlank())
        .sorted()
        .toList();
    if (codes.isEmpty()) {
      return null;
    }
    Extension lineExt = cr.getExtensionByUrl(PasConstants.EXT_SERVICE_LINE_NUMBER);
    String line = lineExt != null && lineExt.getValue() instanceof PositiveIntType p
        ? String.valueOf(p.getValue())
        : "";
    if (codes.contains(PasConstants.LOINC_QUESTIONNAIRE_REQUEST)) {
      String trn = cr.getIdentifierFirstRep().getValue();
      return trn == null ? null : "questionnaire|" + line + "|" + trn;
    }
    return "code|" + line + "|" + String.join(",", codes);
  }

  // ===== Attached Documentation =====

  /**
   * Downgrades pended documentation decisions whose required questionnaires are already
   * answered by completed QuestionnaireResponses attached via Claim.supportingInfo
   * (DTR intendedUse=withpa). The PAS additionalInformation slice exists to satisfy
   * documentation at submit time, so a satisfied requirement is certified instead of
   * re-requested. Questionnaires are compared through this server's catalog so
   * versioned and unversioned canonicals match.
   */
  Map<Integer, CoverageDecision> applyAttachedDocumentation(
      Map<Integer, CoverageDecision> decisions, Claim claim, Bundle requestBundle) {
    Set<String> attachedKeys = attachedQuestionnaireKeys(claim, requestBundle);
    if (attachedKeys.isEmpty()) {
      return decisions;
    }
    Map<Integer, CoverageDecision> adjusted = new LinkedHashMap<>();
    for (Map.Entry<Integer, CoverageDecision> entry : decisions.entrySet()) {
      adjusted.put(entry.getKey(),
          satisfiedByAttachedDocumentation(entry.getValue(), attachedKeys)
              ? new CoverageDecision(REVIEW_CODE_A1, "Certified in total", false)
              : entry.getValue());
    }
    return adjusted;
  }

  private boolean satisfiedByAttachedDocumentation(CoverageDecision decision, Set<String> attachedKeys) {
    if (!REVIEW_CODE_A4.equals(decision.reviewActionCode())
        || !decision.hasAdditionalDocumentationInfo()) {
      return false;
    }
    // Requested non-questionnaire attachment codes cannot be satisfied by a QuestionnaireResponse.
    if (!decision.requestedAttachmentCodes().isEmpty()) {
      return false;
    }
    return decision.questionnaireUrls().stream()
        .map(this::questionnaireKey)
        .allMatch(key -> key != null && attachedKeys.contains(key));
  }

  private Set<String> attachedQuestionnaireKeys(Claim claim, Bundle requestBundle) {
    Set<String> keys = new HashSet<>();
    for (Claim.SupportingInformationComponent info : claim.getSupportingInfo()) {
      if (!(info.getValue() instanceof Reference ref) || !ref.hasReference()) {
        continue;
      }
      QuestionnaireResponse qr = ResourceResolver.findInBundle(
          ref.getReference(), QuestionnaireResponse.class, requestBundle);
      if (qr == null
          || qr.getStatus() != QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
          || !qr.hasQuestionnaire()) {
        continue;
      }
      String key = questionnaireKey(qr.getQuestionnaire());
      if (key != null) {
        keys.add(key);
      }
    }
    return keys;
  }

  private String questionnaireKey(String canonical) {
    Questionnaire questionnaire = FhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonical);
    return questionnaire != null ? questionnaire.getIdElement().getIdPart() : null;
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
    params.setCount(1);
    return daoRegistry.getResourceDao(Claim.class)
        .searchForResources(params, new SystemRequestDetails())
        .stream()
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
    params.setCount(1);
    return daoRegistry.getResourceDao(ClaimResponse.class)
        .searchForResources(params, new SystemRequestDetails())
        .stream()
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
