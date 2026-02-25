package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Test;

class PayorIdentifierUtilTest {

  @Test
  void extractFromCoverageAndOrganizations_returnsOnlyValidIdentifiers() {
    Coverage coverage = new Coverage();
    coverage.addPayor().setReference("Organization/org-1");

    Organization org = new Organization();
    org.setId("org-1");
    org.addIdentifier().setSystem("sys").setValue("val");
    org.addIdentifier().setSystem("sys-only");

    List<Identifier> identifiers = PayorIdentifierUtil.extractFromCoverageAndOrganizations(
        coverage, List.of(org));

    assertEquals(1, identifiers.size());
    assertEquals("sys", identifiers.get(0).getSystem());
    assertEquals("val", identifiers.get(0).getValue());
  }

  @Test
  void extractFirstFromCoverageAndBundle_returnsIdentifiersForFirstResolvablePayor() {
    Coverage coverage = new Coverage();
    coverage.addPayor().setReference("Organization/org-1");

    Organization org = new Organization();
    org.setId("org-1");
    org.addIdentifier().setSystem("sys").setValue("val");

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(org);

    List<Identifier> identifiers = PayorIdentifierUtil.extractFirstFromCoverageAndBundle(coverage, bundle);

    assertEquals(1, identifiers.size());
    assertEquals("val", identifiers.get(0).getValue());
  }

  @Test
  void addValidIdentifiers_ignoresNullInputs() {
    PayorIdentifierUtil.addValidIdentifiers(null, null);
    assertTrue(PayorIdentifierUtil.validIdentifiers(null).isEmpty());
  }
}
