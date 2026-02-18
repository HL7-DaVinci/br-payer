package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasResponseBuilderTest {

  private PasResponseBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new PasResponseBuilder();
  }

  @Test
  void buildSubmitResponse_approved_hasA1AndAuthNumber() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");

    assertEquals(Bundle.BundleType.COLLECTION, response.getType());
    assertTrue(response.getMeta().hasProfile(PasExtensions.PROFILE_PAS_RESPONSE_BUNDLE));
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    assertNotNull(cr);
    assertEquals(ClaimResponse.ClaimResponseStatus.ACTIVE, cr.getStatus());
    assertEquals(ClaimResponse.Use.PREAUTHORIZATION, cr.getUse());
    assertEquals(ClaimResponse.RemittanceOutcome.COMPLETE, cr.getOutcome());
    assertFalse(cr.getItem().isEmpty());

    // Verify review action on item adjudication
    ClaimResponse.ItemComponent item = cr.getItem().get(0);
    assertFalse(item.getAdjudication().isEmpty());
    Extension reviewAction = item.getAdjudication().get(0).getExtensionByUrl(PasExtensions.REVIEW_ACTION);
    assertNotNull(reviewAction);
    Extension codeExt = reviewAction.getExtensionByUrl(PasExtensions.REVIEW_ACTION_CODE);
    assertNotNull(codeExt);
    CodeableConcept cc = (CodeableConcept) codeExt.getValue();
    assertEquals("A1", cc.getCodingFirstRep().getCode());

    // Approved items have an auth number
    Extension numberExt = reviewAction.getExtensionByUrl("number");
    assertNotNull(numberExt, "Approved items must have an authorization number");
    assertTrue(((StringType) numberExt.getValue()).getValue().startsWith("AUTH"));
  }

  @Test
  void buildSubmitResponse_approved_hasPreAuthPeriod() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A1, "Certified in total", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    ClaimResponse.ItemComponent item = cr.getItem().get(0);

    Extension preAuthPeriod = item.getExtensionByUrl(PasExtensions.ITEM_PREAUTH_PERIOD);
    assertNotNull(preAuthPeriod, "Approved items must have itemPreAuthPeriod");
  }

  @Test
  void buildSubmitResponse_pended_hasA4NoAuthNumber() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A4, "Pending", true));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");

    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    ClaimResponse.ItemComponent item = cr.getItem().get(0);
    Extension reviewAction = item.getAdjudication().get(0).getExtensionByUrl(PasExtensions.REVIEW_ACTION);

    // Pended items do not have an auth number
    assertNull(reviewAction.getExtensionByUrl("number"),
        "Pended items must not have an authorization number");

    // Pended items do not have preAuthPeriod
    assertNull(item.getExtensionByUrl(PasExtensions.ITEM_PREAUTH_PERIOD),
        "Pended items must not have itemPreAuthPeriod");
  }

  @Test
  void buildSubmitResponse_denied_hasA2() {
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A2, "Not Certified", false));

    Bundle response = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");

    ClaimResponse cr = (ClaimResponse) response.getEntryFirstRep().getResource();
    Extension reviewAction = cr.getItem().get(0).getAdjudication().get(0)
        .getExtensionByUrl(PasExtensions.REVIEW_ACTION);
    Extension codeExt = reviewAction.getExtensionByUrl(PasExtensions.REVIEW_ACTION_CODE);
    CodeableConcept cc = (CodeableConcept) codeExt.getValue();
    assertEquals("A2", cc.getCodingFirstRep().getCode());
  }

  @Test
  void buildSubmitResponse_itemSequencesMatchRequest() {
    Claim claim = buildClaim();
    claim.addItem().setSequence(2).setProductOrService(
        new CodeableConcept().addCoding(new Coding("http://example.com", "99214", "Office Visit")));
    var decisions = Map.of(
        1, new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A1, "Certified in total", false),
        2, new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A3, "Not Required", false));

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
        new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A3, "Not Required", false));

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
        new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A1, "Certified in total", false));

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
  void buildResolvedResponse_upgradesA4ToA1() {
    // Build an initial pended response
    Claim claim = buildClaim();
    var decisions = Map.of(1,
        new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A4, "Pending", true));
    Bundle pendedResponse = builder.buildSubmitResponse(claim, new Bundle(), decisions, "AUTH");
    ClaimResponse pendedCr = (ClaimResponse) pendedResponse.getEntryFirstRep().getResource();
    pendedCr.setId("CR-PENDED");

    // Resolve it
    Bundle resolvedBundle = builder.buildResolvedResponse(pendedCr, "AUTH");
    ClaimResponse resolvedCr = (ClaimResponse) resolvedBundle.getEntryFirstRep().getResource();

    // Should now be A1 with auth number
    ClaimResponse.ItemComponent item = resolvedCr.getItem().get(0);
    Extension reviewAction = item.getAdjudication().get(0).getExtensionByUrl(PasExtensions.REVIEW_ACTION);
    Extension codeExt = reviewAction.getExtensionByUrl(PasExtensions.REVIEW_ACTION_CODE);
    CodeableConcept cc = (CodeableConcept) codeExt.getValue();
    assertEquals("A1", cc.getCodingFirstRep().getCode());

    Extension numberExt = reviewAction.getExtensionByUrl("number");
    assertNotNull(numberExt, "Resolved items must have an authorization number");
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
}
