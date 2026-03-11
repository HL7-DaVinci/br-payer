package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.SimpleQuantity;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.starter.AppProperties;

class PasResponseBuilderTest {

  private static final String SERVER_BASE = "http://localhost:8080/fhir";
  private PasResponseBuilder builder;

  @BeforeEach
  void setUp() {
    AppProperties appProperties = new AppProperties();
    appProperties.setServer_address(SERVER_BASE);
    builder = new PasResponseBuilder(appProperties);
  }

  @Test
  void buildSubmitResponse_approved_hasA1AndAuthNumber() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");

    assertEquals(Bundle.BundleType.COLLECTION, response.getType());
    assertTrue(response.getMeta().hasProfile(PasConstants.PROFILE_PAS_RESPONSE_BUNDLE));
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    assertNotNull(cr);
    assertEquals(ClaimResponse.ClaimResponseStatus.ACTIVE, cr.getStatus());
    assertEquals(ClaimResponse.Use.PREAUTHORIZATION, cr.getUse());
    assertEquals(ClaimResponse.RemittanceOutcome.COMPLETE, cr.getOutcome());
    assertFalse(cr.getItem().isEmpty());

    // Verify review action on item adjudication
    ClaimResponse.ItemComponent item = cr.getItem().get(0);
    assertFalse(item.getAdjudication().isEmpty());
    Extension reviewAction = item.getAdjudication().get(0).getExtensionByUrl(PasConstants.REVIEW_ACTION);
    assertNotNull(reviewAction);
    Extension codeExt = reviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    assertNotNull(codeExt);
    CodeableConcept cc = (CodeableConcept) codeExt.getValue();
    assertEquals("A1", cc.getCodingFirstRep().getCode());

    // Approved items have an auth number
    Extension numberExt = reviewAction.getExtensionByUrl("number");
    assertNotNull(numberExt, "Approved items must have an authorization number");
    assertTrue(((StringType) numberExt.getValue()).getValue().startsWith("AUTH"));

