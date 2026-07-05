package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

/**
 * Constructs PAS-conformant ClaimResponse bundles and inquiry response Parameters.
 * Handles correct extension placement (reviewAction on adjudication), item sequence echoing,
 * and bundle wrapping per the PAS IG profiles.
 */
@Component
public class PasResponseBuilder {

  private static final Logger log = LoggerFactory.getLogger(PasResponseBuilder.class);

  private final AtomicLong authCounter = new AtomicLong(0);
  private final AtomicLong pendCounter = new AtomicLong(0);
  private final String serverBase;
  private final DaoRegistry daoRegistry;

  public PasResponseBuilder(AppProperties appProperties, DaoRegistry daoRegistry) {
    this.serverBase = FhirUtil.normalizeServerBase(appProperties.getServer_address());
    this.daoRegistry = daoRegistry;
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

    ClaimResponse claimResponse = buildClaimResponse(requestClaim, requestBundle, itemDecisions, authNumberPrefix);
    Bundle responseBundle = wrapInResponseBundle(claimResponse, requestBundle);

    // Per PAS IG, pended items needing documentation get a CommunicationRequest referenced
    // from ClaimResponse.communicationRequest; the provider builds the Task locally.
    addCommunicationRequests(responseBundle, requestClaim, claimResponse, itemDecisions);

    return responseBundle;
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
      addReferencedCommunicationRequests(responseBundle, sanitized,
          ref -> readReferencedResource(ref, daoRegistry));
      params.addParameter().setName("responseBundle").setResource(responseBundle);
    }
    return params;
  }

  /**
   * Finalizes pended adjudications in-place, transitioning A4 to A1 (Certified).
   */
  public void resolvePendedItems(ClaimResponse claimResponse, String authNumberPrefix) {
    finalizePendedItems(claimResponse, REVIEW_CODE_A1, "Certified in total", authNumberPrefix);
  }

  /**
   * Finalizes pended adjudications in-place, transitioning A4 to A6 (Modified).
   */
  public void modifyPendedItems(ClaimResponse claimResponse, String authNumberPrefix) {
    finalizePendedItems(claimResponse, REVIEW_CODE_A6, "Modified", authNumberPrefix);
  }

  /**
   * Applies coverage decisions to an existing ClaimResponse in-place. For existing items
   * with a matching decision, replaces the reviewAction extension. For decisions targeting
   * sequences not present in the CR, adds new response items.
   */
  public void applyItemDecisions(ClaimResponse claimResponse,
      Map<Integer, CoverageDecision> itemDecisions, String authNumberPrefix) {

    // Track which decision sequences were applied to existing items
    java.util.Set<Integer> appliedSequences = new java.util.HashSet<>();

    for (ClaimResponse.ItemComponent item : claimResponse.getItem()) {
      int seq = item.getItemSequence();
      CoverageDecision decision = itemDecisions.get(seq);
      if (decision == null) {
        continue;
      }
      appliedSequences.add(seq);

      // Preserve existing auth number -- it is a stable tracking identifier
      String existingAuth = PasExtensions.extractAuthorizationNumber(item);

      // Replace reviewAction on existing adjudication
      for (ClaimResponse.AdjudicationComponent adj : item.getAdjudication()) {
        adj.getExtension().removeIf(e -> PasConstants.REVIEW_ACTION.equals(e.getUrl()));

        boolean isApproved = REVIEW_CODE_A1.equals(decision.reviewActionCode());
        boolean includeAuthNumber = requiresAuthorizationNumber(decision.reviewActionCode());
        String authNumber = null;
        if (includeAuthNumber) {
          // Reuse existing auth number if present; only generate new if none exists
          authNumber = existingAuth != null
              ? existingAuth
              : nextAuthorizationNumber(authNumberPrefix);
        }
        adj.addExtension(PasExtensions.buildReviewActionExtension(
            decision.reviewActionCode(), decision.reviewActionDisplay(), authNumber));

        // Manage preAuthPeriod: add for approved, remove for non-approved
        if (isApproved && item.getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD) == null) {
          item.addExtension(buildDefaultPreAuthPeriod());
        } else if (!isApproved) {
          item.getExtension().removeIf(e -> PasConstants.ITEM_PREAUTH_PERIOD.equals(e.getUrl()));
        }
      }

      // Manage adminRefNumber: add for pended items, remove for non-pended
      if (REVIEW_CODE_A4.equals(decision.reviewActionCode())) {
        if (item.getExtensionByUrl(PasConstants.ADMIN_REF_NUMBER) == null) {
          String adminRef = authNumberPrefix + "PEND" + String.format("%04d", pendCounter.incrementAndGet());
          item.addExtension(PasConstants.ADMIN_REF_NUMBER, new StringType(adminRef));
        }
      } else {
        item.getExtension().removeIf(e -> PasConstants.ADMIN_REF_NUMBER.equals(e.getUrl()));
      }
    }

    // Add new items for decisions that didn't match existing items
    for (Map.Entry<Integer, CoverageDecision> entry : itemDecisions.entrySet()) {
      if (appliedSequences.contains(entry.getKey())) {
        continue;
      }

      CoverageDecision decision = entry.getValue();
      ClaimResponse.ItemComponent newItem = claimResponse.addItem();
      newItem.setItemSequence(entry.getKey());

      ClaimResponse.AdjudicationComponent adj = newItem.addAdjudication();
      adj.setCategory(new CodeableConcept().addCoding(
          new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")));

      boolean isApproved = REVIEW_CODE_A1.equals(decision.reviewActionCode());
      boolean includeAuthNumber = requiresAuthorizationNumber(decision.reviewActionCode());
      String authNumber = includeAuthNumber
          ? nextAuthorizationNumber(authNumberPrefix)
          : null;
      adj.addExtension(PasExtensions.buildReviewActionExtension(
          decision.reviewActionCode(), decision.reviewActionDisplay(), authNumber));

      if (isApproved) {
        newItem.addExtension(buildDefaultPreAuthPeriod());
      }

      if (REVIEW_CODE_A4.equals(decision.reviewActionCode())) {
        String adminRef = authNumberPrefix + "PEND" + String.format("%04d", pendCounter.incrementAndGet());
        newItem.addExtension(PasConstants.ADMIN_REF_NUMBER, new StringType(adminRef));
      }
    }
  }

  /**
   * Builds a PAS Response Bundle for subscription notifications. Resolves referenced
   * resources (Patient, Insurer, Requestor) from the database rather than a request bundle.
   */
  public Bundle buildNotificationResponseBundle(ClaimResponse claimResponse, DaoRegistry daoRegistry) {
    ClaimResponse sanitized = sanitizeForBundle(claimResponse);
    Bundle bundle = wrapInResponseBundle(sanitized);

    Function<Reference, Resource> resolver = ref -> readReferencedResource(ref, daoRegistry);
    addReferencedResources(bundle, sanitized, resolver);
    addReferencedCommunicationRequests(bundle, sanitized, resolver);

    return bundle;
  }

  private Resource readReferencedResource(Reference ref, DaoRegistry daoRegistry) {
    if (ref == null || !ref.hasReference()) {
      return null;
    }

    try {
      IdType refId = new IdType(ref.getReference());
      String resourceType = refId.getResourceType();
      if (resourceType == null || resourceType.isBlank()) {
        return null;
      }

      return (Resource) daoRegistry.getResourceDao(resourceType)
          .read(refId, new SystemRequestDetails());
    } catch (ResourceNotFoundException e) {
      // Referenced resource no longer exists; skip it
      return null;
    }
  }

  // ===== Internal =====

  /**
   * Resolves an Organization reference from a bundle and returns its first NPI value, or null.
   */
  private String resolveNpiFromBundle(Reference ref, Bundle bundle) {
    return PasBundleReferenceResolver.findOrganizationNpiInBundle(bundle, ref);
  }

  private void finalizePendedItems(ClaimResponse claimResponse,
      String targetCode, String targetDisplay, String authNumberPrefix) {
    for (ClaimResponse.ItemComponent item : claimResponse.getItem()) {
      boolean itemWasFinalized = false;
      for (ClaimResponse.AdjudicationComponent adj : item.getAdjudication()) {
        if (!isPendedReviewAction(adj)) {
          continue;
        }

        adj.getExtension().removeIf(e -> PasConstants.REVIEW_ACTION.equals(e.getUrl()));
        String authNumber = nextAuthorizationNumber(authNumberPrefix);
        adj.addExtension(PasExtensions.buildReviewActionExtension(targetCode, targetDisplay, authNumber));
        itemWasFinalized = true;
      }

      if (itemWasFinalized) {
        item.getExtension().removeIf(e -> PasConstants.ADMIN_REF_NUMBER.equals(e.getUrl()));
        if (item.getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD) == null) {
          item.addExtension(buildDefaultPreAuthPeriod());
        }
      }
    }

    // Update outcome: if no pended items remain, mark as complete
    boolean stillPended = claimResponse.getItem().stream()
        .flatMap(item -> item.getAdjudication().stream())
        .anyMatch(this::isPendedReviewAction);
    if (!stillPended) {
      claimResponse.setOutcome(ClaimResponse.RemittanceOutcome.COMPLETE);
    }
  }

  private ClaimResponse buildClaimResponse(Claim requestClaim, Bundle requestBundle,
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
    // Outcome is always complete; a pend is conveyed by the item-level A4 reviewAction
    cr.setOutcome(ClaimResponse.RemittanceOutcome.COMPLETE);

    // Reference back to the original Claim
    String claimId = requestClaim.getIdElement().getIdPart();
    if (claimId != null) {
      cr.setRequest(new Reference("Claim/" + claimId));
    }

    // CR-level identifier (PASIdentifier profile)
    cr.addIdentifier(new Identifier()
        .setSystem("http://example.org/PATIENT_EVENT_TRACE_NUMBER")
        .setValue(UUID.randomUUID().toString()));

    // Reverse TransmissionIdentifiers for the response (payer is now sender, provider is receiver)
    Extension requestTransmissionIds = PasExtensions.extractTransmissionIdentifiers(requestClaim);
    if (requestTransmissionIds != null) {
      String requestSender = PasExtensions.extractApplicationSenderCode(requestClaim);
      String requestReceiver = PasExtensions.extractApplicationReceiverCode(requestClaim);
      cr.addExtension(PasExtensions.buildTransmissionIdentifiersExtension(
          requestReceiver != null ? requestReceiver : "",
          requestSender != null ? requestSender : ""));
    } else {
      String providerNpi = resolveNpiFromBundle(requestClaim.getProvider(), requestBundle);
      String insurerNpi = resolveNpiFromBundle(requestClaim.getInsurer(), requestBundle);
      if (providerNpi != null || insurerNpi != null) {
        cr.addExtension(PasExtensions.buildTransmissionIdentifiersExtension(
            insurerNpi != null ? insurerNpi : "",
            providerNpi != null ? providerNpi : ""));
      }
    }

    Map<Integer, String> itemAuthNumbers = new HashMap<>();

    // Build response items echoing request item sequences
    for (Claim.ItemComponent requestItem : requestClaim.getItem()) {
      int seq = requestItem.getSequence();
      CoverageDecision decision = itemDecisions.getOrDefault(seq,
          new CoverageDecision(REVIEW_CODE_A3, "Not Required", false));

      ClaimResponse.ItemComponent responseItem = cr.addItem();
      responseItem.setItemSequence(seq);

      // itemTraceNumber is 0..*: retain the provider-echoed request trace(s), then append one
      // payer trace per pended questionnaire canonical (each correlates to its 102089-0 request).
      for (Identifier traceId : PasExtensions.extractItemTraceNumbers(requestItem)) {
        responseItem.addExtension(PasExtensions.buildItemTraceNumberExtension(traceId));
      }
      if (REVIEW_CODE_A4.equals(decision.reviewActionCode())) {
        for (String questionnaireCanonical : decision.questionnaireUrls()) {
          String questionnaireKey = resolveQuestionnaireKey(questionnaireCanonical);
          if (questionnaireKey != null) {
            responseItem.addExtension(PasExtensions.buildItemTraceNumberExtension(new Identifier()
                .setSystem(PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM).setValue(questionnaireKey)));
          }
        }
      }

      // Adjudication with reviewAction extension
      ClaimResponse.AdjudicationComponent adj = responseItem.addAdjudication();
      adj.setCategory(new CodeableConcept().addCoding(
          new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")));

      boolean isApproved = REVIEW_CODE_A1.equals(decision.reviewActionCode());
      boolean includeAuthNumber = requiresAuthorizationNumber(decision.reviewActionCode());
      String authNumber = null;
      if (includeAuthNumber) {
        authNumber = nextAuthorizationNumber(authNumberPrefix);
        itemAuthNumbers.put(seq, authNumber);
      }

      adj.addExtension(PasExtensions.buildReviewActionExtension(
          decision.reviewActionCode(), decision.reviewActionDisplay(), authNumber));

      // Approved items get additional extensions per PAS IG
      if (isApproved) {
        responseItem.addExtension(buildDefaultPreAuthPeriod());
        responseItem.addExtension(PasExtensions.buildPreAuthIssueDateExtension(new Date()));
      }

      // Echo requestedServiceDate from request item serviced[x]
      Type servicedValue = PasExtensions.extractServicedValue(requestItem);
      if (servicedValue != null) {
        responseItem.addExtension(PasExtensions.buildRequestedServiceDateExtension(servicedValue));
      }

      // Pended items get an administrationReferenceNumber for inquiry matching
      if (REVIEW_CODE_A4.equals(decision.reviewActionCode())) {
        String adminRef = authNumberPrefix + "PEND" + String.format("%04d", pendCounter.incrementAndGet());
        responseItem.addExtension(PasConstants.ADMIN_REF_NUMBER, new StringType(adminRef));
      }
    }

    // addItem entries for A6 (Modified) decisions with modifications
    for (Map.Entry<Integer, CoverageDecision> entry : itemDecisions.entrySet()) {
      CoverageDecision decision = entry.getValue();
      if (REVIEW_CODE_A6.equals(decision.reviewActionCode()) && decision.hasModification()) {
        ClaimResponse.AddedItemComponent addItem = cr.addAddItem();
        addItem.addItemSequence(entry.getKey());
        if (decision.modifiedProductOrService() != null) {
          addItem.setProductOrService(decision.modifiedProductOrService());
        }
        if (decision.modifiedQuantity() != null) {
          addItem.setQuantity(decision.modifiedQuantity());
        }
        String authNumber = itemAuthNumbers.get(entry.getKey());
        if (authNumber == null) {
          authNumber = nextAuthorizationNumber(authNumberPrefix);
        }
        ClaimResponse.AdjudicationComponent addItemAdj = addItem.addAdjudication();
        addItemAdj.setCategory(new CodeableConcept().addCoding(
            new Coding(ADJUDICATION_SYSTEM, "submitted", "Submitted Amount")));
        addItemAdj.addExtension(PasExtensions.buildReviewActionExtension(
            REVIEW_CODE_A6, "Modified", authNumber));
      }
    }

    return cr;
  }

  Bundle wrapInResponseBundle(ClaimResponse claimResponse) {
    return wrapInResponseBundle(claimResponse, null);
  }

  Bundle wrapInResponseBundle(ClaimResponse claimResponse, Bundle requestBundle) {
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

    Bundle.BundleEntryComponent entry = bundle.addEntry()
        .setResource(claimResponse);

    String fullUrl = FhirUtil.buildVersionlessResourceUrl(serverBase, "ClaimResponse", idPart);
    if (fullUrl != null) {
      entry.setFullUrl(fullUrl);
    }

    if (requestBundle != null) {
      addReferencedResources(bundle, claimResponse,
          ref -> ResourceResolver.findInBundle(ref.getReference(), Resource.class, requestBundle));
    }

    return bundle;
  }

  /**
   * Adds resources referenced by the ClaimResponse (patient, insurer, requestor)
   * from the request bundle into the response bundle, using server-base fullUrls
   * so bundle resolution rules match the relative references on the ClaimResponse.
   */
  private void addReferencedResources(Bundle responseBundle, ClaimResponse cr,
      Function<Reference, Resource> resourceResolver) {
    Set<String> addedKeys = new HashSet<>();
    addReferencedResource(responseBundle, cr.getPatient(), resourceResolver, addedKeys);
    addReferencedResource(responseBundle, cr.getInsurer(), resourceResolver, addedKeys);
    addReferencedResource(responseBundle, cr.getRequestor(), resourceResolver, addedKeys);
  }

  /**
   * Adds the CommunicationRequests referenced by ClaimResponse.communicationRequest into the
   * response bundle so inquiry/notification bundles satisfy spec-61's resolvable-reference rule.
   */
  private void addReferencedCommunicationRequests(Bundle responseBundle, ClaimResponse cr,
      Function<Reference, Resource> resourceResolver) {
    Set<String> addedKeys = new HashSet<>();
    for (Reference ref : cr.getCommunicationRequest()) {
      addReferencedResource(responseBundle, ref, resourceResolver, addedKeys);
    }
  }

  private void addReferencedResource(Bundle responseBundle, Reference ref,
      Function<Reference, Resource> resourceResolver, Set<String> addedKeys) {
    if (ref == null || !ref.hasReference()) {
      return;
    }

    String reference = ref.getReference();
    if (addedKeys.contains(reference)) {
      return;
    }

    Resource resource = resourceResolver.apply(ref);
    if (resource == null) {
      return;
    }

    addedKeys.add(reference);
    Bundle.BundleEntryComponent responseEntry = responseBundle.addEntry()
        .setResource(resource);
    String resourceType = resource.getResourceType().name();
    String idPart = resource.getIdElement().getIdPart();
    String fullUrl = FhirUtil.buildVersionlessResourceUrl(serverBase, resourceType, idPart);
    if (fullUrl != null) {
      responseEntry.setFullUrl(fullUrl);
    }
  }

  private boolean requiresAuthorizationNumber(String reviewActionCode) {
    return REVIEW_CODE_A1.equals(reviewActionCode) || REVIEW_CODE_A6.equals(reviewActionCode);
  }

  private String nextAuthorizationNumber(String authNumberPrefix) {
    return authNumberPrefix + String.format("%04d", authCounter.incrementAndGet());
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
        .anyMatch(coding -> REVIEW_CODE_A4.equals(coding.getCode()));
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

  /**
   * Per the PAS IG, when a payer pends a request and needs additional documentation, the
   * response bundle carries a CommunicationRequest per pended item, referenced from
   * ClaimResponse.communicationRequest. The provider builds its own Task from these.
   *
   * @see <a href="https://hl7.org/fhir/us/davinci-pas/2.2.1/en/additionalinfo.html">
   *   PAS IG: Request for Additional Information</a>
   */
  void addCommunicationRequests(Bundle responseBundle, Claim requestClaim,
      ClaimResponse claimResponse, Map<Integer, CoverageDecision> itemDecisions) {
    String patientRef = requestClaim.hasPatient() ? requestClaim.getPatient().getReference() : null;

    for (Map.Entry<Integer, CoverageDecision> entry : itemDecisions.entrySet()) {
      CoverageDecision decision = entry.getValue();
      if (!REVIEW_CODE_A4.equals(decision.reviewActionCode())) continue;
      if (!decision.hasAdditionalDocumentationInfo()) continue;

      int lineNumber = entry.getKey();
      // payload is 0..1: emit one 102089-0 CommunicationRequest per questionnaire canonical,
      // its identifier the same TRN appended to the item so the two correlate.
      for (String questionnaireCanonical : decision.questionnaireUrls()) {
        String trn = resolveQuestionnaireKey(questionnaireCanonical);
        if (trn == null) {
          log.warn("Skipping 102089-0 CommunicationRequest for item {}: no TRN resolvable "
              + "for questionnaire {}", lineNumber, questionnaireCanonical);
        } else {
          addCommunicationRequest(responseBundle, claimResponse,
              PasCommunicationRequestBuilder.buildQuestionnaireRequest(lineNumber, patientRef, trn));
        }
      }
      for (String code : decision.requestedAttachmentCodes()) {
        addCommunicationRequest(responseBundle, claimResponse,
            PasCommunicationRequestBuilder.buildAttachmentCodeRequest(lineNumber, code, patientRef));
      }
    }
  }

  private void addCommunicationRequest(Bundle responseBundle, ClaimResponse claimResponse,
      CommunicationRequest communicationRequest) {
    String fullUrl = "urn:uuid:" + communicationRequest.getId();
    responseBundle.addEntry().setFullUrl(fullUrl).setResource(communicationRequest);
    claimResponse.addCommunicationRequest(new Reference(fullUrl));
  }

  /** Maps a questionnaire canonical to its logical id via the catalog. */
  private String resolveQuestionnaireKey(String canonical) {
    Questionnaire questionnaire = FhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonical);
    return questionnaire != null ? questionnaire.getIdElement().getIdPart() : null;
  }
}
