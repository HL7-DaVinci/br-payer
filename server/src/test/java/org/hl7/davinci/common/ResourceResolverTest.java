package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;

@ExtendWith(MockitoExtension.class)
class ResourceResolverTest {

  @Mock
  private DaoRegistry daoRegistry;

  @Test
  @DisplayName("resolveTypedReferenceFromDao returns inline typed resource")
  void resolveTypedReferenceFromDao_returnsInlineTypedResource() {
    Organization inline = new Organization();
    Reference reference = new Reference();
    reference.setResource(inline);

    Organization result = ResourceResolver.resolveTypedReferenceFromDao(
        reference, Organization.class, null, daoRegistry);

    assertSame(inline, result);
    verifyNoInteractions(daoRegistry);
  }

  @Test
  @DisplayName("resolveTypedReferenceFromDao returns contained typed resource for local hash reference")
  void resolveTypedReferenceFromDao_returnsContainedTypedResourceForLocalHashReference() {
    Coverage coverage = new Coverage();
    Organization contained = new Organization();
    contained.setId("payor-org");
    coverage.addContained(contained);
    Reference reference = new Reference("#payor-org");

    Organization result = ResourceResolver.resolveTypedReferenceFromDao(
        reference, Organization.class, coverage, daoRegistry);

    assertSame(contained, result);
    verifyNoInteractions(daoRegistry);
  }

  @Test
  @DisplayName("resolveTypedReferenceFromDao matches non-hash contained references")
  void resolveTypedReferenceFromDao_matchesNonHashContainedReferences() {
    Coverage coverage = new Coverage();
    Organization contained = new Organization();
    contained.setId("urn:uuid:org-1");
    coverage.addContained(contained);
    Reference reference = new Reference("Organization/org-1");

    Organization result = ResourceResolver.resolveTypedReferenceFromDao(
        reference, Organization.class, coverage, daoRegistry);

    assertSame(contained, result);
    verifyNoInteractions(daoRegistry);
  }

  @Test
  @DisplayName("resolveTypedReferenceFromDao resolves from DAO for typed external references")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void resolveTypedReferenceFromDao_resolvesFromDaoForTypedExternalReferences() {
    IFhirResourceDao organizationDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(Organization.class)).thenReturn(organizationDao);

    Organization resolved = new Organization();
    resolved.setId("Organization/123");
    when(organizationDao.read(any(), any(SystemRequestDetails.class))).thenReturn(resolved);

    Organization result = ResourceResolver.resolveTypedReferenceFromDao(
        new Reference("Organization/123"), Organization.class, null, daoRegistry);

