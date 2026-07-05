package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.davinci.pas.PasCoverageEvaluator.CoverageDecision;
import org.hl7.davinci.pas.PasSubmitService.SubmissionType;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasSubmitServiceTest {

  private static final String SERVER_BASE = "http://localhost:8080/fhir";

  private PasBundleValidator validator;
  private PasCoverageEvaluator evaluator;
  private PasResponseBuilder responseBuilder;
  private PasBundleReferenceResolver bundleReferenceResolver;
  private PasPendedResolutionService resolutionService;
  private PasSubscriptionNotificationService notificationService;
  private DaoRegistry daoRegistry;
  private PasSubmitService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    validator = mock(PasBundleValidator.class);
    evaluator = mock(PasCoverageEvaluator.class);
    responseBuilder = mock(PasResponseBuilder.class);
    bundleReferenceResolver = mock(PasBundleReferenceResolver.class);
    resolutionService = mock(PasPendedResolutionService.class);
    notificationService = mock(PasSubscriptionNotificationService.class);
    daoRegistry = mock(DaoRegistry.class);

    IFhirResourceDao<ClaimResponse> crDao = mock(IFhirResourceDao.class);
    DaoMethodOutcome crOutcome = new DaoMethodOutcome();
    crOutcome.setId(new IdType("ClaimResponse/server-cr-id"));
    when(crDao.create(any(), any(RequestDetails.class))).thenReturn(crOutcome);
    when(crDao.update(any(), any(RequestDetails.class))).thenReturn(crOutcome);
    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(crDao);

    IFhirResourceDao<Claim> claimDao = mock(IFhirResourceDao.class);
    DaoMethodOutcome claimOutcome = new DaoMethodOutcome();
    claimOutcome.setId(new IdType("Claim/server-claim-id"));
    when(claimDao.create(any(), any(RequestDetails.class))).thenReturn(claimOutcome);
    when(daoRegistry.getResourceDao(Claim.class)).thenReturn(claimDao);

    IFhirResourceDao<Patient> patientDao = mock(IFhirResourceDao.class);
    DaoMethodOutcome patientOutcome = new DaoMethodOutcome();
    patientOutcome.setId(new IdType("Patient/server-patient-id"));
    when(patientDao.update(any(), any(RequestDetails.class))).thenReturn(patientOutcome);
    when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);

    IFhirResourceDao<Organization> orgDao = mock(IFhirResourceDao.class);
    DaoMethodOutcome orgOutcome = new DaoMethodOutcome();
    orgOutcome.setId(new IdType("Organization/server-org-id"));
    when(orgDao.update(any(), any(RequestDetails.class))).thenReturn(orgOutcome);
    when(daoRegistry.getResourceDao(Organization.class)).thenReturn(orgDao);

    IFhirResourceDao<Coverage> coverageDao = mock(IFhirResourceDao.class);
    DaoMethodOutcome coverageOutcome = new DaoMethodOutcome();
    coverageOutcome.setId(new IdType("Coverage/server-coverage-id"));
    when(coverageDao.update(any(), any(RequestDetails.class))).thenReturn(coverageOutcome);
    when(daoRegistry.getResourceDao(Coverage.class)).thenReturn(coverageDao);

    IFhirResourceDao<CommunicationRequest> commReqDao = mock(IFhirResourceDao.class);
    DaoMethodOutcome commReqOutcome = new DaoMethodOutcome();
    commReqOutcome.setId(new IdType("CommunicationRequest/server-commreq-id"));
    when(commReqDao.create(any(), any(RequestDetails.class))).thenReturn(commReqOutcome);
    when(daoRegistry.getResourceDao(CommunicationRequest.class)).thenReturn(commReqDao);

    AppProperties appProperties = mock(AppProperties.class);
    when(appProperties.getServer_address()).thenReturn(SERVER_BASE);

    PasProperties pasProperties = new PasProperties(30, "AUTH-", 100);
    service = new PasSubmitService(validator, evaluator, responseBuilder, daoRegistry,
        bundleReferenceResolver, resolutionService, notificationService, appProperties, pasProperties);
  }

  @Test
  void submit_approvedBundle_storeWithoutPendedTag() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    ClaimResponse cr = new ClaimResponse();
    cr.setId("CR-001");
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(cr);

    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified", false));
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);

    Bundle result = service.submit(requestBundle);
    assertNotNull(result);

    // Verify stored ClaimResponse has NO pended tag
    verify(daoRegistry.getResourceDao(ClaimResponse.class)).create(argThat(cr2 ->
        cr2.getMeta().getTag(PasSubmitService.PENDED_TAG_SYSTEM, PasSubmitService.PENDED_TAG_CODE) == null
    ), any(RequestDetails.class));
  }

  @Test
  void submit_pendedBundle_storeWithPendedTag() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    ClaimResponse cr = new ClaimResponse();
    cr.setId("CR-PENDED");
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(cr);

    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A4, "Pended", true));
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);

    service.submit(requestBundle);

    // Verify stored ClaimResponse HAS pended tag
    verify(daoRegistry.getResourceDao(ClaimResponse.class)).create(argThat(cr2 ->
        cr2.getMeta().getTag(PasSubmitService.PENDED_TAG_SYSTEM, PasSubmitService.PENDED_TAG_CODE) != null
    ), any(RequestDetails.class));
  }

  @Test
  void submit_pendedWithDocumentation_persistsCommunicationRequestAndResolvesReference() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();

    String trn = "trace-abc";
    CommunicationRequest commReq = new CommunicationRequest();
    commReq.setId("cr-abc123");
    commReq.addIdentifier(new Identifier()
        .setSystem(PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM).setValue(trn));

    ClaimResponse cr = new ClaimResponse();
    cr.setId("CR-PENDED");
    cr.addCommunicationRequest(new Reference("urn:uuid:cr-abc123"));

    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(cr);
    responseBundle.addEntry().setFullUrl("urn:uuid:cr-abc123").setResource(commReq);

    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A4, "Pended", true));
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);

    service.submit(requestBundle);

    // The CommunicationRequest carrying the TRN identifier is persisted (findable by identifier)
    IFhirResourceDao<CommunicationRequest> commReqDao =
        (IFhirResourceDao<CommunicationRequest>) daoRegistry.getResourceDao(CommunicationRequest.class);
    verify(commReqDao).create(argThat(persisted ->
        trn.equals(persisted.getIdentifierFirstRep().getValue())), any(RequestDetails.class));

    // The dangling urn:uuid reference is rewritten to the persisted server id
    assertEquals("CommunicationRequest/server-commreq-id",
        cr.getCommunicationRequestFirstRep().getReference());
  }

  @Test
  void submit_claimResponseReferencesPayerAssignedClaimId() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    claim.setId("provider-side-id");
    ClaimResponse cr = new ClaimResponse();
    cr.setId("CR-001");
    cr.setRequest(new Reference("Claim/provider-side-id"));
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(cr);

    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified", false));
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);

    Bundle result = service.submit(requestBundle);

    // ClaimResponse.request must reference the payer-assigned ID, not the provider's
    assertEquals("Claim/server-claim-id", cr.getRequest().getReference());
    // ClaimResponse ID itself should be the server-assigned value
    assertEquals("server-cr-id", cr.getId());
    // fullUrl must point to the payer's server, not example.org
    assertEquals(SERVER_BASE + "/ClaimResponse/server-cr-id",
        result.getEntryFirstRep().getFullUrl());
  }

  @Test
  void submit_invalidBundle_throwsIllegalArgument() {
    when(validator.validateSubmitBundle(any())).thenThrow(new IllegalArgumentException("Bad bundle"));
    assertThrows(IllegalArgumentException.class, () -> service.submit(new Bundle()));
  }

  @Test
  void submit_payorReferencedByBundleFullUrl_extractsPayorIdentifier() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();

    Coverage coverage = new Coverage();
    coverage.setId("Coverage/1");
    coverage.addPayor(new Reference("urn:uuid:payor-org-1"));
    requestBundle.addEntry().setResource(coverage);

    Organization payor = new Organization();
    payor.setId("Organization/not-used-for-lookup");
    payor.addIdentifier()
        .setSystem("http://example.org/payer-id")
        .setValue("payer-123");
    requestBundle.addEntry()
        .setFullUrl("urn:uuid:payor-org-1")
        .setResource(payor);

    ClaimResponse cr = new ClaimResponse();
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(cr);

    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified", false));
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);

    service.submit(requestBundle);

    verify(evaluator).evaluate(any(), argThat(ids ->
            ids.size() == 1
                && "http://example.org/payer-id".equals(ids.get(0).getSystem())
                && "payer-123".equals(ids.get(0).getValue())),
        same(coverage), any(), same(requestBundle));
  }

  // ===== Submission Type Detection =====

  @Test
  void detectSubmissionType_plainClaim_returnsInitial() {
    Claim claim = buildMinimalClaim();
    assertEquals(SubmissionType.INITIAL, service.detectSubmissionType(claim));
  }

  @Test
  void detectSubmissionType_itemWithCertTypeR_returnsRenewal() {
    Claim claim = buildMinimalClaim();
    claim.getItemFirstRep().addExtension(PasConstants.CERTIFICATION_TYPE,
        new CodeableConcept().addCoding(new Coding(
            X12_CERT_TYPE_SYSTEM, CERT_TYPE_RENEWAL, "Renewal")));
    assertEquals(SubmissionType.RENEWAL, service.detectSubmissionType(claim));
  }

  @Test
  void detectSubmissionType_claimWithRelated_returnsUpdate() {
    // Claim.related with a claim reference = structural signal for update
    Claim claim = buildMinimalClaim();
    claim.addRelated().setClaim(new Reference("Claim/prior-claim-id"));
    assertEquals(SubmissionType.UPDATE, service.detectSubmissionType(claim));
  }

  @Test
  void detectSubmissionType_claimLevelCertType3_returnsCancel() {
    // Claim.related + Claim-level certificationType "3" = whole-authorization cancel
    Claim claim = buildMinimalClaim();
    claim.addRelated().setClaim(new Reference("Claim/prior-claim-id"));
    claim.addExtension(PasConstants.CERTIFICATION_TYPE,
        new CodeableConcept().addCoding(new Coding(
            X12_CERT_TYPE_SYSTEM, CERT_TYPE_CANCEL, "Cancel")));
    assertEquals(SubmissionType.CANCEL, service.detectSubmissionType(claim));
  }

  @Test
  void detectSubmissionType_allItemsInfoCancelledWithoutClaimCertType_returnsUpdate() {
    // All items have infoCancelled but no Claim-level certificationType "3"
    // -- this is an UPDATE with item-level cancellations, not a whole-authorization CANCEL
    Claim claim = buildMinimalClaim();
    claim.addRelated().setClaim(new Reference("Claim/prior-claim-id"));
    claim.getItemFirstRep().addModifierExtension(
        new Extension(PasConstants.INFO_CANCELLED, new BooleanType(true)));
    assertEquals(SubmissionType.UPDATE, service.detectSubmissionType(claim));
  }

  // ===== Renewal Tests =====

  @Test
  void submit_renewal_evaluatesViaCql() {
    // Renewals use the base PAS Claim profile (Claim.related prohibited),
    // so all items are evaluated via CQL like an initial submission
    Bundle requestBundle = buildRenewalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    mockValidatorAndResponseBuilder(requestBundle, claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    verify(evaluator).evaluate(any(), any(), any(), any(), any());
    Map<Integer, CoverageDecision> decisions = captureDecisions();
    assertEquals(REVIEW_CODE_A1, decisions.get(1).reviewActionCode());
  }

  // ===== Update Tests =====

  @Test
  void submit_updateChangedItemsReEvaluated() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A1, "Certified in total"));
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A4, "Pending", true));

    service.submit(requestBundle);

    verify(evaluator).evaluate(any(), any(), any(), any(), any());
    Map<Integer, CoverageDecision> decisions = captureAppliedDecisions();
    assertEquals(REVIEW_CODE_A4, decisions.get(1).reviewActionCode());
    verifyCrUpdatedNotCreated();
  }

  @Test
  void submit_updateIntroducesPended_addsPendedTag() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A1, "Certified in total"));
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A4, "Pending", true));

    service.submit(requestBundle);

    IFhirResourceDao<ClaimResponse> crDao =
        (IFhirResourceDao<ClaimResponse>) daoRegistry.getResourceDao(ClaimResponse.class);
    verify(crDao).update(argThat(cr ->
            cr.getMeta().getTag(PasSubmitService.PENDED_TAG_SYSTEM, PasSubmitService.PENDED_TAG_CODE) != null),
        any(RequestDetails.class));
    verify(crDao, never()).metaDeleteOperation(any(), any(Meta.class), any(RequestDetails.class));
  }

  @Test
  void submit_updateUnchangedApprovedItem_keepsPriorDecisionState() {
    Claim claim = buildMinimalClaim();
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    claim.addRelated().setClaim(new Reference("Claim/prior-claim-id"));
    Bundle requestBundle = wrapInBundle(claim);
    addPriorClaimToBundle(requestBundle, "prior-claim-id");

    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());
    mockClaimResponseSearch(buildPriorClaimResponse(
        REVIEW_CODE_A1, "Certified in total", "AUTH-1111"));

    service.submit(requestBundle);

    verifyNoInteractions(evaluator);
    Map<Integer, CoverageDecision> decisions = captureAppliedDecisions();
    assertFalse(decisions.containsKey(1),
        "Unchanged item decisions should be omitted so prior auth state is preserved");
    verifyCrUpdatedNotCreated();
  }

  @Test
  void submit_updateCancelledItemsGetA2() {
    // Two items: item 1 cancelled, item 2 changed -- detected as UPDATE (not all cancelled)
    Claim claim = buildMinimalClaim();
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    claim.addRelated().setClaim(new Reference("Claim/prior-claim-id"));
    claim.getItemFirstRep().addModifierExtension(
        new Extension(PasConstants.INFO_CANCELLED, new BooleanType(true)));
    Claim.ItemComponent item2 = claim.addItem();
    item2.setSequence(2);
    item2.setProductOrService(new CodeableConcept().addCoding(
        new Coding("http://snomed.info/sct", "999999", "Other")));
    item2.addExtension(new Extension(PasConstants.INFO_CHANGED, new CodeType("changed")));
    Bundle requestBundle = wrapInBundle(claim);
    addPriorClaimToBundle(requestBundle, "prior-claim-id");

    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A1, "Certified"));
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    Map<Integer, CoverageDecision> decisions = captureAppliedDecisions();
    assertEquals(REVIEW_CODE_A2, decisions.get(1).reviewActionCode());
    verifyCrUpdatedNotCreated();
  }

  @Test
  void submit_updatePriorDenied_throws() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockStoredClaimSearch(buildStoredPriorClaim());
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A2, "Not Certified"));

    assertThrows(IllegalArgumentException.class, () -> service.submit(requestBundle));
  }

  @Test
  void submit_updateUnchangedItemNotInPrior_defaultsA3() {
    // Item 1: changed (re-evaluated), Item 2: unchanged and not in prior -> A3
    Claim claim = buildMinimalClaim();
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    claim.addRelated().setClaim(new Reference("Claim/prior-claim-id"));
    claim.getItemFirstRep().addExtension(
        new Extension(PasConstants.INFO_CHANGED, new CodeType("changed")));
    Claim.ItemComponent item2 = claim.addItem();
    item2.setSequence(2);
    item2.setProductOrService(new CodeableConcept().addCoding(
        new Coding("http://snomed.info/sct", "999999", "Other")));
    Bundle requestBundle = wrapInBundle(claim);
    addPriorClaimToBundle(requestBundle, "prior-claim-id");

    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());
    // Prior only has item seq 1
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A1, "Certified in total"));
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    Map<Integer, CoverageDecision> decisions = captureAppliedDecisions();
    assertEquals(REVIEW_CODE_A1, decisions.get(1).reviewActionCode());
    assertEquals(REVIEW_CODE_A3, decisions.get(2).reviewActionCode());
    verifyCrUpdatedNotCreated();
  }

  @Test
  void submit_updatePriorClaimNotInBundle_throws() {
    // Claim.related references a prior Claim, but that Claim is not in the Bundle
    Claim claim = buildMinimalClaim();
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    claim.addRelated().setClaim(new Reference("Claim/prior-claim-id"));
    claim.getItemFirstRep().addExtension(
        new Extension(PasConstants.INFO_CHANGED, new CodeType("changed")));
    Bundle requestBundle = wrapInBundle(claim);
    // Deliberately NOT adding prior Claim to bundle
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> service.submit(requestBundle));
    assertTrue(ex.getMessage().contains("prior Claim"));
  }

  // ===== Cancel Tests =====

  @Test
  void submit_cancelAllItemsGetA2() {
    Bundle requestBundle = buildCancelBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A1, "Certified"));

    service.submit(requestBundle);

    verifyNoInteractions(evaluator);
    Map<Integer, CoverageDecision> decisions = captureAppliedDecisions();
    assertEquals(REVIEW_CODE_A2, decisions.get(1).reviewActionCode());
    verifyCrUpdatedNotCreated();
  }

  @Test
  void submit_updatePriorClaimNotInStore_throws() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    // Prior Claim is in bundle but no matching stored Claim
    mockStoredClaimSearch();

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> service.submit(requestBundle));
    assertTrue(ex.getMessage().contains("stored authorization"));
  }

  @Test
  void submit_cancelPriorClaimNotInStore_throws() {
    Bundle requestBundle = buildCancelBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    // Prior Claim is in bundle but no matching stored Claim
    mockStoredClaimSearch();

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> service.submit(requestBundle));
    assertTrue(ex.getMessage().contains("stored authorization"));
  }

  @Test
  void submit_cancelPriorClaimNotInBundle_throws() {
    Claim claim = buildMinimalClaim();
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    claim.addRelated().setClaim(new Reference("Claim/prior-claim-id"));
    claim.addExtension(PasConstants.CERTIFICATION_TYPE,
        new CodeableConcept().addCoding(new Coding(
            X12_CERT_TYPE_SYSTEM, CERT_TYPE_CANCEL, "Cancel")));
    Bundle requestBundle = wrapInBundle(claim);
    // Deliberately NOT adding prior Claim to bundle
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> service.submit(requestBundle));
    assertTrue(ex.getMessage().contains("prior Claim"));
  }

  @Test
  void submit_cancelNoClaimItems_stillCancelsPriorCrItems() {
    // Cancel Claim has no items, but prior CR has items -- all prior items get A2
    Claim claim = new Claim();
    claim.setUse(Claim.Use.PREAUTHORIZATION);
    claim.setPatient(new Reference("Patient/1"));
    claim.setInsurer(new Reference("Organization/1"));
    claim.setProvider(new Reference("PractitionerRole/1"));
    claim.addInsurance().setCoverage(new Reference("Coverage/1")).setFocal(true);
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    claim.addRelated().setClaim(new Reference("Claim/prior-claim-id"));
    claim.addExtension(PasConstants.CERTIFICATION_TYPE,
        new CodeableConcept().addCoding(new Coding(
            X12_CERT_TYPE_SYSTEM, CERT_TYPE_CANCEL, "Cancel")));
    Bundle requestBundle = wrapInBundle(claim);
    addPriorClaimToBundle(requestBundle, "prior-claim-id");
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A1, "Certified"));

    service.submit(requestBundle);

    verifyNoInteractions(evaluator);
    // Decisions are derived from prior CR items, not the cancel Claim's (empty) items
    Map<Integer, CoverageDecision> decisions = captureAppliedDecisions();
    assertFalse(decisions.isEmpty());
    assertEquals(REVIEW_CODE_A2, decisions.get(1).reviewActionCode());
  }

  @Test
  void submit_cancelRemovesPendedTag() {
    Bundle requestBundle = buildCancelBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());

    // Prior CR is pended and tagged
    ClaimResponse pendedCr = buildPriorClaimResponse(REVIEW_CODE_A4, "Pending");
    pendedCr.getMeta().addTag(PasSubmitService.PENDED_TAG_SYSTEM,
        PasSubmitService.PENDED_TAG_CODE, "Pended Resolution");
    mockClaimResponseSearch(pendedCr);

    service.submit(requestBundle);

    IFhirResourceDao<ClaimResponse> crDao =
        (IFhirResourceDao<ClaimResponse>) daoRegistry.getResourceDao(ClaimResponse.class);
    verify(crDao).update(any(), any(RequestDetails.class));
    verify(crDao).metaDeleteOperation(any(), any(Meta.class), any(RequestDetails.class));
    verify(notificationService).dispatchResolvedClaimResponse("prior-cr-id");
  }

  @Test
  void submit_cancelPriorClaimResponseNotFound_throws() {
    Bundle requestBundle = buildCancelBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockStoredClaimSearch(buildStoredPriorClaim());
    // Stored Claim exists but no associated ClaimResponse
    mockClaimResponseSearch();

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> service.submit(requestBundle));
    assertTrue(ex.getMessage().contains("ClaimResponse not found"));
  }

  @Test
  void submit_cancelPriorDenied_throws() {
    Bundle requestBundle = buildCancelBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockStoredClaimSearch(buildStoredPriorClaim());
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A2, "Not Certified"));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> service.submit(requestBundle));
    assertTrue(ex.getMessage().contains("denied"));
  }

  @Test
  void submit_updateOnPendedCR_removesPendedTagIfNoLongerPended() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());

    // Prior CR was pended (A4), tagged for scheduler
    ClaimResponse pendedCr = buildPriorClaimResponse(REVIEW_CODE_A4, "Pending");
    pendedCr.getMeta().addTag(PasSubmitService.PENDED_TAG_SYSTEM,
        PasSubmitService.PENDED_TAG_CODE, "Pended Resolution");
    mockClaimResponseSearch(pendedCr);

    // Update re-evaluates item and it transitions to A1
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    IFhirResourceDao<ClaimResponse> crDao =
        (IFhirResourceDao<ClaimResponse>) daoRegistry.getResourceDao(ClaimResponse.class);
    verify(crDao).update(any(), any(RequestDetails.class));
    // After applying A1 decisions, no items are A4, so pended tag should be removed
    verify(crDao).metaDeleteOperation(any(), any(Meta.class), any(RequestDetails.class));
    verify(notificationService).dispatchResolvedClaimResponse("prior-cr-id");
  }

  // ===== Resolution Scheduling Tests =====

  @Test
  void submit_pendedInitial_schedulesResolution() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    mockValidatorAndResponseBuilder(requestBundle, claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A4, "Pended", true));

    service.submit(requestBundle);

    verify(resolutionService).scheduleResolution("server-cr-id");
  }

  @Test
  void submit_approvedInitial_doesNotScheduleResolution() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    mockValidatorAndResponseBuilder(requestBundle, claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    verify(resolutionService, never()).scheduleResolution(any());
  }

  @Test
  void submit_pendedInitialAwaitingDocumentation_doesNotScheduleResolution() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A4, "Pended", true));

    ClaimResponse pendedCr = new ClaimResponse();
    pendedCr.addCommunicationRequest(new Reference("urn:uuid:doc-request"));
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(pendedCr);
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);

    service.submit(requestBundle);

    // Tagged as pended so $submit-attachment can still resolve it, but NOT armed on the timer.
    IFhirResourceDao<ClaimResponse> crDao =
        (IFhirResourceDao<ClaimResponse>) daoRegistry.getResourceDao(ClaimResponse.class);
    verify(crDao).create(argThat(cr2 -> cr2.getMeta().getTag(
        PasSubmitService.PENDED_TAG_SYSTEM, PasSubmitService.PENDED_TAG_CODE) != null),
        any(RequestDetails.class));
    verify(resolutionService, never()).scheduleResolution(any());
  }

  @Test
  void submit_cancelRemovesPended_cancelsResolution() {
    Bundle requestBundle = buildCancelBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());

    ClaimResponse pendedCr = buildPriorClaimResponse(REVIEW_CODE_A4, "Pending");
    pendedCr.getMeta().addTag(PasSubmitService.PENDED_TAG_SYSTEM,
        PasSubmitService.PENDED_TAG_CODE, "Pended Resolution");
    mockClaimResponseSearch(pendedCr);

    service.submit(requestBundle);

    verify(resolutionService).cancelResolution("prior-cr-id");
    verify(resolutionService, never()).scheduleResolution(any());
    verify(notificationService).dispatchResolvedClaimResponse("prior-cr-id");
  }

  @Test
  void submit_updateIntroducesPended_schedulesResolution() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A1, "Certified in total"));
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A4, "Pending", true));

    service.submit(requestBundle);

    verify(resolutionService).scheduleResolution("prior-cr-id");
  }

  @Test
  void submit_updateStillPendedAwaitingDocumentation_doesNotScheduleResolution() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());

    ClaimResponse pendedCr = buildPriorClaimResponse(REVIEW_CODE_A4, "Pending");
    pendedCr.getMeta().addTag(PasSubmitService.PENDED_TAG_SYSTEM,
        PasSubmitService.PENDED_TAG_CODE, "Pended Resolution");
    pendedCr.addCommunicationRequest(new Reference("urn:uuid:doc-request"));
    mockClaimResponseSearch(pendedCr);

    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A4, "Pending", true));

    service.submit(requestBundle);

    verify(resolutionService, never()).scheduleResolution(any());
  }

  @Test
  void submit_updateReEvaluatedPendNeedsDocumentation_excludedFromTimerAndPersistsCommunicationRequest() {
    // A re-evaluated item pends AND newly needs documentation on an update -- this must be routed
    // through the same CommunicationRequest logic as the create path, not just armed on the timer.
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());
    // Prior CR was fully approved -- no pended tag, no CommunicationRequest
    mockClaimResponseSearch(buildPriorClaimResponse(REVIEW_CODE_A1, "Certified in total"));

    CoverageDecision docNeededDecision = new CoverageDecision(
        REVIEW_CODE_A4, "Pending", true, null, null, "clinical", List.of(), List.of("18776-5"));
    when(evaluator.evaluate(any(), any(), any(), any(), any())).thenReturn(docNeededDecision);

    // Simulate PasResponseBuilder attaching a CommunicationRequest for the re-evaluated item, the
    // same way it already does on the create path via buildSubmitResponse.
    doAnswer(invocation -> {
      Bundle bundle = invocation.getArgument(0);
      ClaimResponse cr = invocation.getArgument(2);
      CommunicationRequest commReq = new CommunicationRequest();
      commReq.setId("doc-request");
      bundle.addEntry().setFullUrl("urn:uuid:doc-request").setResource(commReq);
      cr.addCommunicationRequest(new Reference("urn:uuid:doc-request"));
      return null;
    }).when(responseBuilder).addCommunicationRequests(any(Bundle.class), any(Claim.class),
        any(ClaimResponse.class), anyMap());

    service.submit(requestBundle);

    // Excluded from the auto-resolve timer -- awaits attachment-driven resolution instead
    verify(resolutionService, never()).scheduleResolution(any());

    // The CommunicationRequest is persisted and its dangling reference rewritten on the stored CR
    IFhirResourceDao<CommunicationRequest> commReqDao =
        (IFhirResourceDao<CommunicationRequest>) daoRegistry.getResourceDao(CommunicationRequest.class);
    verify(commReqDao).create(any(), any(RequestDetails.class));

    IFhirResourceDao<ClaimResponse> crDao =
        (IFhirResourceDao<ClaimResponse>) daoRegistry.getResourceDao(ClaimResponse.class);
    verify(crDao).update(argThat(cr -> cr.hasCommunicationRequest()
            && "CommunicationRequest/server-commreq-id".equals(cr.getCommunicationRequestFirstRep().getReference())),
        any(RequestDetails.class));
  }

  @Test
  void submit_updateResolvesAllPended_cancelsResolution() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockUpdatePathResponse();
    mockStoredClaimSearch(buildStoredPriorClaim());

    ClaimResponse pendedCr = buildPriorClaimResponse(REVIEW_CODE_A4, "Pending");
    pendedCr.getMeta().addTag(PasSubmitService.PENDED_TAG_SYSTEM,
        PasSubmitService.PENDED_TAG_CODE, "Pended Resolution");
    mockClaimResponseSearch(pendedCr);

    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    verify(resolutionService).cancelResolution("prior-cr-id");
    verify(resolutionService, never()).scheduleResolution(any());
    verify(notificationService).dispatchResolvedClaimResponse("prior-cr-id");
  }

  @Test
  void submit_initialStillCreatesNewClaimResponse() {
    // Regression: initial submissions must still use the create path
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    mockValidatorAndResponseBuilder(requestBundle, claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    IFhirResourceDao<ClaimResponse> crDao =
        (IFhirResourceDao<ClaimResponse>) daoRegistry.getResourceDao(ClaimResponse.class);
    verify(crDao).create(any(), any(RequestDetails.class));
    verify(crDao, never()).update(any(), any(RequestDetails.class));
    verify(responseBuilder).buildSubmitResponse(any(), any(), any(), any());
    verify(responseBuilder, never()).applyItemDecisions(any(), any(), any());
  }

  // ===== Bundle Resource Resolution Tests =====

  @Test
  void submit_resolvesPatientByMemberIdentifier() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();

    // Stub resolver to simulate resolving patient to a server-side resource
    doAnswer(inv -> {
      Claim c = inv.getArgument(1);
      c.setPatient(new Reference("Patient/server-patient-123"));
      return null;
    }).when(bundleReferenceResolver).resolveReferences(eq(requestBundle), eq(claim), eq(true));

    mockValidatorAndResponseBuilder(requestBundle, claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    assertEquals("Patient/server-patient-123", claim.getPatient().getReference());
    verify(bundleReferenceResolver).resolveReferences(requestBundle, claim, true);
  }

  @Test
  void submit_delegatesBundleReferenceResolution() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();

    mockValidatorAndResponseBuilder(requestBundle, claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    verify(bundleReferenceResolver).resolveReferences(requestBundle, claim, true);
  }

  @Test
  void submit_resolvesOrganizationByNpi() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();

    // Stub resolver to simulate resolving insurer and provider
    doAnswer(inv -> {
      Claim c = inv.getArgument(1);
      c.setInsurer(new Reference("Organization/server-insurer-id"));
      c.setProvider(new Reference("Organization/server-provider-id"));
      return null;
    }).when(bundleReferenceResolver).resolveReferences(eq(requestBundle), eq(claim), eq(true));

    mockValidatorAndResponseBuilder(requestBundle, claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    assertEquals("Organization/server-insurer-id", claim.getInsurer().getReference());
    assertEquals("Organization/server-provider-id", claim.getProvider().getReference());
  }

  @Test
  void submit_resolvesCoverageByIdentifier() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();

    // Stub resolver to simulate resolving coverage
    doAnswer(inv -> {
      Claim c = inv.getArgument(1);
      c.getInsuranceFirstRep().setCoverage(new Reference("Coverage/server-coverage-456"));
      return null;
    }).when(bundleReferenceResolver).resolveReferences(eq(requestBundle), eq(claim), eq(true));

    mockValidatorAndResponseBuilder(requestBundle, claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(new CoverageDecision(REVIEW_CODE_A1, "Certified", false));

    service.submit(requestBundle);

    assertEquals("Coverage/server-coverage-456",
        claim.getInsuranceFirstRep().getCoverage().getReference());
  }

  // ===== Test Helpers =====

  private void mockStoredClaimSearch(Claim... storedClaims) {
    IFhirResourceDao<Claim> claimDao =
        (IFhirResourceDao<Claim>) daoRegistry.getResourceDao(Claim.class);
    when(claimDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(storedClaims));
  }

  private void mockClaimResponseSearch(ClaimResponse... responses) {
    IFhirResourceDao<ClaimResponse> crDao =
        (IFhirResourceDao<ClaimResponse>) daoRegistry.getResourceDao(ClaimResponse.class);
    when(crDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenReturn(List.of(responses));
  }

  private void mockValidatorAndResponseBuilder(Bundle requestBundle, Claim claim) {
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    ClaimResponse responseCr = new ClaimResponse();
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(responseCr);
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);
  }

  private void mockUpdatePathResponse() {
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(new ClaimResponse());
    when(responseBuilder.wrapInResponseBundle(any(ClaimResponse.class), any())).thenReturn(responseBundle);
  }

  @SuppressWarnings("unchecked")
  private Map<Integer, CoverageDecision> captureDecisions() {
    ArgumentCaptor<Map<Integer, CoverageDecision>> captor = ArgumentCaptor.forClass(Map.class);
    verify(responseBuilder).buildSubmitResponse(any(), any(), captor.capture(), any());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private Map<Integer, CoverageDecision> captureAppliedDecisions() {
    ArgumentCaptor<Map<Integer, CoverageDecision>> captor = ArgumentCaptor.forClass(Map.class);
    verify(responseBuilder).applyItemDecisions(any(), captor.capture(), any());
    return captor.getValue();
  }

  private void verifyCrUpdatedNotCreated() {
    IFhirResourceDao<ClaimResponse> crDao =
        (IFhirResourceDao<ClaimResponse>) daoRegistry.getResourceDao(ClaimResponse.class);
    verify(crDao).update(any(), any(RequestDetails.class));
    verify(crDao, never()).create(any(), any(RequestDetails.class));
  }

  // ===== Pended-update CommunicationRequest dedup =====

  private static CommunicationRequest documentationRequest(String id, String trn,
      CommunicationRequest.CommunicationRequestStatus status) {
    CommunicationRequest cr = new CommunicationRequest();
    cr.setId(id);
    cr.setStatus(status);
    cr.addIdentifier(new Identifier()
        .setSystem(PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM).setValue(trn));
    cr.addExtension(PasConstants.EXT_SERVICE_LINE_NUMBER, new PositiveIntType(1));
    cr.addPayload().setContent(new StringType(PasConstants.LOINC_QUESTIONNAIRE_REQUEST));
    return cr;
  }

  /** Response bundle shaped as addCommunicationRequests leaves it: fresh urn:uuid CR + CR ref. */
  private Bundle updateResponseBundleWithFreshRequest(ClaimResponse existingCr, String trn) {
    CommunicationRequest fresh = documentationRequest("cr-fresh", trn,
        CommunicationRequest.CommunicationRequestStatus.ACTIVE);
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(existingCr);
    responseBundle.addEntry().setFullUrl("urn:uuid:cr-fresh").setResource(fresh);
    existingCr.addCommunicationRequest(new Reference("urn:uuid:cr-fresh"));
    when(responseBuilder.wrapInResponseBundle(any(ClaimResponse.class), any()))
        .thenReturn(responseBundle);
    return responseBundle;
  }

  @Test
  @SuppressWarnings("unchecked")
  void submit_updateStillPended_reusesEquivalentOpenCommunicationRequest() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockStoredClaimSearch(buildStoredPriorClaim());

    ClaimResponse prior = buildPriorClaimResponse(REVIEW_CODE_A4, "Pending");
    prior.addCommunicationRequest(new Reference("CommunicationRequest/cr-open-1"));
    mockClaimResponseSearch(prior);
    updateResponseBundleWithFreshRequest(prior, "home-o2-std-questionnaire");

    IFhirResourceDao<CommunicationRequest> commReqDao =
        (IFhirResourceDao<CommunicationRequest>) daoRegistry.getResourceDao(CommunicationRequest.class);
    when(commReqDao.read(any(IdType.class), any(RequestDetails.class)))
        .thenReturn(documentationRequest("cr-open-1", "home-o2-std-questionnaire",
            CommunicationRequest.CommunicationRequestStatus.ACTIVE));

    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(pendedQuestionnaireDecision(HOME_O2_CANONICAL));
    mockQuestionnaireCatalog(Map.of(HOME_O2_CANONICAL, "home-o2-std-questionnaire"));

    service.submit(requestBundle);

    verify(commReqDao, never()).create(any(), any(RequestDetails.class));
    List<String> refs = prior.getCommunicationRequest().stream()
        .map(Reference::getReference).toList();
    assertEquals(List.of("CommunicationRequest/cr-open-1"), refs,
        "The urn:uuid duplicate must rewrite to the existing open request and dedupe");
  }

  @Test
  @SuppressWarnings("unchecked")
  void submit_updateStillPended_completedRequestDoesNotBlockANewOne() {
    Bundle requestBundle = buildUpdateBundle("prior-claim-id");
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    mockStoredClaimSearch(buildStoredPriorClaim());

    ClaimResponse prior = buildPriorClaimResponse(REVIEW_CODE_A4, "Pending");
    prior.addCommunicationRequest(new Reference("CommunicationRequest/cr-done-1"));
    mockClaimResponseSearch(prior);
    updateResponseBundleWithFreshRequest(prior, "home-o2-std-questionnaire");

    IFhirResourceDao<CommunicationRequest> commReqDao =
        (IFhirResourceDao<CommunicationRequest>) daoRegistry.getResourceDao(CommunicationRequest.class);
    when(commReqDao.read(any(IdType.class), any(RequestDetails.class)))
        .thenReturn(documentationRequest("cr-done-1", "home-o2-std-questionnaire",
            CommunicationRequest.CommunicationRequestStatus.COMPLETED));

    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(pendedQuestionnaireDecision(HOME_O2_CANONICAL));
    mockQuestionnaireCatalog(Map.of(HOME_O2_CANONICAL, "home-o2-std-questionnaire"));

    service.submit(requestBundle);

    verify(commReqDao).create(any(), any(RequestDetails.class));
    List<String> refs = prior.getCommunicationRequest().stream()
        .map(Reference::getReference).toList();
    assertEquals(
        List.of("CommunicationRequest/cr-done-1", "CommunicationRequest/server-commreq-id"),
        refs,
        "A completed request stays in history and a new open request is minted");
  }

  // ===== Attached documentation (supportingInfo QuestionnaireResponses) =====

  private static final String HOME_O2_CANONICAL =
      "http://example.org/fhir/Questionnaire/home-o2-std-questionnaire";

  @SuppressWarnings("unchecked")
  private void mockQuestionnaireCatalog(Map<String, String> canonicalToId) {
    IFhirResourceDao<Questionnaire> questionnaireDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(Questionnaire.class)).thenReturn(questionnaireDao);
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(RequestDetails.class)))
        .thenAnswer(invocation -> {
          SearchParameterMap params = invocation.getArgument(0);
          String url = ((ca.uhn.fhir.rest.param.UriParam) params.get("url").get(0).get(0)).getValue();
          String id = canonicalToId.get(url);
          if (id == null) {
            return List.of();
          }
          Questionnaire q = new Questionnaire();
          q.setId(id);
          q.setUrl(url);
          return List.of(q);
        });
  }

  private static CoverageDecision pendedQuestionnaireDecision(String canonical) {
    return new CoverageDecision(REVIEW_CODE_A4, "Pending", true, null, null,
        "clinical", List.of(canonical));
  }

  private static void attachCompletedQr(Bundle bundle, Claim claim, String canonical,
      QuestionnaireResponse.QuestionnaireResponseStatus status) {
    QuestionnaireResponse qr = new QuestionnaireResponse();
    qr.setId("qr-1");
    qr.setStatus(status);
    qr.setQuestionnaire(canonical);
    bundle.addEntry().setFullUrl("QuestionnaireResponse/qr-1").setResource(qr);
    claim.addSupportingInfo()
        .setSequence(1)
        .setCategory(new CodeableConcept().addCoding(
            new Coding("http://hl7.org/fhir/us/davinci-pas/CodeSystem/PASTempCodes",
                "additionalInformation", null)))
        .setValue(new Reference("QuestionnaireResponse/qr-1"));
  }

  @Test
  void submit_attachedCompletedQrSatisfiesDocumentation_certifiesInsteadOfPending() {
    Bundle requestBundle = buildMinimalBundle();
    Claim claim = (Claim) requestBundle.getEntryFirstRep().getResource();
    attachCompletedQr(requestBundle, claim, HOME_O2_CANONICAL,
        QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
    mockQuestionnaireCatalog(Map.of(HOME_O2_CANONICAL, "home-o2-std-questionnaire"));

    ClaimResponse cr = new ClaimResponse();
    cr.setId("CR-SAT");
    Bundle responseBundle = new Bundle();
    responseBundle.setType(Bundle.BundleType.COLLECTION);
    responseBundle.addEntry().setResource(cr);

    when(validator.validateSubmitBundle(requestBundle)).thenReturn(claim);
    when(evaluator.evaluate(any(), any(), any(), any(), any()))
        .thenReturn(pendedQuestionnaireDecision(HOME_O2_CANONICAL));
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);

    service.submit(requestBundle);

    ArgumentCaptor<Map<Integer, CoverageDecision>> decisionsCaptor =
        ArgumentCaptor.forClass(Map.class);
    verify(responseBuilder).buildSubmitResponse(any(), any(), decisionsCaptor.capture(), any());
    CoverageDecision applied = decisionsCaptor.getValue().get(1);
    assertEquals(REVIEW_CODE_A1, applied.reviewActionCode());
    assertFalse(applied.isPended());

    verify(resolutionService, never()).scheduleResolution(any());
    verify(daoRegistry.getResourceDao(ClaimResponse.class)).create(argThat(stored ->
        stored.getMeta().getTag(PasSubmitService.PENDED_TAG_SYSTEM, PasSubmitService.PENDED_TAG_CODE) == null
    ), any(RequestDetails.class));
  }

  @Test
  void applyAttachedDocumentation_inProgressQrDoesNotSatisfy() {
    Bundle bundle = buildMinimalBundle();
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();
    attachCompletedQr(bundle, claim, HOME_O2_CANONICAL,
        QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS);
    mockQuestionnaireCatalog(Map.of(HOME_O2_CANONICAL, "home-o2-std-questionnaire"));

    Map<Integer, CoverageDecision> adjusted = service.applyAttachedDocumentation(
        Map.of(1, pendedQuestionnaireDecision(HOME_O2_CANONICAL)), claim, bundle);

    assertEquals(REVIEW_CODE_A4, adjusted.get(1).reviewActionCode());
  }

  @Test
  void applyAttachedDocumentation_differentQuestionnaireDoesNotSatisfy() {
    String otherCanonical = "http://example.org/fhir/Questionnaire/other";
    Bundle bundle = buildMinimalBundle();
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();
    attachCompletedQr(bundle, claim, otherCanonical,
        QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
    mockQuestionnaireCatalog(Map.of(
        HOME_O2_CANONICAL, "home-o2-std-questionnaire",
        otherCanonical, "other-questionnaire"));

    Map<Integer, CoverageDecision> adjusted = service.applyAttachedDocumentation(
        Map.of(1, pendedQuestionnaireDecision(HOME_O2_CANONICAL)), claim, bundle);

    assertEquals(REVIEW_CODE_A4, adjusted.get(1).reviewActionCode());
  }

  @Test
  void applyAttachedDocumentation_requestedAttachmentCodesAreNotSatisfiedByQr() {
    Bundle bundle = buildMinimalBundle();
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();
    attachCompletedQr(bundle, claim, HOME_O2_CANONICAL,
        QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
    mockQuestionnaireCatalog(Map.of(HOME_O2_CANONICAL, "home-o2-std-questionnaire"));

    CoverageDecision withAttachmentCodes = new CoverageDecision(REVIEW_CODE_A4, "Pending", true,
        null, null, "clinical", List.of(HOME_O2_CANONICAL), List.of("18776-5"));
    Map<Integer, CoverageDecision> adjusted = service.applyAttachedDocumentation(
        Map.of(1, withAttachmentCodes), claim, bundle);

    assertEquals(REVIEW_CODE_A4, adjusted.get(1).reviewActionCode());
  }

  @Test
  void applyAttachedDocumentation_versionedCanonicalStillMatchesViaCatalog() {
    Bundle bundle = buildMinimalBundle();
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();
    attachCompletedQr(bundle, claim, HOME_O2_CANONICAL + "|2.2.0",
        QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
    mockQuestionnaireCatalog(Map.of(HOME_O2_CANONICAL, "home-o2-std-questionnaire"));

    Map<Integer, CoverageDecision> adjusted = service.applyAttachedDocumentation(
        Map.of(1, pendedQuestionnaireDecision(HOME_O2_CANONICAL)), claim, bundle);

    assertEquals(REVIEW_CODE_A1, adjusted.get(1).reviewActionCode());
    assertFalse(adjusted.get(1).isPended());
  }

  private Claim buildMinimalClaim() {
    Claim claim = new Claim();
    claim.setUse(Claim.Use.PREAUTHORIZATION);
    claim.setPatient(new Reference("Patient/1"));
    claim.setInsurer(new Reference("Organization/1"));
    claim.setProvider(new Reference("PractitionerRole/1"));
    claim.addInsurance().setCoverage(new Reference("Coverage/1")).setFocal(true);
    claim.addItem().setSequence(1)
        .setProductOrService(new CodeableConcept().addCoding(
            new Coding("http://snomed.info/sct", "417005", "Hospital Re-admit")));
    return claim;
  }

  private Bundle wrapInBundle(Claim claim) {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(claim);
    return bundle;
  }

  private Bundle buildMinimalBundle() {
    return wrapInBundle(buildMinimalClaim());
  }

  private Bundle buildRenewalBundle() {
    Claim claim = buildMinimalClaim();
    claim.getItemFirstRep().addExtension(PasConstants.CERTIFICATION_TYPE,
        new CodeableConcept().addCoding(new Coding(
            X12_CERT_TYPE_SYSTEM, CERT_TYPE_RENEWAL, "Renewal")));
    return wrapInBundle(claim);
  }

  private Bundle buildUpdateBundle(String priorClaimId) {
    Claim claim = buildMinimalClaim();
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    claim.addRelated().setClaim(new Reference("Claim/" + priorClaimId));
    claim.getItemFirstRep().addExtension(
        new Extension(PasConstants.INFO_CHANGED, new CodeType("changed")));
    Bundle bundle = wrapInBundle(claim);
    addPriorClaimToBundle(bundle, priorClaimId);
    return bundle;
  }

  private Bundle buildCancelBundle(String priorClaimId) {
    Claim claim = buildMinimalClaim();
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    claim.addRelated().setClaim(new Reference("Claim/" + priorClaimId));
    // Whole-authorization cancel via Claim-level certificationType "3"
    claim.addExtension(PasConstants.CERTIFICATION_TYPE,
        new CodeableConcept().addCoding(new Coding(
            X12_CERT_TYPE_SYSTEM, CERT_TYPE_CANCEL, "Cancel")));
    Bundle bundle = wrapInBundle(claim);
    addPriorClaimToBundle(bundle, priorClaimId);
    return bundle;
  }

  private void addPriorClaimToBundle(Bundle bundle, String priorClaimId) {
    Claim priorClaim = new Claim();
    priorClaim.setId(priorClaimId);
    priorClaim.addIdentifier()
        .setSystem("http://example.org/PATIENT_EVENT_TRACE_NUMBER")
        .setValue(priorClaimId);
    bundle.addEntry().setResource(priorClaim);
  }

  private Claim buildStoredPriorClaim() {
    Claim stored = new Claim();
    stored.setId("server-stored-prior-claim-id");
    return stored;
  }

  private ClaimResponse buildPriorClaimResponse(String reviewCode, String displayText) {
    return buildPriorClaimResponse(reviewCode, displayText, null);
  }

  private ClaimResponse buildPriorClaimResponse(String reviewCode, String displayText,
      String authNumber) {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("prior-cr-id");
    cr.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);

    ClaimResponse.ItemComponent item = cr.addItem();
    item.setItemSequence(1);
    ClaimResponse.AdjudicationComponent adj = item.addAdjudication();
    adj.setCategory(new CodeableConcept().addCoding(
        new Coding("http://terminology.hl7.org/CodeSystem/adjudication", "submitted", "Submitted")));
    adj.addExtension(PasExtensions.buildReviewActionExtension(reviewCode, displayText, authNumber));

    return cr;
  }
}
