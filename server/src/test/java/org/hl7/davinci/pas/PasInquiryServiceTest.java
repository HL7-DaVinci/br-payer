package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasInquiryServiceTest {

  private PasBundleValidator validator;
  private DaoRegistry daoRegistry;
  private PasResponseBuilder responseBuilder;
  private PasBundleReferenceResolver bundleReferenceResolver;
  private IFhirResourceDao<ClaimResponse> claimResponseDao;
  private IFhirResourceDao<Claim> claimDao;
  private IFhirResourceDao<Patient> patientDao;
  private IFhirResourceDao<Organization> organizationDao;
  private IFhirResourceDao<Coverage> coverageDao;
  private PasInquiryService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    validator = mock(PasBundleValidator.class);
    daoRegistry = mock(DaoRegistry.class);
    responseBuilder = mock(PasResponseBuilder.class);
    bundleReferenceResolver = mock(PasBundleReferenceResolver.class);

    claimResponseDao = mock(IFhirResourceDao.class);
    claimDao = mock(IFhirResourceDao.class);
    patientDao = mock(IFhirResourceDao.class);
    organizationDao = mock(IFhirResourceDao.class);
    coverageDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(claimResponseDao);
    when(daoRegistry.getResourceDao(Claim.class)).thenReturn(claimDao);
    when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
    when(daoRegistry.getResourceDao(Organization.class)).thenReturn(organizationDao);
    when(daoRegistry.getResourceDao(Coverage.class)).thenReturn(coverageDao);

    // Default: resource resolution DAOs return empty results (no server-side match)
    when(patientDao.searchForResources(any(), any())).thenReturn(List.of());
    when(organizationDao.searchForResources(any(), any())).thenReturn(List.of());
    when(coverageDao.searchForResources(any(), any())).thenReturn(List.of());

    service = new PasInquiryService(validator, daoRegistry, responseBuilder, bundleReferenceResolver);
  }

  @Test
  void inquire_noMatches_scopesSearchToClaimContext() {
    Bundle requestBundle = new Bundle();
    Claim claim = buildInquiryClaim("Coverage/1");

    when(claimResponseDao.searchForResources(any(), any())).thenReturn(List.of());
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(claim);
    when(responseBuilder.buildInquiryResponse(List.of())).thenReturn(new Parameters());

    Parameters result = service.inquire(requestBundle);
    assertNotNull(result);

    ArgumentCaptor<SearchParameterMap> paramsCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
    verify(claimResponseDao).searchForResources(paramsCaptor.capture(), any());
    SearchParameterMap params = paramsCaptor.getValue();
    assertNotNull(params.get("status"));
    assertNotNull(params.get("use"));
    assertNotNull(params.get("patient"));
    assertNotNull(params.get("insurer"));
    assertNotNull(params.get("requestor"));
  }

  @Test
  void inquire_filtersOutClaimResponsesWithDifferentCoverage() {
    Bundle requestBundle = new Bundle();
    Claim claim = buildInquiryClaim("Coverage/1");
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(claim);

    ClaimResponse matchingResponse = buildClaimResponse("CR-001", "Claim/100");
    ClaimResponse nonMatchingResponse = buildClaimResponse("CR-002", "Claim/200");

    when(claimResponseDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingResponse, nonMatchingResponse));

    Claim matchingClaim = buildStoredClaim("100", "Coverage/1");
    Claim nonMatchingClaim = buildStoredClaim("200", "Coverage/2");
    when(claimDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingClaim, nonMatchingClaim));

    Parameters expected = new Parameters();
    expected.addParameter().setName("responseBundle").setResource(new Bundle());
    when(responseBuilder.buildInquiryResponse(List.of(matchingResponse))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(matchingResponse));
  }

  @Test
  void inquire_authorizationNumberFiltersAgainstClaimResponse() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    inquiryClaim.addExtension(PasConstants.AUTHORIZATION_NUMBER, new StringType("AUTH0001"));
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse matchingResponse = buildClaimResponseWithAuthNumber("CR-001", "Claim/100", "AUTH0001");
    ClaimResponse nonMatchingResponse = buildClaimResponseWithAuthNumber("CR-002", "Claim/200", "AUTH9999");

    when(claimResponseDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingResponse, nonMatchingResponse));

    when(claimDao.searchForResources(any(), any()))
        .thenReturn(List.of(buildStoredClaim("100", "Coverage/1"), buildStoredClaim("200", "Coverage/1")));

    Parameters expected = new Parameters();
    when(responseBuilder.buildInquiryResponse(List.of(matchingResponse))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(matchingResponse));
  }

  @Test
  void inquire_itemLevelAuthorizationNumberFiltersAgainstClaimResponse() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    addItemStringExtension(inquiryClaim, PasConstants.AUTHORIZATION_NUMBER, "AUTH0001");
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse matchingResponse = buildClaimResponseWithAuthNumber("CR-001", "Claim/100", "AUTH0001");
    ClaimResponse nonMatchingResponse = buildClaimResponseWithAuthNumber("CR-002", "Claim/200", "AUTH9999");

    when(claimResponseDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingResponse, nonMatchingResponse));

    when(claimDao.searchForResources(any(), any()))
        .thenReturn(List.of(buildStoredClaim("100", "Coverage/1"), buildStoredClaim("200", "Coverage/1")));

    Parameters expected = new Parameters();
    when(responseBuilder.buildInquiryResponse(List.of(matchingResponse))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(matchingResponse));
  }

  @Test
  void inquire_adminRefNumberFiltersAgainstClaimResponse() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    inquiryClaim.addExtension(PasConstants.ADMIN_REF_NUMBER, new StringType("PEND0001"));
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse matchingResponse = buildClaimResponseWithAdminRef("CR-001", "Claim/100", "PEND0001");
    ClaimResponse nonMatchingResponse = buildClaimResponseWithAdminRef("CR-002", "Claim/200", "PEND9999");

    when(claimResponseDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingResponse, nonMatchingResponse));

    when(claimDao.searchForResources(any(), any()))
        .thenReturn(List.of(buildStoredClaim("100", "Coverage/1"), buildStoredClaim("200", "Coverage/1")));

    Parameters expected = new Parameters();
    when(responseBuilder.buildInquiryResponse(List.of(matchingResponse))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(matchingResponse));
  }

  @Test
  void inquire_itemLevelAdminRefNumberFiltersAgainstClaimResponse() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    addItemStringExtension(inquiryClaim, PasConstants.ADMIN_REF_NUMBER, "PEND0001");
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse matchingResponse = buildClaimResponseWithAdminRef("CR-001", "Claim/100", "PEND0001");
    ClaimResponse nonMatchingResponse = buildClaimResponseWithAdminRef("CR-002", "Claim/200", "PEND9999");

    when(claimResponseDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingResponse, nonMatchingResponse));

    when(claimDao.searchForResources(any(), any()))
        .thenReturn(List.of(buildStoredClaim("100", "Coverage/1"), buildStoredClaim("200", "Coverage/1")));

    Parameters expected = new Parameters();
    when(responseBuilder.buildInquiryResponse(List.of(matchingResponse))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(matchingResponse));
  }

  @Test
  void inquire_noReferenceNumbers_skipsFilter() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    // No authorizationNumber or adminRefNumber extensions
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse cr1 = buildClaimResponseWithAuthNumber("CR-001", "Claim/100", "AUTH0001");
    ClaimResponse cr2 = buildClaimResponseWithAdminRef("CR-002", "Claim/200", "PEND0001");

    when(claimResponseDao.searchForResources(any(), any()))
        .thenReturn(List.of(cr1, cr2));

    when(claimDao.searchForResources(any(), any()))
        .thenReturn(List.of(buildStoredClaim("100", "Coverage/1"), buildStoredClaim("200", "Coverage/1")));

    Parameters expected = new Parameters();
    when(responseBuilder.buildInquiryResponse(List.of(cr1, cr2))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(cr1, cr2));
  }

  @Test
  void inquire_missingCoverage_throwsIllegalArgument() {
    Bundle requestBundle = new Bundle();
    Claim claim = new Claim();
    claim.setPatient(new Reference("Patient/1"));
    claim.setInsurer(new Reference("Organization/1"));
    claim.setProvider(new Reference("PractitionerRole/1"));
    claim.addInsurance().setFocal(true);
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(claim);

    assertThrows(IllegalArgumentException.class, () -> service.inquire(requestBundle));
    verifyNoInteractions(claimResponseDao);
  }

  @Test
  void inquire_invalidBundle_throwsIllegalArgument() {
    when(validator.validateInquiryBundle(any())).thenThrow(new IllegalArgumentException("bad"));
    assertThrows(IllegalArgumentException.class, () -> service.inquire(new Bundle()));
  }

  @Test
  void inquire_productOrServiceFiltersOutClaimsWithDifferentServiceCode() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    addItemWithProductOrService(inquiryClaim,
        "https://codesystem.x12.org/005010/1365", "42");
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse matchingResponse = buildClaimResponse("CR-001", "Claim/100");
    ClaimResponse nonMatchingResponse = buildClaimResponse("CR-002", "Claim/200");

    when(claimResponseDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingResponse, nonMatchingResponse));

    Claim matchingClaim = buildStoredClaim("100", "Coverage/1");
    addItemWithProductOrService(matchingClaim,
        "https://codesystem.x12.org/005010/1365", "42");

    Claim nonMatchingClaim = buildStoredClaim("200", "Coverage/1");
    addItemWithProductOrService(nonMatchingClaim,
        "https://codesystem.x12.org/005010/1365", "73");

    when(claimDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingClaim, nonMatchingClaim));

    Parameters expected = new Parameters();
    when(responseBuilder.buildInquiryResponse(List.of(matchingResponse))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(matchingResponse));
  }

  @Test
  void inquire_notApplicableProductOrServiceSkipsFilter() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    addItemWithProductOrService(inquiryClaim,
        "http://terminology.hl7.org/CodeSystem/data-absent-reason", "not-applicable");
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse cr1 = buildClaimResponse("CR-001", "Claim/100");
    ClaimResponse cr2 = buildClaimResponse("CR-002", "Claim/200");

    when(claimResponseDao.searchForResources(any(), any()))
        .thenReturn(List.of(cr1, cr2));

    Claim claim1 = buildStoredClaim("100", "Coverage/1");
    addItemWithProductOrService(claim1,
        "https://codesystem.x12.org/005010/1365", "42");
    Claim claim2 = buildStoredClaim("200", "Coverage/1");
    addItemWithProductOrService(claim2,
        "https://codesystem.x12.org/005010/1365", "73");

    when(claimDao.searchForResources(any(), any()))
        .thenReturn(List.of(claim1, claim2));

    Parameters expected = new Parameters();
    when(responseBuilder.buildInquiryResponse(List.of(cr1, cr2))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(cr1, cr2));
  }

  @Test
  void inquire_resolvesBundleReferencesBeforeSearch() {
    Bundle requestBundle = new Bundle();

    // Build the inquiry Claim using bundle references (pre-resolution)
    Claim claim = new Claim();
    claim.setPatient(new Reference("Patient/BeneficiaryExample"));
    claim.setInsurer(new Reference("Organization/InsurerExample"));
    claim.setProvider(new Reference("Organization/ProviderExample"));
    claim.addInsurance().setFocal(true).setCoverage(new Reference("Coverage/CoverageExample"));
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(claim);

    // Stub the resolver to simulate resolving bundle references to server-side IDs
    doAnswer(invocation -> {
      Claim c = invocation.getArgument(1);
      c.setPatient(new Reference("Patient/SubscriberExample"));
      c.setInsurer(new Reference("Organization/example"));
      c.setProvider(new Reference("Organization/provider-server"));
      return null;
    }).when(bundleReferenceResolver).resolveReferences(eq(requestBundle), eq(claim), eq(false));

    // Mock empty ClaimResponse search results (we just care about the reference resolution)
    when(claimResponseDao.searchForResources(any(), any())).thenReturn(List.of());
    when(responseBuilder.buildInquiryResponse(List.of())).thenReturn(new Parameters());

    service.inquire(requestBundle);

    // Verify the resolver was invoked and the search used resolved references
    verify(bundleReferenceResolver).resolveReferences(requestBundle, claim, false);

    ArgumentCaptor<SearchParameterMap> paramsCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
    verify(claimResponseDao).searchForResources(paramsCaptor.capture(), any());
    SearchParameterMap searchParams = paramsCaptor.getValue();

    String patientParam = searchParams.get("patient").get(0).get(0).getValueAsQueryToken();
    String insurerParam = searchParams.get("insurer").get(0).get(0).getValueAsQueryToken();
    String requestorParam = searchParams.get("requestor").get(0).get(0).getValueAsQueryToken();

    assertEquals("Patient/SubscriberExample", patientParam,
        "Patient reference should be resolved to server-side resource");
    assertEquals("Organization/example", insurerParam,
        "Insurer reference should be resolved to server-side resource");
    assertEquals("Organization/provider-server", requestorParam,
        "Provider reference should be resolved to server-side resource");
  }

  @Test
  void inquire_itemTraceNumberFiltersOutClaimsWithDifferentTraceNumber() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    addItemWithTraceNumber(inquiryClaim, "trace-hospital-beds");
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse matchingResponse = buildClaimResponse("CR-001", "Claim/100");
    ClaimResponse seedDataResponse = buildClaimResponse("CR-002", "Claim/200");

    when(claimResponseDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingResponse, seedDataResponse));

    // Claim/100 has the matching trace number
    Claim matchingClaim = buildStoredClaim("100", "Coverage/1");
    addItemWithTraceNumber(matchingClaim, "trace-hospital-beds");

    // Claim/200 has a different trace number (simulates seed/unrelated data)
    Claim seedClaim = buildStoredClaim("200", "Coverage/1");
    addItemWithTraceNumber(seedClaim, "trace-unrelated-1122334");

    when(claimDao.searchForResources(any(), any()))
        .thenReturn(List.of(matchingClaim, seedClaim));

    Parameters expected = new Parameters();
    when(responseBuilder.buildInquiryResponse(List.of(matchingResponse))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(matchingResponse));
  }

  private void addItemWithTraceNumber(Claim claim, String traceValue) {
    Identifier traceId = new Identifier()
        .setSystem("http://example.org/ITEM_TRACE_NUMBER")
        .setValue(traceValue);
    Extension traceExt = new Extension(PasConstants.ITEM_TRACE_NUMBER, traceId);
    claim.addItem().addExtension(traceExt);
  }

  private void addItemWithProductOrService(Claim claim, String system, String code) {
    claim.addItem().setProductOrService(
        new CodeableConcept().addCoding(new Coding(system, code, null)));
  }

  private void addItemStringExtension(Claim claim, String extensionUrl, String value) {
    claim.addItem().addExtension(extensionUrl, new StringType(value));
  }

  private Claim buildInquiryClaim(String coverageReference) {
    Claim claim = new Claim();
    claim.setPatient(new Reference("Patient/1"));
    claim.setInsurer(new Reference("Organization/1"));
    claim.setProvider(new Reference("PractitionerRole/1"));
    claim.addInsurance().setFocal(true).setCoverage(new Reference(coverageReference));
    return claim;
  }

  private Claim buildStoredClaim(String claimId, String coverageReference) {
    Claim claim = new Claim();
    claim.setId(claimId);
    claim.addInsurance().setFocal(true).setCoverage(new Reference(coverageReference));
    return claim;
  }

  private ClaimResponse buildClaimResponse(String id, String requestReference) {
    ClaimResponse response = new ClaimResponse();
    response.setId(id);
    response.setRequest(new Reference(requestReference));
    return response;
  }

  private ClaimResponse buildClaimResponseWithAuthNumber(String id, String requestReference,
      String authNumber) {
    ClaimResponse response = buildClaimResponse(id, requestReference);
    ClaimResponse.ItemComponent item = response.addItem();
    item.setItemSequence(1);
    ClaimResponse.AdjudicationComponent adj = item.addAdjudication();
    adj.setCategory(new CodeableConcept().addCoding(
        new Coding("http://terminology.hl7.org/CodeSystem/adjudication", "submitted", "Submitted Amount")));
    adj.addExtension(PasExtensions.buildReviewActionExtension(REVIEW_CODE_A1, "Certified", authNumber));
    return response;
  }

  private ClaimResponse buildClaimResponseWithAdminRef(String id, String requestReference,
      String adminRef) {
    ClaimResponse response = buildClaimResponse(id, requestReference);
    ClaimResponse.ItemComponent item = response.addItem();
    item.setItemSequence(1);
    ClaimResponse.AdjudicationComponent adj = item.addAdjudication();
    adj.setCategory(new CodeableConcept().addCoding(
        new Coding("http://terminology.hl7.org/CodeSystem/adjudication", "submitted", "Submitted Amount")));
    adj.addExtension(PasExtensions.buildReviewActionExtension(REVIEW_CODE_A4, "Pended", null));
    item.addExtension(PasConstants.ADMIN_REF_NUMBER, new StringType(adminRef));
    return response;
  }
}