    assertSame(resolved, result);
    verify(organizationDao).read(
        argThat((IIdType id) -> "Organization".equals(id.getResourceType()) && "123".equals(id.getIdPart())),
        any(SystemRequestDetails.class));
  }

  @Test
  @DisplayName("resolveTypedReferenceFromDao supports id-only references with expected type")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void resolveTypedReferenceFromDao_supportsIdOnlyReferencesWithExpectedType() {
    IFhirResourceDao organizationDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(Organization.class)).thenReturn(organizationDao);

    Organization resolved = new Organization();
    resolved.setId("Organization/abc");
    when(organizationDao.read(any(), any(SystemRequestDetails.class))).thenReturn(resolved);

    Organization result = ResourceResolver.resolveTypedReferenceFromDao(
        new Reference("abc"), Organization.class, null, daoRegistry);

    assertSame(resolved, result);
    verify(organizationDao).read(
        argThat((IIdType id) -> "Organization".equals(id.getResourceType()) && "abc".equals(id.getIdPart())),
        any(SystemRequestDetails.class));
  }

  @Test
  @DisplayName("resolveTypedReferenceFromDao normalizes urn:uuid ids before DAO lookup")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void resolveTypedReferenceFromDao_normalizesUrnUuidIdsBeforeDaoLookup() {
    IFhirResourceDao organizationDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(Organization.class)).thenReturn(organizationDao);

    Organization resolved = new Organization();
    resolved.setId("Organization/org-2");
    when(organizationDao.read(any(), any(SystemRequestDetails.class))).thenReturn(resolved);

    Organization result = ResourceResolver.resolveTypedReferenceFromDao(
        new Reference("Organization/urn:uuid:org-2"), Organization.class, null, daoRegistry);

    assertSame(resolved, result);
    verify(organizationDao).read(
        argThat((IIdType id) -> "Organization".equals(id.getResourceType()) && "org-2".equals(id.getIdPart())),
        any(SystemRequestDetails.class));
  }

  @Test
  @DisplayName("resolveTypedReferenceFromDao returns null for type mismatch")
  void resolveTypedReferenceFromDao_returnsNullForTypeMismatch() {
    Organization result = ResourceResolver.resolveTypedReferenceFromDao(
        new Reference("Patient/123"), Organization.class, null, daoRegistry);

    assertNull(result);
    verify(daoRegistry, never()).getResourceDao(Organization.class);
  }

  @Test
  @DisplayName("resolveReferenceFromDao returns inline resource")
  void resolveReferenceFromDao_returnsInlineResource() {
    Patient inline = new Patient();
    Reference reference = new Reference();
    reference.setResource(inline);

    Resource result = ResourceResolver.resolveReferenceFromDao(reference, null, daoRegistry);

    assertSame(inline, result);
    verifyNoInteractions(daoRegistry);
  }

  @Test
  @DisplayName("resolveReferenceFromDao returns contained resource for local hash reference")
  void resolveReferenceFromDao_returnsContainedResourceForLocalHashReference() {
    QuestionnaireResponse qr = new QuestionnaireResponse();
    Organization contained = new Organization();
    contained.setId("contained-org");
    qr.addContained(contained);

    Resource result = ResourceResolver.resolveReferenceFromDao(new Reference("#contained-org"), qr, daoRegistry);

    assertSame(contained, result);
    verifyNoInteractions(daoRegistry);
  }

  @Test
  @DisplayName("resolveReferenceFromDao matches non-hash contained references")
  void resolveReferenceFromDao_matchesNonHashContainedReferences() {
    QuestionnaireResponse qr = new QuestionnaireResponse();
    Organization contained = new Organization();
    contained.setId("urn:uuid:contained-2");
    qr.addContained(contained);

    Resource result = ResourceResolver.resolveReferenceFromDao(
        new Reference("Organization/contained-2"), qr, daoRegistry);

    assertSame(contained, result);
    verifyNoInteractions(daoRegistry);
  }

  @Test
  @DisplayName("resolveReferenceFromDao resolves typed external reference from DAO")
  @SuppressWarnings({ "rawtypes" })
  void resolveReferenceFromDao_resolvesTypedExternalReferenceFromDao() {
    IFhirResourceDao organizationDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao("Organization")).thenReturn(organizationDao);

    Organization resolved = new Organization();
    resolved.setId("Organization/444");
    when(organizationDao.read(any(), any(SystemRequestDetails.class))).thenReturn(resolved);

    Resource result = ResourceResolver.resolveReferenceFromDao(
        new Reference("Organization/444"), null, daoRegistry);

    assertSame(resolved, result);
    verify(organizationDao).read(
        argThat((IIdType id) -> "Organization".equals(id.getResourceType()) && "444".equals(id.getIdPart())),
        any(SystemRequestDetails.class));
  }

  @Test
  @DisplayName("resolveReferenceFromDao returns null for id-only references")
  void resolveReferenceFromDao_returnsNullForIdOnlyReferences() {
    Resource result = ResourceResolver.resolveReferenceFromDao(new Reference("444"), null, daoRegistry);

    assertNull(result);
    verify(daoRegistry, never()).getResourceDao(anyString());
  }

  @Test
  @DisplayName("resolveReferenceFromDao returns null when DAO read fails")
  @SuppressWarnings({ "rawtypes" })
  void resolveReferenceFromDao_returnsNullWhenDaoReadFails() {
    IFhirResourceDao organizationDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao("Organization")).thenReturn(organizationDao);
    when(organizationDao.read(any(), any(SystemRequestDetails.class)))
        .thenThrow(new RuntimeException("read failed"));

    Resource result = ResourceResolver.resolveReferenceFromDao(
        new Reference("Organization/555"), null, daoRegistry);

    assertNull(result);
  }

  @Test
  @DisplayName("toVersionlessTypedReference normalizes typed references and rejects mismatches")
  void toVersionlessTypedReference_normalizesAndValidatesType() {
    assertEquals(
        "http://example.org/fhir/Patient/123",
        ResourceResolver.toVersionlessTypedReference(
            "http://example.org/fhir/Patient/123/_history/4",
            "Patient"));
    assertEquals(
        "Patient/123",
        ResourceResolver.toVersionlessTypedReference(new Reference("Patient/123"), "Patient"));
    assertNull(ResourceResolver.toVersionlessTypedReference("Observation/1", "Patient"));
  }

  @Test
  @DisplayName("getReferenceResourceType resolves type from string and Reference")
  void getReferenceResourceType_resolvesType() {
    assertEquals("Patient", ResourceResolver.getReferenceResourceType("Patient/123"));
    assertEquals("Coverage", ResourceResolver.getReferenceResourceType(new Reference("Coverage/abc")));
    assertNull(ResourceResolver.getReferenceResourceType((String) null));
  }

  @Test
  @DisplayName("findPayorOrganizations matches coverage payor references")
  void findPayorOrganizations_matchesReferencedOrganizations() {
    Coverage coverage = new Coverage();
    coverage.addPayor(new Reference("Organization/org-1"));

    Organization match = new Organization();
    match.setId("org-1");
    Organization nonMatch = new Organization();
    nonMatch.setId("org-2");

    var matched = ResourceResolver.findPayorOrganizations(coverage, List.of(match, nonMatch));

    assertEquals(1, matched.size());
    assertEquals("org-1", matched.get(0).getIdElement().getIdPart());
  }

  @Test
  @DisplayName("referencesMatch handles equivalent references across forms")
  void referencesMatch_handlesEquivalentReferences() {
    assertTrue(ResourceResolver.referencesMatch("Patient/123", "http://example.org/fhir/Patient/123"));
    assertFalse(ResourceResolver.referencesMatch("Patient/123", "Coverage/123"));
  }

  @Test
  @DisplayName("resolveOrderReference resolves supported order references from prefetch")
  void resolveOrderReference_resolvesFromPrefetchBundle() {
    CdsServiceRequestJson request = new CdsServiceRequestJson();
    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-100");
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(order);
    request.addPrefetch("draftOrders", bundle);

    Resource resolved = ResourceResolver.resolveOrderReference("DeviceRequest/dr-100", request);
    assertSame(order, resolved);
  }
}
