package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasSubmitServiceTest {

  private static final String SERVER_BASE = "http://localhost:8080/fhir";

  private PasBundleValidator validator;
  private PasCoverageEvaluator evaluator;
  private PasResponseBuilder responseBuilder;
  private DaoRegistry daoRegistry;
  private PasSubmitService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    validator = mock(PasBundleValidator.class);
    evaluator = mock(PasCoverageEvaluator.class);
    responseBuilder = mock(PasResponseBuilder.class);
    daoRegistry = mock(DaoRegistry.class);

    IFhirResourceDao<ClaimResponse> crDao = mock(IFhirResourceDao.class);
    DaoMethodOutcome crOutcome = new DaoMethodOutcome();
    crOutcome.setId(new IdType("ClaimResponse/server-cr-id"));
    when(crDao.create(any(), any(RequestDetails.class))).thenReturn(crOutcome);
    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(crDao);

    IFhirResourceDao<Claim> claimDao = mock(IFhirResourceDao.class);
    DaoMethodOutcome claimOutcome = new DaoMethodOutcome();
    claimOutcome.setId(new IdType("Claim/server-claim-id"));
    when(claimDao.create(any(), any(RequestDetails.class))).thenReturn(claimOutcome);
    when(daoRegistry.getResourceDao(Claim.class)).thenReturn(claimDao);

    AppProperties appProperties = mock(AppProperties.class);
    when(appProperties.getServer_address()).thenReturn(SERVER_BASE);

    service = new PasSubmitService(validator, evaluator, responseBuilder, daoRegistry, appProperties);
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
        .thenReturn(new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A1, "Certified", false));
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);

    Bundle result = service.submit(requestBundle);
    assertNotNull(result);

    // Verify stored ClaimResponse has NO pended tag
    verify(daoRegistry.getResourceDao(ClaimResponse.class)).create(argThat(cr2 ->
        cr2.getMeta().getTag("http://hl7.org/fhir/us/davinci-pas/tag", "pended-resolution") == null
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
        .thenReturn(new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A4, "Pended", true));
    when(responseBuilder.buildSubmitResponse(any(), any(), any(), any())).thenReturn(responseBundle);

    service.submit(requestBundle);

    // Verify stored ClaimResponse HAS pended tag
    verify(daoRegistry.getResourceDao(ClaimResponse.class)).create(argThat(cr2 ->
        cr2.getMeta().getTag("http://hl7.org/fhir/us/davinci-pas/tag", "pended-resolution") != null
    ), any(RequestDetails.class));
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
        .thenReturn(new PasCoverageEvaluator.CoverageDecision(PasExtensions.REVIEW_CODE_A1, "Certified", false));
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

  private Bundle buildMinimalBundle() {
    Claim claim = new Claim();
    claim.setUse(Claim.Use.PREAUTHORIZATION);
    claim.setPatient(new Reference("Patient/1"));
    claim.setInsurer(new Reference("Organization/1"));
    claim.setProvider(new Reference("PractitionerRole/1"));
    claim.addInsurance().setCoverage(new Reference("Coverage/1")).setFocal(true);
    claim.addItem().setSequence(1)
        .setProductOrService(new CodeableConcept().addCoding(
            new Coding("http://snomed.info/sct", "417005", "Hospital Re-admit")));
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(claim);
    return bundle;
  }
}
