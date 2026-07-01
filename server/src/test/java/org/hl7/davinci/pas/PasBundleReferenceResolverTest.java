package org.hl7.davinci.pas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

class PasBundleReferenceResolverTest {

  private DaoRegistry daoRegistry;
  private IFhirResourceDao<Coverage> coverageDao;
  private PasBundleReferenceResolver resolver;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    daoRegistry = mock(DaoRegistry.class);
    coverageDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(Coverage.class)).thenReturn(coverageDao);
    resolver = new PasBundleReferenceResolver(daoRegistry);
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

  private static Claim claimReferencing(String coverageRef) {
    Claim claim = new Claim();
    claim.addInsurance().setCoverage(new Reference(coverageRef));
    return claim;
  }

  @Test
  void reusesExistingCoverageWithoutUpdating() {
    when(coverageDao.read(any(IIdType.class), any(RequestDetails.class)))
        .thenReturn(new Coverage());

    resolver.resolveReferences(
        bundleWith(versionedCoverage()), claimReferencing("Coverage/cov013"), true);

    verify(coverageDao, never()).update(any(), any(RequestDetails.class));
  }

  @Test
  void storesAbsentCoverageWithoutVersionPrecondition() {
    when(coverageDao.read(any(IIdType.class), any(RequestDetails.class)))
        .thenThrow(new ResourceNotFoundException("absent"));

    resolver.resolveReferences(
        bundleWith(versionedCoverage()), claimReferencing("Coverage/cov013"), true);

    verify(coverageDao).update(
        argThat((Coverage c) -> c.getMeta().getVersionId() == null),
        any(RequestDetails.class));
  }
}
