package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasInquiryServiceTest {

  private PasBundleValidator validator;
  private DaoRegistry daoRegistry;
  private PasResponseBuilder responseBuilder;
  private IFhirResourceDao<ClaimResponse> claimResponseDao;
  private IFhirResourceDao<Claim> claimDao;
  private PasInquiryService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    validator = mock(PasBundleValidator.class);
    daoRegistry = mock(DaoRegistry.class);
    responseBuilder = mock(PasResponseBuilder.class);

    claimResponseDao = mock(IFhirResourceDao.class);
    claimDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(ClaimResponse.class)).thenReturn(claimResponseDao);
    when(daoRegistry.getResourceDao(Claim.class)).thenReturn(claimDao);

    service = new PasInquiryService(validator, daoRegistry, responseBuilder);
  }

  @Test
  void inquire_noMatches_scopesSearchToClaimContext() {
    Bundle requestBundle = new Bundle();
    Claim claim = buildInquiryClaim("Coverage/1");

    IBundleProvider emptyProvider = mock(IBundleProvider.class);
    when(emptyProvider.getResources(anyInt(), anyInt())).thenReturn(List.of());
    when(claimResponseDao.search(any(), any())).thenReturn(emptyProvider);
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(claim);
    when(responseBuilder.buildInquiryResponse(List.of())).thenReturn(new Parameters());

    Parameters result = service.inquire(requestBundle);
    assertNotNull(result);

    ArgumentCaptor<SearchParameterMap> paramsCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
    verify(claimResponseDao).search(paramsCaptor.capture(), any());
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

    IBundleProvider mockProvider = mock(IBundleProvider.class);
    when(mockProvider.getResources(anyInt(), anyInt()))
        .thenReturn(List.of(matchingResponse, nonMatchingResponse));
    when(claimResponseDao.search(any(), any())).thenReturn(mockProvider);

    when(claimDao.read(argThat(id -> id != null && "100".equals(id.getIdPart())), any()))
        .thenReturn(buildStoredClaim("Coverage/1"));
    when(claimDao.read(argThat(id -> id != null && "200".equals(id.getIdPart())), any()))
        .thenReturn(buildStoredClaim("Coverage/2"));

    Parameters expected = new Parameters();
    expected.addParameter().setName("responseBundle").setResource(new Bundle());
    when(responseBuilder.buildInquiryResponse(List.of(matchingResponse))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(matchingResponse));
  }

  @Test
  void inquire_identifierFiltersAgainstReferencedClaim() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    inquiryClaim.addIdentifier(new Identifier().setSystem("http://example.org/auth-ref").setValue("AUTH-123"));
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse matchingResponse = buildClaimResponse("CR-001", "Claim/100");
    ClaimResponse nonMatchingResponse = buildClaimResponse("CR-002", "Claim/200");

    IBundleProvider mockProvider = mock(IBundleProvider.class);
    when(mockProvider.getResources(anyInt(), anyInt()))
        .thenReturn(List.of(matchingResponse, nonMatchingResponse));
    when(claimResponseDao.search(any(), any())).thenReturn(mockProvider);

    Claim matchingClaim = buildStoredClaim("Coverage/1");
    matchingClaim.addIdentifier(new Identifier()
        .setSystem("http://example.org/auth-ref")
        .setValue("AUTH-123"));
    Claim nonMatchingClaim = buildStoredClaim("Coverage/1");
    nonMatchingClaim.addIdentifier(new Identifier()
        .setSystem("http://example.org/auth-ref")
        .setValue("AUTH-999"));

    when(claimDao.read(argThat(id -> id != null && "100".equals(id.getIdPart())), any()))
        .thenReturn(matchingClaim);
    when(claimDao.read(argThat(id -> id != null && "200".equals(id.getIdPart())), any()))
        .thenReturn(nonMatchingClaim);

    Parameters expected = new Parameters();
    when(responseBuilder.buildInquiryResponse(List.of(matchingResponse))).thenReturn(expected);

    Parameters result = service.inquire(requestBundle);
    assertSame(expected, result);
    verify(responseBuilder).buildInquiryResponse(List.of(matchingResponse));
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
  void inquire_itemTraceNumberFiltersOutClaimsWithDifferentTraceNumber() {
    Bundle requestBundle = new Bundle();
    Claim inquiryClaim = buildInquiryClaim("Coverage/1");
    addItemWithTraceNumber(inquiryClaim, "trace-hospital-beds");
    when(validator.validateInquiryBundle(requestBundle)).thenReturn(inquiryClaim);

    ClaimResponse matchingResponse = buildClaimResponse("CR-001", "Claim/100");
    ClaimResponse seedDataResponse = buildClaimResponse("CR-002", "Claim/200");

    IBundleProvider mockProvider = mock(IBundleProvider.class);
    when(mockProvider.getResources(anyInt(), anyInt()))
        .thenReturn(List.of(matchingResponse, seedDataResponse));
    when(claimResponseDao.search(any(), any())).thenReturn(mockProvider);

    // Claim/100 has the matching trace number
    Claim matchingClaim = buildStoredClaim("Coverage/1");
    addItemWithTraceNumber(matchingClaim, "trace-hospital-beds");

    // Claim/200 has a different trace number (simulates seed/unrelated data)
    Claim seedClaim = buildStoredClaim("Coverage/1");
    addItemWithTraceNumber(seedClaim, "trace-unrelated-1122334");

    when(claimDao.read(argThat(id -> id != null && "100".equals(id.getIdPart())), any()))
        .thenReturn(matchingClaim);
    when(claimDao.read(argThat(id -> id != null && "200".equals(id.getIdPart())), any()))
        .thenReturn(seedClaim);

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
    Extension traceExt = new Extension(PasExtensions.ITEM_TRACE_NUMBER, traceId);
    claim.addItem().addExtension(traceExt);
  }

  private Claim buildInquiryClaim(String coverageReference) {
    Claim claim = new Claim();
    claim.setPatient(new Reference("Patient/1"));
    claim.setInsurer(new Reference("Organization/1"));
    claim.setProvider(new Reference("PractitionerRole/1"));
    claim.addInsurance().setFocal(true).setCoverage(new Reference(coverageReference));
    return claim;
  }

  private Claim buildStoredClaim(String coverageReference) {
    Claim claim = new Claim();
    claim.addInsurance().setFocal(true).setCoverage(new Reference(coverageReference));
    return claim;
  }

  private ClaimResponse buildClaimResponse(String id, String requestReference) {
    ClaimResponse response = new ClaimResponse();
    response.setId(id);
    response.setRequest(new Reference(requestReference));
    return response;
  }
}