    String fullUrl = response.getEntryFirstRep().getFullUrl();
    assertTrue(fullUrl.startsWith(SERVER_BASE + "/ClaimResponse/"));
    assertFalse(fullUrl.contains("/_history/"), "Response fullUrl should be versionless");
    assertFalse(fullUrl.contains("/ClaimResponse/ClaimResponse/"),
        "Response fullUrl should not duplicate the resource type segment");
  }

  @Test
  void buildSubmitResponse_approved_hasPreAuthPeriod() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    ClaimResponse.ItemComponent item = cr.getItem().get(0);

    Extension preAuthPeriod = item.getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD);
    assertNotNull(preAuthPeriod, "Approved items must have itemPreAuthPeriod");
  }

  @Test
  void buildSubmitResponse_pended_hasA4NoAuthNumber() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A4, "Pending", true));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");

    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    ClaimResponse.ItemComponent item = cr.getItem().get(0);
    Extension reviewAction = item.getAdjudication().get(0).getExtensionByUrl(PasConstants.REVIEW_ACTION);

    // Pended items do not have an auth number
    assertNull(reviewAction.getExtensionByUrl("number"),
        "Pended items must not have an authorization number");

    // Pended items do not have preAuthPeriod
    assertNull(item.getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD),
        "Pended items must not have itemPreAuthPeriod");
  }

  @Test
  void buildSubmitResponse_denied_hasA2() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A2, "Not Certified", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");

    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    Extension reviewAction = cr.getItem().get(0).getAdjudication().get(0)
        .getExtensionByUrl(PasConstants.REVIEW_ACTION);
    Extension codeExt = reviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    CodeableConcept cc = (CodeableConcept) codeExt.getValue();
    assertEquals("A2", cc.getCodingFirstRep().getCode());
  }

  @Test
  void buildSubmitResponse_itemSequencesMatchRequest() {
    Claim claim = buildClaim();
    claim.addItem().setSequence(2).setProductOrService(
        new CodeableConcept().addCoding(new Coding("http://example.com", "99214", "Office Visit")));
    var decisions = Map.of(
        1, new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false),
        2, new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A3, "Not Required", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    assertEquals(2, cr.getItem().size());
    assertEquals(1, cr.getItem().get(0).getItemSequence());
    assertEquals(2, cr.getItem().get(1).getItemSequence());
  }

  @Test
  void buildSubmitResponse_echoesClaimFields() {
    Claim claim = buildClaim();
    claim.setId("test-claim");
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A3, "Not Required", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    assertEquals("Patient/1", cr.getPatient().getReference());
    assertEquals("Organization/1", cr.getInsurer().getReference());
    assertEquals("PractitionerRole/1", cr.getRequestor().getReference());
    assertTrue(cr.getRequest().getReference().contains("test-claim"));
  }

  @Test
  void buildSubmitResponse_adjudicationCategoryIsSubmitted() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    ClaimResponse.AdjudicationComponent adj = cr.getItem().get(0).getAdjudication().get(0);
    assertEquals("submitted", adj.getCategory().getCodingFirstRep().getCode());
    assertEquals("http://terminology.hl7.org/CodeSystem/adjudication",
        adj.getCategory().getCodingFirstRep().getSystem());
  }

  @Test
  void buildInquiryResponse_wrapsInParameters() {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("CR-001");
    cr.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);

    Parameters params = builder.buildInquiryResponse(List.of(cr));
    assertNotNull(params);
    assertFalse(params.getParameter().isEmpty());
    assertEquals("responseBundle", params.getParameter().get(0).getName());
    assertNotNull(params.getParameter().get(0).getResource());
    assertInstanceOf(Bundle.class, params.getParameter().get(0).getResource());
  }

  @Test
  void buildInquiryResponse_emptyList_returnsEmptyParameters() {
    Parameters params = builder.buildInquiryResponse(List.of());
    assertNotNull(params);
    assertTrue(params.getParameter().isEmpty());
  }

  @Test
  void resolvePendedItems_upgradesA4ToA1() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A4, "Pending", true));
    Bundle pendedResponse = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse pendedCr = (ClaimResponse) pendedResponse.getEntryFirstRep().getResource();

    builder.resolvePendedItems(pendedCr, "AUTH");

    ClaimResponse.ItemComponent item = pendedCr.getItem().get(0);
    Extension reviewAction = item.getAdjudication().get(0).getExtensionByUrl(PasConstants.REVIEW_ACTION);
    Extension codeExt = reviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    CodeableConcept cc = (CodeableConcept) codeExt.getValue();
    assertEquals("A1", cc.getCodingFirstRep().getCode());

    Extension numberExt = reviewAction.getExtensionByUrl("number");
    assertNotNull(numberExt, "Resolved items must have an authorization number");
  }

  @Test
  void resolvePendedItems_onlyUpgradesPendedItems() {
    Claim claim = buildClaim();
    claim.addItem().setSequence(2).setProductOrService(
        new CodeableConcept().addCoding(new Coding("http://example.com", "99214", "Office Visit")));

    var decisions = Map.of(
        1, new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A4, "Pending", true),
        2, new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A2, "Not Certified", false));
    Bundle pendedResponse = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse pendedCr = (ClaimResponse) pendedResponse.getEntryFirstRep().getResource();

    builder.resolvePendedItems(pendedCr, "AUTH");

    ClaimResponse.ItemComponent resolvedPendedItem = pendedCr.getItem().get(0);
    Extension resolvedReviewAction = resolvedPendedItem.getAdjudication().get(0)
        .getExtensionByUrl(PasConstants.REVIEW_ACTION);
    Extension resolvedCodeExt = resolvedReviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    CodeableConcept resolvedCode = (CodeableConcept) resolvedCodeExt.getValue();
    assertEquals("A1", resolvedCode.getCodingFirstRep().getCode());
    assertNotNull(resolvedReviewAction.getExtensionByUrl("number"));
    assertNotNull(resolvedPendedItem.getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD));

    ClaimResponse.ItemComponent unchangedDeniedItem = pendedCr.getItem().get(1);
    Extension deniedReviewAction = unchangedDeniedItem.getAdjudication().get(0)
        .getExtensionByUrl(PasConstants.REVIEW_ACTION);
    Extension deniedCodeExt = deniedReviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    CodeableConcept deniedCode = (CodeableConcept) deniedCodeExt.getValue();
    assertEquals("A2", deniedCode.getCodingFirstRep().getCode());
    assertNull(deniedReviewAction.getExtensionByUrl("number"));
    assertNull(unchangedDeniedItem.getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD));
  }

  @Test
  void modifyPendedItems_upgradesA4ToA6WithAuthNumber() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A4, "Pending", true));
    Bundle pendedResponse = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse pendedCr = (ClaimResponse) pendedResponse.getEntryFirstRep().getResource();

    builder.modifyPendedItems(pendedCr, "MOD");

    ClaimResponse.ItemComponent item = pendedCr.getItem().get(0);
    Extension reviewAction = item.getAdjudication().get(0).getExtensionByUrl(PasConstants.REVIEW_ACTION);
    Extension codeExt = reviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
    CodeableConcept cc = (CodeableConcept) codeExt.getValue();
    assertEquals("A6", cc.getCodingFirstRep().getCode());
    assertEquals("Modified", cc.getCodingFirstRep().getDisplay());

    Extension numberExt = reviewAction.getExtensionByUrl("number");
    assertNotNull(numberExt, "Modified items must have an authorization number");
    assertTrue(((StringType) numberExt.getValue()).getValue().startsWith("MOD"));
  }

  @Test
  void modifyPendedItems_addsPreAuthPeriod() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A4, "Pending", true));
    Bundle pendedResponse = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse pendedCr = (ClaimResponse) pendedResponse.getEntryFirstRep().getResource();

    builder.modifyPendedItems(pendedCr, "MOD");

    Extension preAuthPeriod = pendedCr.getItem().get(0).getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD);
    assertNotNull(preAuthPeriod, "Modified items must have itemPreAuthPeriod");
  }

  // ===== administrationReferenceNumber lifecycle =====

  @Test
  void buildSubmitResponse_pendedItemGetsAdminRefNumber() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A4, "Pending", true));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    ClaimResponse.ItemComponent item = cr.getItem().get(0);

    Extension adminRef = item.getExtensionByUrl(PasConstants.ADMIN_REF_NUMBER);
    assertNotNull(adminRef, "Pended items must have administrationReferenceNumber");
    assertTrue(((StringType) adminRef.getValue()).getValue().startsWith("AUTH"));
    assertTrue(((StringType) adminRef.getValue()).getValue().contains("PEND"));
  }

  @Test
  void buildSubmitResponse_approvedItemDoesNotGetAdminRefNumber() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    ClaimResponse.ItemComponent item = cr.getItem().get(0);

    assertNull(item.getExtensionByUrl(PasConstants.ADMIN_REF_NUMBER),
        "Approved items must not have administrationReferenceNumber");
  }

  @Test
  void resolvePendedItems_removesAdminRefNumber() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A4, "Pending", true));
    Bundle pendedResponse = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse pendedCr = (ClaimResponse) pendedResponse.getEntryFirstRep().getResource();

    // Verify admin ref is present before finalization
    assertNotNull(pendedCr.getItem().get(0).getExtensionByUrl(PasConstants.ADMIN_REF_NUMBER));

    builder.resolvePendedItems(pendedCr, "AUTH");

    assertNull(pendedCr.getItem().get(0).getExtensionByUrl(PasConstants.ADMIN_REF_NUMBER),
        "Finalized items must not retain administrationReferenceNumber");
  }

  @Test
  void applyItemDecisions_cancelledItemRemovesAdminRefNumber() {
    // Start with a pended item that has adminRefNumber
    ClaimResponse cr = buildClaimResponseWithItems(
        Map.of(1, REVIEW_CODE_A4));
    cr.getItem().get(0).addExtension(PasConstants.ADMIN_REF_NUMBER, new StringType("PEND0001"));

    // Apply A2 (denial/cancel) -- adminRefNumber should be removed
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A2, "Not Certified", false));
    builder.applyItemDecisions(cr, decisions, "AUTH");

    assertNull(cr.getItem().get(0).getExtensionByUrl(PasConstants.ADMIN_REF_NUMBER),
        "Cancelled items must not retain administrationReferenceNumber");
  }

  // ===== applyItemDecisions Tests =====

  @Test
  void applyItemDecisions_existingItemUpdatedWithNewDecision() {
    // Build a CR with an existing item at seq 1 with A4
    ClaimResponse cr = buildClaimResponseWithItems(
        Map.of(1, REVIEW_CODE_A4));

    // Apply A1 decision to seq 1
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));
    builder.applyItemDecisions(cr, decisions, "AUTH");

    ClaimResponse.ItemComponent item = cr.getItem().get(0);
    String reviewCode = PasExtensions.extractReviewActionCode(item);
    assertEquals(REVIEW_CODE_A1, reviewCode);
  }

  @Test
  void applyItemDecisions_newItemAddedToCR() {
    // Build a CR with only item seq 1
    ClaimResponse cr = buildClaimResponseWithItems(
        Map.of(1, REVIEW_CODE_A1));

    // Apply decision to seq 2 (not in original CR)
    var decisions = Map.of(2,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A3, "Not Required", false));
    builder.applyItemDecisions(cr, decisions, "AUTH");

    assertEquals(2, cr.getItem().size());
    assertEquals(2, cr.getItem().get(1).getItemSequence());
    String newItemCode = PasExtensions.extractReviewActionCode(cr.getItem().get(1));
    assertEquals(REVIEW_CODE_A3, newItemCode);
  }

  @Test
  void applyItemDecisions_approvedItemGetsPreAuthPeriod() {
    ClaimResponse cr = buildClaimResponseWithItems(
        Map.of(1, REVIEW_CODE_A4));

    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));
    builder.applyItemDecisions(cr, decisions, "AUTH");

    Extension preAuthPeriod = cr.getItem().get(0).getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD);
    assertNotNull(preAuthPeriod, "Approved items must have itemPreAuthPeriod");
  }

  @Test
  void applyItemDecisions_nonApprovedItemDoesNotGetPreAuthPeriod() {
    // Start with an approved item that has preAuthPeriod
    ClaimResponse cr = buildClaimResponseWithItems(
        Map.of(1, REVIEW_CODE_A1));
    cr.getItem().get(0).addExtension(PasConstants.ITEM_PREAUTH_PERIOD,
        new Period().setStart(new java.util.Date()));  // existing preAuthPeriod

    // Apply A2 (denial) -- preAuthPeriod should be removed
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A2, "Not Certified", false));
    builder.applyItemDecisions(cr, decisions, "AUTH");

    Extension preAuthPeriod = cr.getItem().get(0).getExtensionByUrl(PasConstants.ITEM_PREAUTH_PERIOD);
    assertNull(preAuthPeriod, "Denied items must not have itemPreAuthPeriod");
  }

  // ===== CR-level identifier and transmissionIdentifiers =====

  @Test
  void buildSubmitResponse_echoesTransmissionIdentifiersFromRequest() {
    Claim claim = buildClaim();
    claim.addExtension(PasExtensions.buildTransmissionIdentifiersExtension("SENDER-APP", "RECEIVER-APP"));
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    assertFalse(cr.getIdentifier().isEmpty(), "ClaimResponse must have at least one identifier");
    Extension txExt = cr.getExtensionByUrl(PasConstants.TRANSMISSION_IDENTIFIERS);
    assertNotNull(txExt, "ClaimResponse must have transmissionIdentifiers extension");
    assertEquals("RECEIVER-APP",
        ((StringType) txExt.getExtensionByUrl("applicationSenderCode").getValue()).getValue());
    assertEquals("SENDER-APP",
        ((StringType) txExt.getExtensionByUrl("applicationReceiverCode").getValue()).getValue());
  }

  @Test
  void buildSubmitResponse_derivesTransmissionIdentifiersFromOrganizationNpi() {
    Claim claim = buildClaim();
    claim.setProvider(new Reference("Organization/provider-org"));
    claim.setInsurer(new Reference("Organization/insurer-org"));

    Organization provider = new Organization();
    provider.setId("provider-org");
    provider.addIdentifier().setSystem("http://hl7.org/fhir/sid/us-npi").setValue("8189991234");

    Organization insurer = new Organization();
    insurer.setId("insurer-org");
    insurer.addIdentifier().setSystem("http://hl7.org/fhir/sid/us-npi").setValue("1234567893");

    Bundle requestBundle = new Bundle();
    requestBundle.addEntry().setResource(provider).setFullUrl("Organization/provider-org");
    requestBundle.addEntry().setResource(insurer).setFullUrl("Organization/insurer-org");

    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));
    Bundle response = builder.buildSubmitResponse(claim, requestBundle, decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    Extension txExt = cr.getExtensionByUrl(PasConstants.TRANSMISSION_IDENTIFIERS);
    assertNotNull(txExt, "ClaimResponse must derive transmissionIdentifiers from Org NPI");
    assertEquals("1234567893",
        ((StringType) txExt.getExtensionByUrl("applicationSenderCode").getValue()).getValue());
    assertEquals("8189991234",
        ((StringType) txExt.getExtensionByUrl("applicationReceiverCode").getValue()).getValue());
  }

  @Test
  void buildSubmitResponse_omitsTransmissionIdentifiersWhenNoSourceAvailable() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    Extension txExt = cr.getExtensionByUrl(PasConstants.TRANSMISSION_IDENTIFIERS);
    assertNull(txExt, "TransmissionIdentifiers should be omitted when no source available");
  }

  // ===== Item-level trace numbers =====

  @Test
  void buildSubmitResponse_echoesTraceNumbersFromRequest() {
    Claim claim = buildClaim();
    Identifier traceId = new Identifier()
        .setSystem("http://example.org/trace").setValue("TRACE-001");
    claim.getItemFirstRep().addExtension(PasConstants.ITEM_TRACE_NUMBER, traceId);

    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));
    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    Extension traceExt = cr.getItem().get(0).getExtensionByUrl(PasConstants.ITEM_TRACE_NUMBER);
    assertNotNull(traceExt, "Response item must echo trace number from request");
    Identifier resultTrace = (Identifier) traceExt.getValue();
    assertEquals("TRACE-001", resultTrace.getValue());
  }

  // ===== preAuthIssueDate =====

  @Test
  void buildSubmitResponse_approvedItemHasPreAuthIssueDate() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    Extension issueDateExt = cr.getItem().get(0).getExtensionByUrl(PasConstants.ITEM_PREAUTH_ISSUE_DATE);
    assertNotNull(issueDateExt, "Approved items must have preAuthIssueDate");
    assertInstanceOf(DateType.class, issueDateExt.getValue());
  }

  @Test
  void buildSubmitResponse_pendedItemDoesNotHavePreAuthIssueDate() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A4, "Pending", true));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    assertNull(cr.getItem().get(0).getExtensionByUrl(PasConstants.ITEM_PREAUTH_ISSUE_DATE),
        "Pended items must not have preAuthIssueDate");
  }

  // ===== requestedServiceDate =====

  @Test
  void buildSubmitResponse_echoesRequestedServiceDate() {
    Claim claim = buildClaim();
    Period servicedPeriod = new Period().setStart(new Date());
    claim.getItemFirstRep().setServiced(servicedPeriod);

    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));
    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    Extension serviceDateExt = cr.getItem().get(0)
        .getExtensionByUrl(PasConstants.ITEM_REQUESTED_SERVICE_DATE);
    assertNotNull(serviceDateExt, "Response item must echo requestedServiceDate from request");
    assertInstanceOf(Period.class, serviceDateExt.getValue());
  }

  // ===== addItem for A6 Modified =====

  @Test
  void buildSubmitResponse_a6WithModification_producesAddItem() {
    Claim claim = buildClaim();
    CodeableConcept modifiedService = new CodeableConcept().addCoding(
        new Coding("http://example.com", "99214", "Higher Office Visit"));
    SimpleQuantity modifiedQty = new SimpleQuantity();
    modifiedQty.setValue(2);

    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(
            REVIEW_CODE_A6, "Modified", false, modifiedService, modifiedQty));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    assertFalse(cr.getAddItem().isEmpty(), "A6 with modification must produce addItem");

    ClaimResponse.ItemComponent item = cr.getItem().get(0);
    Extension itemReviewAction = item.getAdjudication().get(0)
        .getExtensionByUrl(PasConstants.REVIEW_ACTION);
    Extension itemAuthNumber = itemReviewAction.getExtensionByUrl("number");
    assertNotNull(itemAuthNumber, "A6 item adjudication must include an authorization number");
    String itemAuthValue = ((StringType) itemAuthNumber.getValue()).getValue();

    ClaimResponse.AddedItemComponent addItem = cr.getAddItem().get(0);
    assertEquals(1, addItem.getItemSequence().get(0).getValue());
    assertEquals("99214", addItem.getProductOrService().getCodingFirstRep().getCode());
    assertEquals(2, addItem.getQuantity().getValue().intValue());

    // addItem should have reviewAction adjudication
    assertFalse(addItem.getAdjudication().isEmpty());
    Extension reviewAction = addItem.getAdjudication().get(0)
        .getExtensionByUrl(PasConstants.REVIEW_ACTION);
    assertNotNull(reviewAction);
    Extension addItemAuthNumber = reviewAction.getExtensionByUrl("number");
    assertNotNull(addItemAuthNumber, "A6 addItem adjudication must include an authorization number");
    assertEquals(itemAuthValue, ((StringType) addItemAuthNumber.getValue()).getValue(),
        "A6 addItem authorization number should match the item adjudication number");
  }

  @Test
  void buildSubmitResponse_a6WithoutModification_noAddItem() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A6, "Modified", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();

    assertTrue(cr.getAddItem().isEmpty(), "A6 without modification must not produce addItem");
    assertNotNull(PasExtensions.extractAuthorizationNumber(cr.getItem().get(0)),
        "A6 item adjudication must include an authorization number");
  }

  // ===== Response bundle includes referenced resources =====

  @Test
  void buildSubmitResponse_includesReferencedResources() {
    Claim claim = buildClaim();
    Bundle requestBundle = new Bundle();
    requestBundle.setType(Bundle.BundleType.COLLECTION);

    Patient patient = new Patient();
    patient.setId("1");
    requestBundle.addEntry()
        .setFullUrl("http://example.org/fhir/Patient/1")
        .setResource(patient);

    Organization insurer = new Organization();
    insurer.setId("1");
    requestBundle.addEntry()
        .setFullUrl("http://example.org/fhir/Organization/1")
        .setResource(insurer);

    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, requestBundle, decisions, "AUTH");

    // Response bundle should have ClaimResponse + Patient + Organization
    assertTrue(response.getEntry().size() >= 3,
        "Response bundle must include referenced resources");

    boolean hasPatient = response.getEntry().stream()
        .anyMatch(e -> e.getResource() instanceof Patient);
    boolean hasOrg = response.getEntry().stream()
        .anyMatch(e -> e.getResource() instanceof Organization);
    assertTrue(hasPatient, "Response bundle must include Patient");
    assertTrue(hasOrg, "Response bundle must include Organization");
  }

  @Test
  void buildSubmitResponse_usesServerBaseFullUrl() {
    Claim claim = buildClaim();
    Bundle requestBundle = new Bundle();

    Patient patient = new Patient();
    patient.setId("1");
    requestBundle.addEntry()
        .setFullUrl("http://example.org/fhir/Patient/1")
        .setResource(patient);

    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, requestBundle, decisions, "AUTH");

    String patientFullUrl = response.getEntry().stream()
        .filter(e -> e.getResource() instanceof Patient)
        .map(Bundle.BundleEntryComponent::getFullUrl)
        .findFirst().orElse(null);
    assertTrue(patientFullUrl != null && patientFullUrl.startsWith(SERVER_BASE),
        "Referenced resource fullUrl must use server base for bundle resolution");
  }

  @Test
  void buildSubmitResponse_noDuplicateResources() {
    Claim claim = buildClaim();
    // Insurer and requestor point to same Organization/1
    claim.setProvider(new Reference("Organization/1"));
    Bundle requestBundle = new Bundle();

    Organization org = new Organization();
    org.setId("1");
    requestBundle.addEntry()
        .setFullUrl("http://example.org/fhir/Organization/1")
        .setResource(org);

    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, requestBundle, decisions, "AUTH");

    long orgCount = response.getEntry().stream()
        .filter(e -> e.getResource() instanceof Organization)
        .count();
    assertEquals(1, orgCount, "Must not add duplicate resources");
  }

  @Test
  void wrapInResponseBundle_nullRequestBundle_stillWorks() {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("CR-001");
    Bundle response = builder.wrapInResponseBundle(cr, null);

    assertNotNull(response);
    assertEquals(1, response.getEntry().size(), "Should only contain ClaimResponse");
  }

  // ===== Auth number stability =====

  @Test
  void applyItemDecisions_preservesExistingAuthNumber() {
    ClaimResponse cr = buildClaimResponseWithItems(Map.of(1, REVIEW_CODE_A1));
    ClaimResponse.AdjudicationComponent adj = cr.getItem().get(0).getAdjudication().get(0);
    adj.getExtension().removeIf(e -> PasConstants.REVIEW_ACTION.equals(e.getUrl()));
    adj.addExtension(PasExtensions.buildReviewActionExtension(REVIEW_CODE_A1, "Certified", "AUTH-ORIG"));

    // Apply new A1 decision (update scenario) -- auth number should be preserved
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));
    builder.applyItemDecisions(cr, decisions, "AUTH-NEW-");

    String authNumber = PasExtensions.extractAuthorizationNumber(cr.getItem().get(0));
    assertEquals("AUTH-ORIG", authNumber,
        "Existing auth number must be preserved across updates");
  }

  @Test
  void applyItemDecisions_generatesNewAuthNumberWhenNoneExists() {
    ClaimResponse cr = buildClaimResponseWithItems(Map.of(1, REVIEW_CODE_A4));

    // Apply A1 decision to previously pended item (no auth number yet)
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A1, "Certified in total", false));
    builder.applyItemDecisions(cr, decisions, "AUTH-");

    String authNumber = PasExtensions.extractAuthorizationNumber(cr.getItem().get(0));
    assertNotNull(authNumber, "New auth number must be generated when none exists");
    assertTrue(authNumber.startsWith("AUTH-"));
  }

  @Test
  void applyItemDecisions_modifiedItemGetsAuthNumber() {
    ClaimResponse cr = buildClaimResponseWithItems(Map.of(1, REVIEW_CODE_A4));

    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(REVIEW_CODE_A6, "Modified", false));
    builder.applyItemDecisions(cr, decisions, "AUTH-");

    String authNumber = PasExtensions.extractAuthorizationNumber(cr.getItem().get(0));
    assertNotNull(authNumber, "A6 decisions must include an authorization number");
    assertTrue(authNumber.startsWith("AUTH-"));
  }

  // ===== Helpers =====

  private Claim buildClaim() {
    Claim claim = new Claim();
    claim.setUse(Claim.Use.PREAUTHORIZATION);
    claim.setType(new CodeableConcept().addCoding(new Coding(
        "http://terminology.hl7.org/CodeSystem/claim-type", "professional", "Professional")));
    claim.setPatient(new Reference("Patient/1"));
    claim.setInsurer(new Reference("Organization/1"));
    claim.setProvider(new Reference("PractitionerRole/1"));
    claim.addInsurance().setCoverage(new Reference("Coverage/1")).setFocal(true);
    claim.addItem().setSequence(1)
        .setProductOrService(new CodeableConcept().addCoding(
            new Coding("http://example.com", "99213", "Office Visit")));
    return claim;
  }

  private ClaimResponse buildClaimResponseWithItems(Map<Integer, String> itemReviewCodes) {
    ClaimResponse cr = new ClaimResponse();
    cr.setId("test-cr");
    cr.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);
    for (Map.Entry<Integer, String> entry : itemReviewCodes.entrySet()) {
      ClaimResponse.ItemComponent item = cr.addItem();
      item.setItemSequence(entry.getKey());
      ClaimResponse.AdjudicationComponent adj = item.addAdjudication();
      adj.setCategory(new CodeableConcept().addCoding(
          new Coding("http://terminology.hl7.org/CodeSystem/adjudication", "submitted", "Submitted Amount")));
      adj.addExtension(PasExtensions.buildReviewActionExtension(entry.getValue(), "Display", null));
    }
    return cr;
  }
}
