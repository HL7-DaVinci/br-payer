package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;

class PasBundleReferenceResolverTest {

  private DaoRegistry daoRegistry;
  private IFhirResourceDao<Patient> patientDao;
  private IFhirResourceDao<Coverage> coverageDao;
  private IFhirResourceDao<Organization> organizationDao;
  private IFhirResourceDao<Practitioner> practitionerDao;
  private IFhirResourceDao<PractitionerRole> practitionerRoleDao;
  private IFhirResourceDao<DeviceRequest> deviceRequestDao;
  private IFhirResourceDao<QuestionnaireResponse> questionnaireResponseDao;
  private PasBundleReferenceResolver resolver;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    daoRegistry = mock(DaoRegistry.class);
    patientDao = mock(IFhirResourceDao.class);
    coverageDao = mock(IFhirResourceDao.class);
    organizationDao = mock(IFhirResourceDao.class);
    practitionerDao = mock(IFhirResourceDao.class);
    practitionerRoleDao = mock(IFhirResourceDao.class);
    deviceRequestDao = mock(IFhirResourceDao.class);
    questionnaireResponseDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(QuestionnaireResponse.class)).thenReturn(questionnaireResponseDao);
    when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
    when(daoRegistry.getResourceDao(Coverage.class)).thenReturn(coverageDao);
    when(daoRegistry.getResourceDao(Organization.class)).thenReturn(organizationDao);
    when(daoRegistry.getResourceDao(Practitioner.class)).thenReturn(practitionerDao);
    when(daoRegistry.getResourceDao(PractitionerRole.class)).thenReturn(practitionerRoleDao);
    when(daoRegistry.getResourceDao(DeviceRequest.class)).thenReturn(deviceRequestDao);
    resolver = new PasBundleReferenceResolver(daoRegistry);
  }

  /**
   * Mocks create() to assign the given server id, recording the client-side id each created
   * resource carried at create time (must always be null: ids are payer-assigned).
   */
  private <T extends IBaseResource> List<String> mockCreateAssigning(
      IFhirResourceDao<T> dao, String resourceType, String serverId) {
    List<String> idsAtCreateTime = new ArrayList<>();
    when(dao.create(any(), any(RequestDetails.class))).thenAnswer(invocation -> {
      T resource = invocation.getArgument(0);
      idsAtCreateTime.add(resource.getIdElement().getIdPart());
      DaoMethodOutcome outcome = new DaoMethodOutcome();
      outcome.setId(new IdType(resourceType, serverId));
      return outcome;
    });
    return idsAtCreateTime;
  }

  private static Coverage versionedCoverage() {
    Coverage coverage = new Coverage();
    coverage.setId("cov013");
    coverage.getMeta().setVersionId("1");
    return coverage;
  }

  private static Bundle bundleWith(Coverage coverage) {
    Bundle bundle = new Bundle();
    bundle.addEntry().setFullUrl("Coverage/cov013").setResource(coverage);
    return bundle;
  }

  private static Bundle bundleWith(Organization organization) {
    Bundle bundle = new Bundle();
    bundle.addEntry().setFullUrl("Organization/" + organization.getIdElement().getIdPart())
        .setResource(organization);
    return bundle;
  }

  private static Claim claimReferencing(String coverageRef) {
    Claim claim = new Claim();
    claim.addInsurance().setCoverage(new Reference(coverageRef));
    return claim;
  }

  private static Claim claimWithProvider(String providerRef) {
    Claim claim = new Claim();
    claim.setProvider(new Reference(providerRef));
    return claim;
  }

  private static Bundle bundleWith(Practitioner practitioner) {
    Bundle bundle = new Bundle();
    bundle.addEntry().setFullUrl("Practitioner/" + practitioner.getIdElement().getIdPart())
        .setResource(practitioner);
    return bundle;
  }

  private static Claim claimWithCareTeamProvider(String providerRef) {
    Claim claim = new Claim();
    claim.addCareTeam().setProvider(new Reference(providerRef));
    return claim;
  }

  private static Bundle bundleWith(PractitionerRole role, Practitioner practitioner, Organization organization) {
    Bundle bundle = new Bundle();
    bundle.addEntry().setFullUrl("PractitionerRole/" + role.getIdElement().getIdPart()).setResource(role);
    bundle.addEntry().setFullUrl("Practitioner/" + practitioner.getIdElement().getIdPart())
        .setResource(practitioner);
    bundle.addEntry().setFullUrl("Organization/" + organization.getIdElement().getIdPart())
        .setResource(organization);
    return bundle;
  }

  @Test
  void reusesExistingCoverageOnIdentifierMatch() {
    Coverage bundleCoverage = versionedCoverage();
    bundleCoverage.addIdentifier().setSystem("http://example.org/coverage").setValue("MEM-1");

    Coverage serverCoverage = new Coverage();
    serverCoverage.setId("server-cov-9");
    when(coverageDao.searchForResources(any(), any(RequestDetails.class)))
        .thenReturn(List.of(serverCoverage));

    Claim claim = claimReferencing("Coverage/cov013");
    resolver.resolveReferences(bundleWith(bundleCoverage), claim, true);

    assertEquals("Coverage/server-cov-9",
        claim.getInsuranceFirstRep().getCoverage().getReference());
    verify(coverageDao, never()).create(any(), any(RequestDetails.class));
  }

  @Test
  void storesUnmatchedCoverageUnderPayerAssignedId() {
    List<String> idsAtCreate = mockCreateAssigning(coverageDao, "Coverage", "payer-cov-1");

    Claim claim = claimReferencing("Coverage/cov013");
    resolver.resolveReferences(bundleWith(versionedCoverage()), claim, true);

    assertEquals(1, idsAtCreate.size());
    assertNull(idsAtCreate.get(0), "Coverage must be created without the provider's id");
    assertEquals("Coverage/payer-cov-1",
        claim.getInsuranceFirstRep().getCoverage().getReference());
  }

  @Test
  void storesRequestorOrgWithOnlyNonNpiIdentifierWhenNoServerMatch() {
    Organization bundleOrg = new Organization();
    bundleOrg.setId("requestor-org");
    bundleOrg.addIdentifier()
        .setSystem("http://example.org/fhir/org-identifier")
        .setValue("1122334455");

    when(organizationDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of());
    List<String> idsAtCreate = mockCreateAssigning(organizationDao, "Organization", "payer-org-1");

    Claim claim = claimWithProvider("Organization/requestor-org");
    resolver.resolveReferences(bundleWith(bundleOrg), claim, true);

    assertEquals(1, idsAtCreate.size());
    assertNull(idsAtCreate.get(0));
    assertEquals("Organization/payer-org-1", claim.getProvider().getReference());
  }

  @Test
  void storesRequestorOrgWithNoIdentifierAtAll() {
    Organization bundleOrg = new Organization();
    bundleOrg.setId("requestor-org-no-id");
    mockCreateAssigning(organizationDao, "Organization", "payer-org-2");

    Claim claim = claimWithProvider("Organization/requestor-org-no-id");
    resolver.resolveReferences(bundleWith(bundleOrg), claim, true);

    assertEquals("Organization/payer-org-2", claim.getProvider().getReference());
    verify(organizationDao, never()).searchForResources(any(), any(RequestDetails.class));
  }

  @Test
  void prefersNpiOverOtherIdentifiersWhenMatchingOrganizations() {
    Organization bundleOrg = new Organization();
    bundleOrg.setId("requestor-org");
    bundleOrg.addIdentifier()
        .setSystem("http://example.org/fhir/org-identifier")
        .setValue("other-id");
    bundleOrg.addIdentifier()
        .setSystem("http://hl7.org/fhir/sid/us-npi")
        .setValue("9998887771");

    Organization serverOrg = new Organization();
    serverOrg.setId("server-org-42");
    when(organizationDao.searchForResources(any(), any(RequestDetails.class)))
        .thenReturn(List.of(serverOrg));

    Claim claim = claimWithProvider("Organization/requestor-org");
    resolver.resolveReferences(bundleWith(bundleOrg), claim, true);

    assertEquals("Organization/server-org-42", claim.getProvider().getReference());
    verify(organizationDao).searchForResources(
        argThat((ca.uhn.fhir.jpa.searchparam.SearchParameterMap m) ->
            m.toNormalizedQueryString(null).contains("9998887771")),
        any(RequestDetails.class));
    verify(organizationDao, never()).create(any(), any(RequestDetails.class));
  }

  @Test
  void rewritesCareTeamPractitionerReferenceOnNpiMatch() {
    Practitioner bundlePractitioner = new Practitioner();
    bundlePractitioner.setId("pract-1");
    bundlePractitioner.addIdentifier().setSystem("http://hl7.org/fhir/sid/us-npi").setValue("1234567890");

    Practitioner serverPractitioner = new Practitioner();
    serverPractitioner.setId("server-pract-99");

    when(practitionerDao.searchForResources(any(), any(RequestDetails.class)))
        .thenReturn(List.of(serverPractitioner));

    Claim claim = claimWithCareTeamProvider("Practitioner/pract-1");
    resolver.resolveReferences(bundleWith(bundlePractitioner), claim, true);

    assertEquals("Practitioner/server-pract-99", claim.getCareTeamFirstRep().getProvider().getReference());
    verify(practitionerDao, never()).create(any(), any(RequestDetails.class));
  }

  @Test
  void storesCareTeamPractitionerWhenNoServerMatch() {
    Practitioner bundlePractitioner = new Practitioner();
    bundlePractitioner.setId("pract-1");
    bundlePractitioner.addIdentifier().setSystem("http://hl7.org/fhir/sid/us-npi").setValue("1234567890");

    when(practitionerDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of());
    List<String> idsAtCreate = mockCreateAssigning(practitionerDao, "Practitioner", "payer-pract-5");

    Claim claim = claimWithCareTeamProvider("Practitioner/pract-1");
    resolver.resolveReferences(bundleWith(bundlePractitioner), claim, true);

    assertEquals(1, idsAtCreate.size());
    assertNull(idsAtCreate.get(0));
    assertEquals("Practitioner/payer-pract-5", claim.getCareTeamFirstRep().getProvider().getReference());
  }

  @Test
  void storesCareTeamPractitionerRoleWithRewrittenInternalReferences() {
    Practitioner bundlePractitioner = new Practitioner();
    bundlePractitioner.setId("pract-1");
    bundlePractitioner.addIdentifier().setSystem("http://hl7.org/fhir/sid/us-npi").setValue("1234567890");

    Organization bundleOrg = new Organization();
    bundleOrg.setId("org-1");

    PractitionerRole role = new PractitionerRole();
    role.setId("role-1");
    role.setPractitioner(new Reference("Practitioner/pract-1"));
    role.setOrganization(new Reference("Organization/org-1"));

    when(practitionerDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of());
    mockCreateAssigning(practitionerDao, "Practitioner", "payer-pract-1");
    mockCreateAssigning(organizationDao, "Organization", "payer-org-1");
    List<String> roleIdsAtCreate = mockCreateAssigning(practitionerRoleDao, "PractitionerRole", "payer-role-1");

    Claim claim = claimWithCareTeamProvider("PractitionerRole/role-1");
    resolver.resolveReferences(bundleWith(role, bundlePractitioner, bundleOrg), claim, true);

    assertEquals(1, roleIdsAtCreate.size());
    assertNull(roleIdsAtCreate.get(0));
    assertEquals("PractitionerRole/payer-role-1", claim.getCareTeamFirstRep().getProvider().getReference());
    assertEquals("Practitioner/payer-pract-1", role.getPractitioner().getReference());
    assertEquals("Organization/payer-org-1", role.getOrganization().getReference());
  }

  @Test
  void rewritesPractitionerRoleInternalPractitionerReferenceOnNpiMatch() {
    Practitioner bundlePractitioner = new Practitioner();
    bundlePractitioner.setId("pract-2");
    bundlePractitioner.addIdentifier().setSystem("http://hl7.org/fhir/sid/us-npi").setValue("1234567890");

    Practitioner serverPractitioner = new Practitioner();
    serverPractitioner.setId("server-pract-77");

    Organization bundleOrg = new Organization();
    bundleOrg.setId("org-2");

    PractitionerRole role = new PractitionerRole();
    role.setId("role-2");
    role.setPractitioner(new Reference("Practitioner/pract-2"));
    role.setOrganization(new Reference("Organization/org-2"));

    when(practitionerDao.searchForResources(any(), any(RequestDetails.class)))
        .thenReturn(List.of(serverPractitioner));
    mockCreateAssigning(organizationDao, "Organization", "payer-org-3");
    mockCreateAssigning(practitionerRoleDao, "PractitionerRole", "payer-role-2");

    Claim claim = claimWithCareTeamProvider("PractitionerRole/role-2");
    resolver.resolveReferences(bundleWith(role, bundlePractitioner, bundleOrg), claim, true);

    verify(practitionerDao, never()).create(any(), any(RequestDetails.class));
    assertEquals("Practitioner/server-pract-77", role.getPractitioner().getReference());
  }

  @Test
  void storesRequestedServiceDeviceRequestUnderPayerAssignedId() {
    DeviceRequest bundleDeviceRequest = new DeviceRequest();
    bundleDeviceRequest.setId("dr-1");

    Bundle bundle = new Bundle();
    bundle.addEntry().setFullUrl("DeviceRequest/dr-1").setResource(bundleDeviceRequest);

    Claim claim = new Claim();
    claim.addItem().addExtension(PasConstants.ITEM_REQUESTED_SERVICE, new Reference("DeviceRequest/dr-1"));

    List<String> idsAtCreate = mockCreateAssigning(deviceRequestDao, "DeviceRequest", "payer-dr-1");

    resolver.resolveReferences(bundle, claim, true);

    assertEquals(1, idsAtCreate.size());
    assertNull(idsAtCreate.get(0));
    Reference orderRef = (Reference) claim.getItemFirstRep()
        .getExtensionByUrl(PasConstants.ITEM_REQUESTED_SERVICE).getValue();
    assertEquals("DeviceRequest/payer-dr-1", orderRef.getReference());
  }

  @Test
  void clearsOrderReferencesThatDidNotResolveWithinTheBundle() {
    Patient bundlePatient = new Patient();
    bundlePatient.setId("1716");
    bundlePatient.addIdentifier().setSystem("http://example.org/MIN").setValue("MEM-77");

    DeviceRequest bundleDeviceRequest = new DeviceRequest();
    bundleDeviceRequest.setId("1717");
    bundleDeviceRequest.setSubject(new Reference("Patient/1716"));
    bundleDeviceRequest.setEncounter(new Reference("Encounter/1715"));

    Bundle bundle = new Bundle();
    bundle.addEntry().setFullUrl("Patient/1716").setResource(bundlePatient);
    bundle.addEntry().setFullUrl("DeviceRequest/1717").setResource(bundleDeviceRequest);

    Claim claim = new Claim();
    claim.setPatient(new Reference("Patient/1716"));
    claim.addItem().addExtension(PasConstants.ITEM_REQUESTED_SERVICE, new Reference("DeviceRequest/1717"));

    when(patientDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of());
    mockCreateAssigning(patientDao, "Patient", "payer-pat-9");
    mockCreateAssigning(deviceRequestDao, "DeviceRequest", "payer-dr-9");

    resolver.resolveReferences(bundle, claim, true);

    assertEquals("Patient/payer-pat-9", bundleDeviceRequest.getSubject().getReference(),
        "In-bundle subject must be rewritten to the stored payer Patient");
    assertNull(bundleDeviceRequest.getEncounter().getReference(),
        "A reference that does not resolve within the bundle must be cleared before storing");
  }

  @Test
  void storesSupportingInfoQuestionnaireResponseUnderPayerAssignedId() {
    Patient bundlePatient = new Patient();
    bundlePatient.setId("1716");
    bundlePatient.addIdentifier().setSystem("http://example.org/MIN").setValue("MEM-88");

    QuestionnaireResponse qr = new QuestionnaireResponse();
    qr.setId("1750");
    qr.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
    qr.setQuestionnaire("http://example.org/fhir/Questionnaire/home-o2-std-questionnaire");
    qr.setSubject(new Reference("Patient/1716"));

    Bundle bundle = new Bundle();
    bundle.addEntry().setFullUrl("Patient/1716").setResource(bundlePatient);
    bundle.addEntry().setFullUrl("QuestionnaireResponse/1750").setResource(qr);

    Claim claim = new Claim();
    claim.setPatient(new Reference("Patient/1716"));
    claim.addSupportingInfo().setSequence(1)
        .setValue(new Reference("QuestionnaireResponse/1750"));

    when(patientDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of());
    mockCreateAssigning(patientDao, "Patient", "payer-pat-7");
    List<String> qrIdsAtCreate = mockCreateAssigning(questionnaireResponseDao,
        "QuestionnaireResponse", "payer-qr-7");

    resolver.resolveReferences(bundle, claim, true);

    assertEquals(1, qrIdsAtCreate.size());
    assertNull(qrIdsAtCreate.get(0));
    Reference supportingRef = (Reference) claim.getSupportingInfoFirstRep().getValue();
    assertEquals("QuestionnaireResponse/payer-qr-7", supportingRef.getReference());
    assertEquals("Patient/payer-pat-7", qr.getSubject().getReference(),
        "Stored QR subject must point at the payer-side Patient");
  }

  @Test
  void rewritesCoverageBeneficiaryToStoredPatientId() {
    Patient bundlePatient = new Patient();
    bundlePatient.setId("1716");
    bundlePatient.addIdentifier().setSystem("http://example.org/MIN").setValue("MEM-42");

    Coverage bundleCoverage = new Coverage();
    bundleCoverage.setId("2020");
    bundleCoverage.setBeneficiary(new Reference("Patient/1716"));

    Bundle bundle = new Bundle();
    bundle.addEntry().setFullUrl("Patient/1716").setResource(bundlePatient);
    bundle.addEntry().setFullUrl("Coverage/2020").setResource(bundleCoverage);

    Claim claim = new Claim();
    claim.setPatient(new Reference("Patient/1716"));
    claim.addInsurance().setCoverage(new Reference("Coverage/2020"));

    when(patientDao.searchForResources(any(), any(RequestDetails.class))).thenReturn(List.of());
    mockCreateAssigning(patientDao, "Patient", "payer-pat-1");
    mockCreateAssigning(coverageDao, "Coverage", "payer-cov-2");

    resolver.resolveReferences(bundle, claim, true);

    assertEquals("Patient/payer-pat-1", claim.getPatient().getReference());
    assertEquals("Coverage/payer-cov-2", claim.getInsuranceFirstRep().getCoverage().getReference());
    assertEquals("Patient/payer-pat-1", bundleCoverage.getBeneficiary().getReference());
  }
}
